package io.github.imroxy2222.lsp_hongguoenhance.hook

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.floor

/**
 * 双页无限轮播容器（自绘宿主进程 UI，不依赖 ViewPager2）。
 *
 * 两个子页（index 0/1），内部维护**无界虚拟页位置** position：
 * 整数 = 稳定停在某一页（mod 2 映射实际子页），小数 = 翻页过渡。
 * 因此可以一直左滑、也可以一直右滑，页与页循环切换，无边界。
 *
 * - 水平拖动跟手（单次手势限一页宽，防止视觉上同页重影）；
 * - 松手按"位移过半（40%）或横向 fling"提交翻页，否则回弹；
 * - 垂直滚动手势（词条页的 ScrollView）不拦截，互不干扰。
 */
class TwoPageCarousel(context: Context) : FrameLayout(context) {

    /** 无界虚拟页位置（浮点）。 */
    private var position = 0f
    private var basePosition = 0f
    private var settledPage = 0
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var tracker: VelocityTracker? = null
    private var animator: ValueAnimator? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** 翻页稳定回调：page = mod(position, 2)（0/1）。 */
    var onPageSettled: ((Int) -> Unit)? = null

    /** 初始定位到第 page 页（0/1），无动画。 */
    fun setCurrentPage(page: Int) {
        cancelAnim()
        basePosition = page.toFloat()
        position = basePosition
        settledPage = page
        layoutPages()
    }

    private fun mod2(i: Int): Int = ((i % 2) + 2) % 2

    /** 子页摆放：取 position 所覆盖的两个整数槽位，各放一个子页（宽度=容器宽）。 */
    private fun layoutPages() {
        val w = width
        if (w <= 0) return
        val f = floor(position.toDouble()).toInt()
        val left0 = Math.round((f - position) * w).toInt()
        val left1 = Math.round((f + 1 - position) * w).toInt()
        val c0 = getChildAt(mod2(f))
        val c1 = getChildAt(mod2(f + 1))
        c0?.layout(left0, 0, left0 + w, height)
        c1?.layout(left1, 0, left1 + w, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // 子页位置完全由 layoutPages 按 position 决定（不用默认叠放）
        layoutPages()
    }

    private fun obtainTracker(ev: MotionEvent) {
        tracker?.recycle()
        tracker = VelocityTracker.obtain()?.also { it.addMovement(ev) }
    }

    private fun cancelAnim() {
        animator?.cancel()
        animator = null
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                cancelAnim()
                obtainTracker(ev)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    dragging = true
                    basePosition = Math.round(position).toFloat()
                    return true
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        tracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                cancelAnim()
                obtainTracker(ev)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = ev.x - downX
                    if (abs(dx) > touchSlop && abs(dx) > abs(ev.y - downY)) {
                        dragging = true
                        basePosition = Math.round(position).toFloat()
                    }
                }
                if (dragging) {
                    // 手指左移（dx<0）→ position 增大 → 下一页从右侧滑入
                    val dx = (ev.x - downX).coerceIn(-width.toFloat(), width.toFloat())
                    position = basePosition - dx / width
                    layoutPages()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) settle()
                dragging = false
                tracker?.recycle()
                tracker = null
            }
        }
        return true
    }

    /** 松手结算：位移过半或 fling 则翻一页，否则回弹。 */
    private fun settle() {
        var vx = 0f
        tracker?.let {
            it.computeCurrentVelocity(1000)
            vx = it.xVelocity
        }
        val delta = position - basePosition
        val fling = abs(vx) > width * 1.5f   // 快速横扫（px/s 超过 1.5 页宽每秒）
        val dir = when {
            fling -> if (vx < 0) 1 else -1
            delta > 0.4f -> 1
            delta < -0.4f -> -1
            else -> 0
        }
        animateTo(basePosition + dir)
    }

    private fun animateTo(target: Float) {
        cancelAnim()
        animator = ValueAnimator.ofFloat(position, target).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                position = it.animatedValue as Float
                layoutPages()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    basePosition = Math.round(target).toFloat()
                    position = basePosition
                    layoutPages()
                    settledPage = mod2(Math.round(target))
                    onPageSettled?.invoke(settledPage)
                }
            })
            start()
        }
    }
}
