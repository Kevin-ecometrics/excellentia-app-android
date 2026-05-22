package com.example.test

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.util.Base64
import java.io.ByteArrayOutputStream

class SignatureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val path = Path()
    private val paint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 7f
    }

    private var lastX = 0f
    private var lastY = 0f

    var isEmpty = true
        private set

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                path.moveTo(x, y)
                lastX = x
                lastY = y
                isEmpty = false
            }
            MotionEvent.ACTION_MOVE -> {
                path.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f)
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                path.lineTo(x, y)
            }
        }
        invalidate()
        return true
    }

    fun clear() {
        path.reset()
        isEmpty = true
        invalidate()
    }

    fun getBase64(): String {
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.drawPath(path, paint)
        val stream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }
}
