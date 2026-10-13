package org.calamansi.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.textview.MaterialTextView
import org.calamansi.engine.PictureFilter
import org.calamansi.engine.PictureFilterDrawable
import org.calamansi.engine.PictureFilters
import org.citra.citra_emu.R

class PictureFilterSettingsActivity : EngineSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSettings(
            getString(R.string.settings_picture_filter),
            getString(R.string.settings_picture_filter_scope)
        )
        val filters = PictureFilter.entries
        val labels = listOf(
            getString(R.string.settings_picture_filter_off),
            getString(R.string.settings_picture_filter_crt),
            getString(R.string.settings_picture_filter_lcd)
        )
        val selected = PictureFilters.selected(this)
        val image = pixelArtSample()
        val comparison = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun preview(label: String): ImageView {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val padding = (16 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
            }
            column.addView(
                MaterialTextView(this).apply {
                    setTextAppearance(
                        com.google.android.material.R.style.TextAppearance_Material3_TitleSmall
                    )
                    text = label
                }
            )
            val view = ImageView(this).apply {
                setImageBitmap(image)
                (drawable as android.graphics.drawable.BitmapDrawable).setFilterBitmap(false)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "$label · pixel-art sample"
            }
            column.addView(view, LinearLayout.LayoutParams(-1, -2))
            comparison.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            return view
        }
        preview(getString(R.string.settings_picture_original))
        val filtered = preview(getString(R.string.settings_picture_preview))
        fun update(filter: PictureFilter) {
            filtered.foreground =
                if (filter == PictureFilter.OFF) null else PictureFilterDrawable(filter)
        }
        choiceRow(getString(R.string.settings_picture_filter), labels, filters.indexOf(selected)) {
            PictureFilters.select(this, filters[it])
            update(filters[it])
        }
        note(getString(R.string.settings_picture_filter_description))
        content.addView(comparison, LinearLayout.LayoutParams(-1, -2))
        note(getString(R.string.settings_picture_sample_note))
        update(selected)
    }

    /** Original generated artwork, shared by both previews; no ROM or game screenshot is bundled. */
    private fun pixelArtSample(): Bitmap {
        val bitmap = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        fun rect(color: Int, left: Int, top: Int, right: Int, bottom: Int) {
            paint.color = color
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
        }
        canvas.drawColor(Color.rgb(105, 175, 224))
        rect(Color.rgb(245, 219, 122), 98, 10, 112, 24)
        rect(Color.rgb(235, 244, 245), 12, 18, 34, 24)
        rect(Color.rgb(235, 244, 245), 20, 14, 29, 27)
        rect(Color.rgb(63, 115, 137), 0, 52, 128, 70)
        rect(Color.rgb(63, 115, 137), 12, 40, 42, 52)
        rect(Color.rgb(63, 115, 137), 80, 44, 118, 52)
        rect(Color.rgb(89, 150, 74), 0, 66, 128, 76)
        rect(Color.rgb(89, 70, 50), 0, 76, 128, 96)
        for (x in 0 until 128 step 8) {
            rect(Color.rgb(125, 100, 69), x, 78, x + 4, 81)
            rect(Color.rgb(125, 100, 69), x + 3, 88, x + 7, 91)
        }
        rect(Color.rgb(77, 64, 49), 18, 54, 22, 70)
        rect(Color.rgb(44, 104, 60), 12, 42, 28, 57)
        rect(Color.rgb(44, 104, 60), 15, 37, 25, 42)
        rect(Color.rgb(45, 57, 84), 58, 59, 70, 69)
        rect(Color.rgb(235, 182, 134), 60, 50, 68, 59)
        rect(Color.rgb(218, 106, 82), 58, 47, 70, 51)
        rect(Color.rgb(37, 41, 48), 58, 69, 62, 73)
        rect(Color.rgb(37, 41, 48), 66, 69, 70, 73)
        return bitmap
    }
}
