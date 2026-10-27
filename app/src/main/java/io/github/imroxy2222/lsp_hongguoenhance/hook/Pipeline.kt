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
        // 尽早拉起日志存储/服务（Application 可能尚未创建；失败则由下方 bootstrap 兜底）
        runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context
            if (app != null) {
                Thread {
                    runCatching { LogStore.init(app) }
                    runCatching { LogServer.ensureStarted(app) }
                }.start()
            }
        }
        Logger.i("pipeline ready")
    }

    /**
     * 日志服务确定性拉起：hook Instrumentation.callApplicationOnCreate，
     * 在红果 Application 创建的第一时间初始化存储并启动服务。
     *
     * 此前服务依赖"首次点击分享"拉起，若用户装完模块没点过分享，
     * 查看日志页会全部"连接被拒绝"——本 hook 消除该依赖。
     * （onPackageReady 时 currentApplication() 也可能为 null，此处为兜底。）
     */
    fun installLogBootstrap(xposed: io.github.libxposed.api.XposedInterface, cl: ClassLoader) {
        try {
            val m = android.app.Instrumentation::class.java.getDeclaredMethod(
                "callApplicationOnCreate", android.app.Application::class.java
            )
            xposed.hook(m).intercept { chain ->
                runCatching {
                    val app = chain.getArg(0) as? android.app.Application
                    if (app != null) {
                        Thread {
                            runCatching { LogStore.init(app) }
                            runCatching { LogServer.ensureStarted(app) }
                        }.start()
                    }
                }
                chain.proceed()
            }
            Logger.i("hook instrumentation log bootstrap ok")
        } catch (t: Throwable) {
            Logger.e("hook instrumentation log bootstrap failed", t)
        }
    }

    /** 分享按钮点击（已替换原分享面板）：打开词条页，无缓存时自动开始采集。 */
    fun onShareClicked(activity: Activity, seriesId: String) {
        // 日志存储/服务兜底初始化（幂等；放到工作线程避免主线程 DB/文件 IO）
        val appContext = activity.applicationContext
        // 剧名按 seriesId 精确匹配（未打开过该剧剧评页时为 null，仅记 id）
        val cache = caches.getOrPut(seriesId) { SeriesCache(seriesId, SeriesIdHolder.nameOf(seriesId)) }
        executor.execute {
            runCatching { LogStore.init(appContext) }
            runCatching { LogServer.ensureStarted(appContext) }
            LogStore.appendMeta(seriesId, cache.seriesName)
        }
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

    /**
     * 剧名采集回填：用户打开某剧剧评页时拿到 (seriesId, seriesName)，
     * 若该剧行已存在于日志库则更新剧名——即使之前分享时没有剧名，
     * 事后打开一次剧评页即可补全，无需重新分享。
     */
    fun onSeriesNameCaptured(seriesId: String?, name: String?) {
        if (seriesId.isNullOrEmpty() || name.isNullOrEmpty()) return
        // getSeriesId 播放期间高频触发：名字没变化就不写库
        if (SeriesIdHolder.nameOf(seriesId) == name) return
        executor.execute {
            runCatching { LogStore.updateSeriesName(seriesId, name) }
        }
    }

    fun onDialogDismissed(dialog: TagDialog) {
        if (activeDialog?.get() === dialog) activeDialog = null
    }

    /** 供对话框初始化时读取缓存。 */
    fun cacheOf(seriesId: String): SeriesCache? = caches[seriesId]

    /** "继续加载"：再拉一批评论，随后重新 AI 总结。 */
    fun loadMore(seriesId: String) {
        val cache = caches.getOrPut(seriesId) { SeriesCache(seriesId, SeriesIdHolder.nameOf(seriesId)) }
        if (cache.state == State.COLLECTING || cache.state == State.AI_PENDING) return
        executor.execute {
            try {
                updateState(seriesId, State.COLLECTING, "正在加载下一批剧评...")
                if (cache.cursor == null && cache.comments.isNotEmpty()) {
                    updateState(seriesId, State.DONE, "剧评已全部加载完（${cache.comments.size} 条）")
                    return@execute
                }
                // 单批精确截断：本批新增合格评论总数不超过单批加载条数
                val batchCap = ModuleConfig.pageCount()
                var batchCount = 0
                var page = CommentFetcher.fetchPage(seriesId, cache.cursor, batchCap)
                val first = page.comments.take(batchCap - batchCount)
                batchCount += first.size
                cache.comments.addAll(first)
                LogStore.appendComments(seriesId, page.all, page.used)
                cache.cursor = page.cursor
                cache.hasMore = page.hasMore
                // 过滤后不足一批时继续补一页（最多再补 2 次）
                var retry = 0
                while (page.comments.size < 5 && page.hasMore && retry < 2 && batchCount < batchCap) {
                    page = CommentFetcher.fetchPage(seriesId, page.cursor, batchCap)
                    val toAdd = page.comments.take(batchCap - batchCount)
                    batchCount += toAdd.size
                    cache.comments.addAll(toAdd)
                    LogStore.appendComments(seriesId, page.all, page.used)
                    cache.cursor = page.cursor
                    cache.hasMore = page.hasMore
                    retry++
                }
                runAi(seriesId)
            } catch (t: Throwable) {
                Logger.e("loadMore failed", t)
                LogStore.appendError(seriesId, "加载失败：${t.message ?: t.javaClass.simpleName}")
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
                // 精确截断：首次采集 target 是"恰好 N 条"语义，
                // 页内合格数可能超过剩余缺口（如 target=10、页 count=30），按缺口截取
                cache.comments.addAll(page.comments.take(target))
                LogStore.appendComments(seriesId, page.all, page.used)
                cache.cursor = page.cursor
                cache.hasMore = page.hasMore
                var rounds = 0
                while (cache.comments.size < target && cache.hasMore && rounds < 10) {
                    notifyComments(seriesId, "正在采集剧评 ${cache.comments.size}/$target...")
                    page = CommentFetcher.fetchPage(seriesId, page.cursor, ModuleConfig.pageCount())
                    // 只补足缺口，多余合格评论留待"继续加载"（避免 9+2=11 超量发给 AI）
                    val remaining = target - cache.comments.size
                    cache.comments.addAll(page.comments.take(remaining))
                    LogStore.appendComments(seriesId, page.all, page.used)
                    cache.cursor = page.cursor
                    cache.hasMore = page.hasMore
                    rounds++
                }
                runAi(seriesId)
            } catch (t: Throwable) {
                Logger.e("initial collect failed", t)
                LogStore.appendError(seriesId, "采集失败：${t.message ?: t.javaClass.simpleName}")
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
        val result = try {
            AiClient.summarize(comments, cache.seriesName)
        } catch (t: Throwable) {
            Logger.e("ai summarize failed", t)
            LogStore.appendError(seriesId, "AI 总结失败：${t.message ?: t.javaClass.simpleName}")
            updateState(seriesId, State.ERROR, "AI 总结失败：${t.message ?: t.javaClass.simpleName}")
            return
        }
        cache.tags = result.tags
        cache.tagsFromCount = comments.size
        LogStore.appendTags(seriesId, result.tags, comments.size, result.request, result.response)
        val tags = result.tags
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
