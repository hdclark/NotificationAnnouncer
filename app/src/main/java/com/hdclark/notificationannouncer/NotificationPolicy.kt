package com.hdclark.notificationannouncer

data class AppNotificationRecord(
    val packageName: String,
    val lastSeenAt: Long,
)

object NotificationPolicy {
    fun deduplicateRepeatedBlocks(parts: List<String>): String {
        val seen = linkedSetOf<String>()
        return parts
            .flatMap { splitIntoBlocks(it) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { seen.add(normalizeBlock(it)) }
            .joinToString(". ")
    }

    private fun splitIntoBlocks(text: String): List<String> {
        val lineBlocks = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val sourceBlocks = if (lineBlocks.size > 1) lineBlocks else listOf(text.trim())
        return sourceBlocks.flatMap { block ->
            block.split(Regex("(?<=[.!?])\\s+"))
                .map { it.trim().trimEnd('.', '!', '?') }
                .filter { it.isNotEmpty() }
        }
    }

    private fun normalizeBlock(block: String): String {
        return block.lowercase().replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?')
    }

    fun shouldFilterByFragments(content: String, fragments: Collection<String>): Boolean {
        val lowercaseContent = content.lowercase()
        return fragments
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { lowercaseContent.contains(it.lowercase()) }
    }

    fun sortByMostRecent(records: Collection<AppNotificationRecord>): List<AppNotificationRecord> {
        return records.sortedByDescending { it.lastSeenAt }
    }
}
