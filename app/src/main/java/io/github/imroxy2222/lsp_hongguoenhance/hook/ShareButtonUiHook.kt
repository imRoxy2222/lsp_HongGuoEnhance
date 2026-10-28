package io.github.imroxy2222.lsp_hongguoenhance.hook

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.imroxy2222.lsp_hongguoenhance.HookEntry
import io.github.imroxy2222.lsp_hongguoenhance.Logger
import io.github.libxposed.api.XposedInterface

/**
 * 分享按钮 UI 改造：把播放页两处分享按钮（view id = hq0，见 doc/分享按钮Hook.md）
 * 的文案"分享"改为"AI总结"、图标替换为 AI 星芒。
 *
 * 替换目标（基于 jadx 反编译的两处布局）：
 * - `d8w.xml`（右侧竖排栏）：FrameLayout hq0 内 ImageView（图标，XML 静态 @drawable/elf）
 *   + 兄弟 TextView jqt（文案由宿主代码动态 setText）
 * - `c96.xml`（选集弹窗底栏）：LinearLayout hq0 内 ImageView（图标）+ TextView jqm
 *   （文案 XML 静态 @string/ebj = "分享"）
 *
 * 覆盖触发点（互相冗余，保证晚创建的视图也被改造）：
 * 1. ShortSeriesActivity.onResume 后遍历 decorView（覆盖主页面已存在按钮）；
 * 2. Dialog.show 后遍历弹窗 decorView（覆盖选集弹窗，context 可能是 ThemeWrapper 需解包）；
 * 3. View.onAttachedToWindow hook（覆盖播放页翻集时新 inflate 的按钮，仅放行图标）；
 * 4. TextView.setText hook（覆盖宿主运行时绑定文案"分享"的时机，仅 jqt/jqm 生效）。
 *
 * 图标不用位图资源：AI 星芒以 Path 程序化绘制（实心白填充），与原图标
 * （纯白实心曲线箭头，@drawable/elf 138px@xxhdpi ≈ 46dp 全幅）风格、颜色、
 * 占幅一致，且任意密度矢量级清晰。
 *
 * 所有替换均幂等且按 id 精确门控，不影响宿主其它视图；失败静默降级，
 * 按钮保持原样（点击仍走 SharePanelHook 的面板替换，功能不受影响）。
 */
object ShareButtonUiHook {
    private const val TAG = HookEntry.TAG

    private const val LABEL = "AI总结"
    private const val OLD_LABEL = "分享"

    /** 宿主资源中解析出的目标 view id。 */
    private class TargetIds(val hq0: Int, val jqt: Int, val jqm: Int)

    @Volatile
    private var ids: TargetIds? = null

    fun install(xposed: XposedInterface, cl: ClassLoader) {
        val shortSeriesCls = try {
            cl.loadClass("com.dragon.read.component.shortvideo.impl.ShortSeriesActivity")
        } catch (t: Throwable) {
            Logger.e("share button ui: ShortSeriesActivity not found", t)
            return
        }
        hookOnResume(xposed, shortSeriesCls)
        hookDialogShow(xposed, shortSeriesCls)
        hookViewAttached(xposed)
        hookSetText(xposed)
    }

    /** 播放页 onResume 后多次遍历 decorView（页面/翻集延迟 inflate 的按钮也能覆盖）。 */
    private fun hookOnResume(xposed: XposedInterface, shortSeriesCls: Class<*>) {
        try {
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
            xposed.hook(onResume).intercept { chain ->
                val r = chain.proceed()
                runCatching {
                    val act = chain.thisObject as? Activity
                    if (act != null && shortSeriesCls.isInstance(act)) {
                        val decor = act.window?.decorView ?: return@runCatching
                        // 立即 + 延迟两轮：等 viewpager 集页与侧栏完成 inflate/绑定
                        decor.post { traverse(decor) }
                        decor.postDelayed({ traverse(decor) }, 800)
                        decor.postDelayed({ traverse(decor) }, 2500)
                    }
                }
                r
            }
            Logger.i("hook share button ui (onResume) ok")
        } catch (t: Throwable) {
            Logger.e("hook share button ui (onResume) failed", t)
        }
    }

