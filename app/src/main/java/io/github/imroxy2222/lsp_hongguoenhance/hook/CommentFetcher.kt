package io.github.imroxy2222.lsp_hongguoenhance.hook

import io.github.imroxy2222.lsp_hongguoenhance.HookEntry
import io.github.imroxy2222.lsp_hongguoenhance.Logger

/**
 * 剧评采集器。
 *
 * 不自己实现网络请求（签名/公共参数复杂且易失效），而是反射调用
 * 红果短剧内部方法，让 app 自身 RPC 框架完成请求。
 *
 * 双路径设计：
 * 1. 优先调用 app 剧评页使用的包装层（混淆类，包
 *    com.dragon.community.common.contentlist.content.comment 下的 h.e）——
 *    它会自动填充 appID / complianceStatus，并在服务端返回业务错误时抛出
 *    带错误码的异常；包装类名可能随混淆映射变化，运行时按签名自动搜索。
 * 2. 兜底直接调用 CommentApiService.getCommentListRxJava(request)（保留名静态方法），
 *    并尽力通过 app 服务定位器反射填充 appID / complianceStatus。
 *
 * 请求参数复刻 app 剧评页（uv1.m）的构造方式：
 *   groupID   = seriesId（剧 id，即 URL 路径中的 group_id）
 *   sort      = UgcSortEnum.SmartHot（智能热评）
 *   count     = 每页条数（app 默认 10）
 *   cursor    = 分页游标（响应 common_list_info.cursor）
 *
 * 数据路径：response.data -> CommentListData{ dataList: List<UgcMixData>,
 *   commonListInfo: { cursor, hasMore, total } }
 *   UgcMixData.reply -> UgcReply{ common: CommentCommon{ content: CommentContent{ text } } }
 */
object CommentFetcher {
    private const val TAG = HookEntry.TAG

    /** 包装层所在包（混淆类名，扫描 a-z 定位）。 */
    private const val WRAPPER_PKG = "com.dragon.community.common.contentlist.content.comment"

    /** 单次请求超时（毫秒）：超时后进入 fallback 路径或报错。 */
    private const val REQUEST_TIMEOUT_MS = 15_000L

    /** 评论文本最小长度（字数），达到即通过（>=）。 */
    private const val MIN_TEXT_LENGTH = 15

    /** 排除型过滤：含"第X季"（多为剧名/续季宣传语）或"更新"（多为催更/版本讨论）的评论，对剧情判断无价值。 */
    private val EXCLUDE_PATTERNS = listOf(
        Regex("第.+季"),
        Regex("更新"),
    )

    data class Page(
        val comments: List<String>,   // 本页合格（长度过滤后）评论文本，即交给 AI 的部分
        val all: List<String>,        // 本页抓到的全部评论文本（含被过滤丢弃的）
        val used: List<Boolean>,      // 与 all 一一对应：true = 通过过滤、进入 AI 输入
        val scores: List<Float?>,     // 与 all 一一对应：评论评分（reply.expand.score，未评分为 null）
        val totalFetched: Int,        // 本页原始条数
        val cursor: String?,          // 下一页游标
        val hasMore: Boolean
    )

    /** app classloader，onPackageLoaded 时注入。 */
    @Volatile
    private var appClassLoader: ClassLoader? = null

    /** 缓存定位到的包装方法（static (GetCommentListRequest)->Single）。 */
    @Volatile
    private var wrapperMethod: java.lang.reflect.Method? = null

    fun onAppReady(cl: ClassLoader) {
        appClassLoader = cl
    }

    /**
     * 拉取一页剧评。回调在调用线程同步完成（内部阻塞等待），
     * 由 [Pipeline] 统一调度到工作线程执行。
     */
    fun fetchPage(seriesId: String, cursor: String?, count: Int): Page {
        val cl = appClassLoader
            ?: throw IllegalStateException("app classloader not ready")
        val request = buildRequest(cl, seriesId, cursor, count)
        Logger.d("fetchPage: seriesId=$seriesId, cursor=$cursor, count=$count")

        val response: Any = try {
            invokeViaWrapper(cl, request)
        } catch (t: Throwable) {
            Logger.w("wrapper path failed (${t.javaClass.simpleName}: ${t.message}), fallback to direct rpc")
            invokeDirect(cl, request)
        }
        return parsePage(response)
    }

