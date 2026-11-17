package com.gigaconet.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

enum class Tool { PAN, PEN, HIGHLIGHT, TEXT, EDIT_TEXT, SIGN, ERASE }

interface PageHost {
    val tool: Tool
    val penColor: Int
    val penWidth: Float
    val preview: Boolean
    val activeAnnot: Annot?
    /** Imagen de la página (la pide si todavía no está lista). */
    fun bitmapOf(page: Int): Bitmap?
    fun annotsOf(page: Int): List<Annot>
    fun blocksOf(page: Int): List<TextBlock>?
    fun onStroke(page: Int, stroke: StrokeAnnot)
    fun onTap(page: Int, x: Float, y: Float)
    fun onErase(page: Int, x: Float, y: Float)
    fun onLongPress(page: Int)
}

/** Una página del PDF con sus anotaciones encima. */
class PageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var host: PageHost? = null
    var pageIndex = 0
    var aspect = 1.414f          // alto / ancho
    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }
    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#992F6FDB")
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val blockFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F2F6FDB") }
    private val editedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#CC1F9468")
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val editedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#141F9468") }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#2F6FDB")
        strokeWidth = 4f
    }
    private val path = Path()
    private var current: StrokeAnnot? = null

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val h = host ?: return false
            if (h.preview) {
                h.onTap(pageIndex, e.x / width, e.y / height)
                return true
            }
            if (h.tool == Tool.TEXT || h.tool == Tool.SIGN || h.tool == Tool.EDIT_TEXT) {
                h.onTap(pageIndex, e.x / width, e.y / height)
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            if (host?.tool == Tool.PAN && host?.preview == false) host?.onLongPress(pageIndex)
        }
    })

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * aspect).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Color.WHITE)
        val hostRef = host
        // Siempre se pide la imagen actual: así nunca queda una página en blanco
        val bmp = if (hostRef != null) hostRef.bitmapOf(pageIndex) else bitmap
        if (bmp != null && !bmp.isRecycled) canvas.drawBitmap(bmp, null, RectF(0f, 0f, w, h), bmpPaint)
        if (hostRef == null) return
        // En vista previa la imagen ya es el PDF final con los cambios aplicados
        if (hostRef.preview) return
        for (a in hostRef.annotsOf(pageIndex)) drawAnnot(canvas, a, w, h)
        current?.let { drawAnnot(canvas, it, w, h) }
        val radius = 6f * resources.displayMetrics.density
        if (hostRef.tool == Tool.EDIT_TEXT) {
            val edited = hostRef.annotsOf(pageIndex).filterIsInstance<EditAnnot>().map { it.box }
            hostRef.blocksOf(pageIndex)?.forEach { b ->
                if (edited.any { near(it, b.box) }) return@forEach
                val r = RectF(b.box.left * w - 3, b.box.top * h - 2, b.box.right * w + 3, b.box.bottom * h + 2)
                canvas.drawRoundRect(r, radius, radius, blockFill)
                canvas.drawRoundRect(r, radius, radius, blockPaint)
            }
            // Lo que ya editaste sigue marcado (en verde) para poder volver a tocarlo
            for (e in hostRef.annotsOf(pageIndex).filterIsInstance<EditAnnot>()) {
                val r = RectF(e.box.left * w - 3, e.box.top * h - 2, e.box.right * w + 3, e.box.bottom * h + 2)
                val tw = measureTextBox(e.text, w, h)
                if (tw != null) r.union(tw)
                canvas.drawRoundRect(r, radius, radius, editedFill)
                canvas.drawRoundRect(r, radius, radius, editedPaint)
            }
        }
        val active = hostRef.activeAnnot
        val box = when (active) {
            is EditAnnot -> RectF(active.box)
            is TextAnnot -> RectF(active.x, active.y, active.x + (if (active.maxWidth > 0f) active.maxWidth else 0.3f), active.y + active.size * 1.4f / aspect)
            else -> null
        }
        if (box != null && active != null && hostRef.annotsOf(pageIndex).contains(active)) {
            canvas.drawRoundRect(RectF(box.left * w - 4, box.top * h - 4, box.right * w + 4, box.bottom * h + 4), radius, radius, activePaint)
        }
    }

    private fun near(a: RectF, b: RectF): Boolean =
        Math.abs(a.left - b.left) < 0.01f && Math.abs(a.top - b.top) < 0.01f

    private fun drawAnnot(c: Canvas, a: Annot, w: Float, h: Float) {
        when (a) {
            is StrokeAnnot -> {
                strokePaint.color = a.color
                strokePaint.alpha = if (a.highlight) 97 else 255
                strokePaint.strokeWidth = a.width * w
                strokePaint.strokeCap = if (a.highlight) Paint.Cap.SQUARE else Paint.Cap.ROUND
                path.reset()
                a.points.forEachIndexed { i, p ->
                    if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h)
                }
                if (a.points.size == 1) path.lineTo(a.points[0].x * w + 0.5f, a.points[0].y * h)
                c.drawPath(path, strokePaint)
            }
            is TextAnnot -> drawText(c, a, w, h)
            is EditAnnot -> {
                fillPaint.color = a.bg
                c.drawRect(a.box.left * w, a.box.top * h, a.box.right * w, a.box.bottom * h, fillPaint)
                drawText(c, a.text, w, h)
            }
        }
    }

    private fun typefaceFor(t: TextAnnot): Typeface {
        val base = when (t.family) {
            "serif" -> Typeface.SERIF
            "mono" -> Typeface.MONOSPACE
            else -> Typeface.SANS_SERIF
        }
        return if (t.bold) Typeface.create(base, Typeface.BOLD) else base
    }

    /** Rectángulo que ocupa un texto en pantalla (para marcarlo). */
    private fun measureTextBox(t: TextAnnot, w: Float, h: Float): RectF? {
        if (t.text.isEmpty()) return null
        textPaint.textSize = t.size * w
        textPaint.typeface = typefaceFor(t)
        val lines = wrapText(t.text, t.maxWidth * w) { textPaint.measureText(it) }
        val maxW = lines.maxOf { textPaint.measureText(it) }
        val height = textPaint.textSize * (1f + (lines.size - 1) * t.lineHeight) + textPaint.textSize * 0.3f
        return RectF(t.x * w - 3, t.y * h, t.x * w + maxW + 3, t.y * h + height)
    }

    private fun drawText(c: Canvas, t: TextAnnot, w: Float, h: Float) {
        if (t.text.isEmpty()) return
        textPaint.color = t.color
        textPaint.textSize = t.size * w
        textPaint.typeface = typefaceFor(t)
        val lines = wrapText(t.text, t.maxWidth * w) { textPaint.measureText(it) }
        var y = t.y * h + textPaint.textSize
        for (line in lines) {
            c.drawText(line, t.x * w, y, textPaint)
            y += textPaint.textSize * t.lineHeight
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val h = host ?: return false
        val x = (e.x / width).coerceIn(0f, 1f)
        val y = (e.y / height).coerceIn(0f, 1f)
        if (h.preview) return gestures.onTouchEvent(e)
        when (h.tool) {
            Tool.PEN, Tool.HIGHLIGHT -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val hl = h.tool == Tool.HIGHLIGHT
                        current = StrokeAnnot(
                            mutableListOf(PointF(x, y)),
                            if (hl) h.penColor else h.penColor,
                            if (hl) h.penWidth * 4f else h.penWidth,
                            hl
                        )
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val s = current ?: return true
                        for (i in 0 until e.historySize) {
                            s.points.add(PointF((e.getHistoricalX(i) / width).coerceIn(0f, 1f), (e.getHistoricalY(i) / height).coerceIn(0f, 1f)))
                        }
                        s.points.add(PointF(x, y))
                        invalidate()
                    }
                    MotionEvent.ACTION_UP -> {
                        current?.let { s ->
                            if (s.highlight && s.points.size > 1) {
                                // El resaltador va en línea recta, como un marcador sobre el renglón
                                val a = s.points.first()
                                val b = s.points.last()
                                if (Math.abs(b.y - a.y) * height < 40) {
                                    s.points.clear()
                                    s.points.add(a)
                                    s.points.add(PointF(b.x, a.y))
                                }
                            }
                            h.onStroke(pageIndex, s)
                        }
                        current = null
                        invalidate()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        current = null
                        invalidate()
                    }
                }
                return true
            }
            Tool.ERASE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
                    h.onErase(pageIndex, x, y)
                }
                return true
            }
            else -> return gestures.onTouchEvent(e)
        }
    }
}
