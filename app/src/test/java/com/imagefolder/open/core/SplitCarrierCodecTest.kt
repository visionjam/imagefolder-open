package com.imagefolder.open.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class SplitCarrierCodecTest {

    @Test
    fun `split and reassemble round trip for every part count`() {
        val envelope = ByteArray(10_000) { (it * 13).toByte() }
        for (parts in 1..9) {
            val payloads = SplitCarrierCodec.split(envelope, parts)
            assertEquals(parts, payloads.size)
            val parsed = payloads.map { requireNotNull(SplitCarrierCodec.parse(it)) }
            assertEquals((0 until parts).toList(), parsed.map { it.header.index })
            assertEquals(parts, parsed.map { it.header.count }.distinct().single())
            assertEquals(envelope.size, parsed.first().header.totalLength)
            assertEquals(1, parsed.map { it.header.groupId }.distinct().size)
            assertArrayEquals(envelope, SplitCarrierCodec.reassemble(parsed))
        }
    }

    @Test
    fun `parts reassemble in any order`() {
        val envelope = ByteArray(4_097) { (it * 7 + 3).toByte() }
        val parsed = SplitCarrierCodec.split(envelope, 5)
            .map { requireNotNull(SplitCarrierCodec.parse(it)) }
        assertArrayEquals(envelope, SplitCarrierCodec.reassemble(parsed.reversed()))
    }

    @Test
    fun `missing part is reported with zero based index`() {
        val envelope = ByteArray(5_000) { it.toByte() }
        val parsed = SplitCarrierCodec.split(envelope, 4)
            .map { requireNotNull(SplitCarrierCodec.parse(it)) }
        val incomplete = parsed.filterNot { it.header.index == 2 }

        val error = assertThrows(SplitIncompleteException::class.java) {
            SplitCarrierCodec.reassemble(incomplete)
        }
        assertEquals(listOf(2), error.missing)
        assertEquals(4, error.count)
    }

    @Test
    fun `parts from different groups are rejected`() {
        val envelope = ByteArray(1_000) { it.toByte() }
        val first = SplitCarrierCodec.split(envelope, 3)
            .map { requireNotNull(SplitCarrierCodec.parse(it)) }
        val second = SplitCarrierCodec.split(envelope, 3)
            .map { requireNotNull(SplitCarrierCodec.parse(it)) }

        val mixed = listOf(first[0], first[1], second[2])
        assertThrows(SplitIntegrityException::class.java) {
            SplitCarrierCodec.reassemble(mixed)
        }
    }

    @Test
    fun `duplicate part is rejected`() {
        val envelope = ByteArray(1_000) { it.toByte() }
        val parsed = SplitCarrierCodec.split(envelope, 2)
            .map { requireNotNull(SplitCarrierCodec.parse(it)) }
        assertThrows(SplitIntegrityException::class.java) {
            SplitCarrierCodec.reassemble(listOf(parsed[0], parsed[0], parsed[1]))
        }
    }

    @Test
    fun `corrupted slice is rejected while parsing`() {
        val payload = SplitCarrierCodec.split(ByteArray(2_000) { it.toByte() }, 2).first().copyOf()
        payload[payload.size - 1] = (payload.last().toInt() xor 0x01).toByte()

        assertThrows(SplitIntegrityException::class.java) {
            SplitCarrierCodec.parse(payload)
        }
    }

    @Test
    fun `tampered envelope hash is rejected on reassembly`() {
        val envelope = ByteArray(3_000) { it.toByte() }
        val payloads = SplitCarrierCodec.split(envelope, 3).map { it.copyOf() }
        // SHA-256 lives at offset 24; flip it in every part so the group still
        // agrees with itself but no longer matches the rebuilt envelope.
        payloads.forEach { it[24] = (it[24].toInt() xor 0x01).toByte() }
        val parsed = payloads.map { requireNotNull(SplitCarrierCodec.parse(it)) }

        assertThrows(SplitIntegrityException::class.java) {
            SplitCarrierCodec.reassemble(parsed)
        }
    }

    @Test
    fun `tiny envelope never yields empty parts`() {
        val payloads = SplitCarrierCodec.split(byteArrayOf(1, 2, 3), 9)
        assertEquals(3, payloads.size)
        val parsed = payloads.map { requireNotNull(SplitCarrierCodec.parse(it)) }
        assertTrue(parsed.all { it.slice.isNotEmpty() })
        assertArrayEquals(byteArrayOf(1, 2, 3), SplitCarrierCodec.reassemble(parsed))
    }

    @Test
    fun `non split payload is not parsed`() {
        assertNull(SplitCarrierCodec.parse("hello".toByteArray(StandardCharsets.UTF_8)))
        assertNull(SplitCarrierCodec.parse(ByteArray(SplitCarrierCodec.HEADER_SIZE)))
        assertTrue(!SplitCarrierCodec.isPart(ByteArray(SplitCarrierCodec.HEADER_SIZE)))
    }

    @Test
    fun `part file names are numbered before the extension`() {
        assertEquals("Tank_20261117.png", SplitCarrierCodec.partFileName("Tank_20261117.png", 0, 1))
        assertEquals("Tank_20261117-1of3.png", SplitCarrierCodec.partFileName("Tank_20261117.png", 0, 3))
        assertEquals("Tank_20261117-3of3.png", SplitCarrierCodec.partFileName("Tank_20261117.png", 2, 3))
        assertEquals("noext-2of9", SplitCarrierCodec.partFileName("noext", 1, 9))
        assertEquals("a.b-9of9.webp", SplitCarrierCodec.partFileName("a.b.webp", 8, 9))
    }

    @Test
    fun `split carriers round trip through every container`() {
        val entries = listOf(
            BundleEntry("a.txt", "hello".toByteArray(StandardCharsets.UTF_8)),
            BundleEntry("dir/b.bin", ByteArray(5_000) { (it * 3).toByte() }),
        )
        val envelope = BundleCodec.pack(entries, compress = true, password = null)
        val payloads = SplitCarrierCodec.split(envelope, 3)

        val covers = listOf(
            CarrierFormat.PNG to minimalPng(),
            CarrierFormat.JPEG to minimalJpeg(),
            CarrierFormat.WEBP to minimalWebp(),
        )
        for ((format, cover) in covers) {
            val images = payloads.mapIndexed { index, payload ->
                CarrierCodec.insertItem(
                    coverBytes = cover,
                    format = format,
                    item = HiddenItem(
                        name = "图片文件夹.zip",
                        mime = "application/zip",
                        bytes = payload,
                        format = SplitCarrierCodec.FORMAT,
                        encrypted = false,
                        compressed = true,
                        entryCount = entries.size,
                        partIndex = index + 1,
                        partCount = payloads.size,
                    ),
                )
            }

            val items = images.map { CarrierCodec.extractItems(it).single() }
            assertEquals(listOf(1, 2, 3), items.map { it.partIndex })
            assertEquals(listOf(3, 3, 3), items.map { it.partCount })
            assertTrue(items.all { it.format == SplitCarrierCodec.FORMAT })

            val rebuilt = SplitCarrierCodec.reassemble(
                items.map { requireNotNull(SplitCarrierCodec.parse(it.bytes)) },
            )
            val unpacked = BundleCodec.unpack(rebuilt, null)
            assertEquals(entries.size, unpacked.size)
            assertEquals("hello", String(unpacked[0].bytes, StandardCharsets.UTF_8))
            assertArrayEquals(entries[1].bytes, unpacked[1].bytes)
        }
    }

    @Test
    fun `encrypted split envelope needs the password`() {
        val envelope = BundleCodec.pack(
            entries = listOf(BundleEntry("secret.txt", "s3cret".toByteArray(StandardCharsets.UTF_8))),
            compress = false,
            password = "pw",
        )
        val rebuilt = SplitCarrierCodec.reassemble(
            SplitCarrierCodec.split(envelope, 2)
                .map { requireNotNull(SplitCarrierCodec.parse(it)) },
        )

        assertThrows(InvalidPasswordException::class.java) {
            BundleCodec.unpack(rebuilt, null)
        }
        assertThrows(InvalidPasswordException::class.java) {
            BundleCodec.unpack(rebuilt, "wrong")
        }
        assertEquals(
            "s3cret",
            String(BundleCodec.unpack(rebuilt, "pw").single().bytes, StandardCharsets.UTF_8),
        )
    }

    @Test
    fun `carrier probe reports split metadata`() {
        val payload = SplitCarrierCodec.split(ByteArray(2_000) { it.toByte() }, 4)[1]
        val png = PngChunkCodec.insertItem(
            coverPng = minimalPng(),
            item = HiddenItem(
                name = "图片文件夹.zip",
                mime = "application/zip",
                bytes = payload,
                format = SplitCarrierCodec.FORMAT,
                partIndex = 2,
                partCount = 4,
            ),
        )

        val probe = CarrierProbe.probe(png)
        assertNotNull(probe)
        assertEquals(2, probe!!.partIndex)
        assertEquals(4, probe.partCount)
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

    private fun minimalWebp(): ByteArray {
        val payload = ByteArray(10) { it.toByte() }
        val body = "VP8 ".toByteArray(StandardCharsets.US_ASCII) +
            u32Le(payload.size) + payload
        return "RIFF".toByteArray(StandardCharsets.US_ASCII) +
            u32Le(4 + body.size) +
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
            pngChunk("IEND", ByteArray(0))
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
