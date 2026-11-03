package com.mindoo.db.bridge

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end test: spawns the real Node sidecar, provisions two isolated users,
 * and exercises read/write/attachments/live over the Kotlin client for both
 * sessions, asserting the two environments stay isolated.
 *
 * Requires the sidecar to be built (`pnpm --filter mindoodb-jvm-bridge build`).
 * Skips gracefully if the built sidecar or `node` is unavailable.
 */
class BridgeIntegrationTest {

    private lateinit var dataRoot: Path
    private var sidecar: SidecarProcess? = null

    private val bridgeDir: Path = Paths.get("..", "mindoodb-jvm-bridge").toAbsolutePath().normalize()
    private val indexScript: Path get() = bridgeDir.resolve("dist/index.js")
    private val provisionScript: Path get() = bridgeDir.resolve("dist/provision.js")

    private fun requirements(): Boolean = indexScript.exists() && provisionScript.exists() && nodeAvailable()

    @BeforeTest
    fun setup() {
        if (!requirements()) return
        dataRoot = Files.createTempDirectory("mindoodb-bridge-it")
    }

    @AfterTest
    fun tearDown() {
        sidecar?.stop()
        if (::dataRoot.isInitialized) dataRoot.toFile().deleteRecursively()
    }

    @Test
    fun readWriteAttachmentsAndLiveAcrossIsolatedSessions() {
        if (!requirements()) {
            println("[integration] skipped: build the sidecar (dist/) and ensure 'node' is on PATH")
            return
        }
        runBlocking {
            // Provision two independent users (separate tenants => separate replicas).
            Sidecar.provision(provisionScript, dataRoot, "tenant-a", "cn=admin", "adminpw", "cn=alice", "pwA", listOf("notes"))
            Sidecar.provision(provisionScript, dataRoot, "tenant-b", "cn=admin", "adminpw", "cn=bob", "pwB", listOf("notes"))

            sidecar = Sidecar.start(indexScript, dataRoot)
            val endpoint = sidecar!!.endpoint

            val sessionA = MindooDBBridge.openSession(endpoint, "tenant-a", "cn=alice", "pwA", listOf("notes"))
            val sessionB = MindooDBBridge.openSession(endpoint, "tenant-b", "cn=bob", "pwB", listOf("notes"))
            try {
                assertEquals(listOf("notes"), sessionA.databases)
                val dbA = sessionA.database("notes")
                val dbB = sessionB.database("notes")

                // --- Create + read ---
                val docA = dbA.documents.create(mapOf("type" to "note", "title" to "A-note", "body" to "hello"))
                assertEquals("A-note", docA.data["title"]!!.jsonPrimitive.content)

                val docB = dbB.documents.create(mapOf("type" to "note", "title" to "B-note"))

                // --- Update with optimistic base heads ---
                val upd = dbA.documents.update(docA.id, baseHeads = docA.heads) {
                    set("title", "A-note (edited)")
                    merge(mapOf("tags" to listOf("x", "y")))
                }
                assertTrue(upd.heads.isNotEmpty())
                val reread = dbA.documents.get(docA.id)
                assertEquals("A-note (edited)", reread.data["title"]!!.jsonPrimitive.content)

                // --- Isolation: A and B do not see each other's documents ---
                val idsA = dbA.documents.list()
                val idsB = dbB.documents.list()
                assertTrue(idsA.contains(docA.id), "A should see its own doc")
                assertTrue(!idsA.contains(docB.id), "A must NOT see B's doc (isolation)")
                assertTrue(idsB.contains(docB.id), "B should see its own doc")
                assertTrue(!idsB.contains(docA.id), "B must NOT see A's doc (isolation)")

                // --- Attachment round-trip (upload then download) ---
                val payload = ByteArray(300_000) { (it % 251).toByte() }
                val stored = dbA.attachments.uploadBytes(docA.id, "blob.bin", "application/octet-stream", payload)
                assertEquals(payload.size.toLong(), stored.size)

                val downloaded = ArrayList<Byte>(payload.size)
                dbA.attachments.read(docA.id, "blob.bin").collect { chunk -> chunk.forEach { downloaded.add(it) } }
                assertEquals(payload.size, downloaded.size)
                assertTrue(payload.toList() == downloaded, "attachment bytes must round-trip")

                val attachments = dbA.attachments.list(docA.id)
                assertEquals(1, attachments.size)
                assertEquals("blob.bin", attachments.first().fileName)

                // --- Live changefeed: create a new doc and observe it stream in ---
                val change = withTimeout(15_000) {
                    val flow = dbA.changes(sinceCursor = null)
                    // The changefeed replays existing docs first; ensure we can read at least one.
                    flow.first()
                }
                assertNotNull(change.doc.id)

                // --- History ---
                val history = dbA.documents.history(docA.id)
                assertTrue(history.isNotEmpty(), "edited document should have history entries")
            } finally {
                sessionA.close()
                sessionB.close()
            }
        }
    }

