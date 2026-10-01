// SPDX-License-Identifier: MIT
package dev.lightbridge.protocol

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class QrTransportTest {
    @Test fun binaryFramesSurviveQrByteSegmentsWithoutTextConversion() {
        for (block in listOf(512,1024,2048,2933)) {
            val data = ByteArray(block + 91) { it.toByte() }
            val frame = FountainEncoder(data, block, 4242).frame(17).encode()
            val matrix = QRCodeWriter().encode(frame.toString(Charsets.ISO_8859_1), BarcodeFormat.QR_CODE, 900, 900,
                mapOf(EncodeHintType.ERROR_CORRECTION to "L", EncodeHintType.MARGIN to 4))
            val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0xff000000.toInt() else -1 }
            val result = try {
                QRCodeMultiReader().decodeMultiple(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels))),
                    mapOf(DecodeHintType.TRY_HARDER to true)).first()
            } catch (e: ReaderException) { throw AssertionError("Could not detect generated QR with block=$block", e) }
            @Suppress("UNCHECKED_CAST")
            val segments = result.resultMetadata[ResultMetadataType.BYTE_SEGMENTS] as List<ByteArray>
            assertArrayEquals(frame, segments.fold(ByteArray(0)) { all, part -> all + part })
        }
    }
}