    /** 弹窗（选集底栏 c96）show 后遍历其 decorView；Dialog 可能包 ThemeWrapper，需解包判断。 */
    private fun hookDialogShow(xposed: XposedInterface, shortSeriesCls: Class<*>) {
        try {
            val show = Dialog::class.java.getDeclaredMethod("show")
            xposed.hook(show).intercept { chain ->
                val r = chain.proceed()
                runCatching {
                    val dialog = chain.thisObject as? Dialog ?: return@runCatching
                    if (unwrap(dialog.context).let { it is Activity && shortSeriesCls.isInstance(it) }) {
                        val decor = dialog.window?.decorView ?: return@runCatching
                        decor.post { traverse(decor) }
                        decor.postDelayed({ traverse(decor) }, 500)
                    }
                }
                r
            }
            Logger.i("hook share button ui (dialog show) ok")
        } catch (t: Throwable) {
            Logger.e("hook share button ui (dialog show) failed", t)
        }
    }

    /** 解开 ContextThemeWrapper / ContextWrapper 链，拿到底层 Context。 */
    private fun unwrap(context: Context?): Context? {
        var c = context
        var guard = 0
        while (c is android.content.ContextWrapper && guard++ < 8) {
            val base = c.baseContext ?: break
            if (base is Activity) return base
            if (base !is android.content.ContextWrapper) return base
            c = base
        }
        return c
    }

    /**
     * 视图 attach 时拦截（app 全局，门控极轻）：
     * - ImageView：父容器 id == hq0 → 替换为 AI 图标（覆盖翻集时新 inflate 的按钮，
     *   其图标来自 XML 静态 src，不走 setImageResource 公开路径）；
     * - TextView：id == jqt/jqm 且当前文案为"分享" → 改为 AI总结。
     */
    private fun hookViewAttached(xposed: XposedInterface) {
        try {
            val attach = View::class.java.getDeclaredMethod("onAttachedToWindow")
            xposed.hook(attach).intercept { chain ->
                val r = chain.proceed()
                runCatching {
                    when (val v = chain.thisObject) {
                        is ImageView -> {
                            val i = ids ?: v.context?.let { resolveIds(it) } ?: return@runCatching
                            if ((v.parent as? View)?.id == i.hq0) {
                                v.setImageDrawable(AiSparkleDrawable(v.context))
                            }
                        }
                        is TextView -> {
                            val i = ids ?: v.context?.let { resolveIds(it) } ?: return@runCatching
                            if (v.id == i.jqt || v.id == i.jqm) fixText(v)
                        }
                    }
                }
                r
            }
            Logger.i("hook share button ui (view attach) ok")
        } catch (t: Throwable) {
            Logger.e("hook share button ui (view attach) failed", t)
        }
    }

    /**
     * 宿主运行时给 jqt/jqm 绑定文案的时机：目标 id 且文案为"分享" → 改为 AI总结。
     * after-proceed 修正而非改参：宿主后续读取自身状态不受影响。
     */
    private fun hookSetText(xposed: XposedInterface) {
        try {
            val setText = TextView::class.java.getDeclaredMethod(
                "setText", CharSequence::class.java, TextView.BufferType::class.java
            )
            xposed.hook(setText).intercept { chain ->
                val r = chain.proceed()
                runCatching {
                    val tv = chain.thisObject as? TextView ?: return@runCatching
                    val i = ids ?: tv.context?.let { resolveIds(it) } ?: return@runCatching
                    if (tv.id == i.jqt || tv.id == i.jqm) fixText(tv)
                }
                r
            }
            Logger.i("hook share button ui (setText) ok")
        } catch (t: Throwable) {
            Logger.e("hook share button ui (setText) failed", t)
        }
    }

    /** 递归遍历视图树，改造目标按钮（幂等，重复执行无副作用）。 */
    private fun traverse(root: View) {
        runCatching {
            val i = ids ?: resolveIds(root.context) ?: return@runCatching
            if (root is ViewGroup) {
                for (n in 0 until root.childCount) traverse(root.getChildAt(n))
            }
            when {
                root.id == i.hq0 -> restyleButton(root)
                root.id == i.jqt || root.id == i.jqm -> (root as? TextView)?.let { fixText(it) }
            }
        }
    }

