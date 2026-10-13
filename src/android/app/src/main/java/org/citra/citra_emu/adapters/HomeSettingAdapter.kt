// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.citra.citra_emu.databinding.ListItemSettingBinding
import org.citra.citra_emu.databinding.ListItemSettingsHeaderBinding
import org.citra.citra_emu.fragments.MessageDialogFragment
import org.citra.citra_emu.model.HomeSetting
import org.citra.citra_emu.model.HomeSettingsHeader
import org.citra.citra_emu.model.HomeSettingsItem

class HomeSettingAdapter(
    private val activity: AppCompatActivity,
    private val viewLifecycle: LifecycleOwner,
    private val options: List<HomeSettingsItem>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemViewType(position: Int): Int =
        if (options[position] is HomeSettingsHeader) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderViewHolder(ListItemSettingsHeaderBinding.inflate(inflater, parent, false))
        } else {
            HomeOptionViewHolder(ListItemSettingBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = options.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> holder.bind(options[position] as HomeSettingsHeader)
            is HomeOptionViewHolder -> holder.bind(options[position] as HomeSetting)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is HomeOptionViewHolder) holder.detailsJob?.cancel()
        super.onViewRecycled(holder)
    }

    private class HeaderViewHolder(val binding: ListItemSettingsHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(header: HomeSettingsHeader) {
            binding.textHeaderName.setText(header.titleId)
            binding.textHeaderDescription.isVisible = false
            ViewCompat.setAccessibilityHeading(binding.textHeaderName, true)
        }
    }

    private inner class HomeOptionViewHolder(val binding: ListItemSettingBinding) :
        RecyclerView.ViewHolder(binding.root) {
        var detailsJob: Job? = null

        fun bind(option: HomeSetting) {
            detailsJob?.cancel()
            binding.textSettingName.setText(option.titleId)
            binding.textSettingDescription.setText(option.descriptionId)
            binding.icon.setImageResource(option.iconId)
            binding.icon.isVisible = true
            binding.textSettingValue.text = option.details.value
            binding.textSettingValue.isVisible = option.details.value.isNotEmpty()
            binding.root.alpha = if (option.isEnabled()) 1f else 0.5f
            binding.root.setOnClickListener {
                if (option.isEnabled()) {
                    option.onClick()
                } else {
                    MessageDialogFragment.newInstance(
                        option.disabledTitleId,
                        option.disabledMessageId
                    ).show(activity.supportFragmentManager, MessageDialogFragment.TAG)
                }
            }
            detailsJob = viewLifecycle.lifecycleScope.launch {
                viewLifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    option.details.collect {
                        binding.textSettingValue.text = it
                        binding.textSettingValue.isVisible = it.isNotEmpty()
                    }
                }
            }
        }
    }
}
