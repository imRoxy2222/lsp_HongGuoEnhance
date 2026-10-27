package io.github.imroxy2222.lsp_hongguoenhance.hook

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

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
            c.tags?.let { onTags(it, c.comments.size) }
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

        // 词条容器（ScrollView 包裹）
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

    /** 词条列表渲染：按数量降序胶囊。 */
    fun onTags(tags: List<AiClient.Tag>, commentsCount: Int) {
        lastTags = tags
        activity.runOnUiThread {
            tagContainer.removeAllViews()
            for (t in tags) {
                tagContainer.addView(makeTagChip("${t.text} ×${t.count}"))
            }
            stateView.text = "基于 $commentsCount 条剧评 · 共 ${tags.size} 个词条"
        }
    }

    fun onState(state: Pipeline.State, info: String, commentsCount: Int, hasMore: Boolean) {
        activity.runOnUiThread {
            stateView.text = when (state) {
                Pipeline.State.IDLE -> info.ifEmpty { "就绪" }
                Pipeline.State.COLLECTING -> "$info（已采集 $commentsCount 条）"
                Pipeline.State.AI_PENDING -> info
                Pipeline.State.DONE -> info.ifEmpty { "完成 · $commentsCount 条剧评" }
                Pipeline.State.ERROR -> info
            }
            loadMoreBtn.visibility =
                if (state == Pipeline.State.DONE && (hasMore || commentsCount > 0)) View.VISIBLE
                else View.GONE
            retryBtn.visibility = if (state == Pipeline.State.ERROR) View.VISIBLE else View.GONE
        }
    }

    private fun makeTagChip(text: String): TextView {
        val chip = TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(20f).toFloat()
                setColor(Color.parseColor("#3A3A40"))
            }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(8f)
            gravity = Gravity.START
        }
        chip.layoutParams = lp
        return chip
    }

    private fun dp(v: Float): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()
}
