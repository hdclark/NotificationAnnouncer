package com.hdclark.notificationannouncer

import android.content.Context
import org.json.JSONObject

object NotificationSettingsStore {
    private const val PREFS_NAME = "notification_announcer"
    private const val KEY_DISABLED_PACKAGES = "disabled_packages"
    private const val KEY_EXCLUDE_FRAGMENTS = "exclude_fragments"
    private const val KEY_APP_LAST_SEEN = "app_last_seen"
    private const val KEY_ANNOUNCEMENTS_ENABLED = "announcements_enabled"
    private const val KEY_MUSIC_RESUME_MODE = "music_resume_mode"
    private const val KEY_SPEECH_STATUS = "speech_status"
    private const val KEY_SPEECH_STATUS_TIME = "speech_status_time"

    fun getSpeechStatus(context: Context): String {
        val preferences = prefs(context)
        val status = preferences.getString(KEY_SPEECH_STATUS, null) ?: return "Speech status not yet available"
        val whenRecorded = preferences.getLong(KEY_SPEECH_STATUS_TIME, 0L)
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(
            java.util.Date(whenRecorded)
        )
        return "$time — $status"
    }

    fun setSpeechStatus(context: Context, status: String) {
        prefs(context).edit()
            .putString(KEY_SPEECH_STATUS, status)
            .putLong(KEY_SPEECH_STATUS_TIME, System.currentTimeMillis())
            .apply()
    }

    fun isPackageEnabled(context: Context, packageName: String): Boolean {
        return !loadDisabledPackages(context).contains(packageName)
    }

    fun setPackageEnabled(context: Context, packageName: String, isEnabled: Boolean) {
        val disabled = loadDisabledPackages(context).toMutableSet()
        if (isEnabled) {
            disabled.remove(packageName)
        } else {
            disabled.add(packageName)
        }
        prefs(context).edit().putStringSet(KEY_DISABLED_PACKAGES, disabled).apply()
    }

    fun areAnnouncementsEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ANNOUNCEMENTS_ENABLED, true)
    }

    fun setAnnouncementsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANNOUNCEMENTS_ENABLED, enabled).apply()
    }

    /** New preference, defaulting to the existing music-detection behaviour for upgrades. */
    fun getMusicResumeMode(context: Context): MusicResumeMode =
        MusicResumeMode.fromStoredValue(prefs(context).getString(KEY_MUSIC_RESUME_MODE, null))

    fun setMusicResumeMode(context: Context, mode: MusicResumeMode) {
        prefs(context).edit().putString(KEY_MUSIC_RESUME_MODE, mode.storedValue).apply()
    }

    fun getExcludeFragments(context: Context): List<String> {
        return prefs(context).getStringSet(KEY_EXCLUDE_FRAGMENTS, emptySet())
            ?.filter { it.isNotBlank() }
            ?.sorted()
            .orEmpty()
    }

    fun addExcludeFragment(context: Context, fragment: String) {
        val normalized = fragment.trim()
        if (normalized.isEmpty()) return

        val fragments = getExcludeFragments(context).toMutableSet()
        fragments.add(normalized)
        prefs(context).edit().putStringSet(KEY_EXCLUDE_FRAGMENTS, fragments).apply()
    }

    fun removeExcludeFragment(context: Context, fragment: String) {
        val fragments = getExcludeFragments(context).toMutableSet()
        fragments.remove(fragment)
        prefs(context).edit().putStringSet(KEY_EXCLUDE_FRAGMENTS, fragments).apply()
    }

    fun getAppLastSeen(context: Context): Map<String, Long> {
        val serialized = prefs(context).getString(KEY_APP_LAST_SEEN, "{}") ?: "{}"
        val json = JSONObject(serialized)
        val result = linkedMapOf<String, Long>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val packageName = keys.next()
            result[packageName] = json.optLong(packageName, 0L)
        }
        return result
    }

    fun updateAppLastSeen(context: Context, packageName: String, timestamp: Long) {
        val existing = JSONObject(prefs(context).getString(KEY_APP_LAST_SEEN, "{}") ?: "{}")
        existing.put(packageName, timestamp)
        prefs(context).edit().putString(KEY_APP_LAST_SEEN, existing.toString()).apply()
    }

    private fun loadDisabledPackages(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_DISABLED_PACKAGES, emptySet()).orEmpty()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
