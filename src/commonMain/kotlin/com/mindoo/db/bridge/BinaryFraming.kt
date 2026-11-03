package com.mindoo.db.bridge

/** A decoded binary attachment chunk. */
data class BinaryChunk(val streamId: Int, val seq: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is BinaryChunk && streamId == other.streamId && seq == other.seq && payload.contentEquals(other.payload)

    override fun hashCode(): Int = (streamId * 31 + seq) * 31 + payload.contentHashCode()
}

/** Encode a chunk with the multiplexing header `[0x01][uint32 streamId][uint32 seq][payload]`. */
fun encodeBinaryChunk(streamId: Int, seq: Int, payload: ByteArray): ByteArray {
    val out = ByteArray(BINARY_HEADER_LENGTH + payload.size)
    out[0] = BINARY_CHUNK_MARKER
    writeUInt32BE(out, 1, streamId)
    writeUInt32BE(out, 5, seq)
    payload.copyInto(out, BINARY_HEADER_LENGTH)
    return out
}

/** Decode a framed binary chunk, or null if the header is invalid. */
fun decodeBinaryChunk(data: ByteArray): BinaryChunk? {
    if (data.size < BINARY_HEADER_LENGTH || data[0] != BINARY_CHUNK_MARKER) return null
    val streamId = readUInt32BE(data, 1)
    val seq = readUInt32BE(data, 5)
    val payload = data.copyOfRange(BINARY_HEADER_LENGTH, data.size)
    return BinaryChunk(streamId, seq, payload)
}

private fun writeUInt32BE(target: ByteArray, offset: Int, value: Int) {
    target[offset] = ((value ushr 24) and 0xFF).toByte()
    target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    target[offset + 3] = (value and 0xFF).toByte()
}

private fun readUInt32BE(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xFF) shl 24) or
        ((source[offset + 1].toInt() and 0xFF) shl 16) or
        ((source[offset + 2].toInt() and 0xFF) shl 8) or
        (source[offset + 3].toInt() and 0xFF)
