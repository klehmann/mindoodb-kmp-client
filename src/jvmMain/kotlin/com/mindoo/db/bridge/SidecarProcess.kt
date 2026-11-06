package com.mindoo.db.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Coordinates the JVM needs to reach the bridge: an address (`host`/`port`) plus
 * the bootstrap token that authorizes the connection. Read from the handshake
 * file the supervisor writes.
 */
data class BridgeEndpoint(
    val host: String,
    val port: Int,
    val bootstrapToken: String,
)

/**
 * A running sidecar supervisor process. It never outlives the JVM: a shutdown
 * hook stops it on any regular exit (including `System.exit`, e.g. quitting
 * from the macOS app menu), and the sidecar itself exits when its stdin closes,
 * which also covers a crash or `kill -9` of the JVM.
 */
class SidecarProcess internal constructor(
    val process: Process,
    val endpoint: BridgeEndpoint,
) {
    private val shutdownHook = Thread({ terminate() }, "mindoodb-sidecar-shutdown")

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    fun stop() {
        terminate()
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
            // the JVM is already shutting down
        } catch (_: IllegalArgumentException) {
            // stopped before
        }
    }

    private fun terminate() {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
    }
}

/**
 * Launches and provisions the Node sidecar from the JVM. The sidecar keeps each
 * user's decrypted keys in its own child process; this launcher only starts the
 * supervisor and reads the handshake.
 */
object Sidecar {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Start the supervisor (`node <entryScript>`). Blocks until the handshake
     * file has been written, then returns the process + connection info.
     */
    fun start(
        entryScript: Path,
        dataRoot: Path,
        handshakePath: Path = dataRoot.resolve("handshake.json"),
        nodeExecutable: String = "node",
        startupTimeout: Duration = 30.seconds,
    ): SidecarProcess {
        Files.createDirectories(dataRoot)
        Files.deleteIfExists(handshakePath)

        val builder = ProcessBuilder(nodeExecutable, entryScript.toAbsolutePath().toString())
        builder.environment()["MINDOODB_BRIDGE_DATA"] = dataRoot.toAbsolutePath().toString()
        builder.environment()["MINDOODB_BRIDGE_HANDSHAKE"] = handshakePath.toAbsolutePath().toString()
        // The sidecar exits when our end of its stdin pipe closes (the JVM is gone).
        builder.environment()["MINDOODB_BRIDGE_EXIT_WITH_PARENT"] = "1"
        builder.redirectInput(ProcessBuilder.Redirect.PIPE)
        builder.redirectErrorStream(false)
        builder.redirectOutput(ProcessBuilder.Redirect.PIPE)
        builder.redirectError(ProcessBuilder.Redirect.INHERIT)

        val process = builder.start()

        // Wait for the "ready" line on stdout, then read the handshake file.
        val reader: BufferedReader = process.inputStream.bufferedReader()
        val deadline = System.currentTimeMillis() + startupTimeout.inWholeMilliseconds
        var ready = false
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive && reader.ready().not()) break
            val line = reader.readLine() ?: break
            if (line.contains("\"ready\"")) {
                ready = true
                break
            }
        }
        if (!ready) {
            process.destroyForcibly()
            error("Sidecar did not become ready within $startupTimeout")
        }

        val endpoint = readHandshake(handshakePath.toFile(), deadline)
        return SidecarProcess(process, endpoint)
    }

    private fun readHandshake(file: File, deadline: Long): BridgeEndpoint {
        while (System.currentTimeMillis() < deadline) {
            if (file.exists() && file.length() > 0) {
                val obj = json.parseToJsonElement(file.readText()).jsonObject
                return BridgeEndpoint(
                    host = obj["host"]!!.jsonPrimitive.content,
                    port = obj["port"]!!.jsonPrimitive.content.toInt(),
                    bootstrapToken = obj["bootstrapToken"]!!.jsonPrimitive.content,
                )
            }
            Thread.sleep(50)
        }
        error("Handshake file was not written: ${file.absolutePath}")
    }

    /**
     * Provision a tenant + app user and write the identity bundle, using the
     * sidecar's provisioning CLI. Runs synchronously and throws on failure.
     */
    fun provision(
        provisionScript: Path,
        dataRoot: Path,
        tenantId: String,
        adminName: String,
        adminPassword: String,
        userName: String,
        userPassword: String,
        databases: List<String>,
        serverUrl: String? = null,
        nodeExecutable: String = "node",
    ) {
        val args = mutableListOf(
            nodeExecutable, provisionScript.toAbsolutePath().toString(),
            "--data", dataRoot.toAbsolutePath().toString(),
            "--tenant", tenantId,
            "--admin-name", adminName,
            "--admin-password", adminPassword,
            "--user-name", userName,
            "--user-password", userPassword,
        )
        databases.forEach { args += listOf("--db", it) }
        serverUrl?.let { args += listOf("--server-url", it) }

        val process = ProcessBuilder(args)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        check(code == 0) { "Provisioning failed (exit $code): $output" }
    }
}
