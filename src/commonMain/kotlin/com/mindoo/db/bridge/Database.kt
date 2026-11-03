package com.mindoo.db.bridge

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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

    /** Set collaborative text (concurrent edits merge character-wise). */
    fun setText(path: List<String>, value: String) {
        ops.add(buildJsonObject {
            put("op", "setText")
            put("path", buildJsonArray { path.forEach { add(it) } })
            put("value", value)
        })
    }

    fun setText(field: String, value: String) = setText(listOf(field), value)

    /** Set an atomic string (IDs, status, enum values): concurrent changes never mix. */
    fun setAtomic(path: List<String>, value: String) = set(path, MindooValue.atomic(value))

    fun setAtomic(field: String, value: String) = setAtomic(listOf(field), value)

    /** Insert/delete characters of collaborative text at [path]. */
    fun textSplice(path: List<String>, index: Int, deleteCount: Int, insert: String? = null) {
        ops.add(buildJsonObject {
            put("op", "textSplice")
            put("path", buildJsonArray { path.forEach { add(it) } })
            put("index", index)
            put("deleteCount", deleteCount)
            insert?.let { put("insert", it) }
        })
    }

    /** Add [delta] to a counter created with [MindooValue.counter]; concurrent increments add up. */
    fun increment(path: List<String>, delta: Long = 1) {
        ops.add(buildJsonObject {
            put("op", "increment")
            put("path", buildJsonArray { path.forEach { add(it) } })
            put("delta", delta)
        })
    }

    fun increment(field: String, delta: Long = 1) = increment(listOf(field), delta)

    fun listInsert(path: List<String>, index: Int, values: List<Any?>) {
        ops.add(buildJsonObject {
            put("op", "listInsert")
            put("path", buildJsonArray { path.forEach { add(it) } })
            put("index", index)
            put("values", anyToJson(values))
        })
    }

    /** Apply marks (e.g. `{"bold": true}`) to [length] characters of text at [path], starting at [index]. */
    fun textMark(path: List<String>, index: Int, length: Int, marks: Map<String, Any?>) {
        ops.add(buildJsonObject {
            put("op", "textMark")
            put("path", buildJsonArray { path.forEach { add(it) } })
            put("index", index)
            put("length", length)
            put("marks", anyToJson(marks))
        })
    }

    /** Remove the marks named [names] from a range of text at [path]. */
    fun textUnmark(path: List<String>, index: Int, length: Int, names: List<String>) {
        ops.add(buildJsonObject {
            put("op", "textUnmark")
            put("path", buildJsonArray { path.forEach { add(it) } })
            put("index", index)
            put("length", length)
            put("names", buildJsonArray { names.forEach { add(it) } })
        })
    }

    /**
     * Replace the rich-text field at [path] with [spans]. MindooDB diffs them
     * against the current spans (at the update's `baseHeads`), so concurrent
     * edits elsewhere in the field survive. Creates the field when missing.
     */
    fun richText(path: List<String>, spans: List<RichTextSpan>) {
        ops.add(buildJsonObject {
            put("op", "richText")
            put("path", buildJsonArray { path.forEach { add(it) } })
            put("spans", richTextJson.encodeToJsonElement(ListSerializer(RichTextSpan.serializer()), spans))
        })
    }

    /** Positional rich-text edits (insert/delete/mark), applied in order at the update's `baseHeads`. */
    fun richTextSteps(path: List<String>, steps: List<RichTextStep>) {
        ops.add(buildJsonObject {
            put("op", "richTextSteps")
            put("path", buildJsonArray { path.forEach { add(it) } })
            put("steps", richTextJson.encodeToJsonElement(ListSerializer(RichTextStep.serializer()), steps))
        })
    }

    fun listDelete(path: List<String>, index: Int, deleteCount: Int = 1) {
        ops.add(buildJsonObject {
            put("op", "listDelete")
            put("path", buildJsonArray { path.forEach { add(it) } })
            put("index", index)
            put("deleteCount", deleteCount)
        })
    }

    internal fun build(): JsonArray = JsonArray(ops)
}

