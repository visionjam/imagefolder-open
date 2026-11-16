package com.imagefolder.open.core

/**
 * Builds the on-disk name of a generated carrier image.
 *
 * Kept as plain Kotlin (no Android types) so the naming rules can be unit
 * tested on the JVM.
 */
object OutputName {

    /** Prefix the original app used for its own output. */
    const val DEFAULT_PREFIX = "Tank_"

    /** Longest stem kept, so the name still fits every file system we write to. */
    const val MAX_STEM = 64

    private val ILLEGAL = charArrayOf(
        '/', '\\', ':', '*', '?', '"', '<', '>', '|',
        '\u0000', '\n', '\r', '\t',
    )

    private val STRIPPABLE_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "heif", "avif", "tif", "tiff",
    )

    /** Replaces characters a MediaStore / SAF file name cannot contain. */
    fun sanitize(raw: String): String {
        val replaced = buildString(raw.length) {
            for (ch in raw) {
                append(if (ch in ILLEGAL) '_' else ch)
            }
        }
        return replaced.trim().trim('.').trim()
    }

    /**
     * Turns whatever the user typed into a safe file name with [extension].
     *
     * - blank input falls back to [fallbackStem] (the timestamped default)
     * - a trailing image extension is replaced by the carrier's real extension
     * - the stem is capped at [MAX_STEM] characters
     */
    fun build(rawName: String, extension: String, fallbackStem: String): String {
        val cleaned = sanitize(rawName)
        // Only strip a *known* image extension: "v1.2 photo" must keep its dot.
        val withoutExtension = if (hasImageExtension(cleaned)) {
            cleaned.substringBeforeLast('.')
        } else {
            cleaned
        }
        val stem = withoutExtension
            .take(MAX_STEM)
            .trim()
            .trim('.')
            .ifBlank { fallbackStem }
        return stem + "." + extension.lowercase()
    }

    /** True when [name] already ends in an image extension we know about. */
    fun hasImageExtension(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in STRIPPABLE_EXTENSIONS
}
