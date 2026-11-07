package com.celzero.bravedns.ui.compose.utilities
data class BubbleAllowedAge(val expired: Boolean, val minutesAgo: Long)
fun bubbleAllowedAge(start: Long, now: Long) = BubbleAllowedAge(now >= start + 15 * 60 * 1000, (now - start) / 60_000)
data class BubbleBlockedAge(val unit: String, val value: Long)
fun bubbleBlockedAge(time: Long, now: Long): BubbleBlockedAge { val diff = now - time; return when {
    diff < 60_000 -> BubbleBlockedAge("now", 0)
    diff < 3_600_000 -> BubbleBlockedAge("minutes", diff / 60_000)
    diff < 86_400_000 -> BubbleBlockedAge("hours", diff / 3_600_000)
    else -> BubbleBlockedAge("date", time)
} }
