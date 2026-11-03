package com.mindoo.db.bridge

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Accumulates document update operations for [DocumentApi.update]. */
class UpdateOpsBuilder {
    private val ops = mutableListOf<JsonObject>()

    fun set(path: List<String>, value: Any?) {
        ops.add(buildJsonObject {
            put("op", "set")
            put("path", buildJsonArray { path.forEach { add(it) } })
            put("value", anyToJson(value))
        })
    }

    fun set(field: String, value: Any?) = set(listOf(field), value)

    fun unset(path: List<String>) {
        ops.add(buildJsonObject {
            put("op", "unset")
            put("path", buildJsonArray { path.forEach { add(it) } })
        })
    }

    fun unset(field: String) = unset(listOf(field))

    fun merge(values: Map<String, Any?>) {
        ops.add(buildJsonObject {
            put("op", "merge")
            put("value", anyToJson(values))
        })
    }

    fun setText(path: List<String>, value: String) {
        ops.add(buildJsonObject {
            put("op", "setText")
            put("path", buildJsonArray { path.forEach { add(it) } })
            put("value", value)
        })
    }

    internal fun build(): JsonArray = JsonArray(ops)
}

fun updateOps(block: UpdateOpsBuilder.() -> Unit): JsonArray = UpdateOpsBuilder().apply(block).build()

/** Document CRUD + history for one database. */
class DocumentApi internal constructor(
    private val rpc: RpcClient,
    private val dbId: String,
) {
    suspend fun get(docId: String): MindooDocument =
        rpc.call(Methods.DOC_GET, params { put("db", dbId); put("docId", docId) })

    suspend fun list(idPrefix: String? = null): List<String> =
        rpc.call<DocumentIdList>(Methods.DOC_LIST, params {
            put("db", dbId)
            idPrefix?.let { put("options", buildJsonObject { put("idPrefix", it) }) }
        }).ids

    suspend fun headCursor(): JsonElement? =
        rpc.call<HeadCursor>(Methods.DOC_HEAD_CURSOR, params { put("db", dbId) }).cursor

    suspend fun create(initialValues: Map<String, Any?>? = null, id: String? = null): MindooDocument =
        rpc.call(Methods.DOC_CREATE, params {
            put("db", dbId)
            put("options", buildJsonObject {
                id?.let { put("id", it) }
                initialValues?.let { put("initialValues", anyToJson(it)) }
            })
        })

    suspend fun createMany(inputs: List<Map<String, Any?>>): List<MindooDocument> =
        rpc.call<CreatedManyResult>(Methods.DOC_CREATE_MANY, params {
            put("db", dbId)
            put("inputs", buildJsonArray {
                inputs.forEach { input -> add(buildJsonObject { put("initialValues", anyToJson(input)) }) }
            })
        }).documents

    suspend fun update(docId: String, baseHeads: List<String>? = null, ops: JsonArray): UpdateResult =
        rpc.call(Methods.DOC_UPDATE, params {
            put("db", dbId)
            put("docId", docId)
            baseHeads?.let { put("baseHeads", buildJsonArray { it.forEach { h -> add(h) } }) }
            put("operations", ops)
        })

    suspend fun update(docId: String, baseHeads: List<String>? = null, block: UpdateOpsBuilder.() -> Unit): UpdateResult =
        update(docId, baseHeads, updateOps(block))

    suspend fun delete(docId: String) {
        rpc.callRaw(Methods.DOC_DELETE, params { put("db", dbId); put("docId", docId) })
    }

    suspend fun undelete(docId: String) {
        rpc.callRaw(Methods.DOC_UNDELETE, params { put("db", dbId); put("docId", docId) })
    }

    suspend fun history(docId: String): List<HistoryEntry> =
        rpc.call<HistoryResult>(Methods.DOC_LIST_HISTORY, params { put("db", dbId); put("docId", docId) }).history

    suspend fun getAtRevision(docId: String, timestamp: Long): MindooDocument? =
        rpc.call<DocumentResult>(Methods.DOC_GET_AT_REVISION, params {
            put("db", dbId); put("docId", docId); put("timestamp", timestamp)
        }).document
}