private val richTextJson = Json {
    encodeDefaults = true
    explicitNulls = false
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

    /**
     * Create a document. [id] is a custom ID (`^[a-z][a-z0-9_]*$`, idempotent and
     * convergent across replicas); [idPrefix] instead prefixes a generated ID.
     * [decryptionKeyId] encrypts the document with a named KeyBag key (see
     * `MindooDBSession.createDocKey`) instead of the tenant's `default` key.
     */
    suspend fun create(
        initialValues: Map<String, Any?>? = null,
        id: String? = null,
        idPrefix: String? = null,
        decryptionKeyId: String? = null,
        assumeUniqueId: Boolean? = null,
    ): MindooDocument =
        rpc.call(Methods.DOC_CREATE, params {
            put("db", dbId)
            put("options", buildJsonObject {
                id?.let { put("id", it) }
                idPrefix?.let { put("idPrefix", it) }
                decryptionKeyId?.let { put("decryptionKeyId", it) }
                assumeUniqueId?.let { put("assumeUniqueId", it) }
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

    suspend fun deleteMany(docIds: List<String>) {
        rpc.callRaw(Methods.DOC_DELETE_MANY, params {
            put("db", dbId)
            put("docIds", buildJsonArray { docIds.forEach { add(it) } })
        })
    }

    /** IDs of deleted documents (for undelete or cleanup). */
    suspend fun listDeleted(idPrefix: String? = null): List<String> =
        rpc.call<DocumentIdList>(Methods.DOC_LIST_DELETED, params {
            put("db", dbId)
            idPrefix?.let { put("options", buildJsonObject { put("idPrefix", it) }) }
        }).ids

    suspend fun undelete(docId: String) {
        rpc.callRaw(Methods.DOC_UNDELETE, params { put("db", dbId); put("docId", docId) })
    }

    /** The spans of a rich-text (or plain collaborative text) field, with marks; plain `get` drops the marks. */
    suspend fun getRichText(docId: String, path: List<String>): RichTextSnapshot =
        rpc.call(Methods.DOC_GET_RICH_TEXT, params {
            put("db", dbId); put("docId", docId)
            put("path", buildJsonArray { path.forEach { add(it) } })
        })

    /**
     * Stable cursors for character positions in the text at [path], e.g. to
     * anchor comments; store them with `setAtomic`. [heads] reads an older version.
     */
    suspend fun getTextCursors(
        docId: String,
        path: List<String>,
        positions: List<TextCursorPosition>,
        heads: List<String>? = null,
    ): TextCursors = rpc.call(Methods.DOC_GET_TEXT_CURSORS, params {
        put("db", dbId); put("docId", docId)
        put("path", buildJsonArray { path.forEach { add(it) } })
        put("positions", JsonArray(positions.map { it.toJson() }))
        heads?.let { h -> put("heads", buildJsonArray { h.forEach { add(it) } }) }
    })

    /** Current (or, with [heads], historical) character indexes of [cursors]. */
    suspend fun resolveTextCursors(
        docId: String,
        path: List<String>,
        cursors: List<String>,
        heads: List<String>? = null,
    ): TextCursorPositions = rpc.call(Methods.DOC_RESOLVE_TEXT_CURSORS, params {
        put("db", dbId); put("docId", docId)
        put("path", buildJsonArray { path.forEach { add(it) } })
        put("cursors", buildJsonArray { cursors.forEach { add(it) } })
        heads?.let { h -> put("heads", buildJsonArray { h.forEach { add(it) } }) }
    })

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

    /** Remove by [AttachmentInfo.attachmentId] (file names need not be unique). */
    suspend fun removeById(docId: String, attachmentId: String) {
        rpc.callRaw(Methods.ATTACH_REMOVE, params { put("db", dbId); put("docId", docId); put("attachmentId", attachmentId) })
    }

    /** Stream an attachment's bytes as a cold flow (credit-based backpressure). */
    fun read(docId: String, fileName: String, startOffset: Long = 0): Flow<ByteArray> =
        readStream(docId, startOffset) { put("fileName", fileName) }

    /** Like [read], addressed by [AttachmentInfo.attachmentId]. */
    fun readById(docId: String, attachmentId: String, startOffset: Long = 0): Flow<ByteArray> =
        readStream(docId, startOffset) { put("attachmentId", attachmentId) }

    private fun readStream(
        docId: String,
        startOffset: Long,
        ref: JsonObjectBuilder.() -> Unit,
    ): Flow<ByteArray> = flow {
        val open: OpenReadResult = rpc.call(Methods.ATTACH_OPEN_READ, params {
            put("db", dbId); put("docId", docId); ref(); put("startOffset", startOffset)
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

    /**
     * Read the changefeed after [sinceCursor] up to its current end, one page at
     * a time, without following live changes: the building block for jobs that
     * mirror data elsewhere. Save the returned page's `cursor` and pass it next time.
     * [idPrefix] skips other documents before they are decrypted.
     */
    suspend fun readChanges(sinceCursor: JsonElement? = null, limit: Int = 100, idPrefix: String? = null): ChangePage =
        rpc.call(Methods.CHANGES_READ, changesParams(sinceCursor, idPrefix) { put("limit", limit) })

    /** Like [readChanges], with document id, deleted flag and time only; nothing is decrypted. */
    suspend fun readChangeMetadata(
        sinceCursor: JsonElement? = null,
        limit: Int = 1000,
        idPrefix: String? = null,
    ): ChangeMetadataPage = rpc.call(Methods.CHANGES_READ, changesParams(sinceCursor, idPrefix) {
        put("limit", limit)
        put("metadataOnly", true)
    })

    /** Every change after [sinceCursor] up to the current end, paged internally; completes when caught up. */
    fun changesUntilNow(sinceCursor: JsonElement? = null, pageSize: Int = 100, idPrefix: String? = null): Flow<DocumentChange> = flow {
        var cursor = sinceCursor
        do {
            val page = readChanges(cursor, pageSize, idPrefix)
            page.changes.forEach { emit(it) }
            cursor = page.cursor ?: cursor
        } while (!page.done)
    }

    /** How many documents changed after [sinceCursor] (e.g. to show progress before [readChanges]). */
    suspend fun countChanges(sinceCursor: JsonElement? = null, idPrefix: String? = null): Int =
        rpc.call<ChangeCount>(Methods.CHANGES_COUNT, changesParams(sinceCursor, idPrefix) {}).count

    private fun changesParams(cursor: JsonElement?, idPrefix: String?, extra: JsonObjectBuilder.() -> Unit) = params {
        put("db", id)
        put("cursor", cursor ?: JsonNull)
        idPrefix?.let { put("idPrefix", it) }
        extra()
    }

    /** Follow the changefeed from an optional cursor; emits each changed document, then live updates. */
    fun changes(sinceCursor: JsonElement? = null, idPrefix: String? = null): Flow<DocumentChange> = channelFlow {
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
            idPrefix?.let { put("idPrefix", it) }
        })
        awaitClose {
            job.cancel()
            launch { rpc.callRaw(Methods.LIVE_QUERY_STOP, params { put("subscriptionId", subscriptionId) }) }
        }
    }

    /** Like [changes], with metadata only: replay, then follow live, without decrypting documents. */
    fun changeMetadata(sinceCursor: JsonElement? = null, idPrefix: String? = null): Flow<ChangeMetadataEvent> = channelFlow {
        val subscriptionId = rpc.newSubscriptionId()
        val job = launch {
            rpc.events(subscriptionId).collect { ev ->
                when (ev.event) {
                    "change" -> trySend(rpc.json.decodeFromJsonElement(ChangeMetadataEvent.serializer(), ev.payload))
                    "change-error" -> close(BridgeException(ErrorCode.Internal, ev.payload.toString()))
                }
            }
        }
        rpc.callRaw(Methods.CHANGES_ITERATE_SINCE, params {
            put("db", id); put("subscriptionId", subscriptionId)
            put("cursor", sinceCursor ?: JsonNull)
            put("metadataOnly", true)
            idPrefix?.let { put("idPrefix", it) }
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
