package com.gigaconet.pdf

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout

/**
 * Permite hacer zoom con dos dedos (pellizcar) sobre las páginas y moverse
 * con dos dedos cuando está ampliado. Los toques llegan bien a las páginas
 * porque Android ajusta las coordenadas al zoom.
 */
class ZoomLayout @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {

    var scale = 1f
        private set
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var zooming = false

    private val detector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            zooming = true
            lastFocusX = d.focusX
            lastFocusY = d.focusY
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            val child = getChildAt(0) ?: return false
            val newScale = (scale * d.scaleFactor).coerceIn(1f, 4f)
            val f = newScale / scale
            // Mantener fijo el punto entre los dedos y mover con ellos
            var tx = d.focusX - (d.focusX - child.translationX) * f + (d.focusX - lastFocusX)
            var ty = d.focusY - (d.focusY - child.translationY) * f + (d.focusY - lastFocusY)
            scale = newScale
            tx = tx.coerceIn(width - width * scale, 0f)
            ty = ty.coerceIn(height - height * scale, 0f)
            child.pivotX = 0f
            child.pivotY = 0f
            child.scaleX = scale
            child.scaleY = scale
            child.translationX = tx
            child.translationY = ty
            lastFocusX = d.focusX
            lastFocusY = d.focusY
            return true
        }
    })

    fun reset() {
        val child = getChildAt(0) ?: return
        scale = 1f
        child.scaleX = 1f
        child.scaleY = 1f
        child.translationX = 0f
        child.translationY = 0f
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount >= 2) {
            detector.onTouchEvent(ev)
            return true
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) zooming = false
        return true
    }
}
