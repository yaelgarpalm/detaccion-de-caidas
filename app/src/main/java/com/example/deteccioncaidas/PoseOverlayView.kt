package com.example.deteccioncaidas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class PoseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private data class PoseFrame(
        val points: Array<FloatArray>?,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val bounds: FloatArray?,
        val score: Float,
    )

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDDF0F4E8.toInt()
        strokeWidth = density * 2.4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5F2D8.toInt() }
    private val referencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD166.toInt() }
    private val boundsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD99CE0A8.toInt()
        style = Paint.Style.STROKE
        strokeWidth = density * 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6244C3C.toInt() }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F7F2.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    @Volatile
    private var poseFrame: PoseFrame? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setDetection(
        points: Array<FloatArray>?,
        sourceWidth: Int,
        sourceHeight: Int,
        personBounds: FloatArray?,
        score: Float,
    ) {
        poseFrame = PoseFrame(points, sourceWidth, sourceHeight, personBounds?.clone(), score)
        postInvalidateOnAnimation()
    }

    fun clear() {
        poseFrame = null
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = poseFrame ?: return
        if (frame.sourceWidth <= 0 || frame.sourceHeight <= 0 || width <= 0 || height <= 0) return

        val scale = min(width / frame.sourceWidth.toFloat(), height / frame.sourceHeight.toFloat())
        val drawnWidth = frame.sourceWidth * scale
        val drawnHeight = frame.sourceHeight * scale
        val offsetX = (width - drawnWidth) / 2f
        val offsetY = (height - drawnHeight) / 2f

        frame.bounds?.let { drawPersonBounds(canvas, it, offsetX, offsetY, drawnWidth, drawnHeight) }
        val points = frame.points ?: return
        if (points.size < LANDMARK_COUNT) return

        CONNECTIONS.forEach { (fromIndex, toIndex) ->
            val from = points[fromIndex]
            val to = points[toIndex]
            if (from.size < 3 || to.size < 3 || from[2] < DRAW_VISIBILITY || to[2] < DRAW_VISIBILITY) return@forEach
            canvas.drawLine(
                map(from[0], offsetX, drawnWidth),
                map(from[1], offsetY, drawnHeight),
                map(to[0], offsetX, drawnWidth),
                map(to[1], offsetY, drawnHeight),
                linePaint,
            )
        }

        points.forEachIndexed { index, point ->
            if (point.size < 3 || point[2] < DRAW_VISIBILITY) return@forEachIndexed
            val isReference = index in REFERENCE_POINTS
            val radius = density * if (isReference) 4.2f else 2.1f
            canvas.drawCircle(
                map(point[0], offsetX, drawnWidth),
                map(point[1], offsetY, drawnHeight),
                radius,
                if (isReference) referencePaint else pointPaint,
            )
        }
    }

    private fun drawPersonBounds(canvas: Canvas, bounds: FloatArray, offsetX: Float, offsetY: Float, drawnWidth: Float, drawnHeight: Float) {
        if (bounds.size < 4) return
        val left = map(bounds[0], offsetX, drawnWidth)
        val top = map(bounds[1], offsetY, drawnHeight)
        val right = map(bounds[2], offsetX, drawnWidth)
        val bottom = map(bounds[3], offsetY, drawnHeight)
        canvas.drawRoundRect(RectF(left, top, right, bottom), density * 12f, density * 12f, boundsPaint)

        val score = poseFrame?.score ?: 0f
        val label = String.format(Locale.getDefault(), "PERSONA · %.0f%%", score * 100f)
        labelTextPaint.textSize = density * 10f
        val horizontalPadding = density * 8f
        val labelHeight = density * 23f
        val labelWidth = labelTextPaint.measureText(label) + horizontalPadding * 2f
        val labelLeft = max(0f, min(left, width - labelWidth))
        val labelTop = max(0f, top - labelHeight - density * 3f)
        canvas.drawRoundRect(
            RectF(labelLeft, labelTop, labelLeft + labelWidth, labelTop + labelHeight),
            density * 7f,
            density * 7f,
            labelBackgroundPaint,
        )
        val metrics = labelTextPaint.fontMetrics
        val baseline = labelTop + (labelHeight - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
        canvas.drawText(label, labelLeft + horizontalPadding, baseline, labelTextPaint)
    }

    private fun map(normalized: Float, offset: Float, drawnSize: Float): Float = offset + normalized.coerceIn(0f, 1f) * drawnSize

    companion object {
        private const val DRAW_VISIBILITY = 0.30f
        private const val LANDMARK_COUNT = 33
        private val REFERENCE_POINTS = setOf(0, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
        private val CONNECTIONS = arrayOf(
            intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 7),
            intArrayOf(0, 4), intArrayOf(4, 5), intArrayOf(5, 6), intArrayOf(6, 8),
            intArrayOf(9, 10), intArrayOf(11, 12), intArrayOf(11, 13), intArrayOf(13, 15),
            intArrayOf(15, 17), intArrayOf(15, 19), intArrayOf(15, 21), intArrayOf(17, 19),
            intArrayOf(12, 14), intArrayOf(14, 16), intArrayOf(16, 18), intArrayOf(16, 20),
            intArrayOf(16, 22), intArrayOf(18, 20), intArrayOf(11, 23), intArrayOf(12, 24),
            intArrayOf(23, 24), intArrayOf(23, 25), intArrayOf(25, 27), intArrayOf(27, 29),
            intArrayOf(29, 31), intArrayOf(27, 31), intArrayOf(24, 26), intArrayOf(26, 28),
            intArrayOf(28, 30), intArrayOf(30, 32), intArrayOf(28, 32),
        )
    }
}

