package com.imagefolder.open.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * MIME helpers for names that `android.webkit.MimeTypeMap` does not know.
 *
 * Extracted entries often carry source-code, config or note names
 * (`README`, `.md`, `.log`, `.yaml`, `.kt`). Without a type the hand-off to
 * another app falls back to `application/octet-stream`, which either resolves
 * to a file manager or to nothing at all, so a text editor never appears in
 * "Open with". Everything here is plain Kotlin so the policy is unit tested on
 * the JVM.
 */
object MimeTypes {

    /** How many leading bytes are inspected when a file has no usable type. */
    const val TEXT_SNIFF_BYTES = 4096

    /**
     * Extensions Android's table misses, mapped to a type text editors accept.
     * Structured formats keep a specific text or application type where that
     * is what other apps expect.
     */
    private val FALLBACK_TYPES = mapOf(
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "log" to "text/plain",
        "ini" to "text/plain",
        "conf" to "text/plain",
        "cfg" to "text/plain",
        "properties" to "text/plain",
        "gradle" to "text/plain",
        "toml" to "text/plain",
        "yaml" to "text/plain",
        "yml" to "text/plain",
        "kt" to "text/plain",
        "kts" to "text/plain",
        "py" to "text/plain",
        "rs" to "text/plain",
        "go" to "text/plain",
        "rb" to "text/plain",
        "php" to "text/plain",
        "swift" to "text/plain",
        "sh" to "text/plain",
        "bash" to "text/plain",
        "zsh" to "text/plain",
        "bat" to "text/plain",
        "cmd" to "text/plain",
        "ps1" to "text/plain",
        "sql" to "text/plain",
        "scss" to "text/plain",
        "less" to "text/plain",
        "vue" to "text/plain",
        "svelte" to "text/plain",
        "dart" to "text/plain",
        "lua" to "text/plain",
        "srt" to "text/plain",
        "vtt" to "text/plain",
        "patch" to "text/plain",
        "diff" to "text/plain",
        "tex" to "text/plain",
        "rst" to "text/plain",
        "adoc" to "text/plain",
    )

    /** Text extensions Android already maps, used by the in-app preview. */
    private val KNOWN_TEXT_EXTENSIONS = setOf(
        "txt", "text", "json", "xml", "csv", "tsv", "html", "htm", "css",
        "js", "mjs", "ts", "tsx", "jsx", "java", "c", "h", "cpp", "hpp", "cs",
        "m", "mm", "asm",
    )

    /**
     * Lowercased extension of [fileName], or "" when it has none.
     *
     * Path separators are ignored so an archive path and a display name resolve
     * the same way; dotfiles such as `.gitignore` count as extensionless.
     */
    fun extensionOf(fileName: String): String {
        val leaf = fileName.substringAfterLast('/').substringAfterLast('\\')
        val dot = leaf.lastIndexOf('.')
        if (dot <= 0 || dot == leaf.lastIndex) return ""
        return leaf.substring(dot + 1).lowercase()
    }

    /** Type for an extension [extensionOf] knows but the platform table does not. */
    fun fallbackForFileName(fileName: String): String? =
        FALLBACK_TYPES[extensionOf(fileName)]

    /** True when [extension] (no dot) is a text-ish type this project recognises. */
    fun isTextExtension(extension: String): Boolean {
        val ext = extension.lowercase()
        return ext in FALLBACK_TYPES || ext in KNOWN_TEXT_EXTENSIONS
    }

    /**
     * Cheap content sniff for files whose name carries no usable type, such as
     * `README` or `LICENSE` inside an archive.
     *
     * A NUL byte rules text out immediately; otherwise the sample must decode
     * as strict UTF-8. The sample may end in the middle of a multi-byte
     * character, so up to three trailing bytes are dropped before giving up.
     * A false positive only changes which apps are offered, never the bytes.
     */
    fun looksLikeText(bytes: ByteArray, sampleSize: Int = TEXT_SNIFF_BYTES): Boolean {
        if (bytes.isEmpty()) return true
        val sample = minOf(bytes.size, sampleSize.coerceAtLeast(1))
        for (index in 0 until sample) {
            if (bytes[index] == 0.toByte()) return false
        }
        for (trim in 0..minOf(3, sample - 1)) {
            val end = sample - trim
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            if (runCatching { decoder.decode(ByteBuffer.wrap(bytes, 0, end)) }.isSuccess) {
                return true
            }
        }
        return false
    }
}
