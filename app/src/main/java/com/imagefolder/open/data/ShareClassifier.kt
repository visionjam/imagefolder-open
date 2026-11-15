package com.imagefolder.open.data

data class SharedFile(
    val uri: String,
    val name: String,
    val mime: String?,
    val size: Long,
    val hasHiddenPayload: Boolean,
)

data class SharePlan(
    val packUris: List<String>,
    val extractUris: List<String>,
) {
    val needsChoice: Boolean get() = extractUris.isNotEmpty()
}

object ShareClassifier {
    const val MAX_INSPECT_BYTES = 64 * 1024 * 1024

    fun shouldInspect(size: Long): Boolean = size < 0 || size <= MAX_INSPECT_BYTES

    fun classify(files: List<SharedFile>): SharePlan {
        val unique = files.distinctBy { it.uri }
        return SharePlan(
            packUris = unique.map { it.uri },
            extractUris = unique.filter { it.hasHiddenPayload }.map { it.uri },
        )
    }

    /** Merges a second share into a pending one, keeping each URI only once. */
    fun merge(existing: SharePlan?, incoming: SharePlan): SharePlan = SharePlan(
        packUris = ((existing?.packUris ?: emptyList()) + incoming.packUris).distinct(),
        extractUris = ((existing?.extractUris ?: emptyList()) + incoming.extractUris).distinct(),
    )
}
