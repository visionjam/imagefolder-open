package com.imagefolder.open.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.Deflater

class PngChunkCodecTest {

    @Test
    fun `round trip keeps file name mime and bytes`() {
        val png = minimalPng()
        val text = HiddenFile("说明.txt", "text/plain", "这里有中文和 ASCII".toByteArray(StandardCharsets.UTF_8))
        val binary = HiddenFile("blob.bin", null, ByteArray(256) { it.toByte() })

        val covered = PngChunkCodec.insertFiles(png, listOf(text, binary))
        val extracted = PngChunkCodec.extractFiles(covered)

        assertEquals(2, extracted.size)
        assertEquals("说明.txt", extracted[0].name)
        assertEquals("text/plain", extracted[0].mime)
        assertArrayEquals(text.bytes, extracted[0].bytes)
        assertEquals("blob.bin", extracted[1].name)
        assertEquals(null, extracted[1].mime)
        assertArrayEquals(binary.bytes, extracted[1].bytes)
    }

    @Test
    fun `output stays a structurally valid png`() {
        val covered = PngChunkCodec.insertFiles(
            minimalPng(),
            listOf(HiddenFile("a.txt", "text/plain", "hello".toByteArray())),
        )
        assertTrue(PngChunkCodec.isPng(covered))

        var offset = 8
        var sawIdat = false
        var sawIend = false
        while (offset + 12 <= covered.size) {
            val length = readInt(covered, offset)
            val type = String(covered, offset + 4, 4, StandardCharsets.US_ASCII)
            if (type == "IDAT") sawIdat = true
            if (type == "IEND") sawIend = true
            val expectedCrc = CRC32().apply { update(covered, offset + 4, 4 + length) }.value.toInt()
            assertEquals("CRC of $type", readInt(covered, offset + 8 + length), expectedCrc)
            offset += 12 + length
        }

        assertTrue(sawIdat)
        assertTrue(sawIend)
        assertEquals(covered.size, offset)
    }

    @Test
    fun `reads chunks written in the original app layout`() {
        val legacyPayload = buildString {
            append("{\"name\":\"旧文件.txt\",\"mime\":\"text/plain\"}")
        }.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0) + "legacy-content".toByteArray()

        val png = minimalPng()
        val idatOffset = findChunk(png, "IDAT")
        val covered = png.copyOfRange(0, idatOffset) +
            chunk("tpWj", legacyPayload) +
            png.copyOfRange(idatOffset, png.size)

        val extracted = PngChunkCodec.extractFiles(covered)
        assertEquals(1, extracted.size)
        assertEquals("旧文件.txt", extracted[0].name)
        assertEquals("text/plain", extracted[0].mime)
        assertEquals("legacy-content", String(extracted[0].bytes))
    }

    @Test
    fun `ignores malformed chunk payloads`() {
        val png = minimalPng()
        val idatOffset = findChunk(png, "IDAT")
        val malformed = "not-a-json-header".toByteArray()
        val covered = png.copyOfRange(0, idatOffset) +
            chunk("tpWj", malformed) +
            png.copyOfRange(idatOffset, png.size)

        assertTrue(PngChunkCodec.extractFiles(covered).isEmpty())
    }

    @Test
    fun `reads fixture produced by the reference implementation`() {
        val png = javaClass.getResourceAsStream("/legacy-tpWj.png")!!.readBytes()
        val files = PngChunkCodec.extractFiles(png)

        assertEquals(2, files.size)
        assertEquals("参考实现.txt", files[0].name)
        assertEquals("text/plain", files[0].mime)
        assertEquals("来自 Python 参考实现的载荷", String(files[0].bytes, StandardCharsets.UTF_8))
        assertEquals("data.bin", files[1].name)
        assertArrayEquals(ByteArray(64) { it.toByte() }, files[1].bytes)
    }

    @Test
    fun `rejects data that is not png`() {
        assertFalse(PngChunkCodec.isPng("GIF89a".toByteArray()))
    }

    private fun minimalPng(): ByteArray {
        val ihdr = ByteArray(13).also { data ->
            writeInt(data, 0, 1)
            writeInt(data, 4, 1)
            data[8] = 8
            data[9] = 2
        }
        val rawScanline = byteArrayOf(0, 255.toByte(), 0, 0)
        val deflater = Deflater()
        deflater.setInput(rawScanline)
        deflater.finish()
        val compressed = ByteArray(64)
        val length = deflater.deflate(compressed)
        deflater.end()

        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return signature +
            chunk("IHDR", ihdr) +
            chunk("IDAT", compressed.copyOf(length)) +
            chunk("IEND", byteArrayOf())
    }

    private fun chunk(type: String, payload: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
        val body = typeBytes + payload
        val crc = CRC32().apply { update(body) }.value.toInt()
        val header = ByteArray(4).also { writeInt(it, 0, payload.size) }
        val crcBytes = ByteArray(4).also { writeInt(it, 0, crc) }
        return header + body + crcBytes
    }

    private fun findChunk(png: ByteArray, type: String): Int {
        var offset = 8
        while (offset + 12 <= png.size) {
            val length = readInt(png, offset)
            if (String(png, offset + 4, 4, StandardCharsets.US_ASCII) == type) return offset
            offset += 12 + length
        }
        error("chunk $type not found")
    }

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun writeInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }
}
