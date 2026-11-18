package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Shows two versions of the same page (before and after processing) split by a draggable divider:
 * the "before" version is on the left of the divider, the "after" version on the right.
 *
 * Only a touch that starts near the divider is handled. Everything else (taps, page swipes) is left to
 * the pager, so navigating still works while this view is showing.
 */
class CompareSliderView(
    context: Context,
    private val beforeLabel: String,
    private val afterLabel: String,
) : View(context) {

    private var before: Bitmap? = null
    private var after: Bitmap? = null

    private var ratio = 0.5f
    private var dragging = false

    private val density = resources.displayMetrics.density
    private val imageRect = RectF()

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f * density
        setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(4f * density, 0f, 0f, Color.BLACK)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
    }

    fun setBitmaps(before: Bitmap, after: Bitmap) {
        this.before = before
        this.after = after
        invalidate()
    }

    /**
     * Drops the bitmaps. Call when the view is no longer used.
     */
    fun release() {
        before = null
        after = null
    }

    private fun updateImageRect(): Boolean {
        val reference = after ?: return false
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return false

        val scale = min(viewWidth / reference.width, viewHeight / reference.height)
        val drawWidth = reference.width * scale
        val drawHeight = reference.height * scale
        val left = (viewWidth - drawWidth) / 2f
        val top = (viewHeight - drawHeight) / 2f
        imageRect.set(left, top, left + drawWidth, top + drawHeight)
        return true
    }

    private fun dividerX() = imageRect.left + imageRect.width() * ratio

    override fun onDraw(canvas: Canvas) {
        val afterBitmap = after ?: return
        if (!updateImageRect()) return
        val beforeBitmap = before ?: afterBitmap

        canvas.drawBitmap(afterBitmap, null, imageRect, bitmapPaint)

        val dividerX = dividerX()
        canvas.save()
        canvas.clipRect(imageRect.left, imageRect.top, dividerX, imageRect.bottom)
        canvas.drawBitmap(beforeBitmap, null, imageRect, bitmapPaint)
        canvas.restore()

        // Divider line and handle
        canvas.drawLine(dividerX, imageRect.top, dividerX, imageRect.bottom, linePaint)
        val centerY = imageRect.centerY().coerceIn(imageRect.top, imageRect.bottom)
        val radius = 18f * density
        canvas.drawCircle(dividerX, centerY, radius, handlePaint)
        val arrow = 6f * density
        canvas.drawLines(
            floatArrayOf(
                dividerX - arrow - 2f * density, centerY, dividerX - 2f * density, centerY - arrow,
                dividerX - arrow - 2f * density, centerY, dividerX - 2f * density, centerY + arrow,
                dividerX + arrow + 2f * density, centerY, dividerX + 2f * density, centerY - arrow,
                dividerX + arrow + 2f * density, centerY, dividerX + 2f * density, centerY + arrow,
            ),
            arrowPaint,
        )

        // Labels
        val margin = 8f * density
        val textY = imageRect.top + margin + labelPaint.textSize
        canvas.drawText(beforeLabel, imageRect.left + margin, textY, labelPaint)
        canvas.drawText(afterLabel, imageRect.right - margin - labelPaint.measureText(afterLabel), textY, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!updateImageRect()) return false
                if (abs(event.x - dividerX()) > 32f * density) return false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                ratio = ((event.x - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return dragging
    }
}
