package com.imagefolder.open.core

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * One file hidden inside a PNG chunk.
 *
 * [name] and [mime] come from the JSON header written by the original
 * "图片文件夹" app; [bytes] is the raw file content.
 */
data class HiddenFile(
    val name: String,
    val mime: String?,
    val bytes: ByteArray,
)

/**
 * A parsed `tpWj` chunk. Legacy chunks (written by the original app or by
 * plain packing) have no [format]; v0.2 bundle chunks use `format = "ifv2"`.
 */
data class HiddenItem(
    val name: String,
    val mime: String?,
    val bytes: ByteArray,
    val format: String? = null,
    val encrypted: Boolean? = null,
    val compressed: Boolean? = null,
    val entryCount: Int? = null,
)

/**
 * Reads and writes the private PNG chunk format used by the original
 * "图片文件夹" app (chunk name `tpWj`).
 *
 * Chunk payload layout:
 * ```
 * {"name":"<file name>","mime":"<mime type>"} 0x00 <raw file bytes>
 * ```
 * Each file gets its own chunk, inserted right before the first IDAT chunk.
 * Chunk length and CRC32 follow the PNG specification (big-endian).
 */
object PngChunkCodec {
    const val CHUNK_NAME = "tpWj"

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val CHUNK_OVERHEAD = 12

    fun isPng(data: ByteArray): Boolean =
        data.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { data[it] == PNG_SIGNATURE[it] }

    fun insertFiles(
        coverPng: ByteArray,
        files: List<HiddenFile>,
        chunkName: String = CHUNK_NAME,
    ): ByteArray {
        require(chunkName.length == 4 && chunkName.all { it.code in 0x20..0x7E }) {
            "chunk name must be four printable ASCII characters"
        }
        require(isPng(coverPng)) { "封面不是有效的 PNG" }
        val idatOffset = firstChunkOffset(coverPng, "IDAT")
            ?: throw IllegalArgumentException("PNG 缺少 IDAT 数据块")

        val out = ByteArrayOutputStream(coverPng.size)
        out.write(coverPng, 0, idatOffset)
        files.forEach { file ->
            val payload = HiddenPayloadCodec.encodePayload(
                HiddenItem(name = file.name, mime = file.mime, bytes = file.bytes),
            )
            out.write(buildChunk(chunkName, payload))
        }
        out.write(coverPng, idatOffset, coverPng.size - idatOffset)
        return out.toByteArray()
    }

    fun extractFiles(png: ByteArray, chunkName: String = CHUNK_NAME): List<HiddenFile> =
        extractItems(png, chunkName)
            .filter { it.format == null }
            .map { HiddenFile(it.name, it.mime, it.bytes) }

    fun insertBundle(
        coverPng: ByteArray,
        name: String,
        mime: String?,
        envelope: ByteArray,
        encrypted: Boolean,
        compressed: Boolean,
        entryCount: Int,
        chunkName: String = CHUNK_NAME,
    ): ByteArray {
        require(chunkName.length == 4 && chunkName.all { it.code in 0x20..0x7E }) {
            "chunk name must be four printable ASCII characters"
        }
        require(isPng(coverPng)) { "封面不是有效的 PNG" }
        val idatOffset = firstChunkOffset(coverPng, "IDAT")
            ?: throw IllegalArgumentException("PNG 缺少 IDAT 数据块")

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

        val out = ByteArrayOutputStream(coverPng.size + payload.size + CHUNK_OVERHEAD)
        out.write(coverPng, 0, idatOffset)
        out.write(buildChunk(chunkName, payload))
        out.write(coverPng, idatOffset, coverPng.size - idatOffset)
        return out.toByteArray()
    }

    fun extractItems(png: ByteArray, chunkName: String = CHUNK_NAME): List<HiddenItem> {
        require(isPng(png)) { "不是有效的 PNG" }
        val items = mutableListOf<HiddenItem>()
        var offset = PNG_SIGNATURE.size
        while (offset + CHUNK_OVERHEAD <= png.size) {
            val length = readInt(png, offset)
            val next = offset.toLong() + CHUNK_OVERHEAD + length
            if (length < 0 || next > png.size) break
            val type = String(png, offset + 4, 4, StandardCharsets.US_ASCII)
            if (type == chunkName) {
                val payload = png.copyOfRange(offset + 8, offset + 8 + length)
                HiddenPayloadCodec.parse(payload)?.let(items::add)
            }
            offset = next.toInt()
        }
        return items
    }

    private fun buildChunk(name: String, payload: ByteArray): ByteArray {
        val chunk = ByteArray(CHUNK_OVERHEAD + payload.size)
        writeInt(chunk, 0, payload.size)
        name.toByteArray(StandardCharsets.US_ASCII).copyInto(chunk, 4)
        payload.copyInto(chunk, 8)
        val crc = CRC32()
        crc.update(chunk, 4, 4 + payload.size)
        writeInt(chunk, 8 + payload.size, crc.value.toInt())
        return chunk
    }

    private fun firstChunkOffset(png: ByteArray, type: String): Int? {
        var offset = PNG_SIGNATURE.size
        while (offset + CHUNK_OVERHEAD <= png.size) {
            val length = readInt(png, offset)
            val next = offset.toLong() + CHUNK_OVERHEAD + length
            if (length < 0 || next > png.size) return null
            if (String(png, offset + 4, 4, StandardCharsets.US_ASCII) == type) return offset
            offset = next.toInt()
        }
        return null
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
