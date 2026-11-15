package me.river.nightbell

import me.river.nightbell.data.capture.readableBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Regression for "the HTTPS response in a test log was not decrypted": the body
 * was compressed, not encrypted. Replaying a captured Accept-Encoding used to
 * leave OkHttp's transparent gzip off, and decoding the compressed bytes as a
 * String destroyed them before anything could decompress them.
 */
class CaptureBodyTest {

    @Test
    fun `a gzip body comes back as text`() {
        val bytes = ByteArrayOutputStream().let { buffer ->
            GZIPOutputStream(buffer).use { it.write("{\"ok\":true}".toByteArray()) }
            buffer.toByteArray()
        }
        assertEquals("{\"ok\":true}", readableBody(bytes, "gzip"))
    }

    @Test
    fun `a deflate body comes back as text`() {
        val bytes = ByteArrayOutputStream().let { buffer ->
            DeflaterOutputStream(buffer).use { it.write("pong".toByteArray()) }
            buffer.toByteArray()
        }
        assertEquals("pong", readableBody(bytes, "deflate"))
    }

    @Test
    fun `an uncompressed body and a failed decompression stay readable`() {
        assertEquals("plain body", readableBody("plain body".toByteArray(), null))
        // A wrong encoding must fall back to the bytes rather than throw.
        assertTrue(readableBody("already text".toByteArray(), "gzip").isNotBlank())
    }

    @Test
    fun `magic bytes decompress a body with no Content-Encoding header`() {
        // Happens when a captured Accept-Encoding was replayed: OkHttp leaves
        // the compressed bytes alone and the header may no longer be usable.
        val bytes = ByteArrayOutputStream().let { buffer ->
            GZIPOutputStream(buffer).use { it.write("{\"ok\":true}".toByteArray()) }
            buffer.toByteArray()
        }
        assertEquals("{\"ok\":true}", readableBody(bytes, null))
    }

    @Test
    fun `legacy GBK Chinese is readable instead of replacement characters`() {
        val bytes = "服务正常".toByteArray(java.nio.charset.Charset.forName("GBK"))
        assertEquals("服务正常", readableBody(bytes, null))
    }
}
