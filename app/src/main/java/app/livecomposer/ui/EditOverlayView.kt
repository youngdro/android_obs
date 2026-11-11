package app.livecomposer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import app.livecomposer.core.Layer

/**
 * 叠在预览 SurfaceView 之上的编辑层：绘制选中框、辅助线并处理手势。
 * 只存在于 Android View 层，永远不会进入合成画面 / 输出视频。
 */
@SuppressLint("ViewConstructor")
class EditOverlayView(context: Context, private val callback: Callback) : View(context) {

    interface Callback {
        fun layers(): List<Layer>
        fun canvasSize(): Pair<Int, Int>
        fun interactionMode(): Boolean
        fun selectedId(): String?
        fun onSelect(id: String?)
        fun onLayerChanged(layer: Layer, finished: Boolean)
        fun onTapFocus(canvasX: Float, canvasY: Float)
        fun onCameraPinch(scale: Float)
        /** 网页交互模式下的触摸，坐标已换算为画布像素。返回是否消费。 */
        fun onWebTouch(ev: MotionEvent, canvasX: Float, canvasY: Float): Boolean
    }

    private val box = Paint().apply { color = Color.rgb(0, 200, 255); style = Paint.Style.STROKE; strokeWidth = 4f }
    private val guide = Paint().apply {
        color = Color.argb(160, 255, 255, 0); style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val handle = Paint().apply { color = Color.rgb(0, 200, 255); style = Paint.Style.FILL }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var moved = false
    private var dragging: Layer? = null
    private var scaling = false
    private var snapX = false
    private var snapY = false
    private var longPressed = false
    private var longPressX = 0f
    private var longPressY = 0f
    /** 长按：选中手指下最上层的可见图层。 */
    private val longPress = Runnable {
        val hitLayer = callback.layers().lastOrNull { it.visible && hit(it, longPressX, longPressY) }
        if (hitLayer != null) {
            longPressed = true
            callback.onSelect(hitLayer.id)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val l = selected()
            if (l != null && !l.locked) {
                val f = d.scaleFactor
                val cx = l.x + l.w / 2; val cy = l.y + l.h / 2
                l.w = (l.w * f).coerceAtLeast(16f); l.h = (l.h * f).coerceAtLeast(16f)
                l.x = cx - l.w / 2; l.y = cy - l.h / 2
                callback.onLayerChanged(l, false)
            } else if (l == null) {
                callback.onCameraPinch(d.scaleFactor)
            }
            invalidate()
            return true
        }
    })

    private fun scale(): Float = callback.canvasSize().first.toFloat() / width.coerceAtLeast(1)
    private fun selected(): Layer? = callback.selectedId()?.let { id -> callback.layers().firstOrNull { it.id == id } }

    override fun onDraw(c: Canvas) {
        val l = selected() ?: return
        val s = 1f / scale()
        val left = l.x * s; val top = l.y * s; val right = (l.x + l.w) * s; val bottom = (l.y + l.h) * s
        c.drawRect(left, top, right, bottom, box)
        if (!l.locked) {
            val r = 10f
            for ((hx, hy) in listOf(left to top, right to top, left to bottom, right to bottom)) c.drawCircle(hx, hy, r, handle)
        }
        if (dragging != null) {
            if (snapX) c.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), guide)
            if (snapY) c.drawLine(0f, height / 2f, width.toFloat(), height / 2f, guide)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val s = scale()
        val cx = ev.x * s; val cy = ev.y * s
        if (callback.interactionMode()) {
            return callback.onWebTouch(ev, cx, cy)
        }
        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; moved = false; scaling = false; longPressed = false
                longPressX = cx; longPressY = cy
                postDelayed(longPress, 500)
                val sel = selected()
                dragging = if (sel != null && !sel.locked && hit(sel, cx, cy)) sel else null
                if (dragging != null) { startX = sel!!.x; startY = sel.y }
            }
            MotionEvent.ACTION_MOVE -> {
                if (Math.hypot((ev.x - downX).toDouble(), (ev.y - downY).toDouble()) > 12) { moved = true; removeCallbacks(longPress) }
                val d = dragging
                if (d != null && moved && !scaling && ev.pointerCount == 1) {
                    var nx = startX + (ev.x - downX) * s
                    var ny = startY + (ev.y - downY) * s
                    // 中心吸附辅助线
                    val (cw, ch) = callback.canvasSize()
                    snapX = Math.abs(nx + d.w / 2 - cw / 2f) < 12 * s
                    snapY = Math.abs(ny + d.h / 2 - ch / 2f) < 12 * s
                    if (snapX) nx = cw / 2f - d.w / 2
                    if (snapY) ny = ch / 2f - d.h / 2
                    d.x = nx; d.y = ny
                    callback.onLayerChanged(d, false)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (longPressed) {
                    // 长按已处理选择
                } else if (!moved && !scaling) {
                    // 单击：未选中图层时 = 点击对焦；已选中时点其他图层切换选择，点空白或自身取消选择
                    val sel = callback.selectedId()
                    if (sel == null) {
                        callback.onTapFocus(cx, cy)
                    } else {
                        val hitLayer = callback.layers().lastOrNull { it.visible && hit(it, cx, cy) }
                        callback.onSelect(if (hitLayer != null && hitLayer.id != sel) hitLayer.id else null)
                    }
                } else {
                    selected()?.let { callback.onLayerChanged(it, true) }
                }
                dragging = null; snapX = false; snapY = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); dragging = null; invalidate() }
            MotionEvent.ACTION_POINTER_DOWN -> removeCallbacks(longPress)
        }
        return true
    }

    private fun hit(l: Layer, x: Float, y: Float) = x >= l.x && x <= l.x + l.w && y >= l.y && y <= l.y + l.h
}
