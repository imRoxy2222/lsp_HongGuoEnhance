package io.github.imroxy2222.lsp_hongguoenhance.hook

import android.app.Activity
import android.os.Handler
import android.os.Looper
import io.github.imroxy2222.lsp_hongguoenhance.HookEntry
import io.github.imroxy2222.lsp_hongguoenhance.Logger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * 业务流水线：分享点击 -> 采集剧评 -> AI 总结词条 -> 对话框展示。
 *
 * 评论请求与 AI 请求在单线程池串行执行（避免并发打爆 app RPC），
 * 全部结果经主线程 Handler 回传对话框刷新。
 */
object Pipeline {
    private const val TAG = HookEntry.TAG

    enum class State { IDLE, COLLECTING, AI_PENDING, DONE, ERROR }

    class SeriesCache(
        val seriesId: String,
        var seriesName: String? = null,
    ) {
        val comments = Collections.synchronizedList(ArrayList<String>())
        var cursor: String? = null
        var hasMore: Boolean = true
        var state: State = State.IDLE
        var stateInfo: String = ""
        var tags: List<AiClient.Tag>? = null
        /** 生成当前词条时使用的评论数（用于"继续加载"后判断是否需要重新总结） */
        var tagsFromCount: Int = -1
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val caches = HashMap<String, SeriesCache>()

    /** 当前展示中的对话框（弱引用，随 Activity 销毁自动清空）。 */
    @Volatile
    private var activeDialog: java.lang.ref.WeakReference<TagDialog>? = null

    fun onAppReady(xposed: io.github.libxposed.api.XposedInterface, cl: ClassLoader) {
        ModuleConfig.init(xposed)
        CommentFetcher.onAppReady(cl)
        Logger.i("pipeline ready")
    }

    /** 分享按钮点击（已替换原分享面板）：打开词条页，无缓存时自动开始采集。 */
    fun onShareClicked(activity: Activity, seriesId: String) {
        val cache = caches.getOrPut(seriesId) { SeriesCache(seriesId, SeriesIdHolder.currentName) }
        val dialog = TagDialog(activity, seriesId)
        activeDialog = java.lang.ref.WeakReference(dialog)
        dialog.show()
        if (cache.tags != null) {
            // 已有词条缓存，直接展示
            main.post { dialog.onTags(cache.tags!!, cache.comments.size) }
        } else if (cache.state == State.IDLE) {
            startInitialCollect(seriesId)
        }
    }

    fun onDialogDismissed(dialog: TagDialog) {
        if (activeDialog?.get() === dialog) activeDialog = null
    }

    /** 供对话框初始化时读取缓存。 */
    fun cacheOf(seriesId: String): SeriesCache? = caches[seriesId]

    /** "继续加载"：再拉一批评论，随后重新 AI 总结。 */
    fun loadMore(seriesId: String) {
        val cache = caches.getOrPut(seriesId) { SeriesCache(seriesId, SeriesIdHolder.currentName) }
        if (cache.state == State.COLLECTING || cache.state == State.AI_PENDING) return
        executor.execute {
            try {
                updateState(seriesId, State.COLLECTING, "正在加载下一批剧评...")
                if (cache.cursor == null && cache.comments.isNotEmpty()) {
                    updateState(seriesId, State.DONE, "剧评已全部加载完（${cache.comments.size} 条）")
                    return@execute
                }
                var page = CommentFetcher.fetchPage(seriesId, cache.cursor, ModuleConfig.pageCount())
                cache.comments.addAll(page.comments)
                cache.cursor = page.cursor
                cache.hasMore = page.hasMore
                // 过滤后不足一批时继续补一页（最多再补 2 次）
                var retry = 0
                while (page.comments.size < 5 && page.hasMore && retry < 2) {
                    page = CommentFetcher.fetchPage(seriesId, page.cursor, ModuleConfig.pageCount())
                    cache.comments.addAll(page.comments)
                    cache.cursor = page.cursor
                    cache.hasMore = page.hasMore
                    retry++
                }
                runAi(seriesId)
            } catch (t: Throwable) {
                Logger.e("loadMore failed", t)
                updateState(seriesId, State.ERROR, "加载失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /** 首次采集：连续拉页直至达到目标评论数（默认 50）或没有更多。 */
    private fun startInitialCollect(seriesId: String) {
        executor.execute {
            val cache = caches[seriesId] ?: return@execute
            try {
                updateState(seriesId, State.COLLECTING, "正在采集剧评...")
                val target = ModuleConfig.targetCount()
                var page = CommentFetcher.fetchPage(seriesId, null, ModuleConfig.pageCount())
                cache.comments.addAll(page.comments)
                cache.cursor = page.cursor
                cache.hasMore = page.hasMore
                var rounds = 0
                while (cache.comments.size < target && cache.hasMore && rounds < 10) {
                    notifyComments(seriesId, "正在采集剧评 ${cache.comments.size}/$target...")
                    page = CommentFetcher.fetchPage(seriesId, page.cursor, ModuleConfig.pageCount())
                    cache.comments.addAll(page.comments)
                    cache.cursor = page.cursor
                    cache.hasMore = page.hasMore
                    rounds++
                }
                runAi(seriesId)
            } catch (t: Throwable) {
                Logger.e("initial collect failed", t)
                updateState(seriesId, State.ERROR, "采集失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun runAi(seriesId: String) {
        val cache = caches[seriesId] ?: return
        val comments = cache.comments.toList()
        if (comments.isEmpty()) {
            updateState(seriesId, State.ERROR, "没有符合条件的评论（需字数>20）")
            return
        }
        updateState(seriesId, State.AI_PENDING, "正在调用 AI 分析 ${comments.size} 条剧评...")
        val tags = AiClient.summarize(comments, cache.seriesName)
        cache.tags = tags
        cache.tagsFromCount = comments.size
        main.post {
            updateState(seriesId, State.DONE, "基于 ${comments.size} 条剧评")
            activeDialog?.get()?.takeIf { it.seriesId == seriesId }?.let { d ->
                d.onTags(tags, comments.size)
            }
        }
    }

    private fun updateState(seriesId: String, state: State, info: String) {
        val cache = caches[seriesId] ?: return
        cache.state = state
        cache.stateInfo = info
        main.post {
            activeDialog?.get()?.takeIf { it.seriesId == seriesId }?.let { d ->
                d.onState(state, info, cache.comments.size, cache.hasMore)
            }
        }
    }

    private fun notifyComments(seriesId: String, info: String) {
        main.post {
            activeDialog?.get()?.takeIf { it.seriesId == seriesId }?.let { d ->
                d.onState(State.COLLECTING, info, caches[seriesId]?.comments?.size ?: 0, true)
            }
        }
    }
}
