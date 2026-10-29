package io.github.imroxy2222.lsp_hongguoenhance.hook

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 六维评分雷达图（自绘 View，宿主进程纯代码构建，不依赖模块资源）。
 *
 * - 六角从正上方起顺时针依次：剧情 / 音乐 / 爽感 / 期待 / 画面 / 节奏；
 * - 分值域 0~10，中心为 0，外圈为 10；数据多边形半透明萤绿填充 + 实线描边；
 * - 顶点圆点 + 沿轴外偏的分值数字；外圈固定维度标签；
 * - 数据变化时播放"从中心展开"动画（500ms）。
 */
class RadarChartView(context: Context) : View(context) {

    private var labels: List<String> = emptyList()
    private var scores: FloatArray = FloatArray(0)
    private var progress = 1f
    private var animator: ValueAnimator? = null

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E2E34")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f).toFloat()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40B9FF66")
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B9FF66")
        style = Paint.Style.STROKE
        strokeWidth = dp(2f).toFloat()
        strokeJoin = Paint.Join.ROUND
    }
    private val vertexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B9FF66")
        style = Paint.Style.FILL
    }
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B9FF66")
        textSize = dp(11f).toFloat()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8EAED")
        textSize = dp(13f).toFloat()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA0A6")
        textSize = dp(13f).toFloat()
        textAlign = Paint.Align.CENTER
    }

    /** 更新数据；scores 与上次不同时播放展开动画，相同则静默重绘；null/空 = 暂无数据。 */
    fun setData(result: DimensionAnalyzer.Result?) {
        val s = result?.scores?.toFloatArray()
        if (s == null || s.size != DIM_COUNT) {
            labels = emptyList()
            scores = FloatArray(0)
            animator?.cancel()
            progress = 1f
            invalidate()
            return
        }
        val changed = !s.contentEquals(scores)
        labels = result.labels
        scores = s
        if (changed) startAnim() else invalidate()
    }

    private fun startAnim() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        if (scores.size != DIM_COUNT) {
            canvas.drawText("暂无评论数据", cx, cy + dp(5f), emptyPaint)
            return
        }
        // 留足标签/分值文字空间（上下各 ~30dp）
        val radius = min(width, height) / 2f - dp(34f)

        // 网格：4 层同心六边形
        for (k in 1..4) {
            canvas.drawPath(hexPath(cx, cy, radius * k / 4f), gridPaint)
        }
        // 轴线
        for (i in 0 until DIM_COUNT) {
            val a = Math.toRadians(START_ANGLE + i * STEP_ANGLE)
            canvas.drawLine(
                cx, cy,
                cx + (cos(a) * radius).toFloat(), cy + (sin(a) * radius).toFloat(),
                gridPaint
            )
        }
        // 数据多边形
        val path = Path()
        val px = FloatArray(DIM_COUNT)
        val py = FloatArray(DIM_COUNT)
        for (i in 0 until DIM_COUNT) {
            val a = Math.toRadians(START_ANGLE + i * STEP_ANGLE)
            val r = radius * (scores[i] / 10f) * progress
            px[i] = cx + (cos(a) * r).toFloat()
            py[i] = cy + (sin(a) * r).toFloat()
            if (i == 0) path.moveTo(px[i], py[i]) else path.lineTo(px[i], py[i])
        }
        path.close()
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)

        // 顶点 + 分值（沿轴向外偏移）+ 维度标签（固定在外圈外）
        for (i in 0 until DIM_COUNT) {
            val a = Math.toRadians(START_ANGLE + i * STEP_ANGLE)
            val ux = cos(a).toFloat()
            val uy = sin(a).toFloat()
            canvas.drawCircle(px[i], py[i], dp(3f).toFloat(), vertexPaint)
            val sr = radius * (scores[i] / 10f) * progress + dp(13f)
            canvas.drawText(
                String.format(Locale.US, "%.1f", scores[i]),
                cx + ux * sr, cy + uy * sr + dp(4f),
                scorePaint
            )
            val lr = radius + dp(24f)
            canvas.drawText(
                labels.getOrElse(i) { "" },
                cx + ux * lr, cy + uy * lr + dp(5f),
                labelPaint
            )
        }
    }

    /** 正六边形路径：顶点从正上方开始顺时针。 */
    private fun hexPath(cx: Float, cy: Float, r: Float): Path {
        val p = Path()
        for (i in 0 until DIM_COUNT) {
            val a = Math.toRadians(START_ANGLE + i * STEP_ANGLE)
            val x = cx + (cos(a) * r).toFloat()
            val y = cy + (sin(a) * r).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    private fun dp(v: Float): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val DIM_COUNT = 6
        const val START_ANGLE = -90.0   // 正上方
        const val STEP_ANGLE = 60.0     // 顺时针
    }
}
