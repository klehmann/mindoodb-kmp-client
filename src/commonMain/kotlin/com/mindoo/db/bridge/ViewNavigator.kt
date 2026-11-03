package com.mindoo.db.bridge

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/**
 * Client handle for a server-side VirtualViewNavigator. Paging and cursor moves
 * are `suspend` round-trips; call [close] (or use within a `use`-style block) to
 * release the server navigator.
 */
class ViewNavigator internal constructor(
    private val rpc: RpcClient,
    val navigatorId: String,
) {
    private suspend fun navCall(method: String, args: List<JsonElement> = emptyList()): JsonElement {
        val result: NavCallResult = rpc.call(Methods.NAV_CALL, params {
            put("navigatorId", navigatorId)
            put("method", method)
            put("args", JsonArray(args))
        })
        return result.result ?: JsonNull
    }

    private fun decodeEntry(element: JsonElement): ViewEntry? =
        if (element is JsonNull) null else rpc.json.decodeFromJsonElement(ViewEntry.serializer(), element)

    private fun decodeEntries(element: JsonElement): List<ViewEntry> =
        rpc.json.decodeFromJsonElement(ListSerializer(ViewEntry.serializer()), element)

    suspend fun readForward(limit: Int = 100, offset: Int = 0): List<ViewEntry> =
        decodeEntries(navCall("readForward", listOf(intArg(limit), intArg(offset))))

    suspend fun readBackward(limit: Int = 100, offset: Int = 0): List<ViewEntry> =
        decodeEntries(navCall("readBackward", listOf(intArg(limit), intArg(offset))))

    suspend fun current(): ViewEntry? = decodeEntry(navCall("getCurrentEntry"))
    suspend fun gotoFirst(): ViewEntry? = decodeEntry(navCall("gotoFirst"))
    suspend fun gotoLast(): ViewEntry? = decodeEntry(navCall("gotoLast"))
    suspend fun gotoNext(): ViewEntry? = decodeEntry(navCall("gotoNext"))
    suspend fun gotoPrev(): ViewEntry? = decodeEntry(navCall("gotoPrev"))
    suspend fun gotoParent(): ViewEntry? = decodeEntry(navCall("gotoParent"))
    suspend fun gotoFirstChild(): ViewEntry? = decodeEntry(navCall("gotoFirstChild"))

    suspend fun expandAll() { navCall("expandAll") }
    suspend fun collapseAll() { navCall("collapseAll") }
    suspend fun expandToLevel(level: Int) { navCall("expandToLevel", listOf(intArg(level))) }
    suspend fun expand(origin: String, docId: String) { navCall("expand", listOf(strArg(origin), strArg(docId))) }
    suspend fun collapse(origin: String, docId: String) { navCall("collapse", listOf(strArg(origin), strArg(docId))) }

    /** Emits [Unit] whenever the underlying view changes. */
    fun changes(): Flow<Unit> = channelFlow {
        val subscriptionId = rpc.newSubscriptionId()
        val job = launch {
            rpc.events(subscriptionId).collect { ev -> if (ev.event == "view-changed") trySend(Unit) }
        }
        rpc.callRaw(Methods.NAV_SUBSCRIBE, params {
            put("navigatorId", navigatorId)
            put("subscriptionId", subscriptionId)
        })
        awaitClose {
            job.cancel()
            launch { rpc.callRaw(Methods.NAV_UNSUBSCRIBE, params { put("subscriptionId", subscriptionId) }) }
        }
    }

    suspend fun close() {
        rpc.callRaw(Methods.NAV_DISPOSE, params { put("navigatorId", navigatorId) })
    }

    private fun intArg(value: Int): JsonElement = JsonPrimitive(value)
    private fun strArg(value: String): JsonElement = JsonPrimitive(value)
}
