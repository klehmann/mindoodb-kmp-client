package com.mindoo.db.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/**
 * JVM entry point. Opens an authenticated MindooDB session over the loopback
 * bridge. One session == one WebSocket connection == one isolated sidecar child
 * process, so call [openSession] once per user.
 */
object MindooDBBridge {

    /**
     * Open a session against an already-running supervisor.
     *
     * @param host / [port] / [bootstrapToken] come from the supervisor handshake.
     */
    suspend fun openSession(
        host: String,
        port: Int,
        bootstrapToken: String,
        tenantId: String,
        userId: String,
        password: String,
        databases: List<String>,
    ): MindooDBSession {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = KtorTransport.connect(host, port)
        val rpc = RpcClient(transport, scope)
        rpc.start()

        rpc.callRaw(Methods.HELLO, params { put("bootstrapToken", bootstrapToken) })

        val info: SessionInfo = rpc.call(Methods.SESSION_OPEN, params {
            put("tenantId", tenantId)
            put("userId", userId)
            put("password", password)
            put("databases", buildJsonArray { databases.forEach { add(it) } })
        })
        rpc.sessionToken = info.sessionToken

        return MindooDBSession(rpc, info) {
            transport.close()
            scope.cancel()
        }
    }

    /** Convenience: open a session using a [BridgeEndpoint] from [Sidecar.start]. */
    suspend fun openSession(
        endpoint: BridgeEndpoint,
        tenantId: String,
        userId: String,
        password: String,
        databases: List<String>,
    ): MindooDBSession = openSession(
        endpoint.host, endpoint.port, endpoint.bootstrapToken,
        tenantId, userId, password, databases,
    )

    /**
     * Create a tenant + admin + app user and persist a password-locked identity
     * bundle on disk, so a later [openSession] with [userId] + [password] can
     * reopen it. Runs over a short-lived bootstrap connection (no session).
     */
    suspend fun createTenant(
        host: String,
        port: Int,
        bootstrapToken: String,
        tenantId: String,
        adminName: String,
        adminPassword: String,
        userId: String,
        password: String,
        databases: List<String> = emptyList(),
        serverUrl: String? = null,
    ): CreatedTenant = bootstrap(host, port, bootstrapToken) { rpc ->
        rpc.call(Methods.TENANT_CREATE, params {
            put("tenantId", tenantId)
            put("adminName", adminName)
            put("adminPassword", adminPassword)
            put("userName", userId)
            put("userPassword", password)
            put("databases", buildJsonArray { databases.forEach { add(it) } })
            serverUrl?.let { put("serverUrl", it) }
        })
    }

    /** Convenience: [createTenant] using a [BridgeEndpoint] from [Sidecar.start]. */
    suspend fun createTenant(
        endpoint: BridgeEndpoint,
        tenantId: String,
        adminName: String,
        adminPassword: String,
        userId: String,
        password: String,
        databases: List<String> = emptyList(),
        serverUrl: String? = null,
    ): CreatedTenant = createTenant(
        endpoint.host, endpoint.port, endpoint.bootstrapToken,
        tenantId, adminName, adminPassword, userId, password, databases, serverUrl,
    )

    /**
     * Create a standalone, password-locked user identity (no tenant). The
     * returned [CreatedUser.identity] is safe to persist and later feed into a
     * tenant join flow.
     */
    suspend fun createUser(
        host: String,
        port: Int,
        bootstrapToken: String,
        username: String,
        password: String,
    ): CreatedUser = bootstrap(host, port, bootstrapToken) { rpc ->
        rpc.call(Methods.IDENTITY_CREATE_USER, params {
            put("username", username)
            put("password", password)
        })
    }

    /** Convenience: [createUser] using a [BridgeEndpoint] from [Sidecar.start]. */
    suspend fun createUser(
        endpoint: BridgeEndpoint,
        username: String,
        password: String,
    ): CreatedUser = createUser(
        endpoint.host, endpoint.port, endpoint.bootstrapToken, username, password,
    )

    /**
     * Build a join request from a [CreatedUser.identity] (or any PrivateUserId JSON).
     * Default [format] is `"uri"` for out-of-band exchange.
     */
    suspend fun createJoinRequest(
        host: String,
        port: Int,
        bootstrapToken: String,
        identity: JsonElement,
        label: String? = null,
        format: String = "uri",
    ): JoinRequestResult = bootstrap(host, port, bootstrapToken) { rpc ->
        rpc.call(Methods.IDENTITY_CREATE_JOIN_REQUEST, params {
            put("identity", identity)
            put("format", format)
            label?.let { put("label", it) }
        })
    }

    suspend fun createJoinRequest(
        endpoint: BridgeEndpoint,
        identity: JsonElement,
        label: String? = null,
        format: String = "uri",
    ): JoinRequestResult = createJoinRequest(
        endpoint.host, endpoint.port, endpoint.bootstrapToken, identity, label, format,
    )

    /**
     * Join a tenant with a join-response (object or URI) and persist an identity
     * bundle so [openSession] can reopen the user.
     */
    suspend fun joinTenant(
        host: String,
        port: Int,
        bootstrapToken: String,
        joinResponse: JsonElement,
        user: JsonElement,
        password: String,
        sharePassword: String,
        databases: List<String> = emptyList(),
        serverUrl: String? = null,
    ): JoinedTenant = bootstrap(host, port, bootstrapToken) { rpc ->
        rpc.call(Methods.IDENTITY_JOIN_TENANT, params {
            put("joinResponse", joinResponse)
            put("user", user)
            put("password", password)
            put("sharePassword", sharePassword)
            put("databases", buildJsonArray { databases.forEach { add(it) } })
            serverUrl?.let { put("serverUrl", it) }
        })
    }

    suspend fun joinTenant(
        endpoint: BridgeEndpoint,
        joinResponse: JsonElement,
        user: JsonElement,
        password: String,
        sharePassword: String,
        databases: List<String> = emptyList(),
        serverUrl: String? = null,
    ): JoinedTenant = joinTenant(
        endpoint.host, endpoint.port, endpoint.bootstrapToken,
        joinResponse, user, password, sharePassword, databases, serverUrl,
    )

    /**
     * Open a short-lived, authenticated bootstrap connection (no session),
     * run [block], then always tear the connection down.
     */
    private suspend fun <T> bootstrap(
        host: String,
        port: Int,
        bootstrapToken: String,
        block: suspend (RpcClient) -> T,
    ): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = KtorTransport.connect(host, port)
        try {
            val rpc = RpcClient(transport, scope)
            rpc.start()
            rpc.callRaw(Methods.HELLO, params { put("bootstrapToken", bootstrapToken) })
            return block(rpc)
        } finally {
            transport.close()
            scope.cancel()
        }
    }
}
