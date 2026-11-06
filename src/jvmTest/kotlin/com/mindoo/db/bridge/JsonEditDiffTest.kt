package com.mindoo.db.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** [JsonEditDiff] without a sidecar: the operations it emits, and that they rebuild the edited copy. */
class JsonEditDiffTest {
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun ops(before: String, after: String, types: Map<String, String> = emptyMap()): String =
        JsonEditDiff.diff(obj(before), obj(after), types).toString()

    @Test
    fun textBecomesSplices() {
        assertEquals(
            """[{"op":"textSplice","path":["t"],"index":10,"deleteCount":0,"insert":"brown "}]""",
            ops("""{"t":"The quick fox"}""", """{"t":"The quick brown fox"}"""),
        )
        // two places: back to front, so both indices refer to the old text
        assertEquals(
            """[{"op":"textSplice","path":["t"],"index":8,"deleteCount":3,"insert":"cat"},""" +
                """{"op":"textSplice","path":["t"],"index":0,"deleteCount":1,"insert":"A"}]""",
            ops("""{"t":"a quick fox"}""", """{"t":"A quick cat"}"""),
        )
    }

    @Test
    fun typedValuesKeepTheirType() {
        assertEquals(
            """[{"op":"set","path":["s"],"value":{"${'$'}mindoo":"atomic","value":"done"}},""" +
                """{"op":"increment","path":["n"],"delta":-2}]""",
            ops("""{"s":"open","n":5}""", """{"s":"done","n":3}""", mapOf("/s" to "atomic", "/n" to "counter")),
        )
    }

    @Test
    fun nestedKeysAndLists() {
        assertEquals(
            """[{"op":"set","path":["a","x"],"value":2},{"op":"unset","path":["a","y"]},{"op":"set","path":["b"],"value":true}]""",
            ops("""{"a":{"x":1,"y":1}}""", """{"a":{"x":2},"b":true}"""),
        )
        assertEquals(
            """[{"op":"listDelete","path":["l"],"index":1,"deleteCount":1},""" +
                """{"op":"listInsert","path":["l"],"index":2,"values":["d"]}]""",
            ops("""{"l":["a","b","c"]}""", """{"l":["a","c","d"]}"""),
        )
        // elements changed in place: per element, so the text inside is spliced
        assertEquals(
            """[{"op":"textSplice","path":["l",1,"n"],"index":3,"deleteCount":0,"insert":"!"}]""",
            ops("""{"l":[{"n":"one"},{"n":"two"}]}""", """{"l":[{"n":"one"},{"n":"two!"}]}"""),
        )
        assertEquals("[]", ops("""{"_lastModified":1,"a":1}""", """{"_lastModified":2,"a":1}"""))
    }

    /** Replays the splices the way MindooDB does (in order) and compares with the target. */
    @Test
    fun randomTextEditsReplayToTheTarget() {
        val rnd = Random(42)
        val alphabet = "ab c\n"
        repeat(500) {
            val a = String(CharArray(rnd.nextInt(0, 40)) { alphabet[rnd.nextInt(alphabet.length)] })
            val b = String(CharArray(rnd.nextInt(0, 40)) { alphabet[rnd.nextInt(alphabet.length)] })
            val result = JsonEditDiff.diff(JsonObject(mapOf("t" to kotlinx.serialization.json.JsonPrimitive(a))), JsonObject(mapOf("t" to kotlinx.serialization.json.JsonPrimitive(b))))
            val text = StringBuilder(a)
            for (op in result) {
                val o = op.jsonObject
                val index = o["index"].toString().toInt()
                val del = o["deleteCount"].toString().toInt()
                val ins = o["insert"]?.let { Json.decodeFromJsonElement(kotlinx.serialization.json.JsonPrimitive.serializer(), it).content } ?: ""
                text.replace(index, index + del, ins)
            }
            assertEquals(b, text.toString(), "from '$a' to '$b': $result")
        }
    }

    @Test
    fun randomListEditsReplayToTheTarget() {
        val rnd = Random(7)
        repeat(500) {
            val a = List(rnd.nextInt(0, 12)) { rnd.nextInt(4) }
            val b = List(rnd.nextInt(0, 12)) { rnd.nextInt(4) }
            val before = obj("""{"l":$a}""")
            val after = obj("""{"l":$b}""")
            val list = a.toMutableList()
            val result: JsonArray = JsonEditDiff.diff(before, after)
            for (op in result.filter { it.jsonObject["op"].toString() == "\"set\"" }) {
                val o = op.jsonObject
                list[(o["path"] as JsonArray)[1].toString().toInt()] = o["value"].toString().toInt()
            }
            for (op in result.filter { it.jsonObject["op"].toString() == "\"listDelete\"" }) {
                val o = op.jsonObject
                val index = o["index"].toString().toInt()
                repeat(o["deleteCount"].toString().toInt()) { list.removeAt(index) }
            }
            for (op in result.filter { it.jsonObject["op"].toString() == "\"listInsert\"" }) {
                val o = op.jsonObject
                val values = (o["values"] as JsonArray).map { it.toString().toInt() }
                list.addAll(o["index"].toString().toInt(), values)
            }
            assertEquals(b, list, "from $a to $b: $result")
        }
    }
}
