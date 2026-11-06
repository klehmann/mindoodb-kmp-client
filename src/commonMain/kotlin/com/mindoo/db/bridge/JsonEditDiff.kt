package com.mindoo.db.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Turns "the document as it was" and "the document as edited" (plain JSON)
 * into the smallest set of update operations, for [DocumentApi.applyEdit].
 *
 * - Objects are compared key by key: new keys are set, missing ones unset.
 * - Collaborative text (and rich text) becomes character splices, so two
 *   people editing different words of the same text both keep their edit.
 * - Lists whose elements only changed in place are compared element by
 *   element; otherwise the inserted and removed elements become list inserts
 *   and deletes.
 * - Counters become increments, atomic strings and timestamps new typed values,
 *   everything else (numbers, booleans, null, a changed type) a new value.
 * - A `{"$mindoo": …}` object in the edited copy is written as that typed value.
 *
 * Operation order follows MindooDB's JSON patch, which applies all `set`s,
 * then `unset`s, list deletes, list inserts and text splices: splices and
 * nested edits only happen inside lists whose elements stay in place, so
 * their paths stay valid while other lists change.
 */
object JsonEditDiff {
    /** Above this many differing characters (per text), one splice replaces the changed middle. */
    private const val MAX_TEXT_EDIT_DISTANCE = 2_000

    /** Above this many differing elements (per list), the changed middle is replaced. */
    private const val MAX_LIST_EDIT_DISTANCE = 500

    fun diffInto(builder: UpdateOpsBuilder, before: JsonObject, after: JsonObject, fieldTypes: Map<String, String> = emptyMap()) {
        val ctx = Context(builder, fieldTypes)
        val keys = (before.keys + after.keys).filterNot { it.startsWith("_") }
        for (key in keys) {
            ctx.diffValue(listOf(JsonPrimitive(key)), before[key], after[key])
        }
    }

    /** The operations as JSON, for tests and logging. */
    fun diff(before: JsonObject, after: JsonObject, fieldTypes: Map<String, String> = emptyMap()): JsonArray =
        UpdateOpsBuilder().also { diffInto(it, before, after, fieldTypes) }.build()

    private class Context(val out: UpdateOpsBuilder, val fieldTypes: Map<String, String>) {
        fun diffValue(path: List<JsonPrimitive>, before: JsonElement?, after: JsonElement?) {
            when {
                after == null -> if (before != null) op("unset", path)
                before == null -> set(path, after)
                before == after -> Unit
                after is JsonObject && after.containsKey("\$mindoo") -> set(path, after)
                before is JsonObject && after is JsonObject -> {
                    for (key in (before.keys + after.keys)) diffValue(path + JsonPrimitive(key), before[key], after[key])
                }
                before is JsonArray && after is JsonArray -> diffList(path, before, after)
                before is JsonPrimitive && after is JsonPrimitive -> diffScalar(path, before, after)
                else -> set(path, after)
            }
        }

        private fun diffScalar(path: List<JsonPrimitive>, before: JsonPrimitive, after: JsonPrimitive) {
            val type = fieldTypes[pointer(path)]
            when (type) {
                "atomic" -> if (after.isString) set(path, MindooValue.atomic(after.content)) else set(path, after)
                "counter" -> {
                    val from = before.longOrNull
                    val to = after.longOrNull
                    if (from != null && to != null && !after.isString) increment(path, to - from) else set(path, after)
                }
                "timestamp" -> if (after.isString) {
                    set(path, buildJsonObject { put("\$mindoo", "timestamp"); put("value", after.content) })
                } else set(path, after)
                "bytes" -> set(path, after)
                // plain or rich text: splice the characters that changed
                else -> if (before.isString && after.isString && before !is JsonNull) {
                    textSplices(path, before.content, after.content)
                } else set(path, after)
            }
        }

        private fun diffList(path: List<JsonPrimitive>, before: JsonArray, after: JsonArray) {
            val hunks = Myers.hunks(before, after, MAX_LIST_EDIT_DISTANCE)
            if (hunks.all { it.deleteCount == it.insertCount && it.beforeIndex == it.afterIndex }) {
                // nothing moved: edit the changed elements in place
                for (h in hunks) for (i in h.beforeIndex until h.beforeIndex + h.deleteCount) {
                    diffValue(path + JsonPrimitive(i), before[i], after[i])
                }
                return
            }
            // deletes run first and back to front, so each index is one of `before`
            for (h in hunks.asReversed()) {
                if (h.deleteCount > 0) {
                    out.raw(buildJsonObject {
                        put("op", "listDelete"); put("path", jsonPath(path))
                        put("index", h.beforeIndex); put("deleteCount", h.deleteCount)
                    })
                }
            }
            // then inserts front to back at their final index
            for (h in hunks) {
                if (h.insertCount > 0) {
                    out.raw(buildJsonObject {
                        put("op", "listInsert"); put("path", jsonPath(path))
                        put("index", h.afterIndex)
                        put("values", JsonArray(after.subList(h.afterIndex, h.afterIndex + h.insertCount)))
                    })
                }
            }
        }

