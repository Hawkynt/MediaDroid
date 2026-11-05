package com.hawkynt.mediadroid.player

/**
 * A small map that keeps at most [capacity] entries and, when full, lets go of the one used longest
 * ago. Reading an entry counts as using it. Not thread-safe on its own: callers that share one guard it.
 */
class RecentCache<K, V>(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val entries = object : LinkedHashMap<K, V>(capacity + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > capacity
    }

    /** The value kept for [key], or null; marks it as just used. */
    operator fun get(key: K): V? = entries[key]

    /** Keeps [value] for [key], replacing what was there, and lets go of the oldest entry past capacity. */
    fun put(key: K, value: V) {
        entries[key] = value
    }

    /** The keys kept right now. */
    fun keys(): Set<K> = entries.keys.toSet()

    /** Lets go of everything. */
    fun clear() = entries.clear()
}
