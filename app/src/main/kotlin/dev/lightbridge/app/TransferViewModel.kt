// SPDX-License-Identifier: MIT
package dev.lightbridge.app

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.BarcodeFormat
import dev.lightbridge.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/** Frame rate is stored in tenths, so 0.1 fps is exact and nothing depends on float equality. */
internal data class TransferSettings(
    val fpsTenths: Int = FpsScale.DEFAULT_TENTHS,
    val blockSize: Int = 1024,
    val tiles: Int = 1,
    val brighten: Boolean = true,
)
internal data class Sending(val name: String, val originalSize: Int, val compressed: Boolean, val encoder: FountainEncoder) {
    val sequence = AtomicInteger(0)
}
internal data class Reception(
    val stream: StreamId? = null, val frames: Int = 0, val solved: Int = 0,
    val receipt: Receipt? = null, val error: String? = null, val differentStream: Boolean = false,
)
internal data class TransferState(
    val maxFileBytes: Int = MAX_FILE_BYTES, val busy: Boolean = false, val sending: Sending? = null,
    val reception: Reception = Reception(), val settings: TransferSettings = TransferSettings(),
    val message: String? = null,
)

internal class TransferViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("transfer", 0)
    private val inboxStore: InboxStore = LightBridgeApplication.inbox(application)

    private val initialSettings = TransferSettings(
        // Tenths first; an install from before the slider had tenths stored whole frames per second.
        prefs.getInt("fps_tenths", -1).takeIf { it in FpsScale.MIN_TENTHS..FpsScale.MAX_TENTHS }
            ?: FpsScale.fromWholeFps(prefs.getInt("fps", 8)),
        prefs.getInt("block", 1024).takeIf { it in listOf(512, 1024, 2048) } ?: 1024,
        prefs.getInt("tiles", 1).takeIf { it in listOf(1, 2, 4) } ?: 1,
        prefs.getBoolean("brighten", true),
    )
    // Leave headroom for source, container, compression, reconstructed output, UI, and graph objects.
    private val memoryLimit = minOf(MAX_FILE_BYTES.toLong(), Runtime.getRuntime().maxMemory() / 8).toInt()
    private val mutable = MutableStateFlow(TransferState(settings = initialSettings, maxFileBytes = memoryLimit))
    val state = mutable.asStateFlow()
    private val epoch = AtomicInteger(0)
    private data class Scanned(val epoch: Int, val bytes: ByteArray)
    private val scans = Channel<Scanned>(16)
    private var prepareJob: Job? = null

    // Settings are written at most once per coalescing window: a slider drag must not queue a
    // SharedPreferences commit for every frame it moves.
    private val handler = Handler(Looper.getMainLooper())
    private val pendingSettings = arrayOfNulls<TransferSettings>(1)
    private val flushSettings = Runnable { writePendingSettings() }

    init {
        viewModelScope.launch { inboxStore.refresh() }
        viewModelScope.launch(Dispatchers.Default) {
            var decoder: FountainDecoder? = null
            var localEpoch = -1
            var finished = false
            for (scan in scans) {
                if (scan.epoch != epoch.get()) continue
                if (localEpoch != scan.epoch) { decoder = null; localEpoch = scan.epoch; finished = false }
                if (finished) continue
                val frame = Frame.parse(scan.bytes) ?: continue
                try {
                    if (decoder == null) {
                        require(frame.stream.totalSize <= memoryLimit + 131119) {
                            "This device's safe transfer limit is ${formatSize(memoryLimit.toLong())}. Choose a smaller file."
                        }
                        decoder = FountainDecoder(frame.stream, memoryLimit.toLong(), 500_000)
                    }
                    val current = decoder
                    if (current.stream != frame.stream) {
                        updateReception(scan.epoch) { it.copy(differentStream = true) }
                        continue // Never discard partial progress because another screen entered view.
                    }
                    current.add(frame)
                    if (scan.epoch != epoch.get()) continue
                    updateReception(scan.epoch) { Reception(current.stream, current.framesReceived, current.solvedCount) }
                    if (current.complete) {
                        val container = current.assemble()
                        val originalSize = java.nio.ByteBuffer.wrap(container).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(9)
                        require(originalSize in 1..memoryLimit) { "Expanded file exceeds this device's safe limit (${formatSize(memoryLimit.toLong())})." }
                        val file = Container.unpack(container)
                        if (scan.epoch != epoch.get()) continue
                        val receipt = persist(file)
                        updateReception(scan.epoch) { it.copy(receipt = receipt) }
                        finished = true
                        decoder = null
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    updateReception(scan.epoch) { it.copy(error = e.message ?: "Could not receive this file.") }
                    decoder = null; finished = true
                }
            }
        }
    }

    private fun updateReception(expectedEpoch: Int, transform: (Reception) -> Reception) {
        // The epoch check is inside StateFlow's CAS retry loop: a reset cannot be
        // overwritten by a late decoder update from the previous optical session.
        mutable.update { if (expectedEpoch == epoch.get()) it.copy(reception = transform(it.reception)) else it }
    }
    fun acceptQr(bytes: ByteArray) { scans.trySend(Scanned(epoch.get(), bytes)) }
    fun resetReceiver() {
        epoch.incrementAndGet()
        mutable.update { it.copy(reception = Reception()) }
        // Wake the worker so a large previous decoder is released even before another QR arrives.
        scans.trySend(Scanned(epoch.get(), ByteArray(0)))
    }
    fun cameraError(message: String) { mutable.update { it.copy(reception = it.reception.copy(error = message)) } }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun message(value: String) { mutable.update { it.copy(message = value) } }

    fun settings(value: TransferSettings) {
        mutable.update { it.copy(settings = value) }
        pendingSettings[0] = value
        handler.removeCallbacks(flushSettings)
        handler.postDelayed(flushSettings, SETTINGS_WRITE_DELAY_MS)
    }

    /** Persists the newest settings immediately; called when the app leaves the foreground. */
    fun flushSettings() = writePendingSettings()

    private fun writePendingSettings() {
        handler.removeCallbacks(flushSettings)
        val value = pendingSettings[0] ?: return
        pendingSettings[0] = null
        prefs.edit().putInt("fps_tenths", value.fpsTenths).putInt("block", value.blockSize)
            .putInt("tiles", value.tiles).putBoolean("brighten", value.brighten).apply()
    }

    fun clearSender() {
        prepareJob?.cancel()
        mutable.update { it.copy(sending = null, busy = false) }
    }
    fun importFile(uri: Uri) = prepare {
        val resolver = getApplication<Application>().contentResolver
        var name = "transfer.bin"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) require(cursor.getLong(sizeIndex) <= memoryLimit) { "This device supports files up to ${formatSize(memoryLimit.toLong())}." }
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
            }
        }
        val bytes = resolver.openInputStream(uri)?.use { it.readBounded(memoryLimit) }
            ?: error("This file provider could not open the file.")
        Triple(name, resolver.getType(uri) ?: "application/octet-stream", bytes)
    }
    fun sendText(text: String) = prepare {
        require(text.isNotBlank()) { "Enter some text first." }
        Triple("message.txt", "text/plain;charset=utf-8", text.toByteArray())
    }
    private fun prepare(load: () -> Triple<String, String, ByteArray>) {
        prepareJob?.cancel()
        mutable.update { it.copy(busy = true, sending = null) }
        val blockSize = mutable.value.settings.blockSize
        prepareJob = viewModelScope.launch {
            try {
                val sending = withContext(Dispatchers.IO) {
                    val (name, mime, data) = load()
                    val packed = Container.pack(name, mime, data)
                    ensureActive()
                    Sending(safeName(name), data.size, packed.compressed,
                        FountainEncoder(packed.bytes, blockSize, SecureRandom().nextInt(65536)))
                }
                mutable.update { it.copy(busy = false, sending = sending) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(busy = false, message = e.message ?: "Could not prepare file.") } }
        }
    }
    suspend fun qrFrames(sending: Sending, tiles: Int): List<BitMatrix> = withContext(Dispatchers.Default) {
        val writer = QRCodeWriter()
        List(tiles) {
            ensureActive()
            val bytes = sending.encoder.frame(sending.sequence.getAndIncrement()).encode()
            // ISO-8859-1 maps bytes one-to-one. No Base64 overhead, and QR byte segments
            // are extracted directly at the receiver (never through a lossy decoded string).
            writer.encode(bytes.toString(Charsets.ISO_8859_1), BarcodeFormat.QR_CODE, 0, 0, mapOf(
                EncodeHintType.MARGIN to 4,
                EncodeHintType.ERROR_CORRECTION to "L",
            ))
        }
    }

    /** Stores an optical transfer. The container digest was verified by the decoder. */
    private suspend fun persist(file: OpticalFile): Receipt =
        inboxStore.storeBytes(file.name, safeMime(file.mime), file.bytes, file.digest.hex(), Receipt.Source.Light)

    fun receivedFile(receipt: Receipt): java.io.File = inboxStore.receivedFile(receipt)

    fun save(receipt: Receipt, uri: Uri) {
        viewModelScope.launch {
            try {
                inboxStore.save(receipt, uri, getApplication<Application>().contentResolver)
                message("Saved ${receipt.name}")
            } catch (e: Exception) { message(e.message ?: "Could not save file.") }
        }
    }
    fun delete(receipt: Receipt) {
        viewModelScope.launch {
            try {
                inboxStore.delete(receipt)
                mutable.update { it.copy(reception = if (it.reception.receipt?.id == receipt.id) Reception() else it.reception) }
            } catch (e: Exception) { message(e.message ?: "Could not delete file.") }
        }
    }

    /** The inbox is shared with the Wi-Fi Direct path. */
    val inbox: InboxStore get() = inboxStore

    override fun onCleared() {
        writePendingSettings()
        scans.close()
        super.onCleared()
    }

    private companion object {
        const val SETTINGS_WRITE_DELAY_MS = 400L
    }
}
