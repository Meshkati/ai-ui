package dev.aiui.ui.components

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {

    @Test
    fun frameShowingQrCode_decodesToItsText() {
        // given
        val frame = cameraFrameShowing("sk-ant-api03-Zx9_kQ2-example")

        // when
        val text = decodeQr(frame.luminance, frame.rowStride, frame.width, frame.height)

        // then
        assertEquals("sk-ant-api03-Zx9_kQ2-example", text)
    }

    @Test
    fun frameWithPaddedRows_stillDecodes() {
        // given
        val frame = cameraFrameShowing("http://192.168.1.24:11434/v1", rowPadding = 64)

        // when
        val text = decodeQr(frame.luminance, frame.rowStride, frame.width, frame.height)

        // then
        assertEquals("http://192.168.1.24:11434/v1", text)
    }

    @Test
    fun lightOnDarkQrCode_decodes() {
        // given
        val frame = cameraFrameShowing("https://api.anthropic.com", lightOnDark = true)

        // when
        val text = decodeQr(frame.luminance, frame.rowStride, frame.width, frame.height)

        // then
        assertEquals("https://api.anthropic.com", text)
    }

    @Test
    fun frameWithoutQrCode_decodesToNothing() {
        // given
        val frame = Frame(ByteArray(320 * 240) { 0x80.toByte() }, rowStride = 320, width = 320, height = 240)

        // when
        val text = decodeQr(frame.luminance, frame.rowStride, frame.width, frame.height)

        // then
        assertNull(text)
    }

    private class Frame(val luminance: ByteArray, val rowStride: Int, val width: Int, val height: Int)

    /** A camera frame's brightness plane showing [text] as a QR code; cameras may pad each row. */
    private fun cameraFrameShowing(text: String, rowPadding: Int = 0, lightOnDark: Boolean = false): Frame {
        val code = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 320, 320)
        val rowStride = code.width + rowPadding
        val luminance = ByteArray(rowStride * code.height)
        for (y in 0 until code.height) {
            for (x in 0 until code.width) {
                val dark = code[x, y] != lightOnDark
                luminance[y * rowStride + x] = if (dark) 0 else 0xFF.toByte()
            }
        }
        return Frame(luminance, rowStride, code.width, code.height)
    }
}
