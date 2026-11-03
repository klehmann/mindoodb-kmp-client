package com.mindoo.db.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/** Read an [InputStream] as a flow of byte chunks, suitable for `attachments.upload`. */
fun InputStream.asByteFlow(chunkSize: Int = 64 * 1024): Flow<ByteArray> = flow {
    val buffer = ByteArray(chunkSize)
    use { stream ->
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read > 0) emit(buffer.copyOf(read))
        }
    }
}

/**
 * Bridge a `Flow<ByteArray>` (e.g. `attachments.read(...)`) to a blocking
 * [InputStream] for interop with `java.io` consumers. The flow is collected on
 * [scope]; closing the stream cancels collection.
 */
fun Flow<ByteArray>.asInputStream(scope: CoroutineScope): InputStream {
    val pipeIn = PipedInputStream(64 * 1024)
    val pipeOut = PipedOutputStream(pipeIn)
    val job = scope.launch(Dispatchers.IO) {
        try {
            collect { chunk -> pipeOut.write(chunk) }
        } finally {
            runCatching { pipeOut.close() }
        }
    }
    return object : InputStream() {
        override fun read(): Int = pipeIn.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = pipeIn.read(b, off, len)
        override fun available(): Int = pipeIn.available()
        override fun close() {
            job.cancel()
            pipeIn.close()
        }
    }
}
