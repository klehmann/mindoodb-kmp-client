package com.mindoo.db.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

@Serializable
data class SessionInfo(
    val sessionId: String,
    val sessionToken: String,
    val databases: List<String> = emptyList(),
)

/** Result of a `tenant.create` bootstrap call. */
@Serializable
data class CreatedTenant(
    val tenantId: String,
    val tenantLabel: String? = null,
    /** The app user's username — pass this as `userId` to [MindooDBBridge.openSession]. */
    val userId: String,
    val adminUserId: String,
    val databases: List<String> = emptyList(),
    val envDir: String? = null,
    val identityFile: String? = null,
    /** Admin PrivateUserId (password-locked) for approveJoinRequest / publish helpers. */
    val adminIdentity: JsonElement? = null,
)

/** Result of an `identity.createUser` bootstrap call. */
@Serializable
data class CreatedUser(
    val username: String,
    val signingPublicKey: String,
    val encryptionPublicKey: String,
    /** The full password-locked PrivateUserId (private keys already encrypted). */
    val identity: JsonElement,
)

/** Result of `identity.createJoinRequest`. */
@Serializable
data class JoinRequestResult(
    val format: String,
    val joinRequest: JsonElement,
)

/** Result of `identity.joinTenant` after the identity bundle is written. */
@Serializable
data class JoinedTenant(
    val tenantId: String,
    /** The label in the admin's response; [MindooDBSession.getTenantLabel] reads the live one. */
    val tenantLabel: String? = null,
    val userId: String,
    val databases: List<String> = emptyList(),
    val envDir: String? = null,
    val identityFile: String? = null,
    val serverUrl: String? = null,
)

/** Result of `tenant.approveJoinRequest`. */
@Serializable
data class ApproveJoinResult(
    val format: String,
    val joinResponse: JsonElement,
    /** The directory push that puts the grant on the server (empty without a serverUrl). */
    val pushed: List<DatabaseSyncResult> = emptyList(),
)

enum class DatabaseMode(val wire: String) {
    Local("local"),
    Remote("remote"),
}

@Serializable
data class OpenDatabaseResult(
    val dbId: String,
    val mode: String,
    val databases: List<String> = emptyList(),
)

@Serializable
data class ServerUrlResult(val serverUrl: String? = null)

@Serializable
data class KeybagKeysResult(
    val keys: List<String> = emptyList(),
    val details: JsonElement? = null,
)

@Serializable
data class CreateDocKeyResult(
    val keyId: String,
    val keys: List<String> = emptyList(),
)

@Serializable
data class AttachmentInfo(
    val attachmentId: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
)

@Serializable
data class MindooDocument(
    val id: String,
    val data: JsonObject,
    val heads: List<String>,
    val attachments: List<AttachmentInfo> = emptyList(),
    val isDeleted: Boolean = false,
)

@Serializable
data class UpdateResult(
    val heads: List<String>,
    val conflictExpected: Boolean? = null,
)

@Serializable
data class DocumentIdList(val ids: List<String>)

@Serializable
internal data class DatabaseListResult(val databases: List<String> = emptyList())

@Serializable
data class CreatedManyResult(val documents: List<MindooDocument>)

@Serializable
data class QueryRow(
    val docId: String,
    val fields: JsonObject = JsonObject(emptyMap()),
    val lastModified: Long = 0,
    val textScore: Double? = null,
    /** Joined rows by include name (see `QueryBuilder.include`); null without includes. */
    val includes: JsonObject? = null,
) {
    /** The related row of a `Cardinality.One` include, or null when nothing matched. */
    fun included(name: String): QueryRow? =
        includes?.get(name)?.takeIf { it !is JsonNull }?.let { rowJson.decodeFromJsonElement(serializer(), it) }

    /** The related rows of a `Cardinality.Many` include. */
    fun includedList(name: String): List<QueryRow> =
        (includes?.get(name) as? kotlinx.serialization.json.JsonArray)?.map { rowJson.decodeFromJsonElement(serializer(), it) }.orEmpty()
}

private val rowJson = Json { ignoreUnknownKeys = true }

@Serializable
data class QueryResult(
    val rows: List<QueryRow> = emptyList(),
    val total: Int = 0,
    val coverage: JsonElement? = null,
)

@Serializable
data class ViewEntry(
    val position: String,
    val level: Int,
    val isCategory: Boolean,
    val isDocument: Boolean,
    val origin: String? = null,
    val docId: String? = null,
    val categoryValue: JsonElement? = null,
    val columnValues: JsonObject = JsonObject(emptyMap()),
    val childCount: Int = 0,
    val descendantCount: Int = 0,
    val expanded: Boolean = false,
)

@Serializable
data class HistoryEntry(
    val data: JsonObject,
    val heads: List<String>,
    val isDeleted: Boolean,
    val changeCreatedAt: Long,
    val changeCreatedByPublicKey: String? = null,
)

@Serializable
data class HeadCursor(val cursor: JsonElement? = null)

/** A document change delivered by the changefeed. */
@Serializable
data class DocumentChange(
    val doc: MindooDocument,
    val cursor: JsonElement? = null,
)

/** What changed, without the document body (nothing is decrypted). */
@Serializable
data class ChangeMetadata(
    val docId: String,
    val lastModified: Long,
    val isDeleted: Boolean,
)

/** A metadata-only changefeed item. */
@Serializable
data class ChangeMetadataEvent(
    val meta: ChangeMetadata,
    val cursor: JsonElement? = null,
)

