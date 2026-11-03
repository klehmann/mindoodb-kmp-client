package com.mindoo.db.bridge

/** Wire protocol identifier shared with the Node sidecar. */
const val PROTOCOL: String = "mindoodb-bridge"

/** Binary attachment chunk marker byte. */
const val BINARY_CHUNK_MARKER: Byte = 0x01

/** Fixed binary header length: marker(1) + streamId(4) + seq(4). */
const val BINARY_HEADER_LENGTH: Int = 9

/** Bridge method names (kept in lockstep with the sidecar's `Methods`). */
object Methods {
    const val HELLO = "hello"

    const val TENANT_CREATE = "tenant.create"
    const val IDENTITY_CREATE_USER = "identity.createUser"
    const val IDENTITY_CREATE_JOIN_REQUEST = "identity.createJoinRequest"
    const val IDENTITY_JOIN_TENANT = "identity.joinTenant"

    const val SESSION_OPEN = "session.open"
    const val SESSION_CLOSE = "session.close"
    const val SESSION_REFRESH = "session.refresh"
    const val SESSION_AWAIT_INDEX_READY = "session.awaitIndexReady"
    const val SESSION_LIST_DATABASES = "session.listDatabases"
    const val SESSION_OPEN_DATABASE = "session.openDatabase"
    const val SESSION_SET_SERVER_URL = "session.setServerUrl"
    const val SESSION_PUBLISH_TO_SERVER = "session.publishToServer"

    const val TENANT_APPROVE_JOIN_REQUEST = "tenant.approveJoinRequest"

    const val KEYBAG_CREATE_DOC_KEY = "keybag.createDocKey"
    const val KEYBAG_LIST_KEYS = "keybag.listKeys"
    const val KEYBAG_PERSIST = "keybag.persist"

    const val DOC_GET = "documents.get"
    const val DOC_LIST = "documents.list"
    const val DOC_HEAD_CURSOR = "documents.getHeadCursor"
    const val DOC_CREATE = "documents.create"
    const val DOC_CREATE_MANY = "documents.createMany"
    const val DOC_UPDATE = "documents.update"
    const val DOC_DELETE = "documents.delete"
    const val DOC_DELETE_MANY = "documents.deleteMany"
    const val DOC_LIST_DELETED = "documents.listDeleted"
    const val DOC_GET_RICH_TEXT = "documents.getRichText"
    const val DOC_GET_TEXT_CURSORS = "documents.getTextCursors"
    const val DOC_RESOLVE_TEXT_CURSORS = "documents.resolveTextCursors"
    const val DOC_UNDELETE = "documents.undelete"
    const val DOC_LIST_HISTORY = "documents.listHistory"
    const val DOC_GET_AT_REVISION = "documents.getAtRevision"

    const val QUERY = "db.query"
    const val LIVE_QUERY_START = "db.liveQuery.start"
    const val LIVE_QUERY_STOP = "db.liveQuery.stop"

    const val FULLTEXT_GET_SETUP = "fulltext.getSetup"
    const val FULLTEXT_SET_SETUP = "fulltext.setSetup"

    const val VIEW_OPEN_NAVIGATOR = "view.openNavigator"
    const val VIEW_CREATE_NAVIGATOR = "view.createNavigator"
    const val NAV_CALL = "nav.call"
    const val NAV_SUBSCRIBE = "nav.subscribe"
    const val NAV_UNSUBSCRIBE = "nav.unsubscribe"
    const val NAV_DISPOSE = "nav.dispose"

    const val ATTACH_LIST = "attachments.list"
    const val ATTACH_REMOVE = "attachments.remove"
    const val ATTACH_OPEN_READ = "attachments.openReadStream"
    const val ATTACH_OPEN_WRITE = "attachments.openWriteStream"
    const val ATTACH_CLOSE_WRITE = "attachments.closeWriteStream"
    const val ATTACH_ABORT = "attachments.abortStream"
    const val STREAM_CREDIT = "stream.credit"

    const val CHANGES_ITERATE_SINCE = "changes.iterateSince"
}

/** Stable bridge error codes mirrored from the sidecar. */
enum class ErrorCode {
    NotAuthorized,
    SessionClosed,
    NotFound,
    Conflict,
    IndexNotReady,
    BadRequest,
    FulltextNotEnabled,
    Internal,
    Unknown;

    companion object {
        fun fromWire(value: String?): ErrorCode =
            entries.firstOrNull { it.name == value } ?: Unknown
    }
}

/** Thrown when the sidecar returns an error frame or the transport fails. */
class BridgeException(
    val code: ErrorCode,
    message: String,
) : Exception("[$code] $message")
