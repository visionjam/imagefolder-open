package com.imagefolder.open.core

/** Image containers that can carry hidden files. */
enum class CarrierFormat(val mime: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    WEBP("image/webp", "webp"),
}

/**
 * Entry point for container detection, insertion and extraction.
 *
 * PNG keeps the original `tpWj` chunk format for compatibility with the
 * original app. JPEG and WebP use their own private marker/chunk but share
 * the same payload layout and `IFB2` envelope.
 */
object CarrierCodec {

    fun detect(bytes: ByteArray): CarrierFormat? = when {
        PngChunkCodec.isPng(bytes) -> CarrierFormat.PNG
        JpegCarrierCodec.isJpeg(bytes) -> CarrierFormat.JPEG
        WebpCarrierCodec.isWebp(bytes) -> CarrierFormat.WEBP
        else -> null
    }

    fun insertBundle(
        coverBytes: ByteArray,
        format: CarrierFormat,
        name: String,
        mime: String?,
        envelope: ByteArray,
        encrypted: Boolean,
        compressed: Boolean,
        entryCount: Int,
    ): ByteArray = when (format) {
        CarrierFormat.PNG -> PngChunkCodec.insertBundle(
            coverPng = coverBytes,
            name = name,
            mime = mime,
            envelope = envelope,
            encrypted = encrypted,
            compressed = compressed,
            entryCount = entryCount,
        )
        CarrierFormat.JPEG -> JpegCarrierCodec.insertBundle(
            coverJpeg = coverBytes,
            name = name,
            mime = mime,
            envelope = envelope,
            encrypted = encrypted,
            compressed = compressed,
            entryCount = entryCount,
        )
        CarrierFormat.WEBP -> WebpCarrierCodec.insertBundle(
            coverWebp = coverBytes,
            name = name,
            mime = mime,
            envelope = envelope,
            encrypted = encrypted,
            compressed = compressed,
            entryCount = entryCount,
        )
    }

    /** Inserts one pre-built hidden item (bundle envelope or split part). */
    fun insertItem(
        coverBytes: ByteArray,
        format: CarrierFormat,
        item: HiddenItem,
    ): ByteArray = when (format) {
        CarrierFormat.PNG -> PngChunkCodec.insertItem(coverBytes, item)
        CarrierFormat.JPEG -> JpegCarrierCodec.insertItem(coverBytes, item)
        CarrierFormat.WEBP -> WebpCarrierCodec.insertItem(coverBytes, item)
    }

    fun extractItems(bytes: ByteArray): List<HiddenItem> = when (detect(bytes)) {
        CarrierFormat.PNG -> PngChunkCodec.extractItems(bytes)
        CarrierFormat.JPEG -> JpegCarrierCodec.extractItems(bytes)
        CarrierFormat.WEBP -> WebpCarrierCodec.extractItems(bytes)
        null -> emptyList()
    }
}
