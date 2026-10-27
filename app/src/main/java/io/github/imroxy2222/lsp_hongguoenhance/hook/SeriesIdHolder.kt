package io.github.imroxy2222.lsp_hongguoenhance.hook

import android.util.Log
import io.github.imroxy2222.lsp_hongguoenhance.HookEntry

/**
 * 记录"当前正在播放的短剧 seriesId"。
 *
 * 采集来源（多路冗余，按优先级取用）：
 * 1. [SharePanelHook] hook 的分享模型构造函数（ek2.o 第一个 String 参数）
 * 2. [SharePanelHook] hook 的 SaasVideoData.getSeriesId() 返回值
 * 3. 用户打开剧评页 CSSPlayletCommentListActivity 时 Intent extra "seriesId"
 */
object SeriesIdHolder {
    private const val TAG = HookEntry.TAG

    @Volatile
    var current: String? = null
        private set

    @Volatile
    var currentName: String? = null
        private set

    fun update(seriesId: String?, seriesName: String? = null) {
        if (!seriesId.isNullOrEmpty()) {
            current = seriesId
            if (!seriesName.isNullOrEmpty()) currentName = seriesName
            Log.d(TAG, "seriesId updated: $seriesId")
        }
    }
}
