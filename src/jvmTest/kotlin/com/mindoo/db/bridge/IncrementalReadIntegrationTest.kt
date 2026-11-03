package com.mindoo.db.bridge

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Paged changefeed reads with a persisted cursor, metadata-only reads, counts and id prefixes. */
class IncrementalReadIntegrationTest {

    private lateinit var dataRoot: Path
    private var sidecar: SidecarProcess? = null

    private val bridgeDir: Path = Paths.get("..", "mindoodb-jvm-bridge").toAbsolutePath().normalize()
    private val indexScript: Path get() = bridgeDir.resolve("dist/index.js")

    @BeforeTest
    fun setup() {
        if (!indexScript.exists()) return
        dataRoot = Files.createTempDirectory("mindoodb-bridge-incremental-it")
    }

    @AfterTest
    fun tearDown() {
        sidecar?.stop()
        if (::dataRoot.isInitialized) dataRoot.toFile().deleteRecursively()
    }

    @Test
    fun pagesCursorsMetadataAndCounts() {
        if (!indexScript.exists()) {
            println("[integration] skipped: build the sidecar (dist/)")
            return
        }
        runBlocking {
            sidecar = Sidecar.start(indexScript, dataRoot)
            val endpoint = sidecar!!.endpoint
            MindooDBBridge.createTenant(
                endpoint, tenantId = "tenant-inc", adminName = "cn=admin", adminPassword = "adminpw",
                userId = "cn=alice", password = "pwA", databases = listOf("notes"),
            )
            val session = MindooDBBridge.openSession(endpoint, "tenant-inc", "cn=alice", "pwA", listOf("notes"))
            try {
                val db = session.database("notes")
                repeat(5) { i -> db.documents.create(mapOf("type" to "invoice", "n" to i), idPrefix = "inv") }
                db.documents.create(mapOf("type" to "note"))

                assertEquals(5, db.countChanges(idPrefix = "inv"))
                assertEquals(6, db.countChanges())

                // A job: read pages of 2, persist only the cursor as a string.
                var stored: String? = null
                val seen = mutableListOf<Int>()
                var pages = 0
                do {
                    val page = db.readChanges(ChangeCursors.parse(stored), limit = 2, idPrefix = "inv")
                    page.changes.forEach { seen += it.doc.data["n"]!!.jsonPrimitive.int }
                    stored = ChangeCursors.serialize(page.cursor)
                    pages++
                } while (!page.done)
                assertEquals(listOf(0, 1, 2, 3, 4), seen.sorted())
                assertEquals(3, pages)
                assertEquals(0, db.countChanges(ChangeCursors.parse(stored), idPrefix = "inv"))

                // The next run only sees what changed since.
                val firstId = db.readChanges(limit = 1, idPrefix = "inv").changes.single().doc.id
                db.documents.update(firstId) { set("paid", true) }
                val next = db.changesUntilNow(ChangeCursors.parse(stored), idPrefix = "inv").toList()
                assertEquals(listOf(firstId), next.map { it.doc.id })

                val meta = db.readChangeMetadata()
                assertEquals(6, meta.changes.size)
                assertTrue(meta.done)
                assertTrue(meta.changes.none { it.meta.isDeleted })

                // Live metadata feed with a prefix.
                val live = async { withTimeout(15_000) { db.changeMetadata(meta.cursor, idPrefix = "inv").first() } }
                delay(500)
                db.documents.create(mapOf("type" to "note"))       // filtered out
                db.documents.update(firstId) { set("paid", false) }
                assertEquals(firstId, live.await().meta.docId)
            } finally {
                session.close()
            }
        }
    }
}
