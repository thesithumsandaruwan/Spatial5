package dev.spatial5.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import dev.spatial5.R

/** iOS-style segmented control. The selection thumb follows the finger and settles with a critically damped spring. */
class SegmentedControl @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var segments: List<String> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    var selected = 0
        private set
    var onChange: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.fill) }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.segment_thumb)
        setShadowLayer(4 * d, 0f, 1.5f * d, 0x1F000000)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.label); textSize = 13.5f * d; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val rect = RectF()
    private var pos = 0f
    private val holder = FloatValueHolder(0f)
    private val spring = SpringAnimation(holder).apply {
        spring = Springs.force(0.3f, 1f)
        addUpdateListener { _, v, _ -> pos = v; invalidate() }
    }
    private var dragging = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)    // for the thumb's soft shadow
        isClickable = true; isFocusable = true
    }

    fun select(index: Int, animate: Boolean) {
        selected = index
        if (!animate || Springs.reducedMotion) { spring.cancel(); pos = index.toFloat(); holder.value = pos; invalidate() }
        else { holder.value = pos; spring.animateToFinalPosition(index.toFloat()) }
        contentDescription = segments.getOrNull(index)
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, w), (34 * d).toInt())

    override fun onDraw(c: Canvas) {
        if (segments.isEmpty()) return
        val r = 9 * d
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, r, r, bg)
        val sw = (width - 4 * d) / segments.size
        val x = 2 * d + pos * sw
        rect.set(x, 2 * d, x + sw, height - 2 * d)
        c.drawRoundRect(rect, r - 2 * d, r - 2 * d, thumb)
        val y = height / 2f - (text.descent() + text.ascent()) / 2
        segments.forEachIndexed { i, s -> c.drawText(s, 2 * d + sw * (i + 0.5f), y, text) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (segments.isEmpty()) return false
        val sw = (width - 4 * d) / segments.size
        val idx = ((e.x - 2 * d) / sw).toInt().coerceIn(0, segments.size - 1)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = idx == selected; parent.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                // Thumb tracks the finger 1:1, then snaps to the nearest segment.
                spring.cancel(); pos = ((e.x - 2 * d) / sw - 0.5f).coerceIn(0f, segments.size - 1f); invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val target = if (dragging) Math.round(pos).coerceIn(0, segments.size - 1) else idx
                if (target != selected) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    select(target, true); onChange?.invoke(target)
                } else select(target, true)
                dragging = false
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; select(selected, true) }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.RadioGroup"
        info.contentDescription = segments.getOrNull(selected)?.let { "$it, ${selected + 1} of ${segments.size}" }
    }
}
