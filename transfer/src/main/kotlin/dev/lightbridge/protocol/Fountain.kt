// SPDX-License-Identifier: MIT
// Port of Decimen Optical Transfer v0.3.0 shared/fountain.ts (MIT).
// Copyright (c) 2026 Evan Crawley (Bash Alarmist). See docs/licenses/DECIMEN-MIT.txt.
package dev.lightbridge.protocol

import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.sqrt

/** Arithmetic and operation order are wire format; do not replace with ln(). */
fun deterministicLog(x: Double): Double {
    require(x > 0 && x.isFinite())
    var e = 0; var m = x
    while (m >= 1.5) { m /= 2; e++ }
    while (m < 0.75) { m *= 2; e-- }
    val z = (m - 1) / (m + 1); val z2 = z * z
    var term = z; var sum = 0.0
    for (n in 1..21 step 2) { sum += term / n; term *= z2 }
    return e * 0.6931471805599453 + 2 * sum
}

fun solitonCdf(k: Int): DoubleArray {
    require(k in 1..65535)
    if (k == 1) return doubleArrayOf(1.0)
    val r = maxOf(1.0, 0.1 * deterministicLog(k / 0.5) * sqrt(k.toDouble()))
    val spike = minOf(k, ceil(k / r).toInt())
    var total = 0.0
    val cdf = DoubleArray(k) { i ->
        val d = i + 1
        val rho = if (d == 1) 1.0 / k else 1.0 / (d.toDouble() * (d - 1))
        val tau = when {
            d < spike -> r / (d.toDouble() * k)
            d == spike -> r * maxOf(0.0, deterministicLog(r / 0.5)) / k
            else -> 0.0
        }
        total += rho + tau
        total
    }
    for (i in cdf.indices) cdf[i] /= total
    cdf[k - 1] = 1.0
    return cdf
}

private class SplitMix(private var seed: Int) {
    fun next(): Long {
        seed += 0x9e3779b9.toInt()
        var t = seed xor (seed ushr 16)
        t *= 0x21f0aaad; t = t xor (t ushr 15)
        t *= 0x735a2d97; t = t xor (t ushr 15)
        return t.toLong() and 0xffffffffL
    }
}

fun frameIndices(k: Int, cdf: DoubleArray, session: Int, sequence: Int): IntArray {
    var seed = ((session + 1) * 0x9e3779b1.toInt()) xor (sequence + 0x85ebca6b.toInt())
    seed = (seed xor (seed ushr 13)) * 0xc2b2ae35.toInt()
    val random = SplitMix(seed xor (seed ushr 16))
    val u = random.next() * (1.0 / 4294967296.0)
    var lo = 0; var hi = k - 1
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (cdf[mid] >= u) hi = mid else lo = mid + 1
    }
    val degree = minOf(k, lo + 1)
    if (degree > (k shr 3)) {
        val scratch = IntArray(k) { it }
        return IntArray(degree) { i ->
            val j = i + (random.next() % (k - i)).toInt()
            val temp = scratch[i]; scratch[i] = scratch[j]; scratch[j] = temp
            scratch[i]
        }
    }
    val indices = LinkedHashSet<Int>()
    while (indices.size < degree) indices.add((random.next() % k).toInt())
    return indices.toIntArray()
}

class FountainEncoder(private val data: ByteArray, blockSize: Int, session: Int) {
    val stream: StreamId
    private val cdf: DoubleArray
    init {
        require(data.size in 49..MAX_CONTAINER_BYTES && blockSize in 1..MAX_BLOCK_BYTES && session in 0..65535)
        val k = (data.size + blockSize - 1) / blockSize
        require(k <= 65535) { "Use a denser QR setting for this file (maximum 65,535 blocks)." }
        stream = StreamId(session, k, blockSize, data.size, fnv1a(data))
        cdf = solitonCdf(k)
    }
    fun frame(sequence: Int): Frame {
        val out = ByteArray(stream.blockSize)
        for (index in frameIndices(stream.k, cdf, stream.session, sequence)) {
            val offset = index * stream.blockSize
            for (i in 0 until minOf(stream.blockSize, data.size - offset))
                out[i] = (out[i].toInt() xor data[offset + i].toInt()).toByte()
        }
        return Frame(stream, sequence, out)
    }
}

/** Single-thread confined peeling decoder. Bounded equations/edges resist hostile QR input. */
class FountainDecoder(
    val stream: StreamId,
    private val maxPendingBytes: Long = 96L * 1024 * 1024,
    private val maxEdges: Int = 2_000_000,
) {
    private class Equation(val indices: MutableSet<Int>, val bytes: ByteArray)
    private val cdf = solitonCdf(stream.k)
    private val solved = arrayOfNulls<ByteArray>(stream.k)
    private val waiting = HashMap<Int, MutableSet<Equation>>()
    private val seen = HashSet<Int>()
    private var edges = 0
    private var equations = 0
    var solvedCount = 0; private set
    val framesReceived get() = seen.size
    val complete get() = solvedCount == stream.k
    fun add(frame: Frame) {
        require(frame.stream == stream && frame.payload.size == stream.blockSize) { "Stream changed." }
        if (complete || frame.sequence in seen) return
        require(seen.size < maxOf(10000, stream.k * 12)) { "Too many frames without completion. Restart receiving." }
        seen.add(frame.sequence)
        val indices = frameIndices(stream.k, cdf, stream.session, frame.sequence).toMutableSet()
        val bytes = frame.payload.copyOf()
        for (i in indices.toList()) solved[i]?.let { xor(bytes, it); indices.remove(i) }
        if (indices.isEmpty()) return
        if (indices.size == 1) { resolve(indices.first(), bytes); return }
        require(edges + indices.size <= maxEdges &&
            (equations.toLong() + 1) * stream.blockSize <= maxPendingBytes) {
            "Decoder memory limit reached. Restart with a smaller file."
        }
        val eq = Equation(indices, bytes)
        equations++; edges += indices.size
        for (i in indices) waiting.getOrPut(i) { HashSet() }.add(eq)
    }
    private fun resolve(index: Int, bytes: ByteArray) {
        val queue = ArrayDeque<Pair<Int, ByteArray>>()
        queue.add(index to bytes)
        while (queue.isNotEmpty()) {
            val (i, value) = queue.removeLast()
            if (solved[i] != null) continue
            solved[i] = value; solvedCount++
            val dependents = waiting.remove(i) ?: continue
            for (eq in dependents) {
                xor(eq.bytes, value); eq.indices.remove(i); edges--
                if (eq.indices.size == 1) {
                    val next = eq.indices.first()
                    waiting[next]?.remove(eq); edges--; equations--
                    if (solved[next] == null) queue.add(next to eq.bytes)
                }
            }
        }
    }
    fun assemble(): ByteArray {
        check(complete)
        val result = ByteArray(stream.totalSize)
        for (i in solved.indices) {
            val start = i * stream.blockSize
            solved[i]!!.copyInto(result, start, 0, minOf(stream.blockSize, result.size - start))
        }
        require(fnv1a(result) == stream.checksum) { "Transfer checksum failed. Restart receiving." }
        return result
    }
    private fun xor(dst: ByteArray, src: ByteArray) {
        for (i in dst.indices) dst[i] = (dst[i].toInt() xor src[i].toInt()).toByte()
    }
}
