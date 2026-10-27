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
        hookSaasVideoData(xposed, cl)
        hookPlayletCommentPage(xposed, cl)
    }

    /** 拦截分享面板调用：ShortSeriesActivity 内弹"评论词条"页，其余场景放行。 */
    private fun hookSharePanel(xposed: XposedInterface, cl: ClassLoader) {
        try {
            val proxyCls = cl.loadClass("com.dragon.read.component.biz.api.NsShareProxy")
            val shortSeriesCls =
                cl.loadClass("com.dragon.read.component.shortvideo.impl.ShortSeriesActivity")
            val sharePanelCls =
                cl.loadClass("com.bytedance.ug.sdk.share.impl.ui.panel.ISharePanel")
            val lm3g = cl.loadClass("lm3.g")
            val method = proxyCls.getDeclaredMethod(
                "showShortSeriesSharePanel",
                Activity::class.java, cl.loadClass("ek2.o"), sharePanelCls, lm3g
            )
            xposed.hook(method).intercept { chain ->
                val activity = chain.getArg(0) as? Activity
                if (activity != null && shortSeriesCls.isInstance(activity)) {
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
                        val act = activity
                        act.runOnUiThread {
                            Pipeline.onShareClicked(act, seriesId)
                        }
                        return@intercept null // 替换原分享面板，不调用 proceed()
                    }
                    // 拿不到 seriesId 时放行原分享面板，避免破坏正常分享
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

    /** 播放页数据源 SaasVideoData.getSeriesId()：兜底采集当前剧 id。 */
    private fun hookSaasVideoData(xposed: XposedInterface, cl: ClassLoader) {
        try {
            val cls =
                cl.loadClass("com.dragon.read.component.shortvideo.data.saas.video.SaasVideoData")
            val m = cls.getDeclaredMethod("getSeriesId")
            xposed.hook(m).intercept { chain ->
                val r = chain.proceed()
                runCatching { SeriesIdHolder.update(r as? String) }
                r
            }
            Logger.i("hook SaasVideoData.getSeriesId ok")
        } catch (t: Throwable) {
            Logger.e("hook SaasVideoData.getSeriesId failed", t)
        }
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
                    SeriesIdHolder.update(
                        intent?.getStringExtra("seriesId"),
                        intent?.getStringExtra("seriesName")
                    )
                }
                r
            }
            Logger.i("hook playlet comment page ok")
        } catch (t: Throwable) {
            Logger.e("hook playlet comment page failed", t)
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
