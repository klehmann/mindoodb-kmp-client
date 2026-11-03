package com.mindoo.db.bridge

import kotlinx.coroutines.flow.Flow

/** One inbound message from the sidecar: a JSON text frame or a binary frame. */
sealed interface TransportMessage {
    data class Text(val json: String) : TransportMessage
    data class Binary(val bytes: ByteArray) : TransportMessage {
        override fun equals(other: Any?): Boolean = other is Binary && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }
}

/**
 * Duplex frame transport toward the sidecar. Common code depends only on this
 * abstraction; each platform (JVM here, mobile later) provides an implementation
 * (JVM uses a Ktor WebSocket client).
 */
interface Transport {
    /** Cold-started stream of inbound frames. Completes when the connection closes. */
    val incoming: Flow<TransportMessage>

    suspend fun sendText(text: String)

    suspend fun sendBinary(bytes: ByteArray)

    suspend fun close()
}
