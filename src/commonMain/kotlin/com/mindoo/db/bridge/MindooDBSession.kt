package com.mindoo.db.bridge

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
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

    /**
     * Sync with the server now: one database, or `directory`, `userdirectory`
     * and every open local database (docs and attachments). Throws when there
     * is no server URL or the sync fails.
     */
    suspend fun refresh(dbId: String? = null, direction: SyncDirection = SyncDirection.Both): RefreshResult =
        rpc.call(Methods.SESSION_REFRESH, params {
            dbId?.let { put("db", it) }
            put("direction", direction.wire)
        })

    suspend fun awaitIndexReady(dbId: String? = null) {
        rpc.callRaw(Methods.SESSION_AWAIT_INDEX_READY, params { dbId?.let { put("db", it) } })
    }

    /** Persist or clear the sync peer URL on the identity bundle and restart SyncLoop. */
    suspend fun setServerUrl(serverUrl: String?): ServerUrlResult =
        rpc.call(Methods.SESSION_SET_SERVER_URL, params {
            if (serverUrl == null) put("serverUrl", JsonNull) else put("serverUrl", serverUrl)
        })

    /**
     * Register this tenant on a MindooDB sync server, push its `directory` and
     * `userdirectory` (as the tenant admin when [adminPassword] is given), persist
     * [serverUrl] and start background sync.
     *
     * Pass the *server* system-admin credentials, or leave them out on a server
     * that lets any signed user register tenants (a config.json capability for
     * tenant creation with username `*`); the tenant admin then signs the request.
     */
    suspend fun publishToServer(
        serverUrl: String,
        systemAdminUser: JsonElement? = null,
        systemAdminPassword: String? = null,
        adminPassword: String? = null,
        adminUsername: String? = null,
        adminIdentity: JsonElement? = null,
    ): PublishResult = rpc.call(Methods.SESSION_PUBLISH_TO_SERVER, params {
        put("serverUrl", serverUrl)
        systemAdminUser?.let { put("systemAdminUser", it) }
        systemAdminPassword?.let { put("systemAdminPassword", it) }
        adminPassword?.let { put("adminPassword", it) }
        adminUsername?.let { put("adminUsername", it) }
        adminIdentity?.let { put("adminIdentity", it) }
    })

    /**
     * Progress of every sync of this session (refresh, publish, approval and
     * the background loop) while collected. Each push or pull ends with a
     * `complete` event.
     */
    fun syncProgress(): Flow<SyncProgressEvent> = channelFlow {
        val subscriptionId = rpc.newSubscriptionId()
        val job = launch {
            rpc.events(subscriptionId).collect { ev ->
                if (ev.event == "sync-progress") {
                    trySend(rpc.json.decodeFromJsonElement(SyncProgressEvent.serializer(), ev.payload))
                }
            }
        }
        rpc.callRaw(Methods.SESSION_WATCH_SYNC, params { put("subscriptionId", subscriptionId) })
        awaitClose {
            job.cancel()
            launch { rpc.callRaw(Methods.SESSION_UNWATCH_SYNC, params { put("subscriptionId", subscriptionId) }) }
        }
    }

    /** Whether this session syncs in the background (every 5 seconds while a server URL is set). */
    suspend fun isBackgroundSyncEnabled(): Boolean =
        rpc.call<BackgroundSyncState>(Methods.SESSION_GET_BACKGROUND_SYNC, params { }).enabled

    /**
     * Turn the background sync of this session on or off (on after `openSession`).
     * Off, changes stay on this machine until [refresh] (or a publish or
     * approval) syncs them; turning it on syncs right away. Not persisted.
     */
    suspend fun setBackgroundSync(enabled: Boolean): Boolean =
        rpc.call<BackgroundSyncState>(Methods.SESSION_SET_BACKGROUND_SYNC, params { put("enabled", enabled) }).enabled

    // ----- KeyBag -----

    suspend fun createDocKey(keyId: String): CreateDocKeyResult =
        rpc.call(Methods.KEYBAG_CREATE_DOC_KEY, params { put("keyId", keyId) })

    suspend fun listKeys(): KeybagKeysResult =
        rpc.call(Methods.KEYBAG_LIST_KEYS, params { })

    suspend fun persistKeybag() {
        rpc.callRaw(Methods.KEYBAG_PERSIST, params { })
    }

    // ----- Tenant label -----

    /** The tenant's human-readable name from the synced directory; null when none is set. */
    suspend fun getTenantLabel(): String? =
        rpc.call<TenantLabelResult>(Methods.TENANT_GET_LABEL, params { }).label

    /**
     * Rename the tenant (tenant admin only; a member gets `NotAuthorized`). The
     * name is stored encrypted in the admin-signed directory and reaches the
     * other members with the next sync.
     */
    suspend fun setTenantLabel(label: String, adminPassword: String, adminIdentity: JsonElement? = null): SetTenantLabelResult =
        rpc.call(Methods.TENANT_SET_LABEL, params {
            put("label", label)
            put("adminPassword", adminPassword)
            adminIdentity?.let { put("adminIdentity", it) }
        })

    // ----- Join (admin) -----

    /**
     * Decode a join request (the `mdb://join-request/…` URI or object) without
     * approving it: who asks, the device label, key fingerprints, and whether
     * the name or this device is already in the directory.
     */
    suspend fun inspectJoinRequest(joinRequest: JsonElement): JoinRequestInfo =
        rpc.call(Methods.TENANT_INSPECT_JOIN_REQUEST, params { put("joinRequest", joinRequest) })

    /**
     * Approve a join request. Prefer [adminIdentity] from [CreatedTenant.adminIdentity]
     * (or pass [adminSigningKey]). Default response format is a URI string.
     *
     * [label] names the joining device (overrides the request's), [username]
     * registers it under an existing person (an additional device) instead of
     * the name from the request. With a server URL the grant is pushed right
     * away, so the new member can join and sync.
     */
    suspend fun approveJoinRequest(
        joinRequest: JsonElement,
        adminPassword: String,
        adminIdentity: JsonElement? = null,
        adminSigningKey: JsonElement? = null,
        serverUrl: String? = null,
        sharedDocKeyIds: List<String>? = null,
        label: String? = null,
        username: String? = null,
        tenantLabel: String? = null,
        format: String = "uri",
    ): ApproveJoinResult = rpc.call(Methods.TENANT_APPROVE_JOIN_REQUEST, params {
        put("joinRequest", joinRequest)
        put("adminPassword", adminPassword)
        put("format", format)
        username?.let { put("username", it) }
        tenantLabel?.let { put("tenantLabel", it) }
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
