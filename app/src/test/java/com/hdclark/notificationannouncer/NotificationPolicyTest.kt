package com.hdclark.notificationannouncer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {

    @Test
    fun `filters notification when any fragment matches case-insensitively`() {
        assertTrue(NotificationPolicy.shouldFilterByFragments("Foo", listOf("oo", "123")))
    }

    @Test
    fun `does not filter notification when no fragment matches`() {
        assertFalse(NotificationPolicy.shouldFilterByFragments("bar", listOf("oo", "123")))
    }

    @Test
    fun `sorts records by most recent timestamp first`() {
        val sorted = NotificationPolicy.sortByMostRecent(
            listOf(
                AppNotificationRecord("older", 10L),
                AppNotificationRecord("newest", 20L),
                AppNotificationRecord("middle", 15L),
            )
        )

        assertEquals(listOf("newest", "middle", "older"), sorted.map { it.packageName })
    }
}