/** Attachment list/read/write for one database. */
class AttachmentApi internal constructor(
    private val rpc: RpcClient,
    private val dbId: String,
) {
    suspend fun list(docId: String): List<AttachmentInfo> =
        rpc.call<AttachmentListResult>(Methods.ATTACH_LIST, params { put("db", dbId); put("docId", docId) }).attachments

    suspend fun remove(docId: String, fileName: String) {
        rpc.callRaw(Methods.ATTACH_REMOVE, params { put("db", dbId); put("docId", docId); put("fileName", fileName) })
    }

    /** Stream an attachment's bytes as a cold flow (credit-based backpressure). */
    fun read(docId: String, fileName: String, startOffset: Long = 0): Flow<ByteArray> = flow {
        val open: OpenReadResult = rpc.call(Methods.ATTACH_OPEN_READ, params {
            put("db", dbId); put("docId", docId); put("fileName", fileName); put("startOffset", startOffset)
        })
        val sink = rpc.registerReadStream(open.streamId)
        try {
            for (chunk in sink.chunks) {
                emit(chunk)
                // Replenish one credit per consumed chunk to keep the window full.
                rpc.sendStreamCredit(open.streamId, 1)
            }
        } finally {
            rpc.unregisterReadStream(open.streamId)
        }
    }

    /** Upload an attachment from a chunk flow. Returns the stored attachment reference. */
    suspend fun upload(docId: String, fileName: String, mimeType: String, chunks: Flow<ByteArray>): AttachmentInfo {
        val open: OpenWriteResult = rpc.call(Methods.ATTACH_OPEN_WRITE, params {
            put("db", dbId); put("docId", docId); put("fileName", fileName); put("mimeType", mimeType)
        })
        val credits = rpc.registerWriteStream(open.streamId, open.credits)
        try {
            var available = 0
            var seq = 0
            chunks.collect { chunk ->
                while (available <= 0) available += credits.receive()
                rpc.sendBinaryChunk(open.streamId, seq++, chunk)
                available--
            }
            val closed: CloseWriteResult = rpc.call(Methods.ATTACH_CLOSE_WRITE, params { put("streamId", open.streamId) })
            return closed.attachment
        } catch (t: Throwable) {
            rpc.callRaw(Methods.ATTACH_ABORT, params { put("streamId", open.streamId) })
            throw t
        } finally {
            rpc.unregisterWriteStream(open.streamId)
        }
    }

    /** Upload an attachment from an in-memory byte array (chunked internally). */
    suspend fun uploadBytes(
        docId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        chunkSize: Int = 64 * 1024,
    ): AttachmentInfo = upload(docId, fileName, mimeType, flow {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            emit(bytes.copyOfRange(offset, end))
            offset = end
        }
    })
}

/** A database within an open session: the main handle for reads, writes, and views. */
class Database internal constructor(
    private val rpc: RpcClient,
    val id: String,
) {
    val documents: DocumentApi = DocumentApi(rpc, id)
    val attachments: AttachmentApi = AttachmentApi(rpc, id)

    suspend fun query(query: JsonObject, options: JsonObject? = null): QueryResult =
        rpc.call(Methods.QUERY, params {
            put("db", id)
            put("query", query)
            options?.let { put("options", it) }
        })

    /** Live query: emits an updated [QueryResult] whenever the result changes. */
    fun liveQuery(query: JsonObject, options: JsonObject? = null): Flow<QueryResult> = channelFlow {
        val subscriptionId = rpc.newSubscriptionId()
        val job = launch {
            rpc.events(subscriptionId).collect { ev ->
                when (ev.event) {
                    "query-result" -> trySend(rpc.json.decodeFromJsonElement(QueryResult.serializer(), ev.payload))
                    "query-error" -> close(BridgeException(ErrorCode.Internal, ev.payload.toString()))
                }
            }
        }
        rpc.callRaw(Methods.LIVE_QUERY_START, params {
            put("db", id); put("subscriptionId", subscriptionId); put("query", query)
            options?.let { put("options", it) }
        })
        awaitClose {
            job.cancel()
            launch { rpc.callRaw(Methods.LIVE_QUERY_STOP, params { put("subscriptionId", subscriptionId) }) }
        }
    }

    /** Follow the changefeed from an optional cursor; emits each changed document, then live updates. */
    fun changes(sinceCursor: JsonElement? = null): Flow<DocumentChange> = channelFlow {
        val subscriptionId = rpc.newSubscriptionId()
        val job = launch {
            rpc.events(subscriptionId).collect { ev ->
                when (ev.event) {
                    "change" -> trySend(rpc.json.decodeFromJsonElement(DocumentChange.serializer(), ev.payload))
                    "change-error" -> close(BridgeException(ErrorCode.Internal, ev.payload.toString()))
                }
            }
        }
        rpc.callRaw(Methods.CHANGES_ITERATE_SINCE, params {
            put("db", id); put("subscriptionId", subscriptionId)
            put("cursor", sinceCursor ?: JsonNull)
        })
        awaitClose {
            job.cancel()
            launch { rpc.callRaw(Methods.LIVE_QUERY_STOP, params { put("subscriptionId", subscriptionId) }) }
        }
    }

    /** Open a virtual-view navigator over an ephemeral summary view. */
    suspend fun openViewNavigator(
        definition: JsonObject,
        options: JsonObject? = null,
        navigatorOptions: JsonObject? = null,
    ): ViewNavigator {
        val handle: NavigatorHandle = rpc.call(Methods.VIEW_OPEN_NAVIGATOR, params {
            put("db", id)
            put("definition", definition)
            options?.let { put("options", it) }
            navigatorOptions?.let { put("navigatorOptions", it) }
        })
        return ViewNavigator(rpc, handle.navigatorId)
    }

    suspend fun getFulltextSetup(): JsonElement? =
        rpc.call<FulltextSetupResult>(Methods.FULLTEXT_GET_SETUP, params { put("db", id) }).config

    suspend fun setFulltextSetup(config: JsonElement) {
        rpc.callRaw(Methods.FULLTEXT_SET_SETUP, params { put("db", id); put("config", config) })
    }
}
