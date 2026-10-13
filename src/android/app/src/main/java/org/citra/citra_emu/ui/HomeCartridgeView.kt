// Copyright 2026 Azahar Emulator Project
// Licensed under GPLv3 or any later version

package org.citra.citra_emu.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import org.citra.citra_emu.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A small extruded cartridge; the existing child ImageView supplies the real game icon. */
class HomeCartridgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val front = requireNotNull(ContextCompat.getDrawable(context, R.drawable.home_preview_cartridge))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlineColor = ContextCompat.getColor(context, R.color.home_outline)
    private val shellColor = ContextCompat.getColor(context, R.color.home_tile_base)
    private val highlightColor = ContextCompat.getColor(context, R.color.home_tile_highlight)
    private val outline = floatArrayOf(
        8f, 12f, 16f, 4f, 124f, 4f, 130f, 10f, 130f, 16f, 136f, 16f,
        136f, 26f, 130f, 26f, 130f, 138f, 122f, 146f, 16f, 146f, 8f, 138f
    )
    private val frontPoints = FloatArray(outline.size)
    private val backPoints = FloatArray(outline.size)
    private val corners = floatArrayOf(0f, 0f, 148f, 0f, 148f, 156f, 0f, 156f)
    private val source = FloatArray(8)
    private val target = FloatArray(8)
    private val matrix = Matrix()
    private val side = Path()
    private val face = Path()
    private var angle = 0.0
    private var visible = false
    private var ownerLifecycle: Lifecycle? = null
    private val lifecycleObserver = LifecycleEventObserver { _, _ -> updateAnimation() }
    private val spin = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 8000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedFraction * Math.PI * 2
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ownerLifecycle = findViewTreeLifecycleOwner()?.lifecycle
        ownerLifecycle?.addObserver(lifecycleObserver)
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        ownerLifecycle?.removeObserver(lifecycleObserver)
        ownerLifecycle = null
        spin.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visible = isVisible
        updateAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

    private fun updateAnimation() {
        if (isAttachedToWindow && visible && windowVisibility == View.VISIBLE &&
            ownerLifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true &&
            ValueAnimator.areAnimatorsEnabled()
        ) {
            if (spin.isPaused) spin.resume() else if (!spin.isStarted) spin.start()
        } else if (spin.isStarted && !spin.isPaused) {
            spin.pause()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        front.setBounds(0, 0, w, h)
        for (i in corners.indices step 2) {
            source[i] = corners[i] * w / 148f
            source[i + 1] = corners[i + 1] * h / 156f
        }
        face.rewind()
        face.moveTo(outline[0] * w / 148f, outline[1] * h / 156f)
        for (i in 2 until outline.size step 2) {
            face.lineTo(outline[i] * w / 148f, outline[i + 1] * h / 156f)
        }
        face.close()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val cosine = cos(angle).toFloat()
        val sine = sin(angle).toFloat()
        project(outline, 6f, cosine, sine, frontPoints)
        project(outline, -6f, cosine, sine, backPoints)
        paint.color = outlineColor
        paint.style = Paint.Style.FILL
        for (i in outline.indices step 2) {
            val next = (i + 2) % outline.size
            side.rewind()
            side.moveTo(frontPoints[i], frontPoints[i + 1])
            side.lineTo(frontPoints[next], frontPoints[next + 1])
            side.lineTo(backPoints[next], backPoints[next + 1])
            side.lineTo(backPoints[i], backPoints[i + 1])
            side.close()
            canvas.drawPath(side, paint)
        }
        // At edge-on angles only the physical thickness is visible.
        if (abs(cosine) < 0.01f) return
        project(corners, if (cosine > 0f) 6f else -6f, cosine, sine, target)
        if (!matrix.setPolyToPoly(source, 0, target, 0, 4)) return
        val saved = canvas.save()
        canvas.concat(matrix)
        if (cosine > 0f) {
            front.draw(canvas)
            super.dispatchDraw(canvas)
        } else {
            // The back is moulded plastic, never a mirrored game label.
            paint.color = shellColor
            canvas.drawPath(face, paint)
            canvas.clipPath(face)
            val x = width / 148f
            val y = height / 156f
            paint.color = outlineColor
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = x
            canvas.drawRoundRect(25f * x, 31f * y, 113f * x, 123f * y, 5f * x, 5f * y, paint)
            canvas.drawLine(30f * x, 18f * y, 108f * x, 18f * y, paint)
            canvas.drawLine(30f * x, 21f * y, 108f * x, 21f * y, paint)
            paint.color = highlightColor
            canvas.drawLine(26f * x, 32f * y, 112f * x, 32f * y, paint)
        }
        canvas.restoreToCount(saved)
    }

    private fun project(points: FloatArray, z: Float, cosine: Float, sine: Float, result: FloatArray) {
        val xScale = width / 148f
        val yScale = height / 156f
        for (i in points.indices step 2) {
            val x = points[i] - 74f
            val depth = -x * sine + z * cosine
            val perspective = 600f / (600f - depth)
            result[i] = (74f + (x * cosine + z * sine) * perspective) * xScale
            result[i + 1] = (78f + (points[i + 1] - 78f) * perspective) * yScale
        }
    }
}
