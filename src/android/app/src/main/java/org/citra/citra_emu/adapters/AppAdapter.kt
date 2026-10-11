// Copyright 2026 Azahar Emulator Project
// Licensed under GPLv3 or any later version
// Refer to the LICENSE.txt file included.

package org.citra.citra_emu.adapters

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.citra.citra_emu.databinding.CardAppBinding

class AppAdapter(private val apps: List<App>, private val onLaunch: (App) -> Unit) :
    RecyclerView.Adapter<AppAdapter.AppViewHolder>() {
    data class App(val component: ComponentName, val label: String, val icon: Drawable)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder =
        AppViewHolder(CardAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = apps.size

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    inner class AppViewHolder(private val binding: CardAppBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onLaunch(apps[position])
            }
            binding.root.setOnKeyListener { view, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) view.performClick()
                    true
                } else {
                    false
                }
            }
        }

        fun bind(app: App) {
            binding.root.contentDescription = app.label
            binding.imageAppIcon.setImageDrawable(app.icon)
            binding.textAppName.text = app.label
        }
    }
}
