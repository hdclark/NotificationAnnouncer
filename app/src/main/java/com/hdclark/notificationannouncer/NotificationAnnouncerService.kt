package com.hdclark.notificationannouncer

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.UUID

class NotificationAnnouncerService : NotificationListenerService(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language = Locale.getDefault()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null) return
        if (!isNonSilent(sbn, rankingMap)) return

        NotificationSettingsStore.updateAppLastSeen(this, sbn.packageName, System.currentTimeMillis())
        sendBroadcast(android.content.Intent(ACTION_APP_LIST_UPDATED).setPackage(packageName))

        if (!NotificationSettingsStore.isPackageEnabled(this, sbn.packageName)) return

        val spokenText = extractNotificationText(sbn.notification)
        if (spokenText.isBlank()) return

        val excludeFragments = NotificationSettingsStore.getExcludeFragments(this)
        if (NotificationPolicy.shouldFilterByFragments(spokenText, excludeFragments)) return

        if (!ttsReady) return

        val appLabel = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrElse { sbn.packageName }

        val announcement = "$appLabel. $spokenText"
        tts?.speak(announcement, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }

    private fun isNonSilent(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
    ): Boolean {
        val ranking = Ranking()
        if (rankingMap?.getRanking(sbn.key, ranking) == true) {
            return ranking.importance > NotificationManager.IMPORTANCE_LOW
        }

        val notification = sbn.notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = notification.channelId
            if (!channelId.isNullOrBlank()) {
                val channelImportance =
                    getSystemService(NotificationManager::class.java)?.getNotificationChannel(channelId)?.importance
                if (channelImportance != null) {
                    return channelImportance > NotificationManager.IMPORTANCE_LOW
                }
            }
        }

        @Suppress("DEPRECATION")
        return notification.priority >= Notification.PRIORITY_DEFAULT
    }

    private fun extractNotificationText(notification: Notification): String {
        val extras = notification.extras ?: return ""
        val parts = listOf(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
        ).filter { it.isNotBlank() }

        return parts.joinToString(". ").trim()
    }

    companion object {
        const val ACTION_APP_LIST_UPDATED = "com.hdclark.notificationannouncer.APP_LIST_UPDATED"
    }
}
