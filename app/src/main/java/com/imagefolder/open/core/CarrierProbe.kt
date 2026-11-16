package com.imagefolder.open.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.charset.StandardCharsets

data class CarrierProbeResult(
    val format: CarrierFormat,
    val name: String?,
    val entries: Int,
    val encrypted: Boolean,
    val compressed: Boolean,
    /** 1-based part number when the carrier is one image of a split group. */
    val partIndex: Int? = null,
    /** Total part count when the carrier is one image of a split group. */
    val partCount: Int? = null,
)

/**
 * Reads only enough of a carrier to report whether it contains hidden data
 * and what its header says. Payload bytes are skipped, never materialized.
 */
object CarrierProbe {
    private const val MAX_HEADER_BYTES = 4096
    private const val PREFIX_BYTES = 12
    private const val SKIP_BUFFER_BYTES = 8192

    fun probe(bytes: ByteArray): CarrierProbeResult? =
        probe(ByteArrayInputStream(bytes))

    fun probe(input: InputStream): CarrierProbeResult? {
        val prefix = readFully(input, PREFIX_BYTES) ?: return null
        val format = CarrierCodec.detect(prefix) ?: return null
        val stream = PushbackInputStream(input, PREFIX_BYTES).apply { unread(prefix) }
        return when (format) {
            CarrierFormat.PNG -> probePng(stream)
            CarrierFormat.JPEG -> probeJpeg(stream)
            CarrierFormat.WEBP -> probeWebp(stream)
        }
    }

    private fun probePng(input: InputStream): CarrierProbeResult? {
        readFully(input, 8) ?: return null
        var count = 0
        var header: HiddenHeader? = null
        while (true) {
            val length = readU32Be(input) ?: break
            val type = readFully(input, 4) ?: break
            val name = String(type, StandardCharsets.US_ASCII)
            if (name == "IDAT" || name == "IEND") break
            if (name == "tpWj") {
                count++
                val headerBytes = readFully(
                    input,
                    minOf(length, MAX_HEADER_BYTES.toLong()).toInt(),
                )
                if (header == null && headerBytes != null) {
                    header = HiddenPayloadCodec.parseHeader(headerBytes)
                }
                skipFully(input, length - (headerBytes?.size ?: 0) + 4)
            } else {
                skipFully(input, length + 4)
            }
        }
        if (count == 0) return null
        return CarrierProbeResult(
            format = CarrierFormat.PNG,
            name = header?.name,
            entries = header?.entryCount ?: count,
            encrypted = header?.encrypted ?: false,
            compressed = header?.compressed ?: false,
            partIndex = header?.partIndex,
            partCount = header?.partCount,
        )
    }

    private fun probeJpeg(input: InputStream): CarrierProbeResult? {
        if (readU8(input) != 0xFF || readU8(input) != 0xD8) return null
        var seen = false
        var header: HiddenHeader? = null
        while (true) {
            var marker = readU8(input) ?: break
            while (marker == 0xFF) {
                val next = readU8(input) ?: return finishJpeg(seen, header)
                if (next != 0xFF) {
                    marker = next
                    break
                }
            }
            if (marker == 0xD9 || marker == 0xDA) break
            if (marker == 0x01 || marker in 0xD0..0xD7) continue
            val length = readU16Be(input) ?: break
            if (length < 2) break
            val payloadLength = length - 2
            if (marker == 0xEF) {
                val head = readFully(
                    input,
                    minOf(payloadLength.toLong(), MAX_HEADER_BYTES.toLong()).toInt(),
                )
                if (head != null && head.size >= 9) {
                    if (String(head, 0, 4, StandardCharsets.US_ASCII) == "IFBJ") {
                        seen = true
                        val index = ((head[5].toInt() and 0xFF) shl 8) or
                            (head[6].toInt() and 0xFF)
                        if (header == null && index == 0) {
                            header = HiddenPayloadCodec.parseHeader(
                                head.copyOfRange(9, head.size),
                            )
                        }
                    }
                }
                skipFully(input, payloadLength.toLong() - (head?.size ?: 0))
            } else {
                skipFully(input, payloadLength.toLong())
            }
        }
        return finishJpeg(seen, header)
    }

    private fun finishJpeg(seen: Boolean, header: HiddenHeader?): CarrierProbeResult? {
        if (!seen) return null
        return CarrierProbeResult(
            format = CarrierFormat.JPEG,
            name = header?.name,
            entries = header?.entryCount ?: 1,
            encrypted = header?.encrypted ?: false,
            compressed = header?.compressed ?: false,
            partIndex = header?.partIndex,
            partCount = header?.partCount,
        )
    }

    private fun probeWebp(input: InputStream): CarrierProbeResult? {
        val riff = readFully(input, 4) ?: return null
        if (String(riff, StandardCharsets.US_ASCII) != "RIFF") return null
        readU32Le(input) ?: return null
        val webp = readFully(input, 4) ?: return null
        if (String(webp, StandardCharsets.US_ASCII) != "WEBP") return null

        var seen = false
        var header: HiddenHeader? = null
        while (true) {
            val fourcc = readFully(input, 4) ?: break
            val length = readU32Le(input) ?: break
            val type = String(fourcc, StandardCharsets.US_ASCII)
            if (type == "IFBW") {
                seen = true
                val head = readFully(
                    input,
                    minOf(length, MAX_HEADER_BYTES.toLong()).toInt(),
                )
                if (header == null && head != null) {
                    header = HiddenPayloadCodec.parseHeader(head)
                }
                skipFully(input, length - (head?.size ?: 0))
            } else {
                skipFully(input, length)
            }
            if (length % 2L != 0L) skipFully(input, 1)
        }
        if (!seen) return null
        return CarrierProbeResult(
            format = CarrierFormat.WEBP,
            name = header?.name,
            entries = header?.entryCount ?: 1,
            encrypted = header?.encrypted ?: false,
            compressed = header?.compressed ?: false,
            partIndex = header?.partIndex,
            partCount = header?.partCount,
        )
    }

    private fun readFully(input: InputStream, size: Int): ByteArray? {
        if (size < 0) return null
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(out, offset, size - offset)
            if (read < 0) return null
            offset += read
        }
        return out
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(SKIP_BUFFER_BYTES)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read < 0) return
            remaining -= read
        }
    }

    private fun readU8(input: InputStream): Int? = input.read().takeIf { it >= 0 }

    private fun readU16Be(input: InputStream): Int? {
        val high = readU8(input) ?: return null
        val low = readU8(input) ?: return null
        return (high shl 8) or low
    }

    private fun readU32Be(input: InputStream): Long? {
        val bytes = readFully(input, 4) ?: return null
        return ((bytes[0].toLong() and 0xFF) shl 24) or
            ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or
            (bytes[3].toLong() and 0xFF)
    }

    private fun readU32Le(input: InputStream): Long? {
        val bytes = readFully(input, 4) ?: return null
        return (bytes[0].toLong() and 0xFF) or
            ((bytes[1].toLong() and 0xFF) shl 8) or
            ((bytes[2].toLong() and 0xFF) shl 16) or
            ((bytes[3].toLong() and 0xFF) shl 24)
    }
}
