package com.mindoo.db.bridge

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two users of one tenant syncing through a real MindooDB server: publish,
 * join, document and attachment sync, typed values, a live virtual view.
 *
 * Runs only when `MINDOODB_TEST_SERVER_URL` points at a server that lets any
 * signed user register tenants (a config.json capability for tenant creation
 * with username and publicsignkey `*`). See README "Tests".
 */
class ServerSyncIntegrationTest {

    private lateinit var dataRoot: Path
    private var sidecar: SidecarProcess? = null

    private val serverUrl: String? = System.getenv("MINDOODB_TEST_SERVER_URL")
    private val bridgeDir: Path = Paths.get("..", "mindoodb-jvm-bridge").toAbsolutePath().normalize()
    private val indexScript: Path get() = bridgeDir.resolve("dist/index.js")

    private fun requirements(): Boolean = serverUrl != null && indexScript.exists()

    @BeforeTest
    fun setup() {
        if (!requirements()) return
        dataRoot = Files.createTempDirectory("mindoodb-bridge-sync-it")
    }

    @AfterTest
    fun tearDown() {
        sidecar?.stop()
        if (::dataRoot.isInitialized) dataRoot.toFile().deleteRecursively()
    }

    @Test
    fun twoUsersSyncThroughServer() {
        if (!requirements()) {
            println("[integration] skipped: set MINDOODB_TEST_SERVER_URL and build the sidecar")
            return
        }
        val url = serverUrl!!
        val tenantId = "kt" + System.currentTimeMillis().toString(36)
        runBlocking {
            sidecar = Sidecar.start(indexScript, dataRoot)
            val endpoint = sidecar!!.endpoint

            val created = MindooDBBridge.createTenant(
                endpoint, tenantId = tenantId, adminName = "cn=admin/o=kt", adminPassword = "admin-pw",
                userId = "cn=alice/o=kt", password = "alice-pw", databases = listOf("notes"),
            )
            val alice = MindooDBBridge.openSession(endpoint, tenantId, "cn=alice/o=kt", "alice-pw", listOf("notes"))
            val bob = try {
                val published = alice.publishToServer(url, adminPassword = "admin-pw")
                assertTrue(published.pushed.any { it.dbId == "directory" && it.pushedEntries > 0 }, "directory pushed")

                val notes = alice.database("notes")
                val doc = notes.documents.create(mapOf("type" to "note", "category" to "a", "title" to "Hello"))
                notes.documents.update(doc.id) {
                    setAtomic("status", "open")
                    set("hits", MindooValue.counter(1))
                }
                notes.documents.update(doc.id) { increment("hits", 2) }
                val settings = notes.documents.create(mapOf("theme" to "dark"), id = "settings_main")
                assertEquals("dark", settings.data["theme"]!!.jsonPrimitive.content)
                notes.attachments.uploadBytes(doc.id, "a.bin", "application/octet-stream", ByteArray(200_000) { it.toByte() })
                val pushed = alice.refresh(direction = SyncDirection.Push)
                assertTrue(pushed.pushed, "alice pushed: $pushed")

                val bobUser = MindooDBBridge.createUser(endpoint, "cn=bob/o=kt", "bob-pw")
                val request = MindooDBBridge.createJoinRequest(endpoint, bobUser.identity, label = "Bob's service")
                val info = alice.inspectJoinRequest(request.joinRequest)
                assertEquals("cn=bob/o=kt", info.username)
                assertEquals("Bob's service", info.label)
                assertTrue(info.hasUserKey)
                assertEquals(false, info.directoryUser?.exists)
                assertTrue(Regex("^([0-9a-f]{2}:){7}[0-9a-f]{2}$").matches(info.signingPublicKeyFingerprint))
                val approved = alice.approveJoinRequest(
                    request.joinRequest, adminPassword = "admin-pw", adminIdentity = created.adminIdentity,
                )
                assertTrue(approved.pushed.isNotEmpty(), "grant pushed with the approval")
                val again = alice.inspectJoinRequest(request.joinRequest)
                assertEquals(true, again.directoryUser?.deviceAlreadyGranted, "device granted after approval")
                assertEquals("Bob's service", again.directoryUser?.devices?.single()?.label)
                MindooDBBridge.joinTenant(
                    endpoint, approved.joinResponse, bobUser.identity, "bob-pw", databases = listOf("notes"),
                )
                MindooDBBridge.openSession(endpoint, tenantId, "cn=bob/o=kt", "bob-pw", listOf("notes"))
            } catch (t: Throwable) {
                alice.close()
                throw t
            }

            try {
                val progress = mutableListOf<SyncProgressEvent>()
                val watcher = launch { bob.syncProgress().collect { progress += it } }
                delay(300) // let the subscription reach the sidecar
                bob.refresh(direction = SyncDirection.Pull)
                // Events are delivered asynchronously; give the last ones a moment.
                val notesPulls = withTimeout(5_000) {
                    while (progress.toList().count { it.dbId == "notes" && it.direction == "pull" && it.phase == "complete" } < 2) delay(50)
                    progress.toList().filter { it.dbId == "notes" && it.direction == "pull" && !it.background }
                }
                watcher.cancel()
                assertEquals(setOf("docs", "attachments"), notesPulls.filter { it.phase == "complete" }.map { it.store }.toSet())
                val docsDone = notesPulls.last { it.store == "docs" && it.phase == "complete" }
                assertTrue(docsDone.transferredEntries > 0, "first pull transferred data: $docsDone")
                assertTrue(notesPulls.any { it.phase == "transferring" }, "transferring phase reported")
                val bobNotes = bob.database("notes")
                val ids = bobNotes.documents.list()
                val noteId = ids.first { it != "settings_main" }
                val seen = bobNotes.documents.get(noteId)
                assertEquals("open", seen.data["status"]!!.jsonPrimitive.content)
                assertEquals(3, seen.data["hits"]!!.jsonPrimitive.int)
                val bytes = bobNotes.attachments.readAll(noteId, "a.bin")
                assertContentEquals(ByteArray(200_000) { it.toByte() }, bytes)

                // Bob edits, Alice's live view follows after a pull.
                val nav = alice.database("notes").openViewNavigator(
                    viewDefinition {
                        filter = field("type") eq "note"
                        category("category")
                        column("title")
                    },
                    navigatorOptions = navigatorOptions(expandAll = true),
                )
                assertEquals(1, nav.readForward().count { it.isDocument })
                val viewChanged = async { withTimeout(20_000) { nav.changes().first() } }
                delay(500) // let the subscription reach the sidecar
                bobNotes.documents.create(mapOf("type" to "note", "category" to "b", "title" to "From Bob"))
                bob.refresh(direction = SyncDirection.Push)
                alice.refresh("notes", SyncDirection.Pull)
                viewChanged.await()
                val entries = nav.readForward()
                assertEquals(2, entries.count { it.isDocument }, "view shows Bob's note: $entries")
                assertEquals(2, entries.count { it.isCategory })
                nav.close()
            } finally {
                bob.close()
                alice.close()
            }
        }
    }

    private suspend fun AttachmentApi.readAll(docId: String, fileName: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        read(docId, fileName).collect { out.write(it) }
        return out.toByteArray()
    }
}
