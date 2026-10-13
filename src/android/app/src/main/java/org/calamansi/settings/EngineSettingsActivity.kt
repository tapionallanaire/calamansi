package org.calamansi.settings

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textview.MaterialTextView
import org.citra.citra_emu.R
import org.citra.citra_emu.databinding.ActivitySettingsBinding
import org.citra.citra_emu.databinding.ListItemSettingBinding
import org.citra.citra_emu.databinding.ListItemSettingSwitchBinding
import org.citra.citra_emu.utils.ThemeUtil

/** Presentation only. Each engine retains its own storage and native ownership. */
abstract class EngineSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    protected lateinit var content: LinearLayout
        private set
    private lateinit var scroll: NestedScrollView
    private val categories = mutableListOf<Category>()
    private var currentCategory = 0
    private var console = ""
    private var description = ""

    private class Category(
        val titleId: Int,
        val descriptionId: Int,
        val iconId: Int,
        val build: () -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.setTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(binding.toolbarSettings)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        currentCategory = savedInstanceState?.getInt("settings_category") ?: 0
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Rows rebind from their backend; duplicate widget IDs must not restore or persist state.
            isSaveFromParentEnabled = false
        }
        scroll = NestedScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content, ViewGroup.LayoutParams(-1, -2))
        }
        binding.frameContent.addView(scroll, ViewGroup.LayoutParams(-1, -1))
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = navigateBack()
            }
        )
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.appbarSettings.updatePadding(left = bars.left, right = bars.right)
            scroll.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    protected fun configureSettings(console: String, scope: String, description: String = "") {
        this.console = console
        this.description = description
        binding.toolbarSettingsLayout.title = "$console settings"
        binding.settingsScope.text = scope
        binding.settingsScope.isVisible = true
    }

    protected fun category(titleId: Int, descriptionId: Int, iconId: Int, build: () -> Unit) {
        categories.add(Category(titleId, descriptionId, iconId, build))
    }

    protected fun showCategories() {
        val selected = categories.find { it.titleId == currentCategory }
        if (selected != null) {
            showCategory(selected)
        } else {
            currentCategory = 0
            content.removeAllViews()
            binding.toolbarSettingsLayout.title = "$console settings"
            if (description.isNotEmpty()) note(description)
            categories.forEach { category ->
                val row =
                    valueRow(getString(category.titleId), "", getString(category.descriptionId))
                row.icon.setImageResource(category.iconId)
                row.icon.isVisible = true
                row.root.setOnClickListener { showCategory(category) }
            }
            scroll.scrollTo(0, 0)
            binding.appbarSettings.setExpanded(true, false)
        }
    }

    private fun showCategory(category: Category) {
        currentCategory = category.titleId
        content.removeAllViews()
        binding.toolbarSettingsLayout.title = getString(category.titleId)
        category.build()
        scroll.scrollTo(0, 0)
        binding.appbarSettings.setExpanded(true, false)
    }

    private fun navigateBack() {
        if (currentCategory != 0) {
            currentCategory = 0
            showCategories()
        } else {
            onExitSettings()
        }
    }

    protected open fun onExitSettings() = finish()

    override fun onSupportNavigateUp(): Boolean {
        navigateBack()
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("settings_category", currentCategory)
        super.onSaveInstanceState(outState)
    }

    protected fun note(text: String) {
        content.addView(
            MaterialTextView(this).apply {
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                )
                this.text = text
                val padding = (16 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
            },
            ViewGroup.LayoutParams(-1, -2)
        )
    }

    private fun valueRow(
        label: String,
        value: String,
        summary: String = ""
    ): ListItemSettingBinding {
        val row = ListItemSettingBinding.inflate(layoutInflater, content, false)
        row.textSettingName.text = label
        row.textSettingDescription.text = summary
        row.textSettingDescription.isVisible = summary.isNotEmpty()
        row.textSettingValue.text = value
        row.textSettingValue.isVisible = value.isNotEmpty()
        content.addView(row.root)
        return row
    }

    protected fun toggleRow(
        label: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): MaterialSwitch {
        val row = ListItemSettingSwitchBinding.inflate(layoutInflater, content, false)
        row.textSettingName.text = label
        row.textSettingDescription.isVisible = false
        row.switchWidget.isChecked = checked
        row.switchWidget.contentDescription = label
        row.switchWidget.isFocusable = false
        row.switchWidget.setOnCheckedChangeListener { _, value -> onChange(value) }
        row.root.setOnClickListener { row.switchWidget.toggle() }
        content.addView(row.root)
        return row.switchWidget
    }

    protected fun choiceRow(
        label: String,
        labels: List<String>,
        selected: Int,
        onChange: (Int) -> Unit
    ) {
        var current = selected.coerceIn(labels.indices)
        val row = valueRow(label, labels[current])
        row.root.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(label)
                .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, index ->
                    if (index != current) {
                        try {
                            onChange(index)
                            current = index
                            row.textSettingValue.text = labels[index]
                        } catch (error: Exception) {
                            MaterialAlertDialogBuilder(this)
                                .setTitle(getString(R.string.settings_change_error, label))
                                .setMessage(error.message ?: error.javaClass.simpleName)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    protected fun sliderRow(label: String, value: Int, maximum: Int, onChange: (Int) -> Unit) {
        val initial = value.coerceIn(0, maximum)
        val row = valueRow(label, initial.toString())
        row.root.isClickable = false
        row.root.isFocusable = false
        content.addView(
            Slider(this).apply {
                valueFrom = 0f
                valueTo = maximum.toFloat()
                stepSize = 1f
                this.value = initial.toFloat()
                contentDescription = label
                addOnChangeListener { _, selected, fromUser ->
                    row.textSettingValue.text = selected.toInt().toString()
                    if (fromUser) onChange(selected.toInt())
                }
            },
            LinearLayout.LayoutParams(-1, -2).apply {
                val margin = (16 * resources.displayMetrics.density).toInt()
                marginStart = margin
                marginEnd = margin
            }
        )
    }

    protected fun actionRow(
        label: String,
        summary: String = "",
        onClick: () -> Unit
    ): MaterialButton = MaterialButton(
        this,
        null,
        com.google.android.material.R.attr.borderlessButtonStyle
    ).apply {
        text = if (summary.isEmpty()) label else "$label\n$summary"
        isAllCaps = false
        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        minHeight = (72 * resources.displayMetrics.density).toInt()
        setOnClickListener { onClick() }
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
}
