package io.github.imroxy2222.lsp_hongguoenhance.hook

import android.app.Activity
import android.app.Dialog
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
        root.addView(
            ScrollView(context).apply { addView(tagContainer) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        )

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
            setOnClickListener { Pipeline.loadMore(seriesId) }
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
            for (t in shown) {
                tagContainer.addView(makeTagRow(t.text, t.count, t.count.toFloat() / maxCount))
            }
            val totalInfo =
                if (tags.size > shown.size) "（共 ${tags.size} 个，显示前 ${shown.size}）" else " · 共 ${tags.size} 个词条"
            stateView.text = "${Pipeline.statsLine(seriesId)}$totalInfo"
        }
    }

    fun onState(state: Pipeline.State, info: String, commentsCount: Int, hasMore: Boolean) {
        activity.runOnUiThread {
            stateView.text = when (state) {
                Pipeline.State.IDLE -> info.ifEmpty { "就绪" }
                Pipeline.State.COLLECTING -> "$info（已采集 $commentsCount 条）"
                Pipeline.State.AI_PENDING -> info
                Pipeline.State.DONE -> info.ifEmpty { "${Pipeline.statsLine(seriesId)} · 完成" }
                Pipeline.State.ERROR -> info
            }
            loadMoreBtn.visibility =
                if (state == Pipeline.State.DONE && (hasMore || commentsCount > 0)) View.VISIBLE
                else View.GONE
            retryBtn.visibility = if (state == Pipeline.State.ERROR) View.VISIBLE else View.GONE
        }
    }

    /**
     * 构建一行词条：
     * [词条名 固定宽96dp，超长跑马灯滚动] [条形 weight=1，长度∝fraction] [×N]
     */
    private fun makeTagRow(text: String, count: Int, fraction: Float): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 词条名：固定宽度，保证条形空间不被长词条压缩；超长时跑马灯滚动
        val name = TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1 // 无限滚动
            isSelected = true       // 触发跑马灯（短文本不会滚动，只有超长才滚）
        }
        row.addView(
            name,
            LinearLayout.LayoutParams(dp(72f), ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // 条形：自绘，填充宽度由 fraction 决定，无布局时机问题
        val bar = BarView(context, fraction.coerceIn(0f, 1f))
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

    private fun dp(v: Float): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()
}

/**
 * 横向条形（方案A）：深灰圆角轨道 + 按占比填充的青色圆角条。
 * 自绘 View，无布局时机问题。
 */
private class BarView(context: android.content.Context, private val fraction: Float) :
    View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E2E34")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radius: Float = dp(9f).toFloat()

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
                Color.parseColor("#242323"), Color.parseColor("#B9FF66"),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(0f, 0f, fillW, h, radius, radius, fillPaint)
        }
    }
}