    // ------------------------------------------------------------------
    // 请求构造
    // ------------------------------------------------------------------

    private fun buildRequest(
        cl: ClassLoader, seriesId: String, cursor: String?, count: Int,
    ): Any {
        val clzReq = cl.loadClass("com.dragon.read.saas.ugc.model.GetCommentListRequest")
        val clzCommentBusinessParam =
            cl.loadClass("com.dragon.read.saas.ugc.model.CommentBusinessParam")

        val request = clzReq.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

        setField(clzReq, request, "groupID", seriesId)
        setField(clzReq, request, "count", count)
        setField(clzReq, request, "cursor", cursor)
        setField(clzReq, request, "sort", enumOf(cl, "com.dragon.read.saas.ugc.model.UgcSortEnum", "SmartHot"))
        setField(clzReq, request, "groupType", enumOf(cl, "com.dragon.read.saas.ugc.model.UgcRelativeType", "Book"))
        setField(
            clzReq,
            request,
            "commentType",
            enumOf(cl, "com.dragon.read.saas.ugc.model.UgcCommentGroupTypeOutter", "Book")
        )
        setField(
            clzReq,
            request,
            "commentSource",
            enumOf(cl, "com.dragon.read.saas.ugc.model.UgcCommentSourceEnum", "NovelBookComment")
        )
        setField(
            clzReq,
            request,
            "serverChannel",
            enumOf(cl, "com.dragon.read.saas.ugc.model.UgcCommentChannelEnum", "NovelPlayletCommentInnerList")
        )

        val bizParam = clzCommentBusinessParam.getDeclaredConstructor()
            .apply { isAccessible = true }.newInstance()
        setField(clzCommentBusinessParam, bizParam, "needCount", false)
        setField(clzCommentBusinessParam, bizParam, "bookID", seriesId)
        setField(clzReq, request, "businessParam", bizParam)

        return request
    }

    // ------------------------------------------------------------------
    // 路径 1：app 包装层 e(request) -> Single（自动补公共字段 + 错误校验）
    // ------------------------------------------------------------------

