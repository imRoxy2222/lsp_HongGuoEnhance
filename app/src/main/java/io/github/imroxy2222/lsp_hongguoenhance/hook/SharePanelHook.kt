package io.github.imroxy2222.lsp_hongguoenhance.hook

import android.app.Activity
import android.os.Bundle
import io.github.imroxy2222.lsp_hongguoenhance.HookEntry
import io.github.imroxy2222.lsp_hongguoenhance.Logger
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference

/**
 * 分享入口 hook。
 *
 * 红果短剧播放页（ShortSeriesActivity）中，两处 view id = hq0 的分享按钮
 * （右侧栏 d8w 布局的 FrameLayout、选集弹窗底栏 c96 布局的"分享"横条）
 * 的点击最终都会汇聚到：
 *
 *   com.dragon.read.component.biz.api.NsShareProxy#showShortSeriesSharePanel(
 *       Activity, ek2.o, ISharePanel, lm3.g)
 *
 * 相比 hook View.OnClickListener（受 debounceClick 封装、lambda 混淆影响），
 * 在该汇聚点拦截可以稳定覆盖两个入口，且不调用 proceed() 即可完全替换
 * 原分享面板 —— 即"点击分享按钮后出现的页面"被替换为本模块的评论词条页。
 */
object SharePanelHook {
    private const val TAG = HookEntry.TAG

    /** 最近一次分享面板所属的 Activity，用于弹词条对话框。 */
    @Volatile
    private var lastShareActivity: WeakReference<Activity>? = null

    fun install(xposed: XposedInterface, cl: ClassLoader) {
        hookSharePanel(xposed, cl)
        hookShareModelCtor(xposed, cl)
        hookVideoDetailModelParse(xposed, cl)
        hookSaasVideoData(xposed, cl)
        hookPlayletCommentPage(xposed, cl)
        hookShortSeriesPage(xposed, cl)
        ShareButtonUiHook.install(xposed, cl)
    }

