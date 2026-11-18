package com.hdclark.notificationannouncer

/**
 * Speech-only configuration. The defaults deliberately produce the same queue text and
 * scheduling as before these preferences were introduced.
 */
object SpeechCustomization {
    const val MAX_REPETITIONS = 5
    val DELAY_OPTIONS_SECONDS = listOf(0, 1, 2, 3, 5, 10)

    fun boundedMultiplier(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0.5f, 2.0f) else 1.0f

    /**
     * Repeat inside one TTS utterance instead of generating additional queue entries or
     * introducing new onDone/onStart transitions (which affect media focus).
     * Android's TTS input limit is typically 4000 characters. Leave a safety margin.
     * An original long announcement remains unchanged even when it exceeds this limit.
     */
    fun repeatAnnouncement(
        text: String,
        requestedCount: Int,
        maxSpeechLength: Int = 3_800,
    ): String {
        val count = requestedCount.coerceIn(1, MAX_REPETITIONS)
        if (count == 1 || text.isEmpty()) return text
        val separator = if (text.trimEnd().lastOrNull() in listOf('.', '!', '?')) " " else ". "
        val repeated = StringBuilder(text)
        for (index in 1 until count) {
            if (repeated.length + separator.length + text.length > maxSpeechLength) break
            repeated.append(separator).append(text)
        }
        return repeated.toString()
    }

    /** Optional minimum from the first queued message, independent of newer arrivals. */
    fun additionalWait(
        firstQueuedUptime: Long,
        nowUptime: Long,
        minimumDelayMs: Long,
    ): Long =
        (firstQueuedUptime + minimumDelayMs.coerceAtLeast(0L) - nowUptime)
            .coerceAtLeast(0L)
}
