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

        /** 全部采集过的评论文本（含被过滤丢弃的），用于运行期去重。 */
        val rawSeen = HashSet<String>()

        /** 总采集条数（含被过滤丢弃的，去重后）——状态栏的 x。 */
        var rawCount: Int = 0

        /** 已采集评论的评分总和（未评分计 0）——平均得分 z = scoreSum / rawCount。 */
        var scoreSum: Double = 0.0
        var cursor: String? = null
        var hasMore: Boolean = true
        var state: State = State.IDLE
        var stateInfo: String = ""
        var tags: List<AiClient.Tag>? = null

        /** 生成当前词条时使用的评论数（用于"继续加载"后判断是否需要重新总结） */
        var tagsFromCount: Int = -1
    }

    /** 状态栏统计行：总x条评论,AI参考y条,总评论平均分z。 */
    fun statsLine(seriesId: String): String {
        val cache = caches[seriesId] ?: return "暂无数据"
        val avg = if (cache.rawCount > 0) cache.scoreSum / cache.rawCount else 0.0
        return "总${cache.rawCount}条评论,AI参考${cache.comments.size}条," +
                "总评论平均分${String.format(java.util.Locale.US, "%.1f", avg)}"
    }

    /** 累计一页的采集统计（按文本去重；重复评论不计条数、不计分）。 */
    private fun accumulateRaw(cache: SeriesCache, page: CommentFetcher.Page) {
        for (i in page.all.indices) {
            val t = page.all[i]
            if (cache.rawSeen.add(t)) {
                cache.rawCount++
                cache.scoreSum += page.scores.getOrNull(i)?.toDouble() ?: 0.0
            }
        }
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
            // 内存缓存命中，直接展示
            main.post { dialog.onTags(cache.tags!!) }
        } else if (cache.state == State.IDLE) {
            // 内存未命中：先查本地日志库的历史总结（词条持久缓存），
            // app 被杀后重启也能复用，避免重复调用 AI；无历史才发起采集
            executor.execute {
                val saved = try {
                    LogStore.loadLatestSummary(seriesId)
                } catch (t: Throwable) {
                    Logger.w("load saved summary failed: ${t.message}")
                    null
                }
                if (saved != null) {
                    // 一次性恢复会话：历史词条 + 已用评论 + 采集统计（x/z）+ 分页游标
                    val session = try {
                        LogStore.loadSessionState(seriesId)
                    } catch (t: Throwable) {
                        Logger.w("load session state failed: ${t.message}")
                        null
                    }
                    if (session != null) {
                        if (cache.comments.isEmpty()) {
                            cache.comments.addAll(session.usedComments)
                        }
                        cache.rawSeen.addAll(session.rawTexts)
                        if (cache.rawCount == 0) cache.rawCount = session.rawCount
                        if (cache.scoreSum == 0.0) cache.scoreSum = session.scoreSum
                        if (cache.cursor == null) cache.cursor = session.cursor
                    }
                    cache.tags = saved.tags
                    cache.tagsFromCount = saved.baseCount
                    cache.state = State.DONE
                    cache.stateInfo = statsLine(seriesId)
                    LogStore.appendMeta(seriesId, cache.seriesName)
                    Logger.i("summary restored from cache: $seriesId, tags=${saved.tags.size}")
                    main.post {
                        dialog.onTags(saved.tags)
                        dialog.onState(State.DONE, cache.stateInfo, cache.comments.size, cache.hasMore)
                    }
                } else {
                    startInitialCollect(seriesId)
                }
            }
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
        // 恢复会话后 cursor 可能从首页重拉，去重防止评论重复计数
        val existing = HashSet(cache.comments)
        executor.execute {
            try {
                updateState(seriesId, State.COLLECTING, "正在加载下一批剧评...")
                if (cache.cursor == null && cache.comments.isNotEmpty()) {
                    updateState(seriesId, State.DONE, "${statsLine(seriesId)}（剧评已全部加载完）")
                    return@execute
                }
                fun addDedup(texts: List<String>): List<String> {
                    val added = ArrayList<String>(texts.size)
                    for (t in texts) if (existing.add(t)) {
                        cache.comments.add(t)
                        added.add(t)
                    }
                    return added
                }
                // 单批精确截断：本批新增合格评论总数不超过单批加载条数
                val batchCap = ModuleConfig.pageCount()
                var batchCount = 0
                val batchTexts = ArrayList<String>()
                var page = CommentFetcher.fetchPage(seriesId, cache.cursor, batchCap)
                batchTexts.addAll(addDedup(page.comments.take(batchCap - batchCount)))
                batchCount = batchTexts.size
                accumulateRaw(cache, page)
                LogStore.appendComments(seriesId, page.all, page.used, page.scores, page.reasons)
                cache.cursor = page.cursor
                cache.hasMore = page.hasMore
                // 过滤后不足一批时继续补一页（最多再补 2 次）
                var retry = 0
                while (page.comments.size < 5 && page.hasMore && retry < 2 && batchCount < batchCap) {
                    page = CommentFetcher.fetchPage(seriesId, page.cursor, batchCap)
                    val toAdd = page.comments.take(batchCap - batchCount)
                    batchTexts.addAll(addDedup(toAdd))
                    batchCount = batchTexts.size
                    accumulateRaw(cache, page)
                    LogStore.appendComments(seriesId, page.all, page.used, page.scores, page.reasons)
                    cache.cursor = page.cursor
                    cache.hasMore = page.hasMore
                    retry++
                }
                LogStore.saveCursor(seriesId, cache.cursor)
                runAi(seriesId, batchTexts)
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
                accumulateRaw(cache, page)
                LogStore.appendComments(seriesId, page.all, page.used, page.scores, page.reasons)
                cache.cursor = page.cursor
                cache.hasMore = page.hasMore
                var rounds = 0
                while (cache.comments.size < target && cache.hasMore && rounds < 10) {
                    notifyComments(seriesId, "正在采集剧评 ${cache.comments.size}/$target...")
                    page = CommentFetcher.fetchPage(seriesId, page.cursor, ModuleConfig.pageCount())
                    // 只补足缺口，多余合格评论留待"继续加载"（避免 9+2=11 超量发给 AI）
                    val remaining = target - cache.comments.size
                    cache.comments.addAll(page.comments.take(remaining))
                    accumulateRaw(cache, page)
                    LogStore.appendComments(seriesId, page.all, page.used, page.scores, page.reasons)
                    cache.cursor = page.cursor
                    cache.hasMore = page.hasMore
                    rounds++
                }
                LogStore.saveCursor(seriesId, cache.cursor)
                runAi(seriesId)
            } catch (t: Throwable) {
                Logger.e("initial collect failed", t)
                LogStore.appendError(seriesId, "采集失败：${t.message ?: t.javaClass.simpleName}")
                updateState(seriesId, State.ERROR, "采集失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /**
     * AI 总结词条。
     *
     * @param newBatch "继续加载"时本批新增的评论文本；非 null 且已有旧词条时
     * 走**增量模式**：只把新批次发给 AI（旧评论不重复消耗 token），
     * 返回后与旧词条按文本合并 count、重新降序，得到与全量总结等价的累计结果。
     */
    private fun runAi(seriesId: String, newBatch: List<String>? = null) {
        val cache = caches[seriesId] ?: return
        val oldTags = cache.tags
        val incremental = newBatch != null && !oldTags.isNullOrEmpty() && newBatch.isNotEmpty()
        val toSummarize = if (incremental) newBatch else cache.comments.toList()
        if (toSummarize.isEmpty()) {
            if (incremental) {
                // 无新增（如全部与历史重复）：保留旧词条直接结束
                updateState(seriesId, State.DONE, "没有新增评论，保留原词条（${cache.comments.size} 条）")
            } else {
                updateState(seriesId, State.ERROR, "没有符合条件的评论")
            }
            return
        }
        updateState(
            seriesId, State.AI_PENDING,
            if (incremental) "正在调用 AI 分析 ${toSummarize.size} 条新剧评（增量）..."
            else "正在调用 AI 分析 ${toSummarize.size} 条剧评..."
        )
        val result = try {
            val previous = if (incremental) oldTags.map { it.text } else emptyList()
            if (ModuleConfig.aiViaModule()) {
                // 进程桥模式：宿主组包，模块进程发 HTTP（代理只需放行模块应用）
                AiBridge.summarize(toSummarize, cache.seriesName, previous)
            } else {
                AiClient.summarize(
                    toSummarize, cache.seriesName,
                    // 增量模式：把已有词条发给 AI，让其优先沿用同名词条（便于合并）
                    previous
                )
            }
        } catch (t: Throwable) {
            Logger.e("ai summarize failed", t)
            val hint = if (t is AiBridge.BridgeException) "\n提示：${t.message}" else t.message ?: t.javaClass.simpleName
            LogStore.appendError(seriesId, "AI 总结失败：$hint")
            updateState(seriesId, State.ERROR, "AI 总结失败：$hint")
            return
        }
        // 增量模式：与旧词条合并计数（同名词条 count 相加），再排序；
        // 排序规则：count 降序，count 相同时负面词条（negative=true）在前；
        // negative 按或合并——同一词条任意一次被标注为负面即视为负面
        val mergedTags: List<AiClient.Tag> = if (incremental) {
            val merged = LinkedHashMap<String, AiClient.Tag>()
            fun add(t: AiClient.Tag) {
                val e = merged[t.text]
                merged[t.text] = if (e == null) t
                else AiClient.Tag(t.text, e.count + t.count, e.negative || t.negative)
            }
            for (t in oldTags) add(t)
            for (t in result.tags) add(t)
            merged.values.sortedWith(AiClient.Tag.COMPARATOR)
        } else {
            result.tags
        }
        cache.tags = mergedTags
        cache.tagsFromCount = cache.comments.size
        LogStore.appendTags(
            seriesId, mergedTags, cache.comments.size, result.request, result.response
        )
        val tags = mergedTags
        main.post {
            updateState(seriesId, State.DONE, statsLine(seriesId))
            activeDialog?.get()?.takeIf { it.seriesId == seriesId }?.onTags(tags)
        }
    }

    private fun updateState(seriesId: String, state: State, info: String) {
        val cache = caches[seriesId] ?: return
        cache.state = state
        cache.stateInfo = info
        main.post {
            activeDialog?.get()?.takeIf { it.seriesId == seriesId }
                ?.onState(state, info, cache.comments.size, cache.hasMore)
        }
    }

    private fun notifyComments(seriesId: String, info: String) {
        main.post {
            activeDialog?.get()?.takeIf { it.seriesId == seriesId }
                ?.onState(State.COLLECTING, info, caches[seriesId]?.comments?.size ?: 0, true)
        }
    }
}
