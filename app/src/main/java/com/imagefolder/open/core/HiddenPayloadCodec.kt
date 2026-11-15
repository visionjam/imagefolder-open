package com.imagefolder.open.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
internal object HiddenPayloadCodec {
    const val TERMINATOR: Byte = 0
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeHeader(item: HiddenItem): ByteArray {
        val header = buildJsonObject {
            put("name", item.name)
            item.mime?.let { put("mime", it) }
            item.format?.let { put("format", it) }
            item.encrypted?.let { put("enc", it) }
            item.compressed?.let { put("comp", it) }
            item.entryCount?.let { put("entries", it) }
        }
        return header.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun encodePayload(item: HiddenItem): ByteArray =
        encodeHeader(item) + byteArrayOf(TERMINATOR) + item.bytes

    fun parse(payload: ByteArray): HiddenItem? {
        val separator = payload.indexOfFirst { it == TERMINATOR }
        if (separator <= 0) return null
        val header = runCatching {
            json.parseToJsonElement(
                String(payload, 0, separator, StandardCharsets.UTF_8),
            ).jsonObject
        }.getOrNull() ?: return null
        val name = header["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
        val mime = header["mime"]?.jsonPrimitive?.contentOrNull
        val format = header["format"]?.jsonPrimitive?.contentOrNull
        val encrypted = header["enc"]?.jsonPrimitive?.booleanOrNull
        val compressed = header["comp"]?.jsonPrimitive?.booleanOrNull
        val entryCount = header["entries"]?.jsonPrimitive?.intOrNull
        return HiddenItem(
            name = name,
            mime = mime,
            bytes = payload.copyOfRange(separator + 1, payload.size),
            format = format,
            encrypted = encrypted,
            compressed = compressed,
            entryCount = entryCount,
        )
    }
}
