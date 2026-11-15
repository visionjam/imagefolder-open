package com.imagefolder.open.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class CarrierCodecTest {

    @Test
    fun `detects png jpeg and webp signatures`() {
        assertEquals(CarrierFormat.PNG, CarrierCodec.detect(minimalPng()))
        assertEquals(CarrierFormat.JPEG, CarrierCodec.detect(minimalJpeg()))
        assertEquals(CarrierFormat.WEBP, CarrierCodec.detect(minimalWebp()))
        assertEquals(null, CarrierCodec.detect("GIF89a".toByteArray()))
    }

    @Test
    fun `jpeg bundle round trip`() {
        val envelope = byteArrayOf(1, 2, 3, 4, 5)
        val covered = JpegCarrierCodec.insertBundle(
            coverJpeg = minimalJpeg(),
            name = "bundle.zip",
            mime = "application/zip",
            envelope = envelope,
            encrypted = true,
            compressed = true,
            entryCount = 7,
        )

        assertTrue(JpegCarrierCodec.isJpeg(covered))
        val items = JpegCarrierCodec.extractItems(covered)
        assertEquals(1, items.size)
        assertEquals("bundle.zip", items[0].name)
        assertEquals("application/zip", items[0].mime)
        assertEquals("ifv2", items[0].format)
        assertEquals(true, items[0].encrypted)
        assertEquals(true, items[0].compressed)
        assertEquals(7, items[0].entryCount)
        assertArrayEquals(envelope, items[0].bytes)
    }

    @Test
    fun `jpeg large payload is split across app15 segments`() {
        val envelope = ByteArray(200_000) { (it * 31).toByte() }
        val covered = JpegCarrierCodec.insertBundle(
            coverJpeg = minimalJpeg(),
            name = "large.bin",
            mime = "application/octet-stream",
            envelope = envelope,
            encrypted = false,
            compressed = false,
            entryCount = 1,
        )

        val app15Count = countApp15Segments(covered)
        assertTrue("expected multiple APP15 segments, got $app15Count", app15Count > 1)
        val items = JpegCarrierCodec.extractItems(covered)
        assertEquals(1, items.size)
        assertArrayEquals(envelope, items[0].bytes)
    }

    @Test
    fun `plain jpeg has no hidden items`() {
        assertTrue(JpegCarrierCodec.extractItems(minimalJpeg()).isEmpty())
    }

    @Test
    fun `webp bundle round trip`() {
        val envelope = ByteArray(4096) { it.toByte() }
        val covered = WebpCarrierCodec.insertBundle(
            coverWebp = minimalWebp(),
            name = "图片文件夹.zip",
            mime = "application/zip",
            envelope = envelope,
            encrypted = false,
            compressed = true,
            entryCount = 2,
        )

        assertTrue(WebpCarrierCodec.isWebp(covered))
        assertEquals(covered.size.toLong() - 8, readU32Le(covered, 4))
        val items = WebpCarrierCodec.extractItems(covered)
        assertEquals(1, items.size)
        assertEquals("图片文件夹.zip", items[0].name)
        assertEquals("ifv2", items[0].format)
        assertEquals(2, items[0].entryCount)
        assertArrayEquals(envelope, items[0].bytes)
    }

    @Test
    fun `webp parser skips odd sized chunks and padding`() {
        val webp = riffWebp(
            listOf(
                "VP8 " to ByteArray(7) { 9 },
                "JUNK" to ByteArray(3) { 1 },
            ),
        )
        val covered = WebpCarrierCodec.insertBundle(
            coverWebp = webp,
            name = "a.txt",
            mime = "text/plain",
            envelope = "hello".toByteArray(StandardCharsets.UTF_8),
            encrypted = false,
            compressed = false,
            entryCount = 1,
        )

        val items = WebpCarrierCodec.extractItems(covered)
        assertEquals(1, items.size)
        assertEquals("a.txt", items[0].name)
        assertEquals("hello", String(items[0].bytes, StandardCharsets.UTF_8))
    }

    @Test
    fun `carrier dispatcher extracts jpeg and webp`() {
        val envelope = "payload".toByteArray(StandardCharsets.UTF_8)
        val jpeg = CarrierCodec.insertBundle(
            coverBytes = minimalJpeg(),
            format = CarrierFormat.JPEG,
            name = "j.bin",
            mime = null,
            envelope = envelope,
            encrypted = false,
            compressed = false,
            entryCount = 1,
        )
        val webp = CarrierCodec.insertBundle(
            coverBytes = minimalWebp(),
            format = CarrierFormat.WEBP,
            name = "w.bin",
            mime = null,
            envelope = envelope,
            encrypted = false,
            compressed = false,
            entryCount = 1,
        )

        assertEquals("j.bin", CarrierCodec.extractItems(jpeg).single().name)
        assertEquals("w.bin", CarrierCodec.extractItems(webp).single().name)
        assertTrue(CarrierCodec.extractItems(byteArrayOf(0, 1, 2, 3)).isEmpty())
        assertFalse(CarrierCodec.detect(byteArrayOf(0, 1, 2, 3)) != null)
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

    private fun countApp15Segments(jpeg: ByteArray): Int {
        var offset = 2
        var count = 0
        while (offset + 1 < jpeg.size) {
            if ((jpeg[offset].toInt() and 0xFF) != 0xFF) break
            while (offset + 1 < jpeg.size && (jpeg[offset + 1].toInt() and 0xFF) == 0xFF) offset++
            if (offset + 1 >= jpeg.size) break
            val marker = jpeg[offset + 1].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0xDA) break
            if (marker == 0x01 || marker in 0xD0..0xD7) {
                offset += 2
                continue
            }
            if (offset + 4 > jpeg.size) break
            val length = ((jpeg[offset + 2].toInt() and 0xFF) shl 8) or
                (jpeg[offset + 3].toInt() and 0xFF)
            if (marker == 0xEF) count++
            offset += 2 + length
        }
        return count
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

    private fun readU32Le(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)

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
