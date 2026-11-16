package com.imagefolder.open.core

/**
 * WebP carrier support.
 *
 * The payload is stored in a private RIFF chunk named `IFBW` appended after
 * the image chunks. Unknown RIFF chunks must be ignored by decoders, so the
 * picture renders normally in Android, browsers and desktop viewers.
 *
 * Chunk payload uses the shared `JSON header + 0x00 + raw bytes` layout from
 * [HiddenPayloadCodec].
 */
object WebpCarrierCodec {
    private val MAGIC = byteArrayOf(0x49, 0x46, 0x42, 0x57) // "IFBW"

    fun isWebp(data: ByteArray): Boolean =
        data.size >= 12 &&
            data[0] == 'R'.code.toByte() &&
            data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() &&
            data[3] == 'F'.code.toByte() &&
            data[8] == 'W'.code.toByte() &&
            data[9] == 'E'.code.toByte() &&
            data[10] == 'B'.code.toByte() &&
            data[11] == 'P'.code.toByte()

    fun insertBundle(
        coverWebp: ByteArray,
        name: String,
        mime: String?,
        envelope: ByteArray,
        encrypted: Boolean,
        compressed: Boolean,
        entryCount: Int,
    ): ByteArray = insertItem(
        coverWebp = coverWebp,
        item = HiddenItem(
            name = name,
            mime = mime,
            bytes = envelope,
            format = "ifv2",
            encrypted = encrypted,
            compressed = compressed,
            entryCount = entryCount,
        ),
    )

    /** Inserts one arbitrary hidden item, used by both bundles and split parts. */
    fun insertItem(coverWebp: ByteArray, item: HiddenItem): ByteArray {
        require(isWebp(coverWebp)) { "封面不是有效的 WebP" }
        val payload = HiddenPayloadCodec.encodePayload(item)
        val chunkSize = 8L + payload.size + (payload.size and 1)
        val totalSize = coverWebp.size.toLong() + chunkSize
        require(totalSize <= Int.MAX_VALUE) { "隐藏数据过大，无法写入 WebP" }

        val output = ByteArray(totalSize.toInt())
        coverWebp.copyInto(output, 0, 0, coverWebp.size)
        writeU32(output, 4, totalSize - 8)

        var offset = coverWebp.size
        MAGIC.copyInto(output, offset)
        writeU32(output, offset + 4, payload.size.toLong())
        payload.copyInto(output, offset + 8)
        // RIFF chunks are padded to an even size; the array is zero-filled.
        return output
    }

    fun extractItems(webp: ByteArray): List<HiddenItem> {
        if (!isWebp(webp)) return emptyList()
        val declaredEnd = readU32(webp, 4) + 8
        if (declaredEnd < 12) return emptyList()
        val end = minOf(webp.size.toLong(), declaredEnd).toInt()

        var offset = 12
        while (offset + 8 <= end) {
            val size = readU32(webp, offset + 4)
            if (size > Int.MAX_VALUE) break
            val dataStart = offset + 8
            val dataEnd = dataStart.toLong() + size
            if (dataEnd > end) break
            if (matchesMagic(webp, offset)) {
                val item = HiddenPayloadCodec.parse(webp.copyOfRange(dataStart, dataEnd.toInt()))
                if (item != null) return listOf(item)
            }
            offset = dataEnd.toInt() + (size.toInt() and 1)
        }
        return emptyList()
    }

    private fun matchesMagic(data: ByteArray, offset: Int): Boolean {
        if (offset + MAGIC.size > data.size) return false
        for (i in MAGIC.indices) {
            if (data[offset + i] != MAGIC[i]) return false
        }
        return true
    }

    private fun readU32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)

    private fun writeU32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
