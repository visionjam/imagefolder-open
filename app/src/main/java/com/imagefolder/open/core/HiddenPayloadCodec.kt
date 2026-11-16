package com.imagefolder.open.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets

/**
 * Shared `tpWj` payload layout:
 * ```
 * {"name":"<file name>","mime":"<mime type>",...} 0x00 <raw bytes>
 * ```
 * PNG chunks, JPEG APP15 segments and the WebP carrier chunk all store the
 * same header so extraction and metadata stay consistent across containers.
 */
internal data class HiddenHeader(
    val name: String,
    val mime: String?,
    val format: String?,
    val encrypted: Boolean?,
    val compressed: Boolean?,
    val entryCount: Int?,
    /** 1-based part number for split carriers, null for ordinary items. */
    val partIndex: Int? = null,
    /** Total part count for split carriers, null for ordinary items. */
    val partCount: Int? = null,
)

internal object HiddenPayloadCodec {
    const val TERMINATOR: Byte = 0
    private val json = Json { ignoreUnknownKeys = true }

    fun parseHeader(payload: ByteArray): HiddenHeader? {
        val separator = payload.indexOfFirst { it == TERMINATOR }
        if (separator <= 0) return null
        val header = runCatching {
            json.parseToJsonElement(
                String(payload, 0, separator, StandardCharsets.UTF_8),
            ).jsonObject
        }.getOrNull() ?: return null
        val name = (header["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?: return null
        return HiddenHeader(
            name = name,
            mime = (header["mime"] as? JsonPrimitive)?.contentOrNull,
            format = (header["format"] as? JsonPrimitive)?.contentOrNull,
            encrypted = (header["enc"] as? JsonPrimitive)?.booleanOrNull,
            compressed = (header["comp"] as? JsonPrimitive)?.booleanOrNull,
            entryCount = (header["entries"] as? JsonPrimitive)?.intOrNull,
            partIndex = (header["part"] as? JsonPrimitive)?.intOrNull,
            partCount = (header["parts"] as? JsonPrimitive)?.intOrNull,
        )
    }

    fun encodeHeader(item: HiddenItem): ByteArray {
        val header = buildJsonObject {
            put("name", item.name)
            item.mime?.let { put("mime", it) }
            item.format?.let { put("format", it) }
            item.encrypted?.let { put("enc", it) }
            item.compressed?.let { put("comp", it) }
            item.entryCount?.let { put("entries", it) }
            item.partIndex?.let { put("part", it) }
            item.partCount?.let { put("parts", it) }
        }
        return header.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun encodePayload(item: HiddenItem): ByteArray =
        encodeHeader(item) + byteArrayOf(TERMINATOR) + item.bytes

    fun parse(payload: ByteArray): HiddenItem? {
        val header = parseHeader(payload) ?: return null
        val separator = payload.indexOfFirst { it == TERMINATOR }
        if (separator <= 0) return null
        return HiddenItem(
            name = header.name,
            mime = header.mime,
            bytes = payload.copyOfRange(separator + 1, payload.size),
            format = header.format,
            encrypted = header.encrypted,
            compressed = header.compressed,
            entryCount = header.entryCount,
            partIndex = header.partIndex,
            partCount = header.partCount,
        )
    }
}