    /**
     * 改造单个分享按钮：
     * - hq0 内所有 ImageView（d8w 图标 eul / c96 图标 a）→ AI 图标；
     * - hq0 内 TextView（c96 的 jqm）→ 文案；
     * - hq0 的兄弟 TextView（d8w 的 jqt 在容器外）→ 文案。
     */
    private fun restyleButton(hq0: View) {
        setIconsRecursive(hq0)
        (hq0 as? TextView)?.let { fixText(it) }
        (hq0 as? ViewGroup)?.let { g ->
            for (n in 0 until g.childCount) {
                val child = g.getChildAt(n)
                if (child is TextView) fixText(child)
            }
        }
        (hq0.parent as? ViewGroup)?.let { p ->
            for (n in 0 until p.childCount) {
                val sib = p.getChildAt(n)
                if (sib !== hq0 && sib is TextView) fixText(sib)
            }
        }
    }

    private fun setIconsRecursive(view: View) {
        runCatching {
            if (view is ImageView) {
                view.setImageDrawable(AiSparkleDrawable(view.context))
                return@runCatching
            }
            if (view is ViewGroup) {
                for (n in 0 until view.childCount) setIconsRecursive(view.getChildAt(n))
            }
        }
    }

    private fun fixText(tv: TextView) {
        runCatching {
            if (tv.text?.toString() != LABEL) tv.text = LABEL
        }
    }

    /** 解析宿主目标 id（单次，缓存）；解析失败返回 null（按钮保持原样）。 */
    private fun resolveIds(context: Context?): TargetIds? {
        ids?.let { return it }
        val app = context?.applicationContext ?: return null
        val pkg = app.packageName
        val res = app.resources
        fun id(name: String) = res.getIdentifier(name, "id", pkg)
        val hq0 = id("hq0")
        val jqt = id("jqt")
        val jqm = id("jqm")
        if (hq0 == 0 || jqt == 0 || jqm == 0) return null
        return TargetIds(hq0, jqt, jqm).also { ids = it }
    }

    /**
     * AI 星芒图标：四角圆润凹边大星 + 右上小星，实心白填充。
     * 原分享图标（@drawable/elf）为纯白实心箭头、138px@xxhdpi = 46dp 全幅；
     * 本图标 intrinsic 同取 46dp，24 单位坐标系内星芒占幅约 89%，视觉重量相当。
     */
    private class AiSparkleDrawable(context: Context?) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val density = context?.resources?.displayMetrics?.density ?: 3f
        private val main = mainStar()
        private val small = smallStar()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            canvas.save()
            // 90% 缩放居中绘制：ImageView(fitCenter) 会把 Drawable 拉伸到铺满视图，
            // 缩小只能在绘制阶段做
            val s = b.width() / 24f * SCALE
            val offset = b.width() - 24f * s
            canvas.translate(b.left + offset / 2f, b.top + offset / 2f)
            canvas.scale(s, s)
            canvas.drawPath(main, paint)
            canvas.drawPath(small, paint)
            canvas.restore()
        }

        override fun getIntrinsicWidth(): Int = (46f * density).toInt()
        override fun getIntrinsicHeight(): Int = (46f * density).toInt()
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        companion object {
            /** 绘制占幅比例：90% 居中（略小于视图全幅，视觉更协调）。 */
            private const val SCALE = 0.9f

            /** 大星：中心 (12,12)，四尖角，凹边贝塞尔。 */
            private fun mainStar() = Path().apply {
                moveTo(12f, 1.8f)
                cubicTo(12.9f, 7.1f, 16.9f, 11.1f, 22.2f, 12f)
                cubicTo(16.9f, 12.9f, 12.9f, 16.9f, 12f, 22.2f)
                cubicTo(11.1f, 16.9f, 7.1f, 12.9f, 1.8f, 12f)
                cubicTo(7.1f, 11.1f, 11.1f, 7.1f, 12f, 1.8f)
                close()
            }

            /** 小星：中心 (19.5, 5.7)，位于大星右上留白处。 */
            private fun smallStar() = Path().apply {
                moveTo(19.5f, 2.2f)
                cubicTo(19.83f, 4.05f, 21.15f, 5.37f, 23f, 5.7f)
                cubicTo(21.15f, 6.03f, 19.83f, 7.35f, 19.5f, 9.2f)
                cubicTo(19.17f, 7.35f, 17.85f, 6.03f, 16f, 5.7f)
                cubicTo(17.85f, 5.37f, 19.17f, 4.05f, 19.5f, 2.2f)
                close()
            }
        }
    }
}