    /** 拦截分享面板调用：ShortSeriesActivity 内弹"评论词条"页，主页(MainFragmentActivity)仅 Toast 提示，其余场景放行。 */
    private fun hookSharePanel(xposed: XposedInterface, cl: ClassLoader) {
        try {
            val proxyCls = cl.loadClass("com.dragon.read.component.biz.api.NsShareProxy")
            val shortSeriesCls =
                cl.loadClass("com.dragon.read.component.shortvideo.impl.ShortSeriesActivity")
            val mainFragmentCls = runCatching {
                cl.loadClass("com.dragon.read.pages.main.MainFragmentActivity")
            }.getOrNull()
            val sharePanelCls =
                cl.loadClass("com.bytedance.ug.sdk.share.impl.ui.panel.ISharePanel")
            val lm3g = cl.loadClass("lm3.g")
            val method = proxyCls.getDeclaredMethod(
                "showShortSeriesSharePanel",
                Activity::class.java, cl.loadClass("ek2.o"), sharePanelCls, lm3g
            )
            xposed.hook(method).intercept { chain ->
                val activity = chain.getArg(0) as? Activity
                if (activity != null) {
                    // 信息流主页的分享按钮与播放页同构（同 id hq0，UI 已替换为 AI总结），
                    // 但 AI 总结需进入剧集才能确定目标剧：仅 Toast 提示，
                    // 不弹词条页（SeriesIdHolder 可能残留其他剧的 id）、也不放行原分享面板
                    if (mainFragmentCls != null && mainFragmentCls.isInstance(activity)) {
                        activity.runOnUiThread {
                            android.widget.Toast.makeText(
                                activity, "需要进入剧集内才能触发AI总结", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        return@intercept null
                    }
                    if (shortSeriesCls.isInstance(activity)) {
                    lastShareActivity = WeakReference(activity)
                    val model = chain.getArg(1)
                    if (model != null) {
                        ShareModelStore.recordModel(model)
                    }
                    val fromModel = ShareModelStore.seriesIdOf(model)
                    val fromHolder = SeriesIdHolder.current
                    val seriesId = fromModel ?: fromHolder
                    Logger.i(
                        "share panel intercepted: activity=${activity.javaClass.name}, " +
                            "model=$model, seriesIdFromModel=$fromModel, " +
                            "seriesIdFromHolder=$fromHolder -> $seriesId"
                    )
                    if (!seriesId.isNullOrEmpty()) {
                        SeriesIdHolder.update(seriesId)
                        // 无感兜底：分享模型 ek2.o 的字段 f（ctor 第 6 参）= VideoDetailModel
                        // 的 getEpisodesTitle()，实际存的是剧名（series_title）。
                        // 仅在剧名尚缺时启用，字段名不符则静默跳过。
                        if (SeriesIdHolder.nameOf(seriesId) == null) {
                            val modelName = runCatching {
                                val f = model?.javaClass?.getDeclaredField("f")
                                f?.isAccessible = true
                                (f?.get(model) as? String)?.trim()
                            }.getOrNull()
                            if (!modelName.isNullOrEmpty() &&
                                !modelName.startsWith("#") &&
                                !modelName.matches(Regex("[0-9]+"))
                            ) {
                                SeriesIdHolder.update(seriesId, modelName)
                                Pipeline.onSeriesNameCaptured(seriesId, modelName)
                                Logger.i("series name captured (share model): $seriesId -> $modelName")
                            }
                        }
                        val act = activity
                        act.runOnUiThread {
                            Pipeline.onShareClicked(act, seriesId)
                        }
                        return@intercept null // 替换原分享面板，不调用 proceed()
                    }
                    // 拿不到 seriesId 时放行原分享面板，避免破坏正常分享
                    }
                }
                chain.proceed()
            }
            Logger.i("hook share panel ok")
        } catch (t: Throwable) {
            Logger.e("hook share panel failed", t)
        }
    }

    /**
     * 分享模型 ek2.o 的构造函数：第一个参数为 seriesId。
     * 构造完成后把实例与 seriesId 绑定，供分享面板 hook 查询。
     */
    private fun hookShareModelCtor(xposed: XposedInterface, cl: ClassLoader) {
        try {
            val modelCls = cl.loadClass("ek2.o")
            for (ctor in modelCls.declaredConstructors) {
                val params = ctor.parameterTypes
                if (params.isNotEmpty() && params[0] == String::class.java) {
                    ctor.isAccessible = true
                    xposed.hook(ctor).intercept { chain ->
                        val r = chain.proceed()
                        runCatching {
                            val sid = chain.getArg(0) as? String
                            ShareModelStore.bind(chain.thisObject, sid)
                        }
                        r
                    }
                }
            }
            Logger.i("hook share model ctor ok")
        } catch (t: Throwable) {
            Logger.e("hook share model ctor failed", t)
        }
    }

    /**
     * 剧详情解析 VideoDetailModel.parseVideoDetailVideoData：每次加载剧详情必调。
     *
     * 反编译确认该方法内 `setEpisodesTitle(videoDetailVideoData.seriesTitle)`、
     * `setEpisodesId(String.valueOf(seriesId))` —— getEpisodesId()/getEpisodesTitle()
     * 即成对的 (剧 id, 剧名)，是最可靠的无感剧名通道（主路径）。
     */
    private fun hookVideoDetailModelParse(xposed: XposedInterface, cl: ClassLoader) {
        try {
            val cls = cl.loadClass("com.dragon.read.video.VideoDetailModel")
            val paramCls = cl.loadClass("com.dragon.read.rpc.model.VideoDetailVideoData")
            val m = cls.getDeclaredMethod("parseVideoDetailVideoData", paramCls)
            xposed.hook(m).intercept { chain ->
                val r = chain.proceed()
                runCatching {
                    val model = chain.thisObject
                    val id = cls.getMethod("getEpisodesId").invoke(model) as? String
                    val name = cls.getMethod("getEpisodesTitle").invoke(model) as? String
                    if (!id.isNullOrEmpty() && !name.isNullOrEmpty()) {
                        SeriesIdHolder.update(id, name)
                        Pipeline.onSeriesNameCaptured(id, name)
                        Logger.i("series name captured (detail parse): $id -> $name")
                    }
                }
                r
            }
            Logger.i("hook VideoDetailModel.parseVideoDetailVideoData ok")
        } catch (t: Throwable) {
            Logger.e("hook VideoDetailModel.parseVideoDetailVideoData failed", t)
        }
    }

    /**
     * 播放页数据源 SaasVideoData（冗余路径）：getSeriesId() 播放期间高频触发，
     * 剧名按优先级从同一实例解析：videoDetailData.seriesTitle（RPC 详情，
     * @SerializedName("series_title")，public 字段）→ getSeriesName()。
     */
    private fun hookSaasVideoData(xposed: XposedInterface, cl: ClassLoader) {
        try {
            val cls =
                cl.loadClass("com.dragon.read.component.shortvideo.data.saas.video.SaasVideoData")
            val m = cls.getDeclaredMethod("getSeriesId")
            val loggedMissing = java.util.Collections.synchronizedSet(HashSet<String>())
            xposed.hook(m).intercept { chain ->
                val r = chain.proceed()
                runCatching {
                    val id = r as? String
                    if (!id.isNullOrEmpty()) {
                        val name = resolveSeriesName(cls, chain.thisObject)
                        if (!name.isNullOrEmpty()) {
                            if (SeriesIdHolder.nameOf(id) != name) {
                                Logger.i("series name captured (saas): $id -> $name")
                            }
                            SeriesIdHolder.update(id, name)
                            Pipeline.onSeriesNameCaptured(id, name)
                        } else if (loggedMissing.add(id)) {
                            Logger.i("series name missing on SaasVideoData (will retry via other paths): $id")
                        }
                    }
                }
                r
            }
            Logger.i("hook SaasVideoData.getSeriesId (+seriesTitle/seriesName) ok")
        } catch (t: Throwable) {
            Logger.e("hook SaasVideoData.getSeriesId failed", t)
        }
    }

    /** 从 SaasVideoData 实例解析剧名：videoDetailData.seriesTitle 优先，getSeriesName() 兜底。 */
    private fun resolveSeriesName(saasCls: Class<*>, instance: Any?): String? {
        if (instance == null) return null
        // 1. RPC 详情里的 series_title（public 字段）
        runCatching {
            val detail = saasCls.getMethod("getVideoDetailData").invoke(instance)
            if (detail != null) {
                val title = detail.javaClass.getField("seriesTitle").get(detail) as? String
                if (!title.isNullOrBlank()) return title.trim()
            }
        }
        // 2. SaasVideoData 自身字段
        runCatching {
            val n = saasCls.getMethod("getSeriesName").invoke(instance) as? String
            if (!n.isNullOrBlank()) return n.trim()
        }
        return null
    }

    /** 剧评页 CSSPlayletCommentListActivity onCreate：读取 Intent 中的 seriesId/seriesName。 */
    private fun hookPlayletCommentPage(xposed: XposedInterface, cl: ClassLoader) {
        try {
            val cls = cl.loadClass(
                "com.dragon.community.impl.comment.playlet.list.page.CSSPlayletCommentListActivity"
            )
            val onCreate = cls.getDeclaredMethod("onCreate", Bundle::class.java)
            xposed.hook(onCreate).intercept { chain ->
                val r = chain.proceed()
                runCatching {
                    val activity = chain.thisObject as Activity
                    val intent = activity.intent
                    val sid = intent?.getStringExtra("seriesId")
                    val name = intent?.getStringExtra("seriesName")
                    SeriesIdHolder.update(sid, name)
                    // 事后回填：即使此前分享时没拿到剧名，打开剧评页也能补全日志里的剧名
                    Pipeline.onSeriesNameCaptured(sid, name)
                }
                r
            }
            Logger.i("hook playlet comment page ok")
        } catch (t: Throwable) {
            Logger.e("hook playlet comment page failed", t)
        }
    }

    /**
     * 播放页 ShortSeriesActivity onCreate：尝试读取 Intent 中的剧名 extra
     * （额外一路冗余采集；不同入口 extras 不一定携带，取不到即跳过）。
     */
    private fun hookShortSeriesPage(xposed: XposedInterface, cl: ClassLoader) {
        try {
            val cls = cl.loadClass(
                "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
            )
            val onCreate = cls.getDeclaredMethod("onCreate", Bundle::class.java)
            xposed.hook(onCreate).intercept { chain ->
                val r = chain.proceed()
                runCatching {
                    val intent = (chain.thisObject as Activity).intent ?: return@runCatching
                    val sid = intent.getStringExtra("seriesId")
                        ?: intent.getStringExtra("series_id")
                    val name = intent.getStringExtra("seriesName")
                        ?: intent.getStringExtra("series_name")
                    if (!sid.isNullOrEmpty()) {
                        SeriesIdHolder.update(sid, name)
                        Pipeline.onSeriesNameCaptured(sid, name)
                    }
                }
                r
            }
            Logger.i("hook short series page ok")
        } catch (t: Throwable) {
            Logger.e("hook short series page failed", t)
        }
    }
}

/** ek2.o 实例 → seriesId 的弱引用绑定表。 */
object ShareModelStore {
    private val map = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Any, String>()
    )

    fun bind(model: Any?, seriesId: String?) {
        if (model != null && !seriesId.isNullOrEmpty()) {
            map[model] = seriesId
        }
    }

    fun recordModel(model: Any) {
        // model 构造已 hook，无需处理；预留扩展点
    }

    fun seriesIdOf(model: Any?): String? = model?.let { map[it] }
}
