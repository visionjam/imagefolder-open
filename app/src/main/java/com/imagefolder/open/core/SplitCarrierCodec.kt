package com.imagefolder.open.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32

/**
 * Header of one split carrier image ("分卷图种").
 *
 * [groupId] is 16 lowercase hex characters shared by every part of the same
 * bundle; [index] is 0-based internally while the JSON header shows 1-based
 * numbers to users. [envelopeSha256] and [sliceCrc32] let the extractor prove
 * that all parts belong together and that none of them was truncated.
 */
data class SplitPartHeader(
    val groupId: String,
    val index: Int,
    val count: Int,
    val totalLength: Int,
    val offset: Int,
    val sliceLength: Int,
    val envelopeSha256: ByteArray,
    val sliceCrc32: Long,
)

/** A parsed split payload: its header plus the slice it carries. */
data class SplitPart(
    val header: SplitPartHeader,
    val slice: ByteArray,
)

class SplitFormatException(message: String) : IllegalArgumentException(message)

/**
 * Thrown when the selected images only cover part of a split group. The
 * extractor uses [missing] to tell the user which images are still needed.
 */
class SplitIncompleteException(
    val groupId: String,
    val missing: List<Int>,
    val count: Int,
) : Exception("缺少分卷")

/** Thrown when parts do not belong together or fail their checksums. */
class SplitIntegrityException(message: String) : Exception(message)

/**
 * Splits one IFB2 bundle envelope into 1-9 carrier images and joins them
 * back together.
 *
 * Motivation: chat apps and image hosts reject images above a per-file size
 * limit. A complete carrier ("图种") can exceed that limit, so the envelope is
 * cut into equal slices and every slice is embedded into its own copy of the
 * cover image. Each part is still a normal, viewable image; the app recognises
 * the parts, checks that the whole set is present and rebuilds the envelope
 * before unpacking it.
 *
 * Part payload layout (all integers big-endian, fixed 60-byte header):
 *
 *     "IFSP"         4 bytes magic
 *     version        1 byte  (1)
 *     flags          1 byte  (0; reserved)
 *     index          1 byte  (0-based)
 *     count          1 byte  (1..9)
 *     totalLength    4 bytes (whole envelope length)
 *     offset         4 bytes (slice start inside the envelope)
 *     groupId        8 bytes (random, identical for every part)
 *     envelopeSha256 32 bytes (SHA-256 of the whole envelope)
 *     sliceCrc32     4 bytes (CRC32 of the slice that follows)
 *     slice          sliceLength bytes
 *
 * The payload is wrapped in the usual JSON header + 0x00 + bytes layout with
 * format = "ifsp", so the container codecs stay unchanged.
 */
object SplitCarrierCodec {
    /** Value of the JSON format field for split parts. */
    const val FORMAT = "ifsp"

    /** Product limit: a bundle is cut into at most nine images. */
    const val MAX_PARTS = 9

    /** Bytes of one part payload before the slice. */
    const val HEADER_SIZE = 60

    private val MAGIC = "IFSP".toByteArray(StandardCharsets.US_ASCII)
    private const val VERSION = 1
    private const val GROUP_ID_BYTES = 8
    private const val SHA256_BYTES = 32
    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * Cuts [envelope] into [requestedParts] payloads (clamped to 1..9).
     *
     * An envelope smaller than the requested count yields one byte per part,
     * never an empty part: the request is capped at envelope.size.
     */
    fun split(envelope: ByteArray, requestedParts: Int): List<ByteArray> {
        require(envelope.isNotEmpty()) { "没有可拆分的数据" }
        val parts = requestedParts.coerceIn(1, MAX_PARTS).coerceAtMost(envelope.size)
        val groupId = ByteArray(GROUP_ID_BYTES).also { SecureRandom().nextBytes(it) }
        val digest = sha256(envelope)
        val sliceSize = (envelope.size + parts - 1) / parts
        return (0 until parts).map { index ->
            val offset = index * sliceSize
            val length = minOf(sliceSize, envelope.size - offset)
            val slice = envelope.copyOfRange(offset, offset + length)
            buildPayload(groupId, index, parts, envelope.size, offset, slice, digest)
        }
    }

    /** True when [payload] starts with the split magic. */
    fun isPart(payload: ByteArray): Boolean {
        if (payload.size < HEADER_SIZE) return false
        for (i in MAGIC.indices) {
            if (payload[i] != MAGIC[i]) return false
        }
        return true
    }

