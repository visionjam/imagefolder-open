package com.imagefolder.open.core

/**
 * Access-ordered LRU cache with a hard entry limit.
 *
 * Kept as plain Kotlin (instead of `android.util.LruCache`) so the eviction
 * policy can be exercised by JVM unit tests; the result list uses it to keep
 * decoded thumbnails from being re-decoded while scrolling.
 */
class SimpleLruCache<K : Any, V : Any>(private val maxSize: Int) {

    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    private val entries = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > maxSize
    }

    fun get(key: K): V? = synchronized(entries) { entries[key] }

    fun put(key: K, value: V) {
        synchronized(entries) { entries[key] = value }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    val size: Int
        get() = synchronized(entries) { entries.size }
}
