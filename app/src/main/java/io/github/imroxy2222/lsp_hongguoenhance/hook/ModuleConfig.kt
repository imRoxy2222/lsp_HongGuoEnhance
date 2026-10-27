package io.github.imroxy2222.lsp_hongguoenhance.hook

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface

/**
 * 模块配置（宿主进程侧读取）。
 *
 * 设置页（模块自身进程）通过 XposedService.getRemotePreferences 写入，
 * 本类通过 XposedModule.getRemotePreferences 读取 —— libxposed 框架
 * 负责两个进程间的数据同步，无需 XSharedPreferences。
 */
object ModuleConfig {
    const val PREFS_NAME = "hge_settings"

    const val KEY_BASE_URL = "base_url"
    const val KEY_API_KEY = "api_key"
    const val KEY_MODEL = "model"
    const val KEY_TARGET_COUNT = "target_count"   // 首次采集目标评论数
    const val KEY_PAGE_COUNT = "page_count"       // 单批加载评论数
    const val KEY_EXTRA_PROMPT = "extra_prompt"   // 用户附加提示词

    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_MODEL = "gpt-4o-mini"
    const val DEFAULT_TARGET_COUNT = 50
    const val DEFAULT_PAGE_COUNT = 10

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(xposed: XposedInterface) {
        if (prefs == null) {
            prefs = xposed.getRemotePreferences(PREFS_NAME)
        }
    }

    private fun p(): SharedPreferences =
        prefs ?: throw IllegalStateException("ModuleConfig not initialized")

    fun baseUrl(): String = p().getString(KEY_BASE_URL, null).takeUnless { it.isNullOrEmpty() }
        ?: DEFAULT_BASE_URL

    fun apiKey(): String = p().getString(KEY_API_KEY, null).orEmpty()

    fun model(): String = p().getString(KEY_MODEL, null).takeUnless { it.isNullOrEmpty() }
        ?: DEFAULT_MODEL

    fun targetCount(): Int =
        p().getInt(KEY_TARGET_COUNT, DEFAULT_TARGET_COUNT).coerceIn(10, 200)

    fun pageCount(): Int =
        p().getInt(KEY_PAGE_COUNT, DEFAULT_PAGE_COUNT).coerceIn(5, 30)

    fun extraPrompt(): String = p().getString(KEY_EXTRA_PROMPT, null).orEmpty()
}
