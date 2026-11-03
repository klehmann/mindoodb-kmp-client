package com.mindoo.db.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** An inbound push event keyed by its subscription. */
data class EventEnvelope(val subscriptionId: String, val event: String, val payload: JsonElement)

/** Sink that receives the binary chunks of one attachment read stream. */
class ReadStreamSink {
    // Unlimited: in-flight chunks are already bounded by the credit window, so
    // this never grows without bound and we never drop a chunk from trySend.
    val chunks: Channel<ByteArray> = Channel(Channel.UNLIMITED)
    fun onChunk(payload: ByteArray) {
        chunks.trySend(payload)
    }
    fun onEnd() {
        chunks.close()
    }
    fun onError(cause: Throwable) {
        chunks.close(cause)
    }
}

/**
 * Transport-agnostic JSON-RPC client for the bridge. Owns the pending-request
 * map, event fan-out, and attachment-stream routing. Mirrors the App Bridge's
 * `portRpcClient` but over a duplex frame [Transport].
 */
class RpcClient(
    private val transport: Transport,
    private val scope: CoroutineScope,
) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    @Volatile
    var sessionToken: String? = null

    private val pending = mutableMapOf<String, CompletableDeferred<JsonElement>>()
    private val pendingMutex = Mutex()
    private var counter = 0
    private val counterMutex = Mutex()

    private val eventFlow = MutableSharedFlow<EventEnvelope>(extraBufferCapacity = 256)

    private val readSinks = mutableMapOf<Int, ReadStreamSink>()
    private val writeCredits = mutableMapOf<Int, Channel<Int>>()
    private val streamMutex = Mutex()

    fun start() {
        scope.launch {
            try {
                transport.incoming.collect { message ->
                    when (message) {
                        is TransportMessage.Text -> handleText(message.json)
                        is TransportMessage.Binary -> handleBinary(message.bytes)
                    }
                }
            } finally {
                failAllPending(BridgeException(ErrorCode.SessionClosed, "Transport closed"))
            }
        }
    }

    private suspend fun nextId(): String = counterMutex.withLock { "c-${++counter}" }

    /** Allocate a fresh subscription id for a live query / view watch / changefeed. */
    suspend fun newSubscriptionId(): String = counterMutex.withLock { "sub-${++counter}" }

    /** Send a request and await the raw JSON result. */
    suspend fun callRaw(method: String, params: JsonElement): JsonElement {
        val id = nextId()
        val deferred = CompletableDeferred<JsonElement>()
        pendingMutex.withLock { pending[id] = deferred }
        val frame = buildJsonObject {
            put("protocol", PROTOCOL)
            put("kind", "request")
            put("id", id)
            sessionToken?.let { put("sessionToken", it) }
            put("method", method)
            put("params", params)
        }
        transport.sendText(json.encodeToString(JsonObject.serializer(), frame))
        return deferred.await()
    }

    /** Send a request and decode the result into [T]. */
    suspend inline fun <reified T> call(method: String, params: JsonElement): T {
        val result = callRaw(method, params)
        return json.decodeFromJsonElement<T>(result)
    }

    /** Events for a specific subscription id. */
    fun events(subscriptionId: String): Flow<EventEnvelope> =
        eventFlow.filter { it.subscriptionId == subscriptionId }

    fun eventPayloads(subscriptionId: String, event: String): Flow<JsonElement> =
        events(subscriptionId).filter { it.event == event }.map { it.payload }

    /**
     * The sink for a read stream. The sidecar starts sending as soon as the
     * stream is open, so chunks can arrive before the `openReadStream` reply;
     * they are buffered in a sink created on first sight and handed out here.
     */
    suspend fun registerReadStream(streamId: Int): ReadStreamSink =
        streamMutex.withLock { readSinks.getOrPut(streamId) { ReadStreamSink() } }

    suspend fun unregisterReadStream(streamId: Int) {
        streamMutex.withLock { readSinks.remove(streamId) }
    }

    suspend fun registerWriteStream(streamId: Int, initialCredits: Int): Channel<Int> {
        val channel = Channel<Int>(Channel.UNLIMITED)
        channel.trySend(initialCredits)
        streamMutex.withLock { writeCredits[streamId] = channel }
        return channel
    }

    suspend fun unregisterWriteStream(streamId: Int) {
        streamMutex.withLock { writeCredits.remove(streamId)?.close() }
    }

    suspend fun sendStreamCredit(streamId: Int, credits: Int) {
        val frame = buildJsonObject {
            put("protocol", PROTOCOL)
            put("kind", "stream-credit")
            put("streamId", streamId)
            put("credits", credits)
        }
        transport.sendText(json.encodeToString(JsonObject.serializer(), frame))
    }

    suspend fun sendBinaryChunk(streamId: Int, seq: Int, payload: ByteArray) {
        transport.sendBinary(encodeBinaryChunk(streamId, seq, payload))
    }

    private suspend fun handleText(text: String) {
        val obj = json.parseToJsonElement(text).jsonObject
        when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
            "success" -> {
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return
                val result = obj["result"] ?: JsonNull
                pendingMutex.withLock { pending.remove(id) }?.complete(result)
            }
            "error" -> {
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return
                val error = obj["error"]?.jsonObject
                val code = ErrorCode.fromWire(error?.get("code")?.jsonPrimitive?.contentOrNull)
                val msg = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                pendingMutex.withLock { pending.remove(id) }?.completeExceptionally(BridgeException(code, msg))
            }
            "event" -> {
                val subscriptionId = obj["subscriptionId"]?.jsonPrimitive?.contentOrNull ?: return
                val event = obj["event"]?.jsonPrimitive?.contentOrNull ?: return
                eventFlow.emit(EventEnvelope(subscriptionId, event, obj["payload"] ?: JsonNull))
            }
            "stream-credit" -> {
                val streamId = obj["streamId"]?.jsonPrimitive?.int ?: return
                val credits = obj["credits"]?.jsonPrimitive?.int ?: return
                streamMutex.withLock { writeCredits[streamId] }?.trySend(credits)
            }
            "stream-end" -> {
                val streamId = obj["streamId"]?.jsonPrimitive?.int ?: return
                streamMutex.withLock { readSinks.getOrPut(streamId) { ReadStreamSink() } }.onEnd()
            }
            "stream-error" -> {
                val streamId = obj["streamId"]?.jsonPrimitive?.int ?: return
                val error = obj["error"]?.jsonObject
                val code = ErrorCode.fromWire(error?.get("code")?.jsonPrimitive?.contentOrNull)
                val msg = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "stream error"
                streamMutex.withLock { readSinks.getOrPut(streamId) { ReadStreamSink() } }.onError(BridgeException(code, msg))
            }
        }
    }

    private suspend fun handleBinary(bytes: ByteArray) {
        val chunk = decodeBinaryChunk(bytes) ?: return
        streamMutex.withLock { readSinks.getOrPut(chunk.streamId) { ReadStreamSink() } }.onChunk(chunk.payload)
    }

    private suspend fun failAllPending(cause: Throwable) {
        pendingMutex.withLock {
            pending.values.forEach { it.completeExceptionally(cause) }
            pending.clear()
        }
        streamMutex.withLock {
            readSinks.values.forEach { it.onError(cause) }
            readSinks.clear()
            writeCredits.values.forEach { it.close() }
            writeCredits.clear()
        }
    }

    suspend fun close() {
        transport.close()
    }
}
