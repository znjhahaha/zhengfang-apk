package com.tyust.course.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.doOnPreDraw
import com.tyust.course.R

/** The system splash waits only for the first app frame. Animation is a removable app overlay. */
object StartupLogoAnimation {
    private var played = false
    var contentProgress by androidx.compose.runtime.mutableFloatStateOf(1f)
        private set

    fun install(activity: Activity) {
        val splash = activity.installSplashScreen()
        splash.setOnExitAnimationListener { provider ->
            if (played || activity.isFinishing || activity.isDestroyed) { provider.remove(); return@setOnExitAnimationListener }
            played = true
            val animationsEnabled = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
                else Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            if (!animationsEnabled) { provider.remove(); return@setOnExitAnimationListener }
            val root = activity.window.decorView as? ViewGroup
            if (root == null) { provider.remove(); return@setOnExitAnimationListener }
            showOverlay(activity, root, provider.iconView) { provider.remove() }
        }
    }

    internal fun showOverlay(activity: Activity, root: ViewGroup, icon: View, removeSplash: () -> Unit) {
        // Notification launches on API 31+ may have no splash icon. AndroidX then
        // returns an unattached, empty View whose location is (0, 0), not a logo.
        if (!root.isAttachedToWindow || !icon.isAttachedToWindow) { removeSplash(); return }
        var splashRemoved = false
        val removeOnce = { if (!splashRemoved) { splashRemoved = true; removeSplash() } }
        val overlay = LogoOverlay(activity, removeOnce)
        root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.doOnPreDraw {
            // Read geometry after layout and before removing the system view. Never
            // manufacture bounds for an absent, hidden or detached system icon.
            val bounds = iconBounds(root, icon)
            removeOnce()
            if (bounds == null || activity.isFinishing || activity.isDestroyed) root.removeView(overlay)
            else overlay.start(bounds) { root.removeView(overlay) }
        }
    }

    internal fun iconBounds(root: ViewGroup, icon: View): Rect? {
        if (!root.isAttachedToWindow || !icon.isAttachedToWindow || icon.windowToken != root.windowToken ||
            !root.isLaidOut || !icon.isLaidOut || !icon.isShown || icon.alpha <= 0f ||
            root.width <= 0 || root.height <= 0 || icon.width <= 0 || icon.height <= 0) return null
        val location = IntArray(2)
        val rootLocation = IntArray(2)
        icon.getLocationOnScreen(location)
        root.getLocationOnScreen(rootLocation)
        val left = location[0] - rootLocation[0]
        val top = location[1] - rootLocation[1]
        return Rect(left, top, left + icon.width, top + icon.height).takeIf {
            it.left >= 0 && it.top >= 0 && it.right <= root.width && it.bottom <= root.height
        }
    }

    private class LogoOverlay(context: Context, private val releaseSplash: () -> Unit) : View(context) {
        private var logoBounds: Rect? = null
        private val renderer = StartupLogoRenderer(context)
        private val paint = Paint().apply { color = ContextCompat.getColor(context, R.color.startup_background) }
        private var fraction = 0f
        private var animator: ValueAnimator? = null
        private var finish: (() -> Unit)? = null
        private var finished = false
        private var ending = false

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun start(bounds: Rect, onFinish: () -> Unit) {
            if (finished) { onFinish(); return }
            logoBounds = Rect(bounds)
            finish = onFinish
            contentProgress = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = StartupChoreography.DurationMillis
                interpolator = LinearInterpolator()
                addUpdateListener {
                    fraction = it.animatedValue as Float
                    contentProgress = StartupChoreography.content(fraction * StartupChoreography.DurationMillis)
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { complete() }
                })
                start()
            }
        }

        private fun complete() {
            if (finished || ending) return
            ending = true
            fraction = 1f
            contentProgress = 1f
            invalidate()
        }

        private fun releaseAfterDraw() {
            if (finished) return
            finished = true
            finish?.invoke()
            finish = null
        }

        override fun onDraw(canvas: Canvas) {
            val bounds = logoBounds ?: return
            val milliseconds = fraction * StartupChoreography.DurationMillis
            paint.alpha = ((1f - StartupChoreography.content(milliseconds)) * 255).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            renderer.draw(canvas, bounds, milliseconds)
            // The transparent terminal frame is submitted before removing this view.
            // This avoids combining the final content update and decor relayout in one frame.
            if (ending) post { releaseAfterDraw() }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { animator?.cancel(); complete(); releaseAfterDraw() }
            return false
        }

        override fun onDetachedFromWindow() {
            releaseSplash()
            contentProgress = 1f
            finished = true
            finish = null
            animator?.removeAllListeners()
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }
    }
}