    private fun invokeViaWrapper(cl: ClassLoader, request: Any): Any {
        val method = wrapperMethod ?: findWrapperMethod(cl)?.also {
            wrapperMethod = it
            Logger.i("wrapper method located: ${it.declaringClass.name}.${it.name}")
        } ?: throw NoSuchMethodException("no wrapper method found in $WRAPPER_PKG")

        Logger.d("invoking wrapper method...")
        val single = method.invoke(null, request)
        // Single<CommentListData>.timeout(15s).blockingGet()
        // 注意：h.e 返回的 Single 带 observeOn(mainThread)，响应需主线程派发，
        // 主线程繁忙时 blockingGet 可能长时间挂起 —— 必须加超时，超时后走直连路径。
        val singleClz = single.javaClass
        val timed = singleClz
            .getMethod("timeout", Long::class.javaPrimitiveType, java.util.concurrent.TimeUnit::class.java)
            .invoke(single, REQUEST_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        val result = timed.javaClass.getMethod("blockingGet").invoke(timed)
        Logger.d("wrapper response received")
        return result
    }

    /** 在包装包下扫描 a-z 类，找 static (GetCommentListRequest)->Single 的方法。 */
    private fun findWrapperMethod(cl: ClassLoader): java.lang.reflect.Method? {
        val clzReq = cl.loadClass("com.dragon.read.saas.ugc.model.GetCommentListRequest")
        val clzSingle = try {
            cl.loadClass("io.reactivex.Single")
        } catch (t: Throwable) {
            null
        }
        for (letter in 'a'..'z') {
            try {
                val clazz = cl.loadClass("$WRAPPER_PKG.$letter")
                for (m in clazz.declaredMethods) {
                    if (!java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
                    val ps = m.parameterTypes
                    if (ps.size == 1 && ps[0] == clzReq &&
                        (clzSingle == null || m.returnType == clzSingle)
                    ) {
                        m.isAccessible = true
                        return m
                    }
                }
            } catch (ignored: ClassNotFoundException) {
                // 继续尝试下一个字母
            }
        }
        Logger.w("findWrapperMethod: no candidate in $WRAPPER_PKG (a-z scanned)")
        return null
    }

    // ------------------------------------------------------------------
    // 路径 2：CommentApiService.getCommentListRxJava(request) 直接调用
    // ------------------------------------------------------------------

    private fun invokeDirect(cl: ClassLoader, request: Any): Any {
        Logger.d("invoking direct rpc...")
        fillCommonFields(cl, request)

        val clzService = cl.loadClass("com.dragon.read.saas.ugc.rpc.CommentApiService")
        val clzObservable = cl.loadClass("io.reactivex.Observable")
        val method = clzService.getDeclaredMethod(
            "getCommentListRxJava",
            cl.loadClass("com.dragon.read.saas.ugc.model.GetCommentListRequest")
        )
        val observable = method.invoke(null, request)

        // 阻塞式取回响应：Observable.timeout(15s).blockingSingle()
        // 直连路径无 observeOn(main)，响应在当前线程直接返回，一般不会挂起；
        // 仍加超时防御底层网络层挂起。
        val observableClz = observable.javaClass
        val timed = observableClz
            .getMethod("timeout", Long::class.javaPrimitiveType, java.util.concurrent.TimeUnit::class.java)
            .invoke(observable, REQUEST_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        val response = timed.javaClass.getMethod("blockingSingle").invoke(timed)
            ?: throw IllegalStateException("empty comment response")
        Logger.d("direct rpc response received")
        return response
    }

    /**
     * 尽力填充包装层原本会补的字段：
     *   inner.c.INSTANCE.b().f20579a.c().i().f352044a          -> appID
     *   inner.c.INSTANCE.b().f20579a.c().getComplianceStatus() -> complianceStatus
     * 全链路混淆字段名，逐段防御性反射，任何一步失败只影响该字段填充。
     */
    private fun fillCommonFields(cl: ClassLoader, request: Any) {
        try {
            val clzC = cl.loadClass("com.dragon.read.lib.community.inner.c")
            val instance = runCatching { clzC.getField("f203251a").get(null) }.getOrNull()
                ?: clzC.declaredFields.firstOrNull {
                    java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == clzC
                }?.apply { isAccessible = true }?.get(null)
            if (instance == null) {
                Logger.w("fillCommonFields: inner.c instance not found")
                return
            }
            val depend = clzC.getMethod("b").invoke(instance) ?: return
            val cssDepend = depend.javaClass.getField("f20579a").get(depend) ?: return
            val config = cssDepend.javaClass.getMethod("c").invoke(cssDepend) ?: return
            val appidObj = config.javaClass.getMethod("i").invoke(config)
            val appID = appidObj.javaClass.getField("f352044a").get(appidObj)
            val compliance = config.javaClass.getMethod("getComplianceStatus").invoke(config)
            setField(request.javaClass, request, "appID", appID)
            setField(request.javaClass, request, "complianceStatus", compliance)
            Logger.i("fillCommonFields: appID=$appID, complianceStatus=$compliance")
        } catch (t: Throwable) {
            // 非致命：appID 缺省仅影响埋点/网关路由
            Logger.w("fillCommonFields failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // 响应解析与诊断
    // ------------------------------------------------------------------

    /** 解析响应：过滤长度<15、或含"第X季"/"更新"的评论。 */
    private fun parsePage(response: Any): Page {
        // 两条路径的返回类型不同：
        //   wrapper 路径 h.e(...)            -> Single<CommentListData>（已 map 解包）
        //   direct  路径 getCommentListRxJava -> Observable<GetCommentListResponse>（需解开 .data）
        val isResponse = response.javaClass.name.endsWith("GetCommentListResponse")
        if (isResponse) dumpDiagnostics(response)

        val data: Any = if (isResponse) {
            getField(response, "data")
                ?: throw IllegalStateException(buildNullDataMessage(response))
        } else {
            Logger.d("parsePage: input is ${response.javaClass.name} (already unwrapped)")
            response
        }
        val dataList = getField(data, "dataList") as? List<*>
            ?: throw IllegalStateException("data.dataList is null")
        val info = getField(data, "commonListInfo")
        val cursor = info?.let { getField(it, "cursor") as? String }
        val hasMore = info?.let { getField(it, "hasMore") as? Boolean } ?: false

        val all = ArrayList<String>(dataList.size)
        val usedFlags = ArrayList<Boolean>(dataList.size)
        val scores = ArrayList<Float?>(dataList.size)
        for (item in dataList) {
            if (item == null) continue
            val reply = getField(item, "reply")
                ?: getField(item, "comment")
                ?: continue
            val common = getField(reply, "common") ?: continue
            val content = getField(common, "content") ?: continue
            val text = (getField(content, "text") as? String)
                ?: (getField(content, "srcContent") as? String)
                ?: continue
            val t = text.trim()
            if (t.isEmpty()) continue
            // 评分：reply.expand.score（字符串数字，如 "8.5"）；未评分/解析失败为 null
            val score = (getField(reply, "expand")?.let { getField(it, "score") } as? String)
                ?.toFloatOrNull()
            all.add(t)
            usedFlags.add(shouldKeep(t))
            scores.add(score)
        }
        val texts = all.filterIndexed { i, _ -> usedFlags[i] }
        Logger.i("page fetched: raw=${dataList.size}, got=${all.size}, filtered=${texts.size}, hasMore=$hasMore")
        return Page(texts, all, usedFlags, scores, dataList.size, cursor, hasMore)
    }

    /** 过滤判定：字数 >= [MIN_TEXT_LENGTH]，且不命中 [EXCLUDE_PATTERNS] 任一排除规则。 */
    private fun shouldKeep(t: String): Boolean {
        if (t.length < MIN_TEXT_LENGTH) return false
        return EXCLUDE_PATTERNS.none { it.containsMatchIn(t) }
    }

    /** dump 响应中的 code / message / debug_info / log_id / Header。 */
    private fun dumpDiagnostics(response: Any) {
        runCatching {
            val code = getField(response, "code")
            val codeStr = code?.let {
                runCatching { it.javaClass.getMethod("getValue").invoke(it) }.getOrNull() ?: it
            }
            val message = getField(response, "message")
            val debugInfo = getField(response, "debugInfo")
            val logId = getField(response, "logID")
            val header = getField(response, "header")
            Logger.i(
                "comment response diagnostics: " +
                        "code=$codeStr, message=$message, debugInfo=$debugInfo, " +
                        "logID=$logId, header=$header"
            )
        }.onFailure { Logger.w("dumpDiagnostics failed: ${it.message}") }
    }

    private fun buildNullDataMessage(response: Any): String {
        runCatching {
            val code = getField(response, "code")
            val message = getField(response, "message") as? String
            if (code != null || !message.isNullOrEmpty()) {
                val codeStr = code?.let {
                    runCatching { it.javaClass.getMethod("getValue").invoke(it) }.getOrNull() ?: it
                }
                return "服务端返回错误: code=$codeStr, message=$message"
            }
        }
        return "response.data is null（诊断信息见 LSPosed 日志 / logcat）"
    }

    // ------------------------------------------------------------------
    // 反射工具
    // ------------------------------------------------------------------

    private fun enumOf(cl: ClassLoader, className: String, value: String): Any {
        val clazz = cl.loadClass(className)
        return java.lang.Enum.valueOf(clazz as Class<out Enum<*>>, value)
    }

    private fun setField(clazz: Class<*>, target: Any, name: String, value: Any?) {
        val f = clazz.getField(name)
        f.set(target, value)
    }

    private fun getField(target: Any, name: String): Any? {
        try {
            return target.javaClass.getField(name).get(target)
        } catch (e: NoSuchFieldException) {
            // 字段可能在父类
            var c: Class<*>? = target.javaClass
            while (c != null) {
                try {
                    return c.getDeclaredField(name).apply { isAccessible = true }.get(target)
                } catch (ignored: NoSuchFieldException) {
                    c = c.superclass
                }
            }
            return null
        }
    }
}
