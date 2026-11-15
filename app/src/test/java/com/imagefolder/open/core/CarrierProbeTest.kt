package com.imagefolder.open.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

class CarrierProbeTest {

    @Test
    fun `probes png bundle metadata`() {
        val covered = PngChunkCodec.insertBundle(
            coverPng = minimalPng(),
            name = "bundle.zip",
            mime = "application/zip",
            envelope = ByteArray(1024) { it.toByte() },
            encrypted = true,
            compressed = true,
            entryCount = 7,
        )

        val result = CarrierProbe.probe(covered)
        assertEquals(CarrierFormat.PNG, result?.format)
        assertEquals("bundle.zip", result?.name)
        assertEquals(7, result?.entries)
        assertEquals(true, result?.encrypted)
        assertEquals(true, result?.compressed)
    }

    @Test
    fun `probes jpeg bundle metadata`() {
        val covered = JpegCarrierCodec.insertBundle(
            coverJpeg = minimalJpeg(),
            name = "bundle.zip",
            mime = "application/zip",
            envelope = ByteArray(4096) { it.toByte() },
            encrypted = false,
            compressed = true,
            entryCount = 3,
        )

        val result = CarrierProbe.probe(covered)
        assertEquals(CarrierFormat.JPEG, result?.format)
        assertEquals("bundle.zip", result?.name)
        assertEquals(3, result?.entries)
        assertEquals(false, result?.encrypted)
        assertEquals(true, result?.compressed)
    }

    @Test
    fun `probes webp bundle metadata`() {
        val covered = WebpCarrierCodec.insertBundle(
            coverWebp = minimalWebp(),
            name = "bundle.zip",
            mime = "application/zip",
            envelope = ByteArray(2048) { it.toByte() },
            encrypted = true,
            compressed = false,
            entryCount = 2,
        )

        val result = CarrierProbe.probe(covered)
        assertEquals(CarrierFormat.WEBP, result?.format)
        assertEquals("bundle.zip", result?.name)
        assertEquals(2, result?.entries)
        assertEquals(true, result?.encrypted)
        assertEquals(false, result?.compressed)
    }

    @Test
    fun `probes legacy png chunks and counts them`() {
        val covered = PngChunkCodec.insertFiles(
            minimalPng(),
            listOf(
                HiddenFile("a.txt", "text/plain", "a".toByteArray(StandardCharsets.UTF_8)),
                HiddenFile("b.txt", "text/plain", "b".toByteArray(StandardCharsets.UTF_8)),
            ),
        )

        val result = CarrierProbe.probe(covered)
        assertEquals(CarrierFormat.PNG, result?.format)
        assertEquals("a.txt", result?.name)
        assertEquals(2, result?.entries)
        assertEquals(false, result?.encrypted)
        assertEquals(false, result?.compressed)
    }

    @Test
    fun `plain images have no probe result`() {
        assertNull(CarrierProbe.probe(minimalPng()))
        assertNull(CarrierProbe.probe(minimalJpeg()))
        assertNull(CarrierProbe.probe(minimalWebp()))
        assertNull(CarrierProbe.probe(ByteArray(32)))
    }

    @Test
    fun `probe skips large payloads instead of reading them`() {
        val big = ByteArray(5 * 1024 * 1024) { 7 }
        val covered = PngChunkCodec.insertFiles(
            minimalPng(),
            listOf(HiddenFile("big.bin", null, big)),
        )
        val counting = CountingInputStream(ByteArrayInputStream(covered))

        val result = CarrierProbe.probe(counting)

        assertEquals("big.bin", result?.name)
        assertTrue("read ${counting.readBytes} bytes", counting.readBytes < 64 * 1024)
    }

    @Test
    fun `malformed header does not throw`() {
        val payload = "{\"name\":{}}".toByteArray(StandardCharsets.UTF_8) +
            byteArrayOf(0) +
            "body".toByteArray(StandardCharsets.UTF_8)
        val png = pngWithChunks(
            "IHDR" to ByteArray(13),
            "tpWj" to payload,
            "IDAT" to byteArrayOf(0),
            "IEND" to ByteArray(0),
        )

        val result = CarrierProbe.probe(png)
        assertNull(result?.name)
        assertEquals(1, result?.entries)
    }

    @Test
    fun `truncated carriers do not throw`() {
        val png = PngChunkCodec.insertBundle(
            minimalPng(), "b.zip", "application/zip", ByteArray(100),
            false, false, 1,
        )
        val jpeg = JpegCarrierCodec.insertBundle(
            minimalJpeg(), "b.zip", "application/zip", ByteArray(100),
            false, false, 1,
        )
        val webp = WebpCarrierCodec.insertBundle(
            minimalWebp(), "b.zip", "application/zip", ByteArray(100),
            false, false, 1,
        )

        for (carrier in listOf(png, jpeg, webp)) {
            for (cut in listOf(1, 3, 8, 12, 20, carrier.size / 2)) {
                val outcome = runCatching {
                    CarrierProbe.probe(carrier.copyOfRange(0, cut))
                }
                assertTrue("cut $cut of ${carrier.size}", outcome.isSuccess)
            }
        }
    }

