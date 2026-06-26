package com.hdclark.notificationannouncer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hdclark.notificationannouncer.databinding.ItemAppSettingBinding
import java.text.DateFormat
import java.util.Date

class AppSettingsAdapter(
    private val onEnabledChanged: (packageName: String, enabled: Boolean) -> Unit,
) : RecyclerView.Adapter<AppSettingsAdapter.ViewHolder>() {

    private val dateFormatter = DateFormat.getDateTimeInstance()
    private var items: List<AppNotificationItem> = emptyList()

    fun submitList(newItems: List<AppNotificationItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppSettingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemAppSettingBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AppNotificationItem) {
            binding.appNameText.text = item.appLabel
            binding.packageNameText.text = item.packageName
            binding.lastSeenText.text =
                binding.root.context.getString(
                    R.string.last_seen_template,
                    dateFormatter.format(Date(item.lastSeenAt))
                )

            binding.enabledCheckbox.setOnCheckedChangeListener(null)
            binding.enabledCheckbox.isChecked = item.enabled
            binding.enabledCheckbox.setOnCheckedChangeListener { _, isChecked ->
                onEnabledChanged(item.packageName, isChecked)
            }
        }
    }
}
