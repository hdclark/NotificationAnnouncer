package com.hdclark.notificationannouncer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {

    @Test
    fun `deduplicates repeated notification blocks without removing repeated words`() {
        val deduplicated = NotificationPolicy.deduplicateRepeatedBlocks(
            listOf(
                "Sale sale sale",
                "Order shipped. Order shipped. Arrives today",
                "Arrives today",
            )
        )

        assertEquals("Sale sale sale. Order shipped. Arrives today", deduplicated)
    }

    @Test
    fun `deduplicates repeated line blocks`() {
        val deduplicated = NotificationPolicy.deduplicateRepeatedBlocks(
            listOf("Message from Pat\nMessage from Pat\nCall back soon")
        )

        assertEquals("Message from Pat. Call back soon", deduplicated)
    }

    @Test
    fun `preserves punctuation while deduplicating repeated blocks`() {
        val deduplicated = NotificationPolicy.deduplicateRepeatedBlocks(
            listOf("Are you coming? Are you coming? Yes!")
        )

        assertEquals("Are you coming? Yes!", deduplicated)
    }

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