    @Test
    fun `large jpeg payload is split and probed with a small read`() {
        val covered = JpegCarrierCodec.insertBundle(
            minimalJpeg(), "big.zip", "application/zip",
            ByteArray(200_000) { it.toByte() }, false, false, 1,
        )
        val counting = CountingInputStream(ByteArrayInputStream(covered))

        val result = CarrierProbe.probe(counting)

        assertEquals("big.zip", result?.name)
        assertTrue("read ${counting.readBytes} bytes", counting.readBytes < 64 * 1024)
    }

    @Test
    fun `large webp payload is probed with a small read`() {
        val covered = WebpCarrierCodec.insertBundle(
            minimalWebp(), "big.zip", "application/zip",
            ByteArray(200_000) { it.toByte() }, false, false, 1,
        )
        val counting = CountingInputStream(ByteArrayInputStream(covered))

        val result = CarrierProbe.probe(counting)

        assertEquals("big.zip", result?.name)
        assertTrue("read ${counting.readBytes} bytes", counting.readBytes < 64 * 1024)
    }

    @Test
    fun `probe works when skip returns zero`() {
        val covered = PngChunkCodec.insertFiles(
            minimalPng(),
            listOf(HiddenFile("big.bin", null, ByteArray(1_000_000) { 3 })),
        )

        val result = CarrierProbe.probe(NoSkipInputStream(ByteArrayInputStream(covered)))

        assertEquals("big.bin", result?.name)
    }

    private class NoSkipInputStream(input: InputStream) : FilterInputStream(input) {
        override fun skip(n: Long): Long = 0
    }

    private fun pngWithChunks(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return signature + chunks.fold(ByteArray(0)) { acc, (type, payload) ->
            acc + pngChunk(type, payload)
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var readBytes = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) readBytes++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) readBytes += it }
    }

    private fun minimalJpeg(): ByteArray {
        val app0 = byteArrayOf(
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0,
            1, 1,
            0,
            0, 1,
            0, 1,
            0, 0,
        )
        val dqt = ByteArray(65)
        val sof = byteArrayOf(8, 0, 1, 0, 1, 1, 1, 0x11, 0)
        val sos = byteArrayOf(1, 1, 0, 0, 0x3F, 0)
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            jpegSegment(0xE0, app0) +
            jpegSegment(0xDB, dqt) +
            jpegSegment(0xC0, sof) +
            jpegSegment(0xDA, sos) +
            byteArrayOf(0, 0xFF.toByte(), 0xD9.toByte())
    }

    private fun jpegSegment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(
            0xFF.toByte(),
            marker.toByte(),
            (length shr 8).toByte(),
            length.toByte(),
        ) + payload
    }

    private fun minimalWebp(): ByteArray = riffWebp(
        listOf("VP8 " to ByteArray(10) { it.toByte() }),
    )

    private fun riffWebp(chunks: List<Pair<String, ByteArray>>): ByteArray {
        val body = chunks.fold(ByteArray(0)) { acc, (fourcc, payload) ->
            val padded = if (payload.size % 2 == 0) payload else payload + byteArrayOf(0)
            acc + fourcc.toByteArray(StandardCharsets.US_ASCII) +
                u32Le(payload.size) + padded
        }
        val total = 4 + body.size
        return "RIFF".toByteArray(StandardCharsets.US_ASCII) + u32Le(total) +
            "WEBP".toByteArray(StandardCharsets.US_ASCII) + body
    }

    private fun u32Le(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    private fun minimalPng(): ByteArray {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return signature +
            pngChunk("IHDR", ByteArray(13)) +
            pngChunk("IDAT", byteArrayOf(0)) +
            pngChunk("IEND", byteArrayOf())
    }

    private fun pngChunk(type: String, payload: ByteArray): ByteArray {
        val body = type.toByteArray(StandardCharsets.US_ASCII) + payload
        val crc = java.util.zip.CRC32().apply { update(body) }.value.toInt()
        return byteArrayOf(
            (payload.size ushr 24).toByte(),
            (payload.size ushr 16).toByte(),
            (payload.size ushr 8).toByte(),
            payload.size.toByte(),
        ) + body + byteArrayOf(
            (crc ushr 24).toByte(),
            (crc ushr 16).toByte(),
            (crc ushr 8).toByte(),
            crc.toByte(),
        )
    }
}
