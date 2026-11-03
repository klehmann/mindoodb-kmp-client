# mindoodb-kmp-client

A Kotlin Multiplatform client that gives a JVM backend read/write access to
MindooDB — documents, ad-hoc `query`, virtual views, full-text search,
attachments (both directions), the changefeed, and live updates — **without
porting the TypeScript engine to the JVM**.

The real `mindoodb` engine runs in a Node **sidecar** ([`mindoodb-jvm-bridge`](../mindoodb-jvm-bridge))
that holds the decrypted keys; this module is a thin, coroutine-first transport
client that talks to it over a loopback WebSocket. The wire contract is
documented in [`../mindoodb-jvm-bridge/PROTOCOL.md`](../mindoodb-jvm-bridge/PROTOCOL.md).

```
JVM backend ──(Kotlin client)──► loopback WS ──► Node supervisor ──fork──► session child (real mindoodb, decrypted keys)
```

- **One session = one WebSocket connection = one isolated sidecar child process.**
  Open one session per user; their on-disk replicas and in-memory keys never mix.
- The module is KMP-structured (`commonMain` is pure Kotlin over a `Transport`
  abstraction) so a mobile target can later supply its own transport. Today the
  only implemented target is `jvm`.

## Requirements

- JDK 21+
- Node.js `>=22.13.0` with the sidecar built (see [Building the sidecar](#building-the-sidecar)).

## Add to a build

This module currently builds from source in the workspace. From `mindoodb-kmp-client/`:

```bash
./gradlew build            # compile + test
./gradlew jvmTest          # run the end-to-end integration test
```

Runtime dependencies (managed by the module): `kotlinx-coroutines`,
`kotlinx-serialization-json`, Ktor client (`core` + `websockets`, CIO engine on JVM).

## Quick start

```kotlin
import com.mindoo.db.bridge.*
import java.nio.file.Paths
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

val bridgeDir = Paths.get("..", "mindoodb-jvm-bridge").toAbsolutePath().normalize()
val dataRoot = Paths.get("./bridge-data")

runBlocking {
    // 1. Start the supervisor.
    val sidecar = Sidecar.start(bridgeDir.resolve("dist/index.js"), dataRoot)

    // 2. First-time setup: create a tenant + app user + identity bundle on disk.
    //    (See "Provisioning" below.) Idempotent to skip if the bundle exists.
    MindooDBBridge.createTenant(
        endpoint = sidecar.endpoint,
        tenantId = "acme",                 // lowercase letters/digits/-/_ only
        adminName = "cn=admin/o=acme",
        adminPassword = "admin-pw",
        userId = "cn=alice/o=acme",
        password = "alice-pw",
        databases = listOf("notes"),
    )

    // 3. Open a session for the provisioned user.
    val session = MindooDBBridge.openSession(
        endpoint = sidecar.endpoint,
        tenantId = "acme",
        userId = "cn=alice/o=acme",
        password = "alice-pw",
        databases = listOf("notes"),
    )

    val db = session.database("notes")

    // 4. Create / update / read documents.
    val doc = db.documents.create(mapOf("type" to "note", "title" to "Hello"))
    db.documents.update(doc.id, baseHeads = doc.heads) {
        set("title", "Hello (edited)")
        merge(mapOf("tags" to listOf("kotlin", "mindoodb")))
    }
    val reloaded = db.documents.get(doc.id)

    // 5. Attachments (upload then stream back down).
    db.attachments.uploadBytes(doc.id, "note.txt", "text/plain", "big payload".encodeToByteArray())
    db.attachments.read(doc.id, "note.txt").collect { chunk -> /* consume bytes */ }

    // 6. Follow the changefeed.
    val firstChange = db.changes(sinceCursor = null).first()

    session.close()   // reaps the sidecar child
    sidecar.stop()
}
```

If the supervisor is already running (started out-of-band), skip `Sidecar.start`
and connect with the handshake values directly:

```kotlin
MindooDBBridge.openSession(
    host = "127.0.0.1", port = 51734, bootstrapToken = "…",
    tenantId = "acme", userId = "cn=alice/o=acme", password = "alice-pw",
    databases = listOf("notes"),
)
```

## API surface

Everything is `suspend` or `Flow`-based and carries the per-session token
automatically.

### Session — `MindooDBSession`
`database(id)`, `listDatabases()`, `refresh(dbId?)`, `awaitIndexReady(dbId?)`,
`close()`.

### Documents — `db.documents` (`DocumentApi`)
`get`, `list(idPrefix?)`, `headCursor()`, `create(initialValues?, id?)`,
`createMany`, `update(docId, baseHeads?) { … }`, `delete`, `undelete`,
`history`, `getAtRevision(docId, timestamp)`.

`update` takes an ops block (`set` / `unset` / `merge` / `setText`) and returns
`UpdateResult(heads, conflictExpected?)`. Pass the document's prior `heads` as
`baseHeads` for optimistic concurrency; MindooDB always CRDT-merges, and
`conflictExpected` flags that concurrent changes were merged in.

### Query, views, full-text — `db`
- `query(mindooQuery { … })` → `QueryResult`
- `liveQuery(query)` → `Flow<QueryResult>` (re-emits on change)
- `changes(sinceCursor?)` → `Flow<DocumentChange>` (replay then follow live)
- `openViewNavigator(viewDefinition { … })` → `ViewNavigator`
- `getFulltextSetup()` / `setFulltextSetup(config)`

Full-text search is a `text` clause on a query (`textScore` per row appears on
matches).

Query / view DSL (mirrors the MindooDB expression language):

```kotlin
val q = mindooQuery {
    filter = (field("type") eq "note") and (field("archived") neq true)
    sort("title", SortDirection.ASCENDING)
    limit = 50
}

val view = viewDefinition {
    filter = field("type") eq "note"
    category("category", expression = field("category"))
    column("title")
}
val nav = db.openViewNavigator(view)
val rows = nav.readForward(limit = 100)   // also: gotoFirst/Next/Parent…, expandAll, expandToLevel, changes()
nav.close()
```

### Attachments — `db.attachments` (`AttachmentApi`)
`list`, `remove`, `read(docId, fileName)` → `Flow<ByteArray>`,
`upload(docId, fileName, mimeType, chunks: Flow<ByteArray>)`,
`uploadBytes(...)`. Transfers use credit-based flow control in both directions.

`java.io` interop (jvmMain): `InputStream.asByteFlow()` and
`Flow<ByteArray>.asInputStream(scope)`.

## Provisioning — creating tenants & users

Provisioning happens over the same bridge, as **bootstrap RPCs** that run after
`hello` but before (and without) opening a session — authorized by the bootstrap
token and rate-limited alongside `session.open`.

`MindooDBBridge.createTenant(...)` creates the tenant + admin + app user, writes
a password-locked identity bundle, and returns `CreatedTenant` including
`adminIdentity` (for join approval). `openSession` reopens the app-user bundle.

```kotlin
val created = MindooDBBridge.createTenant(
    endpoint = sidecar.endpoint,
    tenantId = "acme", adminName = "cn=admin/o=acme", adminPassword = "admin-pw",
    userId = "cn=alice/o=acme", password = "alice-pw", databases = listOf("notes"),
)
val session = MindooDBBridge.openSession(
    sidecar.endpoint, created.tenantId, created.userId, "alice-pw", created.databases,
)

// KeyBag
session.createDocKey("finance")
session.listKeys()

// Open another DB (local replica, or remote-primary via ClientNetwork store)
session.openDatabase("tasks", DatabaseMode.Local)

// Publish to a MindooDB sync server (needs *server* system-admin credentials)
// session.publishToServer(url, systemAdminIdentity, systemAdminPassword, adminPassword = "admin-pw")
// session.refresh()

// Join flow
val bob = MindooDBBridge.createUser(sidecar.endpoint, "cn=bob/o=acme", "bob-pw")
val req = MindooDBBridge.createJoinRequest(sidecar.endpoint, bob.identity)
val approved = session.approveJoinRequest(
    req.joinRequest, adminPassword = "admin-pw", sharePassword = "share",
    adminIdentity = created.adminIdentity,
)
val joined = MindooDBBridge.joinTenant(
    sidecar.endpoint, approved.joinResponse, bob.identity, "bob-pw", "share",
    databases = listOf("notes"),
)
```

Also: `createUser`, `createJoinRequest`, `joinTenant`, `setServerUrl`,
`publishToServer`. `Sidecar.provision(...)` remains as a CLI alternative.

## Security

Loopback-only bind + peer check; a mode-`600` bootstrap-token handshake file
authorizes the connection; the user's password unlocks the KeyBag once and is
then discarded; a per-session, memory-only, idle-expiring token authorizes every
frame; `session.open` and bootstrap provisioning are rate-limited; each session runs in its own OS process
for memory isolation of decrypted keys; the child is killed on `close()`, idle
timeout, disconnect, or supervisor exit. See PROTOCOL.md §7.

## Building the sidecar

```bash
# from the workspace root
pnpm --filter mindoodb-jvm-bridge install
pnpm --filter mindoodb-jvm-bridge build   # produces dist/index.js and dist/provision.js
```

## Tests

`BridgeIntegrationTest` (jvmTest) spawns the real sidecar, provisions two
isolated users, and exercises read/write, cross-session isolation, an attachment
round-trip, the changefeed, and history. It skips gracefully if the sidecar
`dist/` or `node` is unavailable.

```bash
./gradlew jvmTest
```
