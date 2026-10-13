package org.calamansi.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** One repeated GPU tile; no game-frame readback, copies or per-frame Java drawing loop. */
class PictureFilterDrawable(val filter: PictureFilter) : Drawable() {
    private val paint = Paint().apply {
        if (filter != PictureFilter.OFF) {
            shader = BitmapShader(bitmap(filter), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }

    override fun draw(canvas: Canvas) {
        if (filter != PictureFilter.OFF) canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private val bitmaps = arrayOfNulls<Bitmap>(PictureFilter.entries.size)

        private fun bitmap(filter: PictureFilter): Bitmap = bitmaps[filter.id]
            ?: Bitmap.createBitmap(
                IntArray(filter.width * filter.height) { index ->
                    filter.argbAt(index % filter.width, index / filter.width)
                },
                filter.width,
                filter.height,
                Bitmap.Config.ARGB_8888
            ).also { bitmaps[filter.id] = it }
    }
}
