package com.imagefolder.open.core

import java.io.ByteArrayOutputStream

/**
 * JPEG carrier support.
 *
 * Hidden bytes are split across APP15 (`0xFFEF`) marker segments inserted
 * before the first SOS scan. APP segments are skipped by every decoder, so
 * the picture itself stays untouched.
 *
 * Each segment payload is:
 * ```
 * "IFBJ" | version (1) | part index (2, BE) | part count (2, BE) | data
 * ```
 * Concatenating all part payloads yields the shared
 * `JSON header + 0x00 + raw bytes` layout used by [HiddenPayloadCodec].
 */
object JpegCarrierCodec {
    private val MAGIC = byteArrayOf(0x49, 0x46, 0x42, 0x4A) // "IFBJ"
    private const val VERSION = 1
    private const val PART_HEADER_SIZE = 9
    private const val MAX_SEGMENT_PAYLOAD = 0xFFFF - 2
    private const val MAX_PART_DATA = MAX_SEGMENT_PAYLOAD - PART_HEADER_SIZE
    private const val MAX_PARTS = 0xFFFF
    private const val MAX_STREAM_BYTES = 512L * 1024 * 1024

    fun isJpeg(data: ByteArray): Boolean =
        data.size >= 3 &&
            (data[0].toInt() and 0xFF) == 0xFF &&
            (data[1].toInt() and 0xFF) == 0xD8 &&
            (data[2].toInt() and 0xFF) == 0xFF

    fun insertBundle(
        coverJpeg: ByteArray,
        name: String,
        mime: String?,
        envelope: ByteArray,
        encrypted: Boolean,
        compressed: Boolean,
        entryCount: Int,
    ): ByteArray {
        require(isJpeg(coverJpeg)) { "封面不是有效的 JPEG" }
        val payload = HiddenPayloadCodec.encodePayload(
            HiddenItem(
                name = name,
                mime = mime,
                bytes = envelope,
                format = "ifv2",
                encrypted = encrypted,
                compressed = compressed,
                entryCount = entryCount,
            ),
        )
        val partCount = (payload.size + MAX_PART_DATA - 1) / MAX_PART_DATA
        require(partCount in 1..MAX_PARTS) { "隐藏数据过大，无法写入 JPEG" }

        val insertion = ByteArrayOutputStream(payload.size + partCount * (PART_HEADER_SIZE + 4))
        for (index in 0 until partCount) {
            val start = index * MAX_PART_DATA
            val end = minOf(start + MAX_PART_DATA, payload.size)
            insertion.write(buildSegment(index, partCount, payload.copyOfRange(start, end)))
        }

        val sos = findSosOffset(coverJpeg) ?: throw IllegalArgumentException("JPEG 缺少 SOS 数据段")
        val output = ByteArray(coverJpeg.size + insertion.size())
        coverJpeg.copyInto(output, 0, 0, sos)
        insertion.toByteArray().copyInto(output, sos)
        coverJpeg.copyInto(output, sos + insertion.size(), sos, coverJpeg.size)
        return output
    }

    fun extractItems(jpeg: ByteArray): List<HiddenItem> {
        if (!isJpeg(jpeg)) return emptyList()
        val parts = HashMap<Int, ByteArray>()
        var expectedCount = -1
        var offset = 2
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
            val markerOffset = offset
            if (markerOffset + 4 > jpeg.size) break
            val length = readU16(jpeg, markerOffset + 2)
            if (length < 2) break
            val dataStart = markerOffset + 4
            val dataEnd = markerOffset + 2 + length
            if (dataEnd > jpeg.size || dataEnd < dataStart) break
            if (marker == 0xEF) {
                val part = parseSegment(jpeg, dataStart, dataEnd)
                if (part != null) {
                    val (index, count, data) = part
                    if (expectedCount == -1) expectedCount = count
                    if (count == expectedCount && index in 0 until count) {
                        parts[index] = data
                    }
                }
            }
            offset = dataEnd
        }
        if (expectedCount <= 0 || parts.size != expectedCount) return emptyList()

        val stream = ByteArrayOutputStream()
        for (index in 0 until expectedCount) {
            stream.write(parts[index] ?: return emptyList())
            if (stream.size().toLong() > MAX_STREAM_BYTES) return emptyList()
        }
        return listOfNotNull(HiddenPayloadCodec.parse(stream.toByteArray()))
    }

    private fun buildSegment(index: Int, count: Int, data: ByteArray): ByteArray {
        val payloadSize = PART_HEADER_SIZE + data.size
        val length = payloadSize + 2
        val segment = ByteArray(2 + length)
        segment[0] = 0xFF.toByte()
        segment[1] = 0xEF.toByte()
        segment[2] = (length ushr 8).toByte()
        segment[3] = length.toByte()
        MAGIC.copyInto(segment, 4)
        segment[8] = VERSION.toByte()
        segment[9] = (index ushr 8).toByte()
        segment[10] = index.toByte()
        segment[11] = (count ushr 8).toByte()
        segment[12] = count.toByte()
        data.copyInto(segment, 13)
        return segment
    }

    private fun parseSegment(data: ByteArray, start: Int, end: Int): Triple<Int, Int, ByteArray>? {
        if (end - start < PART_HEADER_SIZE) return null
        for (i in MAGIC.indices) {
            if (data[start + i] != MAGIC[i]) return null
        }
        if ((data[start + 4].toInt() and 0xFF) != VERSION) return null
        val index = readU16(data, start + 5)
        val count = readU16(data, start + 7)
        if (count == 0 || index >= count) return null
        return Triple(index, count, data.copyOfRange(start + PART_HEADER_SIZE, end))
    }

    private fun findSosOffset(jpeg: ByteArray): Int? {
        var offset = 2
        while (offset + 1 < jpeg.size) {
            if ((jpeg[offset].toInt() and 0xFF) != 0xFF) return null
            val markerStart = offset
            while (offset + 1 < jpeg.size && (jpeg[offset + 1].toInt() and 0xFF) == 0xFF) offset++
            if (offset + 1 >= jpeg.size) return null
            val marker = jpeg[offset + 1].toInt() and 0xFF
            if (marker == 0xDA) return markerStart
            if (marker == 0xD9) return null
            if (marker == 0x01 || marker in 0xD0..0xD7) {
                offset += 2
                continue
            }
            if (offset + 4 > jpeg.size) return null
            val length = readU16(jpeg, offset + 2)
            if (length < 2) return null
            offset += 2 + length
        }
        return null
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
}
