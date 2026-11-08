package dev.headtracker.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.PI

/** Apple-style springs: described by damping ratio + response (seconds), always interruptible. */
object Springs {
    /** Animations disabled / reduced motion in system settings. */
    val reducedMotion get() = !ValueAnimator.areAnimatorsEnabled()

    fun force(response: Float, damping: Float = 1f): SpringForce {
        val k = (2 * PI / response).let { it * it }.toFloat()
        return SpringForce().setStiffness(k).setDampingRatio(damping)
    }

    /** Spring [prop] of [view] to [target], starting from its current (on-screen) value. */
    fun to(view: View, prop: DynamicAnimation.ViewProperty, target: Float, response: Float = 0.35f, damping: Float = 1f) {
        val key = prop.hashCode()
        if (reducedMotion) { (view.getTag(key) as? SpringAnimation)?.cancel(); prop.setValue(view, target); return }
        val anim = (view.getTag(key) as? SpringAnimation) ?: SpringAnimation(view, prop).also { view.setTag(key, it) }
        anim.spring = force(response, damping).setFinalPosition(target)
        anim.start()
    }

    /** Press feedback: scales down instantly on touch-down, springs back on release. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(view: View, scale: Float = 0.96f) {
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { to(v, DynamicAnimation.SCALE_X, scale, 0.12f); to(v, DynamicAnimation.SCALE_Y, scale, 0.12f) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    to(v, DynamicAnimation.SCALE_X, 1f, 0.35f, 0.7f); to(v, DynamicAnimation.SCALE_Y, 1f, 0.35f, 0.7f)
                }
            }
            false
        }
    }
}
