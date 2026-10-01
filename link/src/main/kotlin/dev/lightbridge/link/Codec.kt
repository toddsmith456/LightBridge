// SPDX-License-Identifier: MIT
/*
 * Binary codec for the Wi-Fi Direct link.
 *
 * The link is deliberately JSON-free: every wire structure is a compact, length-checked binary
 * record. That keeps the QR connect payload small enough to scan at the slowest frame rates and
 * makes the whole protocol testable on the JVM without an Android runtime.
 *
 * All integers are big-endian. `blob`/`text` are length-prefixed, so a truncated or over-long
 * field is rejected instead of silently mis-parsed.
 */
package dev.lightbridge.link

/** Thrown when a peer sends something that is not a valid LightBridge link record. */
public class LinkFormatException(message: String) : IllegalArgumentException(message)

internal class Writer(initial: Int = 256) {
    private val out = java.io.ByteArrayOutputStream(initial)

    fun u8(value: Int) = apply { out.write(value and 0xFF) }

    fun u16(value: Int) = apply {
        require(value in 0..0xFFFF) { "u16 out of range: $value" }
        out.write((value ushr 8) and 0xFF); out.write(value and 0xFF)
    }

    fun u32(value: Long) = apply {
        require(value in 0..0xFFFFFFFFL) { "u32 out of range: $value" }
        for (shift in 24 downTo 0 step 8) out.write(((value ushr shift) and 0xFF).toInt())
    }

    fun u64(value: Long) = apply {
        for (shift in 56 downTo 0 step 8) out.write(((value ushr shift) and 0xFF).toInt())
    }

    fun bytes(value: ByteArray) = apply { out.write(value) }

    /** Length-prefixed byte string (u16 length). */
    fun blob(value: ByteArray) = apply {
        require(value.size <= 0xFFFF) { "blob too long: ${value.size}" }
        u16(value.size); out.write(value)
    }

    /** Length-prefixed UTF-8 string (u16 byte length). */
    fun text(value: String) = blob(value.toByteArray(Charsets.UTF_8))

    fun toByteArray(): ByteArray = out.toByteArray()
}

internal class Reader(private val bytes: ByteArray) {
    private var pos = 0

    val remaining: Int get() = bytes.size - pos

    fun u8(): Int {
        require(remaining >= 1) { "truncated record" }
        return bytes[pos++].toInt() and 0xFF
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun u32(): Long {
        var value = 0L
        repeat(4) { value = (value shl 8) or u8().toLong() }
        return value
    }

    fun u64(): Long {
        var value = 0L
        repeat(8) { value = (value shl 8) or u8().toLong() }
        return value
    }

    fun bytes(count: Int): ByteArray {
        require(count >= 0 && remaining >= count) { "truncated record" }
        return bytes.copyOfRange(pos, pos + count).also { pos += count }
    }

    fun blob(): ByteArray = bytes(u16())

    fun text(): String = String(blob(), Charsets.UTF_8)

    /** Every field must be consumed: trailing bytes mean the sender and we disagree on the shape. */
    fun expectEnd(what: String) {
        require(remaining == 0) { "$what has ${remaining} unexpected trailing byte(s)" }
    }
}

/** URL-safe base64 without padding, implemented here so the module stays free of API-level limits. */
public object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val reverse = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    public fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[n ushr 18]).append(ALPHABET[(n ushr 12) and 63])
                .append(ALPHABET[(n ushr 6) and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = bytes[i].toInt() and 0xFF shl 16
                sb.append(ALPHABET[n ushr 18]).append(ALPHABET[(n ushr 12) and 63])
            }
            2 -> {
                val n = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8)
                sb.append(ALPHABET[n ushr 18]).append(ALPHABET[(n ushr 12) and 63])
                    .append(ALPHABET[(n ushr 6) and 63])
            }
        }
        return sb.toString()
    }

    /** Returns null for anything that is not canonical, unpadded, url-safe base64. */
    public fun decodeOrNull(text: String): ByteArray? {
        if (text.isEmpty()) return ByteArray(0) // the encoding of an empty byte string
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (c in text) {
            val value = if (c.code < 128) reverse[c.code] else -1
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
            }
        }
        // A leftover group of 2..4 bits must be zero padding, never data.
        if (bits > 0 && (buffer and ((1 shl bits) - 1)) != 0) return null
        return out.toByteArray()
    }
}

/** Lowercase hex. `java.util.HexFormat` needs API 34, so this stays hand-rolled. */
public fun ByteArray.hex(): String = buildString(size * 2) {
    for (b in this@hex) {
        val v = b.toInt() and 0xFF
        append("0123456789abcdef"[v ushr 4]); append("0123456789abcdef"[v and 0x0F])
    }
}

/** Constant-time comparison, so verification cannot be turned into an oracle by timing. */
public fun ByteArray.contentEqualsConstantTime(other: ByteArray): Boolean {
    if (size != other.size) return false
    var diff = 0
    for (i in indices) diff = diff or (this[i].toInt() xor other[i].toInt())
    return diff == 0
}
