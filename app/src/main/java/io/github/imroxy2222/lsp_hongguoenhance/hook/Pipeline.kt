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

    /** 桥回退 Toast 的节流间隔：连续总结（增量合并等）时避免刷屏。 */
    private const val BRIDGE_TOAST_INTERVAL_MS = 15_000L
    private val lastBridgeToastAt = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * 宿主进程内弹 Toast：桥回退时提醒用户"模块进程未运行，AI 网络已回退到红果进程"。
     * 主线程执行；15s 节流；所有异常静默——提醒绝不影响 AI 主流程。
     */
    private fun toastBridgeFallback() {
        runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context
                ?: return
            val now = System.currentTimeMillis()
            val last = lastBridgeToastAt.get()
            if (now - last < BRIDGE_TOAST_INTERVAL_MS) return
            if (!lastBridgeToastAt.compareAndSet(last, now)) return
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    android.widget.Toast.makeText(
                        app, "模块被kill,AI通信回退",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

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

        /** 六维评分（剧情/音乐/爽感/期待/画面/节奏，各 1~10）；AI 未返回且无本地回退时为 null。 */
        var dims: FloatArray? = null

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
                // AI 走模块进程时绑定保活锚点（幂等、失败静默，见 BridgeKeepAlive）
                if (runCatching { ModuleConfig.aiViaModule() }.getOrDefault(true)) {
                    BridgeKeepAlive.ensureBound(app)
                }
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
                        // Application 创建的第一时间建立进程桥保活绑定
                        // （早于 onAppReady；ModuleConfig 未初始化时按默认开启处理）
                        runCatching { ModuleConfig.init(xposed) }
                        if (runCatching { ModuleConfig.aiViaModule() }.getOrDefault(true)) {
                            BridgeKeepAlive.ensureBound(app)
                        }
                    }
                }
                chain.proceed()
            }
            Logger.i("hook instrumentation log bootstrap ok")
        } catch (t: Throwable) {
            Logger.e("hook instrumentation log bootstrap failed", t)
        }
    }

    /** 分享按钮点击（已替换原分享面板）。 */
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
        when {
            // 已有词条 / 正在采集总结中 / 上次失败待重试：直接弹窗（进行中则挂到现有进度上）
            cache.tags != null || cache.state != State.IDLE -> presentDialog(activity, seriesId)
            // 首次总结：不弹窗等待——Toast 提示后后台执行，成功/失败后自动弹出词条页
            else -> {
                toast(activity, "开始总结")
                pendingShow = java.lang.ref.WeakReference(activity)
                pendingShowSeriesId = seriesId
                executor.execute {
                    // 先查本地日志库的历史总结（词条持久缓存），app 被杀后重启也能复用，
                    // 避免重复调用 AI；无历史才发起采集
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
                        cache.dims = saved.dims
                        LogStore.appendMeta(seriesId, cache.seriesName)
                        Logger.i("summary restored from cache: $seriesId, tags=${saved.tags.size}")
                        // updateState 的终态分支会自动弹出词条页
                        updateState(seriesId, State.DONE, statsLine(seriesId))
                    } else {
                        startInitialCollect(seriesId)
                    }
                }
            }
        }
    }

    /** 首次总结的"延迟弹窗"宿主 Activity：到达终态（成功/失败）时自动弹出词条页。 */
    @Volatile
    private var pendingShow: java.lang.ref.WeakReference<Activity>? = null

    @Volatile
    private var pendingShowSeriesId: String? = null

    /** 在宿主 Activity 上弹出词条页（TagDialog.show 内部自动同步当前缓存状态）。 */
    private fun presentDialog(activity: Activity, seriesId: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = TagDialog(activity, seriesId)
        activeDialog = java.lang.ref.WeakReference(dialog)
        dialog.show()
    }

    /** 宿主进程 Toast（主线程派发 + 容错）。 */
    private fun toast(activity: Activity, text: String) {
        main.post {
            runCatching {
                android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_SHORT).show()
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

    /**
     * "重试"（ERROR 状态下的重试按钮）：**重新调用 AI**，使用已采集的评论，
     * 不拉取新评论——与"继续加载"（拉新批 + 增量总结）语义不同。
     *
     * - AI 总结失败：把当前 cache.comments 全量重发给 AI（全量模式，非增量）；
     * - 首次采集就失败（一条评论都没有）：退回重新走采集流程。
     *
     * 注意：重试按钮在**主线程**触发，而 runAi 是同步网络请求——
     * 必须整体跳到工作线程执行，否则抛 NetworkOnMainThreadException
     * （且重试路径必然有已采集评论，一踩就稳定复现）。
     */
    fun retry(seriesId: String) {
        executor.execute {
            val cache = caches.getOrPut(seriesId) { SeriesCache(seriesId, SeriesIdHolder.nameOf(seriesId)) }
            if (cache.state == State.COLLECTING || cache.state == State.AI_PENDING) return@execute
            if (cache.comments.isEmpty()) {
                startInitialCollect(seriesId)
            } else {
                runAi(seriesId)
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
                // 跨页/页内按文本去重：分页接口可能返回重叠评论（热评/置顶跨窗口
                // 重复出现等），不去重会让 cache.comments 混入重复文本——
                // rawCount 在 accumulateRaw 中是去重的，"AI 参考"条数会反而大于"总"条数
                val seen = HashSet(cache.comments)
                fun addComments(texts: List<String>) {
                    for (t in texts) if (seen.add(t)) cache.comments.add(t)
                }
                var page = CommentFetcher.fetchPage(seriesId, null, ModuleConfig.pageCount())
                // 精确截断：首次采集 target 是"恰好 N 条"语义，
                // 页内合格数可能超过剩余缺口（如 target=10、页 count=30），按缺口截取
                addComments(page.comments.take(target))
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
                    addComments(page.comments.take(remaining))
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
        // 防御护栏：runAi 内部是同步 HTTP 请求，绝不允许在主线程执行；
        // 误用时自动改派到工作线程（根治 NetworkOnMainThreadException 这一类问题）
        if (Looper.myLooper() == Looper.getMainLooper()) {
            executor.execute { runAi(seriesId, newBatch) }
            return
        }
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
                // 进程桥模式：宿主组包，模块进程发 HTTP（代理只需放行模块应用）。
                // 桥不可用（模块 App 未安装/被强停/免 root Embedded 模式无模块 App）
                // 时自动回退宿主直连，不作为错误终止流程。
                try {
                    AiBridge.summarize(toSummarize, cache.seriesName, previous)
                } catch (e: AiBridge.BridgeException) {
                    Logger.w("进程桥不可用，回退宿主进程直连: ${e.message}")
                    toastBridgeFallback()
                    updateState(
                        seriesId, State.AI_PENDING,
                        "进程桥不可用，改为宿主进程直连..." +
                            "（多为模块被划掉后台/强停所致：ROM 禁止拉起 stopped 应用，" +
                            "打开一次模块设置页即可恢复桥接）"
                    )
                    AiClient.summarize(toSummarize, cache.seriesName, previous)
                }
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
                if (e == null) {
                    merged[t.text] = t
                } else {
                    // negative 按或合并（优先级最高）；仅当合并后仍非负面时保留中性标记
                    val neg = e.negative || t.negative
                    merged[t.text] = AiClient.Tag(
                        t.text, e.count + t.count, neg, !neg && (e.neutral || t.neutral)
                    )
                }
            }
            for (t in oldTags) add(t)
            for (t in result.tags) add(t)
            merged.values.sortedWith(AiClient.Tag.COMPARATOR)
        } else {
            result.tags
        }
        cache.tags = mergedTags
        cache.tagsFromCount = cache.comments.size
        // 六维评分：AI 返回优先；未返回或增量合并不足时回退本地关键词分析。
        // AI 的 dims 只反映本次发送的批次，增量模式下按评论数加权合并旧值。
        cache.dims = when {
            !incremental ->
                result.dims ?: localDims(cache)
            else -> mergeDims(
                cache.dims, cache.tagsFromCount.coerceAtLeast(1),
                result.dims, toSummarize.size.coerceAtLeast(1)
            ) ?: localDims(cache)
        }
        LogStore.appendTags(
            seriesId, mergedTags, cache.comments.size, result.request, result.response, cache.dims
        )
        val tags = mergedTags
        main.post {
            updateState(seriesId, State.DONE, statsLine(seriesId))
            activeDialog?.get()?.takeIf { it.seriesId == seriesId }?.onTags(tags)
        }
    }

    /** 本地关键词回退：AI 未返回六维评分时用已采集评论推算。 */
    private fun localDims(cache: SeriesCache): FloatArray =
        DimensionAnalyzer.analyze(cache.comments.toList(), cache.scoreSum, cache.rawCount)
            .scores.toFloatArray()

    /** 六维评分加权合并（按各自覆盖的评论数加权）；任一为空返回另一个。 */
    private fun mergeDims(a: FloatArray?, wa: Int, b: FloatArray?, wb: Int): FloatArray? {
        if (a == null || a.size != 6) return b
        if (b == null || b.size != 6) return a
        return FloatArray(6) { i -> (a[i] * wa + b[i] * wb) / (wa + wb) }
    }

    private fun updateState(seriesId: String, state: State, info: String) {
        val cache = caches[seriesId] ?: return
        cache.state = state
        cache.stateInfo = info
        main.post {
            activeDialog?.get()?.takeIf { it.seriesId == seriesId }
                ?.onState(state, info, cache.comments.size, cache.hasMore)
            // 首次总结不弹窗：终态（成功 DONE / 失败 ERROR）到达时自动弹出词条页；
            // 消费一次性 pending，Activity 已销毁则静默放弃（结果已落库可随时再点开）
            if ((state == State.DONE || state == State.ERROR) && pendingShowSeriesId == seriesId) {
                val act = pendingShow?.get()
                pendingShow = null
                pendingShowSeriesId = null
                if (act != null) presentDialog(act, seriesId)
            }
        }
    }

    private fun notifyComments(seriesId: String, info: String) {
        main.post {
            activeDialog?.get()?.takeIf { it.seriesId == seriesId }
                ?.onState(State.COLLECTING, info, caches[seriesId]?.comments?.size ?: 0, true)
        }
    }
}
