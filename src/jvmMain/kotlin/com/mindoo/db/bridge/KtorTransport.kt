package com.mindoo.db.bridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.DefaultWebSocketSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow

/** JVM [Transport] over a Ktor CIO WebSocket client. */
class KtorTransport private constructor(
    private val client: HttpClient,
    private val session: DefaultWebSocketSession,
) : Transport {

    override val incoming: Flow<TransportMessage> =
        session.incoming.receiveAsFlow().mapNotNull { frame ->
            when (frame) {
                is Frame.Text -> TransportMessage.Text(frame.readText())
                is Frame.Binary -> TransportMessage.Binary(frame.readBytes())
                else -> null
            }
        }

    override suspend fun sendText(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun sendBinary(bytes: ByteArray) {
        session.send(Frame.Binary(true, bytes))
    }

    override suspend fun close() {
        try {
            session.close()
        } finally {
            client.close()
        }
    }

    companion object {
        suspend fun connect(host: String, port: Int): KtorTransport {
            val client = HttpClient(CIO) { install(WebSockets) }
            val session = client.webSocketSession(host = host, port = port, path = "/")
            return KtorTransport(client, session)
        }
    }
}