        private fun textSplices(path: List<JsonPrimitive>, before: String, after: String) {
            val hunks = Myers.hunks(before.toList(), after.toList(), MAX_TEXT_EDIT_DISTANCE)
            // back to front: every index refers to the text as it was
            for (h in hunks.asReversed()) {
                out.raw(buildJsonObject {
                    put("op", "textSplice"); put("path", jsonPath(path))
                    put("index", h.beforeIndex); put("deleteCount", h.deleteCount)
                    if (h.insertCount > 0) put("insert", after.substring(h.afterIndex, h.afterIndex + h.insertCount))
                })
            }
        }

        private fun set(path: List<JsonPrimitive>, value: JsonElement) {
            out.raw(buildJsonObject { put("op", "set"); put("path", jsonPath(path)); put("value", value) })
        }

        private fun increment(path: List<JsonPrimitive>, delta: Long) {
            out.raw(buildJsonObject { put("op", "increment"); put("path", jsonPath(path)); put("delta", delta) })
        }

        private fun op(name: String, path: List<JsonPrimitive>) {
            out.raw(buildJsonObject { put("op", name); put("path", jsonPath(path)) })
        }
    }

    private fun jsonPath(path: List<JsonPrimitive>): JsonArray = JsonArray(path)

    /** The JSON Pointer the bridge uses as key of `fieldTypes`. */
    internal fun pointer(path: List<JsonPrimitive>): String =
        path.joinToString("") { "/" + it.content.replace("~", "~0").replace("/", "~1") }
}

/** One changed region: [deleteCount] items at [beforeIndex] became [insertCount] items at [afterIndex]. */
internal data class Hunk(val beforeIndex: Int, val deleteCount: Int, val afterIndex: Int, val insertCount: Int)

/** Myers' O(ND) difference algorithm, reduced to the changed regions. */
internal object Myers {
    fun <T> hunks(a: List<T>, b: List<T>, maxDistance: Int): List<Hunk> {
        // common prefix and suffix first: most edits touch one place
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }
        if (start == endA && start == endB) return emptyList()
        val midA = a.subList(start, endA)
        val midB = b.subList(start, endB)
        val script = shortestEditScript(midA, midB, maxDistance)
            ?: return listOf(Hunk(start, endA - start, start, endB - start))
        return script.map { Hunk(it.beforeIndex + start, it.deleteCount, it.afterIndex + start, it.insertCount) }
    }

    /** Null when the edit distance exceeds [maxDistance]. */
    private fun <T> shortestEditScript(a: List<T>, b: List<T>, maxDistance: Int): List<Hunk>? {
        val n = a.size
        val m = b.size
        val max = minOf(n + m, maxDistance)
        val offset = max + 1
        val v = IntArray(2 * max + 3)
        val trace = ArrayList<IntArray>()
        var found = -1
        outer@ for (d in 0..max) {
            trace.add(v.copyOf())
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[offset + k] = x
                if (x >= n && y >= m) { found = d; break@outer }
                k += 2
            }
        }
        if (found < 0) return null
        // walk back through the trace, collecting single-item edits
        data class Edit(val ai: Int, val bi: Int, val isDelete: Boolean)
        val edits = ArrayList<Edit>()
        var x = n
        var y = m
        for (d in found downTo 1) {
            val vd = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vd[offset + k - 1] < vd[offset + k + 1])) k + 1 else k - 1
            val prevX = vd[offset + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) { x--; y-- }
            if (prevK == k + 1) edits.add(Edit(prevX, prevY, isDelete = false)) // insert b[prevY]
            else edits.add(Edit(prevX, prevY, isDelete = true))                 // delete a[prevX]
            x = prevX
            y = prevY
        }
        edits.reverse()
        // merge adjacent edits into hunks
        val hunks = ArrayList<Hunk>()
        var i = 0
        while (i < edits.size) {
            val first = edits[i]
            var ai = first.ai
            var bi = first.bi
            var del = 0
            var ins = 0
            while (i < edits.size && edits[i].ai == ai + del && edits[i].bi == bi + ins) {
                if (edits[i].isDelete) del++ else ins++
                i++
            }
            hunks.add(Hunk(ai, del, bi, ins))
        }
        return hunks
    }
}
