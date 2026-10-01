// SPDX-License-Identifier: MIT
// Decimen v0.3.0 protocol port. Copyright (c) 2026 Evan Crawley (Bash Alarmist).
// Android port Copyright (c) 2026 Todd Smith and LightBridge contributors.
package dev.lightbridge.protocol

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

const val MAX_FILE_BYTES = 64 * 1024 * 1024
const val MAX_CONTAINER_BYTES = MAX_FILE_BYTES + 131119
const val MAX_BLOCK_BYTES = 2933
internal fun buffer(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
internal fun ByteBuffer.u16() = short.toInt() and 65535
fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
fun safeName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
    .filter { it.code !in 0..31 && it.code != 127 }.trim().take(240)
    .takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "transfer.bin"

/** Keep untrusted MIME strings out of Android intents and document-provider contracts. */
fun safeMime(mime: String): String {
    val base = mime.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
    return base.takeIf { it.length <= 255 && it.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) }
        ?: "application/octet-stream"
}

private fun alreadyCompressed(mime: String): Boolean {
    val type = safeMime(mime)
    if (type.startsWith("video/")) return true
    if (type.startsWith("image/") && type !in setOf("image/bmp", "image/x-ms-bmp", "image/svg+xml", "image/tiff", "image/x-icon", "image/vnd.microsoft.icon")) return true
    if (type.startsWith("audio/") && type !in setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave", "audio/aiff", "audio/x-aiff", "audio/basic", "audio/l16")) return true
    return type in setOf("application/zip", "application/gzip", "application/x-gzip", "application/x-7z-compressed", "application/vnd.rar", "application/zstd") ||
        type.endsWith("+zip") || type.startsWith("application/vnd.openxmlformats-officedocument.") || type.startsWith("application/vnd.oasis.opendocument.")
}

/** Bounds are enforced while reading, never trusted from a provider or gzip trailer. */
fun InputStream.readBounded(limit: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(limit, 65536))
    val chunk = ByteArray(8192)
    var total = 0
    while (true) {
        val n = read(chunk)
        if (n < 0) break
        total += n
        require(total <= limit) { "File exceeds the supported size ($limit bytes)." }
        out.write(chunk, 0, n)
    }
    return out.toByteArray()
}

data class OpticalFile(val name: String, val mime: String, val bytes: ByteArray, val digest: ByteArray, val compressed: Boolean)
data class PackedFile(val bytes: ByteArray, val compressed: Boolean)

object Container {
    fun pack(name: String, mime: String, bytes: ByteArray): PackedFile {
        require(bytes.isNotEmpty()) { "Choose a non-empty file." }
        require(bytes.size <= MAX_FILE_BYTES) { "Files are limited to 64 MiB." }
        val n = safeName(name).toByteArray(Charsets.UTF_8)
        val t = mime.ifBlank { "application/octet-stream" }.toByteArray(Charsets.UTF_8)
        require(n.size <= 65535 && t.size <= 65535) { "File metadata is too long." }
        val compressed = if (bytes.size >= 768 && !alreadyCompressed(mime)) ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(bytes) }
        }.toByteArray() else null
        val gzip = compressed != null && compressed.size + 64 < bytes.size
        val payload = if (gzip) compressed!! else bytes
        val out = ByteBuffer.allocate(49 + n.size + t.size + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(byteArrayOf(0x44, 0x43, 0x46, 0x32)).put(if (gzip) 1.toByte() else 0.toByte())
        out.putShort(n.size.toShort()).putShort(t.size.toShort()).putInt(bytes.size).putInt(payload.size)
        out.put(sha256(bytes)).put(n).put(t).put(payload)
        return PackedFile(out.array(), gzip)
    }

    /** A recovered file is never exposed before both structural and SHA-256 checks pass. */
    fun unpack(bytes: ByteArray): OpticalFile {
        require(bytes.size in 49..MAX_CONTAINER_BYTES) { "Invalid container length." }
        val b = buffer(bytes)
        require(b.int == 0x32464344) { "Not a DCF2 file." }
        val compression = b.get().toInt()
        require(compression in 0..1) { "Unsupported compression." }
        val n = b.u16(); val t = b.u16(); val size = b.int; val transmitted = b.int
        require(size in 1..MAX_FILE_BYTES && transmitted in 1..MAX_FILE_BYTES) { "Invalid file size." }
        require(49L + n + t + transmitted == bytes.size.toLong()) { "Incomplete container." }
        val hash = ByteArray(32).also(b::get)
        val name = ByteArray(n).also(b::get).toString(Charsets.UTF_8)
        val mime = ByteArray(t).also(b::get).toString(Charsets.UTF_8)
        val payload = ByteArray(transmitted).also(b::get)
        val original = if (compression == 1) {
            require(payload.size >= 18 && buffer(payload).getInt(payload.size - 4) == size) { "Invalid gzip length." }
            GZIPInputStream(payload.inputStream()).use { it.readBounded(size) }
        } else payload
        require(original.size == size) { "File size verification failed." }
        require(MessageDigest.isEqual(hash, sha256(original))) { "SHA-256 verification failed. Restart the transfer." }
        return OpticalFile(safeName(name), mime.ifBlank { "application/octet-stream" }, original, hash, compression == 1)
    }
}

data class StreamId(val session: Int, val k: Int, val blockSize: Int, val totalSize: Int, val checksum: Int)
data class Frame(val stream: StreamId, val sequence: Int, val payload: ByteArray) {
    fun encode(): ByteArray = ByteBuffer.allocate(20 + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(0xd1.toByte()); put(0x0c); putShort(stream.session.toShort()); putInt(sequence)
        putShort(stream.k.toShort()); putShort(stream.blockSize.toShort()); putInt(stream.totalSize)
        putInt(stream.checksum); put(payload)
    }.array()

    companion object {
        fun parse(bytes: ByteArray): Frame? {
            if (bytes.size !in 21..(20 + MAX_BLOCK_BYTES)) return null
            val b = buffer(bytes)
            if (b.get() != 0xd1.toByte() || b.get() != 0x0c.toByte()) return null
            val session = b.u16(); val seq = b.int; val k = b.u16(); val block = b.u16()
            val length = b.int; val hash = b.int
            if (k == 0 || block !in 1..MAX_BLOCK_BYTES || length !in 49..MAX_CONTAINER_BYTES) return null
            if (bytes.size != 20 + block || (length.toLong() + block - 1) / block != k.toLong()) return null
            return Frame(StreamId(session, k, block, length, hash), seq, bytes.copyOfRange(20, bytes.size))
        }
    }
}

fun fnv1a(bytes: ByteArray): Int {
    var h = 0x811c9dc5.toInt()
    for (b in bytes) h = (h xor (b.toInt() and 255)) * 0x01000193
    return h
}
