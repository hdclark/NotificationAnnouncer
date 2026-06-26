package com.hdclark.notificationannouncer

data class AppNotificationItem(
    val packageName: String,
    val appLabel: String,
    val lastSeenAt: Long,
    val enabled: Boolean,
)
