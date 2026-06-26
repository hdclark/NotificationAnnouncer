package com.hdclark.notificationannouncer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.hdclark.notificationannouncer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var appSettingsAdapter: AppSettingsAdapter
    private lateinit var filterAdapter: ArrayAdapter<String>

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NotificationAnnouncerService.ACTION_APP_LIST_UPDATED) {
                refreshData()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appSettingsAdapter = AppSettingsAdapter { packageName, enabled ->
            NotificationSettingsStore.setPackageEnabled(this, packageName, enabled)
            refreshData()
        }

        binding.appsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.appsRecyclerView.adapter = appSettingsAdapter

        filterAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        binding.excludeFiltersList.adapter = filterAdapter

        binding.addFilterButton.setOnClickListener {
            val filter = binding.filterInput.text?.toString().orEmpty().trim()
            if (filter.isEmpty()) {
                Toast.makeText(this, R.string.empty_filter_error, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            NotificationSettingsStore.addExcludeFragment(this, filter)
            binding.filterInput.text?.clear()
            refreshData()
        }

        binding.excludeFiltersList.setOnItemLongClickListener { _, _, position, _ ->
            val value = filterAdapter.getItem(position) ?: return@setOnItemLongClickListener true
            NotificationSettingsStore.removeExcludeFragment(this, value)
            refreshData()
            true
        }

        binding.notificationAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        refreshData()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(NotificationAnnouncerService.ACTION_APP_LIST_UPDATED)
        ContextCompat.registerReceiver(
            this,
            refreshReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        unregisterReceiver(refreshReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    private fun refreshData() {
        val packageManager = packageManager
        val appStates = NotificationSettingsStore.getAppLastSeen(this)

        val items = NotificationPolicy.sortByMostRecent(
            appStates.map { (packageName, timestamp) ->
                AppNotificationRecord(packageName, timestamp)
            }
        ).map { record ->
            val label = runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(record.packageName, 0)).toString()
            }.getOrElse { record.packageName }
            AppNotificationItem(
                packageName = record.packageName,
                appLabel = label,
                lastSeenAt = record.lastSeenAt,
                enabled = NotificationSettingsStore.isPackageEnabled(this, record.packageName),
            )
        }

        appSettingsAdapter.submitList(items)

        val fragments = NotificationSettingsStore.getExcludeFragments(this)
        filterAdapter.clear()
        filterAdapter.addAll(fragments)
        filterAdapter.notifyDataSetChanged()

        val accessGranted = isNotificationAccessGranted()
        binding.notificationAccessStatus.text =
            getString(if (accessGranted) R.string.notification_access_enabled else R.string.notification_access_disabled)
    }

    private fun isNotificationAccessGranted(): Boolean {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabledListeners?.contains(packageName) == true
    }
}
