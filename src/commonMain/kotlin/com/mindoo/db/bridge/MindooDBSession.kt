package com.mindoo.db.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/**
 * An authenticated MindooDB session backed by one sidecar child process. Obtain
 * one via `MindooDBBridge.openSession(...)` on the JVM. All calls are `suspend`
 * and carry the per-session token automatically.
 */
class MindooDBSession internal constructor(
    private val rpc: RpcClient,
    val info: SessionInfo,
    private val closeTransport: suspend () -> Unit,
) {
    val sessionId: String get() = info.sessionId
    val databases: List<String> get() = info.databases

    private val dbCache = mutableMapOf<String, Database>()

    fun database(id: String): Database = dbCache.getOrPut(id) { Database(rpc, id) }

    suspend fun listDatabases(): List<String> =
        rpc.call<DatabaseListResult>(Methods.SESSION_LIST_DATABASES, params { }).databases

    /**
     * Open another database in this session.
     *
     * - [DatabaseMode.Local]: on-disk replica (default; eligible for sync).
     * - [DatabaseMode.Remote]: primary store is `ClientNetworkContentAddressedStore`
     *   (every CAS op is remoted — intentionally slow).
     */
    suspend fun openDatabase(dbId: String, mode: DatabaseMode = DatabaseMode.Local): OpenDatabaseResult =
        rpc.call(Methods.SESSION_OPEN_DATABASE, params {
            put("dbId", dbId)
            put("mode", mode.wire)
        })

    suspend fun refresh(dbId: String? = null): RefreshResult =
        rpc.call(Methods.SESSION_REFRESH, params { dbId?.let { put("db", it) } })

    suspend fun awaitIndexReady(dbId: String? = null) {
        rpc.callRaw(Methods.SESSION_AWAIT_INDEX_READY, params { dbId?.let { put("db", it) } })
    }

    /** Persist or clear the sync peer URL on the identity bundle and restart SyncLoop. */
    suspend fun setServerUrl(serverUrl: String?): ServerUrlResult =
        rpc.call(Methods.SESSION_SET_SERVER_URL, params {
            if (serverUrl == null) put("serverUrl", JsonNull) else put("serverUrl", serverUrl)
        })

    /**
     * Register this tenant on a MindooDB sync server. Requires the *server*
     * system-admin credentials (not the tenant admin). On success, persists
     * [serverUrl] and starts background sync.
     */
    suspend fun publishToServer(
        serverUrl: String,
        systemAdminUser: JsonElement,
        systemAdminPassword: String,
        adminPassword: String? = null,
        adminUsername: String? = null,
    ): JsonElement = rpc.callRaw(Methods.SESSION_PUBLISH_TO_SERVER, params {
        put("serverUrl", serverUrl)
        put("systemAdminUser", systemAdminUser)
        put("systemAdminPassword", systemAdminPassword)
        adminPassword?.let { put("adminPassword", it) }
        adminUsername?.let { put("adminUsername", it) }
    })

    // ----- KeyBag -----

    suspend fun createDocKey(keyId: String): CreateDocKeyResult =
        rpc.call(Methods.KEYBAG_CREATE_DOC_KEY, params { put("keyId", keyId) })

    suspend fun listKeys(): KeybagKeysResult =
        rpc.call(Methods.KEYBAG_LIST_KEYS, params { })

    suspend fun persistKeybag() {
        rpc.callRaw(Methods.KEYBAG_PERSIST, params { })
    }

    // ----- Join (admin) -----

    /**
     * Approve a join request. Prefer [adminIdentity] from [CreatedTenant.adminIdentity]
     * (or pass [adminSigningKey]). Default response format is a URI string.
     */
    suspend fun approveJoinRequest(
        joinRequest: JsonElement,
        adminPassword: String,
        sharePassword: String,
        adminIdentity: JsonElement? = null,
        adminSigningKey: JsonElement? = null,
        serverUrl: String? = null,
        sharedDocKeyIds: List<String>? = null,
        label: String? = null,
        format: String = "uri",
    ): ApproveJoinResult = rpc.call(Methods.TENANT_APPROVE_JOIN_REQUEST, params {
        put("joinRequest", joinRequest)
        put("adminPassword", adminPassword)
        put("sharePassword", sharePassword)
        put("format", format)
        adminIdentity?.let { put("adminIdentity", it) }
        adminSigningKey?.let { put("adminSigningKey", it) }
        serverUrl?.let { put("serverUrl", it) }
        sharedDocKeyIds?.let { ids ->
            put("sharedDocKeyIds", buildJsonArray { ids.forEach { add(it) } })
        }
        label?.let { put("label", it) }
    })

    /** Close the session: reaps the sidecar child process and disconnects. */
    suspend fun close() {
        try {
            rpc.callRaw(Methods.SESSION_CLOSE, params { })
        } catch (_: Throwable) {
            // The child may already be gone; disconnecting the transport is enough.
        } finally {
            closeTransport()
        }
    }
}
