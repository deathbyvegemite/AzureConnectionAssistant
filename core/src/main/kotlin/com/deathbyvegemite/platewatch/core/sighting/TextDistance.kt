package com.deathbyvegemite.platewatch.core.sighting

import kotlin.math.abs

internal object TextDistance {
    /**
     * Levenshtein distance, abandoning early once it exceeds [max]
     * (returns `max + 1` in that case).
     */
    fun levenshtein(a: String, b: String, max: Int): Int {
        if (a == b) return 0
        if (abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            val swap = prev; prev = cur; cur = swap
        }
        return prev[b.length]
    }
}