    @Test
    fun createTenantAndUserViaBridgeRpc() {
        if (!requirements()) {
            println("[integration] skipped: build the sidecar (dist/) and ensure 'node' is on PATH")
            return
        }
        runBlocking {
            sidecar = Sidecar.start(indexScript, dataRoot)
            val endpoint = sidecar!!.endpoint

            // --- tenant.create over the bridge (no session, no CLI) ---
            val created = MindooDBBridge.createTenant(
                endpoint,
                tenantId = "tenant-c",
                adminName = "cn=admin",
                adminPassword = "adminpw",
                userId = "cn=carol",
                password = "pwC",
                databases = listOf("notes"),
            )
            assertEquals("tenant-c", created.tenantId)
            assertEquals("cn=carol", created.userId)
            assertTrue(created.adminUserId.isNotBlank(), "admin user id should be returned")
            assertEquals(listOf("notes"), created.databases)

            // The freshly provisioned bundle must open a working session.
            val session = MindooDBBridge.openSession(endpoint, "tenant-c", "cn=carol", "pwC", listOf("notes"))
            try {
                val db = session.database("notes")
                val doc = db.documents.create(mapOf("type" to "note", "title" to "carol-note"))
                assertEquals("carol-note", db.documents.get(doc.id).data["title"]!!.jsonPrimitive.content)
            } finally {
                session.close()
            }

            // --- identity.createUser over the bridge ---
            val user = MindooDBBridge.createUser(endpoint, username = "cn=dave", password = "pwD")
            assertEquals("cn=dave", user.username)
            assertTrue(user.signingPublicKey.isNotBlank(), "signing public key expected")
            assertTrue(user.encryptionPublicKey.isNotBlank(), "encryption public key expected")
        }
    }

    @Test
    fun keybagCreateDocKeyPersistsAcrossReopen() {
        if (!requirements()) {
            println("[integration] skipped: build the sidecar (dist/) and ensure 'node' is on PATH")
            return
        }
        runBlocking {
            sidecar = Sidecar.start(indexScript, dataRoot)
            val endpoint = sidecar!!.endpoint

            MindooDBBridge.createTenant(
                endpoint,
                tenantId = "tenant-keys",
                adminName = "cn=admin",
                adminPassword = "adminpw",
                userId = "cn=alice",
                password = "pwA",
                databases = listOf("notes"),
            )

            val session1 = MindooDBBridge.openSession(endpoint, "tenant-keys", "cn=alice", "pwA", listOf("notes"))
            try {
                val created = session1.createDocKey("extra")
                assertEquals("extra", created.keyId)
                assertTrue(created.keys.any { it.contains("extra") }, "list should include extra key: ${created.keys}")
            } finally {
                session1.close()
            }

            val session2 = MindooDBBridge.openSession(endpoint, "tenant-keys", "cn=alice", "pwA", listOf("notes"))
            try {
                val keys = session2.listKeys().keys
                assertTrue(keys.any { it.contains("extra") }, "persisted keybag should still contain extra: $keys")
            } finally {
                session2.close()
            }
        }
    }

    @Test
    fun joinTenantFlowLocal() {
        if (!requirements()) {
            println("[integration] skipped: build the sidecar (dist/) and ensure 'node' is on PATH")
            return
        }
        runBlocking {
            sidecar = Sidecar.start(indexScript, dataRoot)
            val endpoint = sidecar!!.endpoint

            val created = MindooDBBridge.createTenant(
                endpoint,
                tenantId = "tenant-join",
                adminName = "cn=admin",
                adminPassword = "adminpw",
                userId = "cn=alice",
                password = "pwA",
                databases = listOf("notes"),
            )
            assertNotNull(created.adminIdentity, "adminIdentity required for approveJoinRequest")

            val bob = MindooDBBridge.createUser(endpoint, username = "cn=bob", password = "pwB")
            val req = MindooDBBridge.createJoinRequest(endpoint, bob.identity)
            assertEquals("uri", req.format)

            val alice = MindooDBBridge.openSession(endpoint, "tenant-join", "cn=alice", "pwA", listOf("notes"))
            val approved = try {
                alice.approveJoinRequest(
                    joinRequest = req.joinRequest,
                    adminPassword = "adminpw",
                    sharePassword = "share-secret",
                    adminIdentity = created.adminIdentity,
                )
            } finally {
                alice.close()
            }
            assertEquals("uri", approved.format)

            val joined = MindooDBBridge.joinTenant(
                endpoint,
                joinResponse = approved.joinResponse,
                user = bob.identity,
                password = "pwB",
                sharePassword = "share-secret",
                // Empty: Bob's isolated local store has no directory grants until
                // he syncs with a server; KeyBag import is what we verify here.
                databases = emptyList(),
            )
            assertEquals("tenant-join", joined.tenantId)
            assertEquals("cn=bob", joined.userId)

            val bobSession = MindooDBBridge.openSession(endpoint, "tenant-join", "cn=bob", "pwB", emptyList())
            try {
                val keys = bobSession.listKeys().keys
                assertTrue(
                    keys.any { it.contains("\$publicinfos") || it.contains("publicinfos") },
                    "joined keybag should include publicinfos key: $keys",
                )
                assertTrue(keys.isNotEmpty(), "joined keybag should not be empty")
            } finally {
                bobSession.close()
            }
        }
    }

    private fun nodeAvailable(): Boolean = try {
        val p = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
        p.waitFor() == 0
    } catch (_: Exception) {
        false
    }
}