    /**
     * Parses one split payload.
     *
     * Returns null when the payload is not a split part at all; throws
     * [SplitFormatException] for a broken header and [SplitIntegrityException]
     * when the slice does not match its CRC32.
     */
    fun parse(payload: ByteArray): SplitPart? {
        if (payload.size < HEADER_SIZE) return null
        if (!isPart(payload)) return null
        val version = payload[4].toInt() and 0xFF
        if (version != VERSION) throw SplitFormatException("不支持的分卷版本：" + version)
        val flags = payload[5].toInt() and 0xFF
        if (flags != 0) throw SplitFormatException("未知的分卷标志位：" + flags)
        val index = payload[6].toInt() and 0xFF
        val count = payload[7].toInt() and 0xFF
        if (count !in 1..MAX_PARTS) throw SplitFormatException("分卷数量异常：" + count)
        if (index !in 0 until count) {
            throw SplitFormatException("分卷序号异常：" + (index + 1) + "/" + count)
        }
        val totalLength = readInt(payload, 8)
        if (totalLength <= 0) throw SplitFormatException("分卷总长度异常")
        val offset = readInt(payload, 12)
        if (offset < 0 || offset >= totalLength) throw SplitFormatException("分卷偏移异常")
        val sliceSize = (totalLength + count - 1) / count
        if (offset != index * sliceSize) throw SplitFormatException("分卷偏移与序号不一致")
        val expectedLength = minOf(sliceSize, totalLength - offset)
        if (payload.size - HEADER_SIZE != expectedLength) {
            throw SplitFormatException("分卷长度异常")
        }
        val slice = payload.copyOfRange(HEADER_SIZE, payload.size)
        val expectedCrc = readUInt(payload, HEADER_SIZE - 4)
        if (crc32(slice) != expectedCrc) {
            throw SplitIntegrityException("第 " + (index + 1) + "/" + count + " 份数据损坏")
        }
        return SplitPart(
            header = SplitPartHeader(
                groupId = hex(payload, 16, GROUP_ID_BYTES),
                index = index,
                count = count,
                totalLength = totalLength,
                offset = offset,
                sliceLength = slice.size,
                envelopeSha256 = payload.copyOfRange(24, 24 + SHA256_BYTES),
                sliceCrc32 = expectedCrc,
            ),
            slice = slice,
        )
    }

    /**
     * Joins a complete group of parts back into the original envelope.
     *
     * Parts may arrive in any order (gallery and chat apps rarely preserve
     * names). Missing, duplicated or foreign parts are rejected with a
     * dedicated exception so the UI can explain what to do.
     */
    fun reassemble(parts: List<SplitPart>): ByteArray {
        if (parts.isEmpty()) throw SplitFormatException("没有分卷")
        val first = parts.first().header
        if (parts.any { it.header.groupId != first.groupId }) {
            throw SplitIntegrityException("这些图片不属于同一组分卷")
        }
        if (parts.any {
                it.header.count != first.count ||
                    it.header.totalLength != first.totalLength ||
                    !it.header.envelopeSha256.contentEquals(first.envelopeSha256)
            }
        ) {
            throw SplitIntegrityException("分卷信息不一致")
        }
        val byIndex = HashMap<Int, SplitPart>(parts.size)
        for (part in parts) {
            if (byIndex.put(part.header.index, part) != null) {
                throw SplitIntegrityException("第 " + (part.header.index + 1) + " 份重复")
            }
        }
        val missing = (0 until first.count).filterNot(byIndex::containsKey)
        if (missing.isNotEmpty()) {
            throw SplitIncompleteException(first.groupId, missing, first.count)
        }

        val envelope = ByteArray(first.totalLength)
        for (index in 0 until first.count) {
            val part = byIndex.getValue(index)
            part.slice.copyInto(envelope, part.header.offset)
        }
        if (!sha256(envelope).contentEquals(first.envelopeSha256)) {
            throw SplitIntegrityException("分卷校验失败，请重新收集全部图片")
        }
        return envelope
    }

    /**
     * Builds the on-disk name of one part, e.g. Tank_20261117-1of3.png.
     * A single-part result keeps the original name untouched.
     */
    fun partFileName(baseFileName: String, index: Int, count: Int): String {
        if (count <= 1) return baseFileName
        val dot = baseFileName.lastIndexOf('.')
        val stem = if (dot > 0) baseFileName.substring(0, dot) else baseFileName
        val extension = if (dot > 0) baseFileName.substring(dot) else ""
        return stem + "-" + (index + 1) + "of" + count + extension
    }

    private fun buildPayload(
        groupId: ByteArray,
        index: Int,
        count: Int,
        totalLength: Int,
        offset: Int,
        slice: ByteArray,
        digest: ByteArray,
    ): ByteArray {
        val payload = ByteArray(HEADER_SIZE + slice.size)
        MAGIC.copyInto(payload, 0)
        payload[4] = VERSION.toByte()
        payload[5] = 0
        payload[6] = index.toByte()
        payload[7] = count.toByte()
        writeInt(payload, 8, totalLength)
        writeInt(payload, 12, offset)
        groupId.copyInto(payload, 16)
        digest.copyInto(payload, 24)
        writeInt(payload, HEADER_SIZE - 4, crc32(slice).toInt())
        slice.copyInto(payload, HEADER_SIZE)
        return payload
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private fun hex(bytes: ByteArray, offset: Int, length: Int): String = buildString(length * 2) {
        for (i in offset until offset + length) {
            val value = bytes[i].toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun readUInt(data: ByteArray, offset: Int): Long =
        readInt(data, offset).toLong() and 0xFFFFFFFFL

    private fun writeInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }
}
