package io.github.imroxy2222.lsp_hongguoenhance.hook

import android.util.Log
import io.github.imroxy2222.lsp_hongguoenhance.HookEntry
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 记录"当前正在播放的短剧 seriesId"。
 *
 * 采集来源（多路冗余，按优先级取用）：
 * 1. [SharePanelHook] hook 的分享模型构造函数（ek2.o 第一个 String 参数）
 * 2. [SharePanelHook] hook 的 SaasVideoData.getSeriesId() 返回值
 * 3. 用户打开剧评页 CSSPlayletCommentListActivity 时 Intent extra "seriesId"
 *
 * 剧名记忆：剧名只随剧评页 Intent（"seriesName"）出现，且必须与 seriesId
 * 成对采集。因此除"最近一次"全局值外，另按 seriesId 存一份映射
 * （[nameOf]），供日志/词条流程精确取用——避免 B 剧分享时误挂 A 剧的旧名。
 */
object SeriesIdHolder {
    private const val TAG = HookEntry.TAG

    /** 剧名映射上限：超过后整体清空（低频场景，简单防膨胀即可）。 */
    private const val MAX_NAMES = 128

    @Volatile
    var current: String? = null
        private set

    @Volatile
    var currentName: String? = null
        private set

    private val names = ConcurrentHashMap<String, String>()

    fun update(seriesId: String?, seriesName: String? = null) {
        if (!seriesId.isNullOrEmpty()) {
            current = seriesId
            if (!seriesName.isNullOrEmpty()) {
                currentName = seriesName
                if (names.size >= MAX_NAMES) names.clear()
                names[seriesId] = seriesName
            }
            Log.d(TAG, "seriesId updated: $seriesId")
        }
    }

    /** 精确取某剧的剧名：只有成对采集到 (seriesId, seriesName) 的剧才有值。 */
    fun nameOf(seriesId: String?): String? =
        seriesId?.let { names[it] }
}