/**
 * One page of the changefeed. Store [cursor] to continue later; [done] is true
 * when the page reached the current end of the feed.
 */
@Serializable
data class ChangePage(
    val changes: List<DocumentChange> = emptyList(),
    val cursor: JsonElement? = null,
    val done: Boolean = true,
)

/** Like [ChangePage], with metadata only. */
@Serializable
data class ChangeMetadataPage(
    val changes: List<ChangeMetadataEvent> = emptyList(),
    val cursor: JsonElement? = null,
    val done: Boolean = true,
)

@Serializable
internal data class ChangeCount(val count: Int)

/**
 * Changefeed cursors are opaque JSON objects. Persist them as a string with
 * [serialize] and pass [parse]d values back unchanged; `null` means "from the
 * beginning".
 */
object ChangeCursors {
    private val json = Json

    fun serialize(cursor: JsonElement?): String? =
        cursor?.takeIf { it !is JsonNull }?.let { json.encodeToString(JsonElement.serializer(), it) }

    fun parse(value: String?): JsonElement? =
        value?.takeIf { it.isNotBlank() }?.let { json.parseToJsonElement(it) }
}

/** Sync counters for one database (entries of the docs and attachments stores). */
@Serializable
data class DatabaseSyncResult(
    val dbId: String,
    val pulledEntries: Int = 0,
    val pushedEntries: Int = 0,
    val pushedBytes: Long = 0,
    /** Entries the server refused (e.g. writes the user is not allowed to make). */
    val rejectedEntries: Int = 0,
)

/** Result of a `session.refresh`. */
@Serializable
data class RefreshResult(
    val pulled: Boolean = false,
    val reconciled: Boolean = false,
    val pushed: Boolean = false,
    val databases: List<DatabaseSyncResult> = emptyList(),
)

/** A device already registered for the person a join request names. */
@Serializable
data class DirectoryDevice(
    val label: String? = null,
    val signingPublicKeyFingerprint: String,
    val wipeRequested: Boolean = false,
)

/** What the directory knows about the name in a join request. */
@Serializable
data class DirectoryUserMatch(
    /** The name is already in the directory: approving adds a device to that person. */
    val exists: Boolean,
    /** This exact device (signing key) is already granted. */
    val deviceAlreadyGranted: Boolean,
    val devices: List<DirectoryDevice> = emptyList(),
)

/**
 * A decoded join request, for the admin to check before approving. Compare the
 * fingerprints with the ones the joining device shows (Haven shows the same format).
 */
@Serializable
data class JoinRequestInfo(
    val version: Int,
    /** Null when the request leaves the name to the admin (pass `username` to approve). */
    val username: String? = null,
    val label: String? = null,
    val signingPublicKey: String,
    val encryptionPublicKey: String,
    val signingPublicKeyFingerprint: String,
    val encryptionPublicKeyFingerprint: String,
    /** False for a device whose person has no User-Key yet; MindooDB then cannot approve it. */
    val hasUserKey: Boolean,
    /** Keys the device asks for (a hint, not a grant); null means the default set. */
    val requestedDocKeyIds: List<String>? = null,
    /** Null when the request carries no name. */
    val directoryUser: DirectoryUserMatch? = null,
)

/** Progress of one push or pull (one database, one store), from [MindooDBSession.syncProgress]. */
@Serializable
data class SyncProgressEvent(
    val dbId: String,
    /** "pull" or "push". */
    val direction: String,
    /** "docs" or "attachments". */
    val store: String,
    /** "preparing", "planning", "transferring", "processing" or "complete" (always last). */
    val phase: String,
    val message: String = "",
    val scannedEntries: Long = 0,
    val transferredEntries: Long = 0,
    val transferredBytes: Long = 0,
    /** Entries in the source store, when known: `scannedEntries / totalSourceEntries` is the completion ratio. */
    val totalSourceEntries: Long? = null,
    /** True for the periodic background sync. */
    val background: Boolean = false,
)

@Serializable
internal data class TenantLabelResult(val tenantId: String, val label: String? = null)

/** Result of [MindooDBSession.setTenantLabel]. */
@Serializable
data class SetTenantLabelResult(
    val label: String,
    /** The directory push that brings the new name to the other members (empty without a server). */
    val pushed: List<DatabaseSyncResult> = emptyList(),
)

/** Which way [MindooDBSession.refresh] syncs. */
enum class SyncDirection(val wire: String) { Both("both"), Pull("pull"), Push("push") }

/** Result of [MindooDBSession.publishToServer]. */
@Serializable
data class PublishResult(
    val ok: Boolean = true,
    val serverUrl: String,
    /** The bootstrap push of `directory` and `userdirectory`. */
    val pushed: List<DatabaseSyncResult> = emptyList(),
)

@Serializable
internal data class OpenReadResult(val streamId: Int, val size: Long, val mimeType: String)

@Serializable
internal data class OpenWriteResult(val streamId: Int, val credits: Int)

@Serializable
internal data class CloseWriteResult(val attachment: AttachmentInfo, val heads: List<String>)

@Serializable
internal data class NavigatorHandle(val navigatorId: String, val columns: JsonElement? = null)

@Serializable
internal data class NavCallResult(val result: JsonElement? = null)

@Serializable
internal data class AttachmentListResult(val attachments: List<AttachmentInfo> = emptyList())

@Serializable
internal data class HistoryResult(val history: List<HistoryEntry> = emptyList())

@Serializable
internal data class FulltextSetupResult(val config: JsonElement? = null)

@Serializable
internal data class DocumentResult(@SerialName("document") val document: MindooDocument? = null)
