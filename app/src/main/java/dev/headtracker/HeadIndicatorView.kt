package dev.headtracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Top-down view: the arc is the (fixed) soundstage, the circle with a nose is your head. */
class HeadIndicatorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var yaw = 0f
        set(v) { if (field != v) { field = v; invalidate() } }
    var tracking = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val density = resources.displayMetrics.density
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = resolveText(); style = Paint.Style.STROKE; strokeWidth = 3 * density }
    private val stage = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D8BE8.toInt(); style = Paint.Style.STROKE; strokeWidth = 6 * density; strokeCap = Paint.Cap.ROUND }
    private val nose = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val arc = RectF()

    private fun resolveText(): Int {
        val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val c = a.getColor(0, 0xFF808080.toInt()); a.recycle(); return c
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height * 0.58f
        val r = min(width, height) * 0.16f
        val sr = r * 2.6f
        arc.set(cx - sr, cy - sr, cx + sr, cy + sr)
        canvas.drawArc(arc, 225f, 90f, false, stage)
        canvas.save()
        canvas.rotate(-yaw, cx, cy)   // + yaw = head turned left = counter-clockwise from above
        nose.color = if (tracking) 0xFF2EAD5B.toInt() else 0xFF9A9A9A.toInt()
        path.reset()
        path.moveTo(cx, cy - r * 1.55f); path.lineTo(cx - r * 0.35f, cy - r * 0.9f); path.lineTo(cx + r * 0.35f, cy - r * 0.9f); path.close()
        canvas.drawPath(path, nose)
        canvas.drawCircle(cx, cy, r, fg)
        canvas.drawCircle(cx - r, cy, r * 0.18f, fg)   // ears
        canvas.drawCircle(cx + r, cy, r * 0.18f, fg)
        canvas.restore()
    }
}
