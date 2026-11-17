package com.gigaconet.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View

/** Recuadro para dibujar la firma con el dedo. */
class SignaturePad(ctx: Context) : View(ctx) {

    private val strokes = ArrayList<MutableList<PointF>>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#1A2B6D")
    }
    private val line = Paint().apply { color = Color.parseColor("#C9CDD6"); strokeWidth = 3f }
    private val path = Path()

    val isEmpty get() = strokes.isEmpty()

    fun clear() {
        strokes.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#F6F7FA"))
        val baseY = height * 0.78f
        canvas.drawLine(width * 0.06f, baseY, width * 0.94f, baseY, line)
        paint.strokeWidth = width * 0.012f
        for (s in strokes) {
            path.reset()
            s.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            if (s.size == 1) path.lineTo(s[0].x + 1f, s[0].y)
            canvas.drawPath(path, paint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> strokes.add(mutableListOf(PointF(e.x, e.y)))
            MotionEvent.ACTION_MOVE -> {
                val s = strokes.lastOrNull() ?: return true
                for (i in 0 until e.historySize) s.add(PointF(e.getHistoricalX(i), e.getHistoricalY(i)))
                s.add(PointF(e.x, e.y))
            }
        }
        invalidate()
        return true
    }

    /** Recorta la firma a lo que se dibujó y la normaliza a 0..1. */
    fun toSignature(): Signature? {
        if (strokes.isEmpty()) return null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (s in strokes) for (p in s) {
            minX = minOf(minX, p.x); maxX = maxOf(maxX, p.x)
            minY = minOf(minY, p.y); maxY = maxOf(maxY, p.y)
        }
        val pad = width * 0.02f
        minX -= pad; minY -= pad; maxX += pad; maxY += pad
        val w = (maxX - minX).coerceAtLeast(1f)
        val h = (maxY - minY).coerceAtLeast(1f)
        val norm = strokes.map { s -> s.map { PointF((it.x - minX) / w, (it.y - minY) / h) } }
        return Signature(norm, h / w)
    }
}
