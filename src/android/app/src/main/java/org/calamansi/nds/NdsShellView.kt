package org.calamansi.nds

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import me.magnum.melonds.domain.model.Rect

internal class NdsShellView(context: Context, private val lowerHalf: Boolean = false) :
    View(context) {
    private val opening = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        // Keep it measured when the native view publishes its geometry during parent layout.
        visibility = INVISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Include the painted margins in SurfaceView's window transparency calculation.
        setBackgroundColor(Color.rgb(245, 245, 241))
    }

    fun frame(rect: Rect?) {
        val nextVisibility = if (rect == null) INVISIBLE else VISIBLE
        if (visibility != nextVisibility) {
            visibility = nextVisibility
            // SurfaceView's transparency hints must follow the newly visible shell.
            requestLayout()
        }
        if (rect != null) {
            opening.set(
                rect.x.toFloat(),
                rect.y.toFloat(),
                rect.right.toFloat(),
                rect.bottom.toFloat()
            )
            invalidate()
        }
    }

    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.clipOutRect(opening)
        super.draw(canvas)
        canvas.restoreToCount(saved)
    }

    override fun onDraw(canvas: Canvas) {
        val unit = opening.width() / 256f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = unit * 0.5f
        paint.color = Color.rgb(205, 206, 201)
        canvas.drawRoundRect(
            unit * 2,
            unit * 2,
            width - unit * 2,
            height - unit * 2,
            unit * 8,
            unit * 8,
            paint
        )
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(47, 48, 45)
        canvas.drawRoundRect(
            opening.left - unit * 3,
            opening.top - unit * 3,
            opening.right + unit * 3,
            opening.bottom + unit * 3,
            unit * 4,
            unit * 4,
            paint
        )
        paint.color = Color.rgb(115, 117, 113)
        if (lowerHalf) {
            lowerControls(canvas, unit)
        } else if (opening.left > unit * 24) {
            speakers(canvas, opening.left / 2, unit)
            speakers(canvas, (opening.right + width) / 2, unit)
        }
        paint.color = Color.rgb(214, 215, 210)
        paint.strokeWidth = unit * 0.5f
        val seamY = if (lowerHalf) unit * 7 else height - unit * 7
        canvas.drawLine(unit * 8, seamY, width - unit * 8, seamY, paint)
    }

    private fun lowerControls(canvas: Canvas, unit: Float) {
        // Moulded shell artwork only; the handheld's physical controls provide DS buttons.
        val x = opening.left / 2
        val y = opening.centerY()
        if (x < unit * 9) return
        paint.color = Color.rgb(145, 147, 141)
        canvas.drawRoundRect(
            x - unit * 7,
            y - unit * 2.5f,
            x + unit * 7,
            y + unit * 2.5f,
            unit,
            unit,
            paint
        )
        canvas.drawRoundRect(
            x - unit * 2.5f,
            y - unit * 7,
            x + unit * 2.5f,
            y + unit * 7,
            unit,
            unit,
            paint
        )
        paint.color = Color.rgb(229, 230, 224)
        canvas.drawRect(x - unit * 6.5f, y - unit * 2, x + unit * 6.5f, y + unit * 2, paint)
        canvas.drawRect(x - unit * 2, y - unit * 6.5f, x + unit * 2, y + unit * 6.5f, paint)
        val right = (opening.right + width) / 2
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = unit * 3
        for (index in 0..3) {
            val buttonX = right + when (index) {
                1 -> -unit * 5
                2 -> unit * 5
                else -> 0f
            }
            val buttonY = y + when (index) {
                0 -> -unit * 5
                3 -> unit * 5
                else -> 0f
            }
            paint.color = Color.rgb(145, 147, 141)
            canvas.drawCircle(buttonX, buttonY, unit * 2.8f, paint)
            paint.color = Color.rgb(229, 230, 224)
            canvas.drawCircle(buttonX, buttonY, unit * 2.3f, paint)
            paint.color = Color.rgb(115, 117, 113)
            val label = when (index) {
                0 -> "X"
                1 -> "Y"
                2 -> "A"
                else -> "B"
            }
            canvas.drawText(label, buttonX, buttonY + unit, paint)
        }
        paint.textSize = unit * 2
        canvas.drawText("SELECT", right, y + unit * 18, paint)
        canvas.drawText("START", right, y + unit * 24, paint)
    }

    private fun speakers(canvas: Canvas, centerX: Float, unit: Float) {
        for (column in 0..1) {
            for (row in -1..1) {
                canvas.drawCircle(
                    centerX + (column - 0.5f) * unit * 5,
                    opening.centerY() + row * unit * 5,
                    unit * 1.1f,
                    paint
                )
            }
        }
    }
}
