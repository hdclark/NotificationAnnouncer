package com.hdclark.notificationannouncer

data class AppNotificationRecord(
    val packageName: String,
    val lastSeenAt: Long,
)

object NotificationPolicy {
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
