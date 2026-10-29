package io.github.imroxy2222.lsp_hongguoenhance.hook

import android.app.Activity
import android.app.Dialog
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import io.github.imroxy2222.lsp_hongguoenhance.hook.TagDialog.Companion.MAX_SHOWN_TAGS

/**
 * "评论词条"页面 —— 点击分享按钮后弹出的替换页面。
 *
 * 全部 UI 用宿主进程纯代码构建（不依赖模块资源，避免跨包 inflate 问题）：
 * 深色圆角面板 + 词条胶囊列表（按数量降序）+ 继续加载按钮。
 */
class TagDialog(private val activity: Activity, val seriesId: String) : Dialog(activity) {

    private lateinit var stateView: TextView
    private lateinit var tagContainer: LinearLayout
    private lateinit var carousel: TwoPageCarousel
    private lateinit var radarView: RadarChartView
    private lateinit var radarCaption: TextView
    private lateinit var dotTag: View
    private lateinit var dotRadar: View
    private lateinit var pageLabel: TextView
    private lateinit var loadMoreBtn: Button
    private lateinit var retryBtn: Button
    private var lastTags: List<AiClient.Tag> = emptyList()

    companion object {
        /** 词条栏最多显示的词条数（tags 已按数量降序，取前 N 名）。 */
        private const val MAX_SHOWN_TAGS = 10
    }

