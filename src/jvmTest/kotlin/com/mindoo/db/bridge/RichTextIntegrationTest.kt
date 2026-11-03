package com.mindoo.db.bridge

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rich text, marks and text cursors through the real sidecar (local tenant, no server). */
class RichTextIntegrationTest {

    private lateinit var dataRoot: Path
    private var sidecar: SidecarProcess? = null

    private val bridgeDir: Path = Paths.get("..", "mindoodb-jvm-bridge").toAbsolutePath().normalize()
    private val indexScript: Path get() = bridgeDir.resolve("dist/index.js")

    @BeforeTest
    fun setup() {
        if (!indexScript.exists()) return
        dataRoot = Files.createTempDirectory("mindoodb-bridge-richtext-it")
    }

    @AfterTest
    fun tearDown() {
        sidecar?.stop()
        if (::dataRoot.isInitialized) dataRoot.toFile().deleteRecursively()
    }

    @Test
    fun richTextMarksAndCursors() {
        if (!indexScript.exists()) {
            println("[integration] skipped: build the sidecar (dist/)")
            return
        }
        runBlocking {
            sidecar = Sidecar.start(indexScript, dataRoot)
            val endpoint = sidecar!!.endpoint
            MindooDBBridge.createTenant(
                endpoint, tenantId = "tenant-rt", adminName = "cn=admin", adminPassword = "adminpw",
                userId = "cn=alice", password = "pwA", databases = listOf("notes"),
            )
            val session = MindooDBBridge.openSession(endpoint, "tenant-rt", "cn=alice", "pwA", listOf("notes"))
            try {
                val docs = session.database("notes").documents
                val doc = docs.create(mapOf("type" to "page"))
                val bold = buildJsonObject { put("bold", true) }

                docs.update(doc.id) {
                    richText(listOf("content"), listOf(
                        RichTextSpan.Block(buildJsonObject { put("type", "paragraph") }),
                        RichTextSpan.Text("Hello "),
                        RichTextSpan.Text("world", bold),
                    ))
                }
                var snapshot = docs.getRichText(doc.id, listOf("content"))
                assertTrue(RichTextSpan.Text("world", bold) in snapshot.spans, "marks kept: ${snapshot.spans}")

                // "￼" stands for the block marker: "Hello" starts at index 1.
                docs.update(doc.id, baseHeads = snapshot.heads) {
                    richTextSteps(listOf("content"), listOf(
                        RichTextStep.Splice(
                            index = 7, insert = "big ",
                            marks = listOf(RichTextMarkRange(7, 3, buildJsonObject { put("italic", true) })),
                        ),
                    ))
                    textMark(listOf("content"), 1, 5, mapOf("underline" to true))
                }
                snapshot = docs.getRichText(doc.id, listOf("content"))
                val texts = snapshot.spans.filterIsInstance<RichTextSpan.Text>()
                assertEquals("Hello big world", texts.joinToString("") { it.value })
                assertEquals(JsonPrimitive(true), texts.first { it.value == "Hello" }.marks?.get("underline"))
                assertEquals(JsonPrimitive(true), texts.first { it.value == "big" }.marks?.get("italic"))

                docs.update(doc.id) { textUnmark(listOf("content"), 1, 5, listOf("underline")) }
                assertTrue(docs.getRichText(doc.id, listOf("content")).spans
                    .filterIsInstance<RichTextSpan.Text>().none { it.marks?.containsKey("underline") == true })

                // Cursors keep pointing at the same characters.
                docs.update(doc.id) { setText("title", "Some title") }
                val cursors = docs.getTextCursors(
                    doc.id, listOf("title"), listOf(TextCursorPosition.At(5), TextCursorPosition.End),
                )
                docs.update(doc.id) { textSplice(listOf("title"), 0, 0, "Awesome ") }
                assertEquals(listOf(13, 18), docs.resolveTextCursors(doc.id, listOf("title"), cursors.cursors).positions)
                assertEquals(
                    listOf(5, 10),
                    docs.resolveTextCursors(doc.id, listOf("title"), cursors.cursors, heads = cursors.heads).positions,
                )
            } finally {
                session.close()
            }
        }
    }
}
