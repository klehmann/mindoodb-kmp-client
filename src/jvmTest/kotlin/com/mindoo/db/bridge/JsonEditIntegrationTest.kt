package com.mindoo.db.bridge

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DocumentApi.applyEdit] through the real sidecar: two edits of the same
 * version (as two editors would make them) both survive, also inside one text
 * and one nested object, and typed values keep their type.
 */
class JsonEditIntegrationTest {

    private lateinit var dataRoot: Path
    private var sidecar: SidecarProcess? = null

    private val bridgeDir: Path = Paths.get("..", "mindoodb-jvm-bridge").toAbsolutePath().normalize()
    private val indexScript: Path get() = bridgeDir.resolve("dist/index.js")

    @BeforeTest
    fun setup() {
        if (!indexScript.exists()) return
        dataRoot = Files.createTempDirectory("mindoodb-bridge-edit-it")
    }

    @AfterTest
    fun tearDown() {
        sidecar?.stop()
        if (::dataRoot.isInitialized) dataRoot.toFile().deleteRecursively()
    }

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun concurrentEditsOfOneVersionMerge() {
        if (!indexScript.exists()) {
            println("[integration] skipped: build the sidecar (dist/)")
            return
        }
        runBlocking {
            sidecar = Sidecar.start(indexScript, dataRoot)
            val endpoint = sidecar!!.endpoint
            MindooDBBridge.createTenant(
                endpoint, tenantId = "tenant-edit", adminName = "cn=admin", adminPassword = "adminpw",
                userId = "cn=alice", password = "pwA", databases = listOf("notes"),
            )
            val session = MindooDBBridge.openSession(endpoint, "tenant-edit", "cn=alice", "pwA", listOf("notes"))
            try {
                val docs = session.database("notes").documents
                val created = docs.create(mapOf(
                    "title" to "The quick fox",
                    "status" to MindooValue.atomic("open"),
                    "views" to MindooValue.counter(10),
                    "address" to mapOf("street" to "Main St 1", "city" to "Berlin"),
                    "tags" to listOf("a", "b", "c"),
                ))
                val base = docs.get(created.id, withFieldTypes = true)
                assertEquals("atomic", base.fieldTypes["/status"])
                assertEquals("counter", base.fieldTypes["/views"])
                assertNull(base.fieldTypes["/title"], "plain text is not listed")

                // Editor 1 and editor 2 both start from `base`.
                val edit1 = obj("""{"title":"The quick brown fox","status":"done","views":11,
                    "address":{"street":"Main St 1","city":"Hamburg"},"tags":["a","b","c","d"]}""")
                val edit2 = obj("""{"title":"The quick fox jumps","status":"open","views":12,
                    "address":{"street":"Elm St 2","city":"Berlin"},"tags":["a","c"]}""")

                val ops1 = JsonEditDiff.diff(base.data, edit1, base.fieldTypes)
                assertTrue(ops1.none { it.jsonObject["op"]?.jsonPrimitive?.content == "set" && it.jsonObject["path"]?.jsonArray?.size == 1 && it.jsonObject["path"]!!.jsonArray[0].jsonPrimitive.content == "title" }, "title is spliced, not replaced: $ops1")

                assertFalse(docs.applyEdit(base, edit1)!!.conflictExpected == true)
                val second = docs.applyEdit(base, edit2)!!
                assertTrue(second.conflictExpected == true, "the second editor saved an older version")

                val merged = docs.get(created.id, withFieldTypes = true)
                val data = merged.data
                assertEquals("The quick brown fox jumps", data["title"]?.jsonPrimitive?.content)
                assertEquals("done", data["status"]?.jsonPrimitive?.content, "edit 2 left status alone")
                assertEquals("atomic", merged.fieldTypes["/status"])
                assertEquals(13L, data["views"]?.jsonPrimitive?.content?.toLong(), "both increments count")
                assertEquals("counter", merged.fieldTypes["/views"])
                val address = data["address"]!!.jsonObject
                assertEquals("Elm St 2", address["street"]?.jsonPrimitive?.content)
                assertEquals("Hamburg", address["city"]?.jsonPrimitive?.content)
                assertEquals(listOf("a", "c", "d"), data["tags"]!!.jsonArray.map { it.jsonPrimitive.content })

                // Nothing changed: no update at all.
                assertNull(docs.applyEdit(merged, JsonObject(merged.data.filterKeys { !it.startsWith("_") })))

                // Unset a field, add one, change a type.
                val edit3 = obj("""{"title":"The quick brown fox jumps","status":"done","views":13,
                    "address":{"street":"Elm St 2","city":"Hamburg"},"tags":"none","note":"new"}""")
                docs.applyEdit(merged, edit3)
                val after = docs.get(created.id).data
                assertEquals(JsonPrimitive("none"), after["tags"])
                assertEquals(JsonPrimitive("new"), after["note"])
            } finally {
                session.close()
            }
        }
    }
}