    override fun show() {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(buildContentView())
        window?.apply {
            setBackgroundDrawable(transparent())
            val dm = context.resources.displayMetrics
            setLayout((dm.widthPixels * 0.88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        setOnDismissListener { Pipeline.onDialogDismissed(this) }
        super.show()
        // 已有缓存则立即渲染
        Pipeline.cacheOf(seriesId)?.let { c ->
            c.tags?.let { onTags(it) }
            onState(c.state, c.stateInfo, c.comments.size, c.hasMore)
        }
    }

    private fun transparent(): GradientDrawable {
        val d = GradientDrawable()
        d.cornerRadius = dp(16f).toFloat()
        d.setColor(Color.parseColor("#F0202024"))
        return d
    }

    private fun buildContentView(): View {
        val pad = dp(20f)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(16f), pad, dp(16f))
        }

        // 标题行
        val titleRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        titleRow.addView(TextView(context).apply {
            text = "评论词条"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(context).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.parseColor("#9AA0A6"))
            setPadding(dp(12f), 0, 0, 0)
            setOnClickListener { dismiss() }
        })
        root.addView(titleRow)

        root.addView(TextView(context).apply {
            id = View.generateViewId()
            stateView = this
            textSize = 12f
            setTextColor(Color.parseColor("#9AA0A6"))
            setPadding(0, dp(6f), 0, 0)
        })

        // 词条容器（方案A：横向条形图，每行 = 词条名 | 条形 | ×N；ScrollView 包裹）
        tagContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12f), 0, dp(4f))
        }
        val tagsPage = ScrollView(context).apply { addView(tagContainer) }

        // 六维评分页：雷达图 + 说明行
        radarView = RadarChartView(context)
        radarCaption = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#9AA0A6"))
            gravity = Gravity.CENTER
            setPadding(dp(8f), 0, dp(8f), 0)
        }
        val radarPage = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(
                radarView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(240f)
                )
            )
            addView(radarCaption)
        }

        // 无限双页轮播：左滑/右滑在「评论词条 ⇄ 六维评分」间循环切换
        carousel = TwoPageCarousel(context).apply {
            addView(
                tagsPage,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                radarPage,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            onPageSettled = { updateIndicator(it) }
        }
        root.addView(
            carousel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300f)
            ).apply { bottomMargin = dp(2f) }
        )

        // 页指示器：两枚圆点 + 当前页名
        dotTag = makeDot()
        dotRadar = makeDot()
        pageLabel = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#9AA0A6"))
        }
        val indicatorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(dotTag, dotParams())
            addView(dotRadar, dotParams().apply { marginStart = dp(6f) })
            addView(
                pageLabel, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(10f) })
        }
        root.addView(indicatorRow)
        // 默认页可配置（设置页「默认显示六维雷达图」开关）
        val defaultPage = try {
            if (ModuleConfig.radarAsDefault()) 1 else 0
        } catch (t: Throwable) {
            0
        }
        carousel.setCurrentPage(defaultPage)
        updateIndicator(defaultPage)

        // 按钮行
        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        loadMoreBtn = Button(context).apply {
            text = "继续加载"
            setOnClickListener {
                Pipeline.loadMore(seriesId)
            }
        }
        retryBtn = Button(context).apply {
            text = "重试"
            visibility = View.GONE
            // 重试 = 用已采集的评论重新调用 AI（不拉新评论）；
            // 拉新评论是"继续加载"按钮的职责
            setOnClickListener { Pipeline.retry(seriesId) }
        }
        btnRow.addView(
            loadMoreBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        btnRow.addView(
            retryBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8f)
            }
        )
        root.addView(btnRow)
        return root
    }

    /**
     * 词条列表渲染（方案A：横向条形图）。
     * 每行：词条名（固定宽，超长跑马灯滚动）+ 条形（长度∝count/maxCount）+ ×N。
     * 仅显示排名前 [MAX_SHOWN_TAGS] 个（tags 已按数量降序），完整列表仍持久化。
     */
    fun onTags(tags: List<AiClient.Tag>) {
        lastTags = tags
        activity.runOnUiThread {
            tagContainer.removeAllViews()
            val shown = tags.take(MAX_SHOWN_TAGS)
            val maxCount = shown.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
            for ((text, count, negative) in shown) {
                tagContainer.addView(
                    makeTagRow(text, count, count.toFloat() / maxCount, negative)
                )
            }
            val totalInfo =
                if (tags.size > shown.size) "（共 ${tags.size} 个，显示前 ${shown.size}）" else " · 共 ${tags.size} 个词条"
            stateView.text = styledStats("${Pipeline.statsLine(seriesId)}$totalInfo")
            // 同步刷新六维雷达：AI 返回的六维评分优先，AI 未返回时回退本地关键词分析
            val cache = Pipeline.cacheOf(seriesId)
            val aiDims = cache?.dims
            if (cache != null && aiDims != null && aiDims.size == 6) {
                radarView.setData(
                    DimensionAnalyzer.Result(
                        DimensionAnalyzer.LABELS, aiDims.toList(),
                        emptyList(), 0f, cache.comments.size
                    )
                )
                radarCaption.text = "AI 六维评分 · 基于 ${cache.comments.size} 条 AI 参考评论"
            } else {
                radarView.setData(
                    if (cache == null) null
                    else DimensionAnalyzer.analyze(
                        cache.comments.toList(), cache.scoreSum, cache.rawCount
                    )
                )
                radarCaption.text =
                    "依据 ${cache?.comments?.size ?: 0} 条 AI 参考评论关键词推算 · 未提及维度取整体平均分"
            }
        }
    }

    fun onState(state: Pipeline.State, info: String, commentsCount: Int, hasMore: Boolean) {
        activity.runOnUiThread {
            stateView.text = styledStats(
                when (state) {
                    Pipeline.State.IDLE -> info.ifEmpty { "就绪" }
                    Pipeline.State.COLLECTING -> "$info（已采集 $commentsCount 条）"
                    Pipeline.State.AI_PENDING -> info
                    Pipeline.State.DONE -> info.ifEmpty { "${Pipeline.statsLine(seriesId)} · 完成" }
                    Pipeline.State.ERROR -> info
                })
            loadMoreBtn.visibility =
                if (state == Pipeline.State.DONE && (hasMore || commentsCount > 0)) View.VISIBLE
                else View.GONE
            retryBtn.visibility = if (state == Pipeline.State.ERROR) View.VISIBLE else View.GONE
        }
    }

    /**
     * 构建一行词条：
     * [词条名 固定宽96dp，超长跑马灯滚动] [条形 weight=1，长度∝fraction] [×N]
     * negative 词条条形使用"灰黑 → 暗红"渐变，普通词条为"灰黑 → 萤绿"。
     */
    private fun makeTagRow(text: String, count: Int, fraction: Float, negative: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 词条名：固定宽度，保证条形空间不被长词条压缩；超长时跑马灯滚动
        val name = TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1 // 无限滚动
            isSelected = true       // 触发跑马灯（短文本不会滚动，只有超长才滚）
        }
        row.addView(
            name,
            LinearLayout.LayoutParams(dp(72f), ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // 条形：自绘，填充宽度由 fraction 决定，无布局时机问题
        val bar = BarView(context, fraction.coerceIn(0f, 1f), negative)
        row.addView(
            bar,
            LinearLayout.LayoutParams(0, dp(18f), 1f).apply { marginStart = dp(8f) }
        )

        // ×N
        val countView = TextView(context).apply {
            this.text = "×$count"
            textSize = 13f
            setTextColor(Color.parseColor("#9AA0A6"))
        }
        row.addView(
            countView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8f) }
        )

        return row.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }
    }

    /** 页指示器：圆点高亮当前页 */
    private fun updateIndicator(page: Int) {
        val radar = page == 1
        val on = Color.parseColor("#B9FF66")
        val off = Color.parseColor("#5A5F66")
        (dotTag.background as? GradientDrawable)?.setColor(if (radar) off else on)
        (dotRadar.background as? GradientDrawable)?.setColor(if (radar) on else off)
    }

    private fun makeDot(): View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#5A5F66"))
        }
    }

    private fun dotParams() = LinearLayout.LayoutParams(dp(6f), dp(6f))

    private fun dp(v: Float): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    /** "平均得分X.X" 中的得分数字着色 + 缩放：≥8 保持原灰色原字号；<8 分越低红越鲜艳，且每降 2 分字号增大一号。 */
    private fun styledStats(text: String): CharSequence {
        val m = SCORE_PATTERN.find(text) ?: return text
        val score = m.groupValues[1].toFloatOrNull() ?: return text
        val range = m.groups[1]!!.range
        return SpannableStringBuilder(text).apply {
            setSpan(
                ForegroundColorSpan(scoreColor(score)),
                range.first, range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                RelativeSizeSpan(scoreSizeFactor(score)),
                range.first, range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** 得分 → 颜色：≥8 原灰色；8 以下在浅红 → 鲜艳红之间线性插值（分越低越鲜艳）。 */
    private fun scoreColor(score: Float): Int {
        if (score >= 8f) return STATE_TEXT_COLOR
        val f = ((8f - score) / 8f).coerceIn(0f, 1f)
        val sr = Color.red(COLOR_RED_SOFT)
        val sg = Color.green(COLOR_RED_SOFT)
        val sb = Color.blue(COLOR_RED_SOFT)
        val er = Color.red(COLOR_RED_VIVID)
        val eg = Color.green(COLOR_RED_VIVID)
        val eb = Color.blue(COLOR_RED_VIVID)
        return Color.rgb(
            (sr + (er - sr) * f).toInt(),
            (sg + (eg - sg) * f).toInt(),
            (sb + (eb - sb) * f).toInt()
        )
    }

    /** 得分 → 字号因子：≥8 原字号；<8 每降 2 分增大一号（+0.15 倍），封顶 1.75 倍防止撑破状态栏。 */
    private fun scoreSizeFactor(score: Float): Float {
        if (score >= 8f) return 1f
        val steps = ((8f - score) / 2f).toInt()  // 6~8 分 0 级、4~6 分 1 级、2~4 分 2 级、0~2 分 3 级
        return (1f + steps * SCORE_SIZE_STEP).coerceAtMost(SCORE_SIZE_MAX)
    }

    private val SCORE_PATTERN = Regex("平均分(\\d+(?:\\.\\d+)?)")  // 兼容"平均得分X.X"与"总评论平均分X.X"
    private val STATE_TEXT_COLOR = Color.parseColor("#9AA0A6")
    private val SCORE_SIZE_STEP = 0.25f  // 每档字号增量（相对原字号倍数）
    private val SCORE_SIZE_MAX = 1.75f   // 字号封顶倍数
    private val COLOR_RED_SOFT = Color.parseColor("#E57373")   // 刚跌破 8 分：浅红
    private val COLOR_RED_VIVID = Color.parseColor("#FF1744")  // 趋近 0 分：鲜艳红
}

/**
 * 横向条形（方案A）：深灰圆角轨道 + 按占比填充的渐变圆角条。
 * 普通词条：灰黑 → 萤绿；负面词条（negative=true）：灰黑 → 暗红。
 * 自绘 View，无布局时机问题。
 */
private class BarView(
    context: android.content.Context,
    private val fraction: Float,
    private val negative: Boolean,
) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E2E34")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radius: Float = dp(9f).toFloat()

    /** 渐变终点色：普通 = 萤绿，负面 = 暗红（起点均为灰黑 #242323）。 */
    private val endColor: Int =
        if (negative) Color.parseColor("#B22222") else Color.parseColor("#B9FF66")

    private fun dp(v: Float): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, trackPaint)
        if (fraction > 0f) {
            // 最低可见宽度：极小占比时也保留一个小圆头
            val fillW = (w * fraction).coerceAtLeast(h)
            fillPaint.shader = LinearGradient(
                0f, 0f, fillW, 0f,
                Color.parseColor("#242323"), endColor,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(0f, 0f, fillW, h, radius, radius, fillPaint)
        }
    }
}
