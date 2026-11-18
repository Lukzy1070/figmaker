package com.figmaker.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

private val checkA = Paint().apply { color = 0xFFFFFFFF.toInt() }
private val checkB = Paint().apply { color = 0xFFD0D0D0.toInt() }

/** mode 0 = xadrez (transparência), 1 = conversa clara do WhatsApp, 2 = conversa escura. */
fun drawBg(c: Canvas, mode: Int, w: Float, h: Float) {
    when (mode) {
        1 -> c.drawColor(0xFFECE5DD.toInt())
        2 -> c.drawColor(0xFF0B141A.toInt())
        else -> {
            c.drawRect(0f, 0f, w, h, checkA)
            val cell = w / 16f
            var y = 0
            while (y * cell < h) {
                var x = 0
                while (x * cell < w) {
                    if ((x + y) % 2 == 1) c.drawRect(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell, checkB)
                    x++
                }
                y++
            }
        }
    }
}

/** Área quadrada = a figurinha 512x512. Arraste para mover, pince para zoom. */
class CropView(ctx: Context) : View(ctx) {
    var item: Item? = null
    var bg = 0
    var onChange: (() -> Unit)? = null

    private val border = Paint().apply {
        style = Paint.Style.STROKE
        color = 0x88000000.toInt()
        strokeWidth = 3f
    }
    private var lx = 0f
    private var ly = 0f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val itm = this@CropView.item ?: return true
            val k = Converter.SIZE / this@CropView.width.toFloat()
            val newScale = (itm.scale * d.scaleFactor).coerceIn(itm.fitScale * 0.3f, itm.fillScale * 6f)
            val real = newScale / itm.scale
            val fx = d.focusX * k
            val fy = d.focusY * k
            itm.tx = fx - (fx - itm.tx) * real
            itm.ty = fy - (fy - itm.ty) * real
            itm.scale = newScale
            changed()
            return true
        }
    })

    private fun changed() {
        invalidate()
        onChange?.invoke()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        val itm = item ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = e.x; ly = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && e.pointerCount == 1) {
                val k = Converter.SIZE / width.toFloat()
                itm.tx += (e.x - lx) * k
                itm.ty += (e.y - ly) * k
                lx = e.x; ly = e.y
                changed()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = if (e.actionIndex == 0) 1 else 0
                lx = e.getX(idx); ly = e.getY(idx)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        drawBg(c, bg, w, height.toFloat())
        item?.draw(c, w)
        c.drawRect(0f, 0f, w, height.toFloat(), border)
    }
}

/** Simula a figurinha numa conversa (tamanho aproximado), com um balão de texto para comparar. */
class ChatPreview(ctx: Context) : View(ctx) {
    var item: Item? = null
    var bg = 0

    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, (w * 0.55f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val mode = if (bg == 0) 1 else bg
        drawBg(c, mode, width.toFloat(), height.toFloat())

        val dark = mode == 2
        bubble.color = if (dark) 0xFF202C33.toInt() else 0xFFFFFFFF.toInt()
        text.color = if (dark) 0xFFE9EDEF.toInt() else 0xFF111B21.toInt()
        text.textSize = 15 * d
        c.drawRoundRect(RectF(12 * d, 12 * d, 92 * d, 48 * d), 10 * d, 10 * d, bubble)
        c.drawText("Oi!", 24 * d, 36 * d, text)

        val s = width * 0.42f
        c.save()
        c.translate(width - s - 12 * d, height - s - 8 * d)
        item?.draw(c, s)
        c.restore()
    }
}
