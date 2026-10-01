// SPDX-License-Identifier: MIT
package dev.lightbridge.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import dev.lightbridge.protocol.safeMime
import dev.lightbridge.protocol.safeName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * What the inbox UI needs from the app: whoever owns the storage supplies these, so the same
 * receipt card works for a file that arrived over light and one that arrived over Wi-Fi Direct.
 */
internal class InboxActions(
    val save: (Receipt, Uri) -> Unit,
    val delete: (Receipt) -> Unit,
    val share: (Receipt) -> Unit,
    val file: (Receipt) -> java.io.File,
    val message: (String) -> Unit,
)

/** One verified file in the inbox, whichever link delivered it. */
internal data class Receipt(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val hash: String,
    val time: Long,
    /** How it arrived, shown in the inbox row. */
    val source: Source = Source.Light,
) {
    internal enum class Source(val label: String) {
        Light("Optical"),
        Link("Wi-Fi Direct"),
    }
}

/**
 * The inbox: a private directory of verified files plus their metadata.
 *
 * Both transfer paths share it, so a file received over Wi-Fi Direct lands in exactly the same
 * place, with the same limits, as one received over light. Payloads are written to a `.part` file
 * first and only renamed once the digest has been checked, so the inbox can never contain a file
 * that failed verification.
 */
internal class InboxStore(context: Context) {

    private val directory = File(context.filesDir, "received").apply { mkdirs() }
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<List<Receipt>>(emptyList())

    /** Every stored receipt, newest first. */
    val history: StateFlow<List<Receipt>> = mutable.asStateFlow()

    /** Inbox ceiling; the same number the QR path enforces. */
    val limitBytes: Long = 256L * 1024 * 1024

    fun receivedFile(receipt: Receipt): File {
        require(UUID.fromString(receipt.id).toString() == receipt.id)
        return File(directory, "${receipt.id}.bin")
    }

    /** Temp file a receiver streams into before verification. */
    fun partFile(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(directory, "$id.part")
    }

    /** Free space guard, used by both paths before accepting anything. */
    fun hasRoomFor(size: Long): Boolean = directory.usableSpace > size + 16L * 1024 * 1024

    /** Current inbox size, for the "x / 256 MiB" line and the capacity check. */
    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory.listFiles()?.filter { it.extension == "bin" }?.sumOf { it.length() } ?: 0L
        }
    }

    /** Stores a complete in-memory payload (the optical path already verified it). */
    suspend fun storeBytes(name: String, mime: String, bytes: ByteArray, digestHex: String, source: Receipt.Source): Receipt =
        withContext(Dispatchers.IO) {
            finish(
                id = UUID.randomUUID().toString(),
                name = name,
                mime = mime,
                size = bytes.size.toLong(),
                digestHex = digestHex,
                source = source,
            ) { target -> target.outputStream().use { it.write(bytes) } }
        }

    /**
     * Promotes a verified `.part` file into the inbox.
     *
     * The caller has already checked the digest while streaming; [size] and [digestHex] are both
     * re-checked here against what is on disk, so a truncated or swapped part file cannot slip in.
     */
    suspend fun commitPart(
        part: File,
        id: String,
        name: String,
        mime: String,
        size: Long,
        digestHex: String,
        source: Receipt.Source,
    ): Receipt = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                require(part.isFile) { "The transfer did not leave a complete file." }
                require(part.length() == size) { "Size mismatch: ${part.length()} bytes, expected $size." }
                val actual = part.inputStream().use { stream ->
                    java.security.MessageDigest.getInstance("SHA-256").run {
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read <= 0) break
                            update(buffer, 0, read)
                        }
                        digest().joinToString("") { "%02x".format(it) }
                    }
                }
                require(actual == digestHex) { "SHA-256 mismatch — the file did not arrive intact." }
                enforceCapacity(size)
                val receipt = Receipt(id, safeName(name), safeMime(mime), size, digestHex,
                    System.currentTimeMillis(), source)
                check(part.renameTo(receivedFile(receipt))) { "Could not finalize the received file." }
                writeMetadata(receipt)
                receipt
            } catch (e: Exception) {
                part.delete()
                throw e
            }
        }
    }

    private suspend fun finish(
        id: String,
        name: String,
        mime: String,
        size: Long,
        digestHex: String,
        source: Receipt.Source,
        write: (File) -> Unit,
    ): Receipt = withContext(Dispatchers.IO) {
        mutex.withLock {
            enforceCapacity(size)
            val receipt = Receipt(id, safeName(name), safeMime(mime), size, digestHex, System.currentTimeMillis(), source)
            val target = receivedFile(receipt)
            val temp = partFile(id)
            try {
                write(temp)
                check(temp.renameTo(target)) { "Could not finalize the received file." }
                writeMetadata(receipt)
                receipt
            } catch (e: Exception) {
                temp.delete(); target.delete(); metadataFile(receipt).delete()
                throw e
            }
        }
    }

    private fun enforceCapacity(size: Long) {
        val used = directory.listFiles()?.filter { it.extension == "bin" }?.sumOf { it.length() } ?: 0L
        require(used + size <= limitBytes) { "Inbox is full (256 MiB). Save and delete older files, then retry." }
        require(hasRoomFor(size)) { "Not enough free storage." }
    }

    private fun writeMetadata(receipt: Receipt) {
        val metadata = JSONObject().put("id", receipt.id).put("name", receipt.name).put("mime", receipt.mime)
            .put("size", receipt.size).put("hash", receipt.hash).put("time", receipt.time)
            .put("source", receipt.source.name)
        val temp = File(directory, "${receipt.id}.json.part")
        temp.writeText(metadata.toString())
        check(temp.renameTo(metadataFile(receipt))) { "Could not save file metadata." }
    }

    private fun metadataFile(receipt: Receipt) = File(directory, "${receipt.id}.json")

    /** Rebuilds the list from disk, dropping anything that was left half-written. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory.listFiles()?.filter {
                it.name.endsWith(".part") ||
                    (it.extension == "bin" && !File(directory, "${it.nameWithoutExtension}.json").exists())
            }?.forEach { it.delete() }
            val receipts = directory.listFiles()?.filter { it.extension == "json" }?.mapNotNull { file ->
                runCatching {
                    val json = JSONObject(file.readText())
                    val id = json.getString("id")
                    require(UUID.fromString(id).toString() == id && file.name == "$id.json")
                    val source = runCatching { Receipt.Source.valueOf(json.optString("source", "Light")) }
                        .getOrDefault(Receipt.Source.Light)
                    Receipt(id, safeName(json.getString("name")), json.getString("mime"), json.getLong("size"),
                        json.getString("hash"), json.getLong("time"), source)
                        .takeIf { receivedFile(it).isFile }
                }.getOrNull()
            }?.sortedByDescending { it.time } ?: emptyList()
            mutable.value = receipts
        }
    }

    suspend fun save(receipt: Receipt, uri: Uri, resolver: ContentResolver) = withContext(Dispatchers.IO) {
        mutex.withLock {
            resolver.openOutputStream(uri, "wt")?.use { output ->
                receivedFile(receipt).inputStream().use { it.copyTo(output) }
            } ?: error("Could not open the selected destination.")
        }
    }

    suspend fun delete(receipt: Receipt) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = receivedFile(receipt)
            if (file.exists() && !file.delete()) error("Could not delete file.")
            metadataFile(receipt).delete()
        }
        refresh()
    }
}
