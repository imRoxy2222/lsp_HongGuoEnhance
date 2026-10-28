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
    const val KEY_LOG_RETENTION_DAYS = "log_retention_days" // 日志保留天数（0=不按天清理）
    const val KEY_LOG_MAX_SIZE_MB = "log_max_size_mb"       // 日志总大小上限 MB（0=不按大小清理）
    const val KEY_LOG_TOKEN = "log_token"         // 日志服务访问令牌（设置页生成，宿主校验）
    const val KEY_LOG_SERVER_STATUS = "log_server_status" // 宿主端服务状态上报（诊断用）
    const val KEY_LOG_DB_PATH = "log_db_path"     // 宿主端真实日志数据库路径上报（root 直读用）
    const val KEY_DISABLE_THINKING = "disable_thinking" // AI 请求关闭思考模式（默认开启）
    const val KEY_AI_VIA_MODULE = "ai_via_module"       // AI 请求经进程桥走模块进程（默认开启）
    const val KEY_THINKING_PROVIDERS = "thinking_providers" // 勾选携带"关闭思考"字段的平台 id，逗号分隔
    const val KEY_EXCLUDE_RULES = "exclude_rules" // 评论过滤正则规则（JSON 字符串数组，按顺序编号）

    /** 单条过滤规则：正则 + 备注（标注该规则的适用情况，设置页展示用，可空）。 */
    data class FilterRule(val pattern: String, val note: String)

    /**
     * 未配置时的内置默认规则（顺序即过滤器编号）：
     * 1. 字数下限（[\s\S]{15,}，containsMatchIn 语义等价"少于 15 字丢弃"，
     *    取代旧的硬编码 MIN_TEXT_LENGTH=15）；
     * 2/3. 历史内置排除规则（剧名/续季宣传语、催更/版本讨论）。
     */
    val DEFAULT_EXCLUDE_RULES = listOf(
        FilterRule("[\\s\\S]{,15}", "字数下限：评论少于 15 字即丢弃"),
        FilterRule("[快出].*第.+季", "剧名/续季宣传语,容易误伤,酌情设置"),
    )

    /**
     * 过滤规则列表（正则 + 备注）。
     * 存储格式：JSON 数组，元素为 {"pattern":"...","note":"..."}；
     * 兼容读取旧版纯字符串数组（note 置空）。
     * 配置项从未写入过 → 返回内置默认规则；写入过空数组 → 空列表（不过滤）；
     * 配置损坏 → 回退默认规则，不影响采集。
     */
    fun excludeRuleItems(): List<FilterRule> {
        val saved = try {
            p().getString(KEY_EXCLUDE_RULES, null)
        } catch (t: Throwable) {
            null
        } ?: return DEFAULT_EXCLUDE_RULES
        return try {
            val arr = org.json.JSONArray(saved)
            (0 until arr.length()).mapNotNull { i ->
                when (val e = arr.get(i)) {
                    is String -> e.takeIf { it.isNotEmpty() }?.let { FilterRule(it, "") }
                    is org.json.JSONObject -> {
                        val p = e.optString("pattern")
                        if (p.isNotEmpty()) FilterRule(p, e.optString("note")) else null
                    }

                    else -> null
                }
            }
        } catch (t: Throwable) {
            DEFAULT_EXCLUDE_RULES
        }
    }

    /** 设置页写入过滤规则（JSON 对象数组；顺序即编号顺序）。 */
    fun setExcludeRuleItems(rules: List<FilterRule>) {
        try {
            val arr = org.json.JSONArray()
            rules.forEach {
                arr.put(org.json.JSONObject().put("pattern", it.pattern).put("note", it.note))
            }
            prefs?.edit()?.putString(KEY_EXCLUDE_RULES, arr.toString())?.apply()
        } catch (t: Throwable) {
            // 写配置失败不影响主流程
        }
    }

    /** 采集侧使用的纯正则列表（编号 = 列表下标 + 1）。 */
    fun excludeRules(): List<String> = excludeRuleItems().map { it.pattern }

    const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    const val DEFAULT_MODEL = "deepseek-flash"
    const val DEFAULT_TARGET_COUNT = 50
    const val DEFAULT_PAGE_COUNT = 30
    const val DEFAULT_LOG_RETENTION_DAYS = 30
    const val DEFAULT_LOG_MAX_SIZE_MB = 100
    const val DEFAULT_DISABLE_THINKING = true
    const val DEFAULT_AI_VIA_MODULE = true

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

    /** AI 请求是否携带"关闭思考"字段（未配置时默认开启）。 */
    fun disableThinking(): Boolean =
        p().getBoolean(KEY_DISABLE_THINKING, DEFAULT_DISABLE_THINKING)

    /** AI 请求是否经进程桥由模块进程发出（未配置时默认开启；关掉则宿主进程直连）。 */
    fun aiViaModule(): Boolean =
        p().getBoolean(KEY_AI_VIA_MODULE, DEFAULT_AI_VIA_MODULE)

    /**
     * 勾选了携带"关闭思考"字段的平台 id 集合（未配置时按各选项的
     * defaultOn 取默认，与旧版"四形状全带"的行为一致）。
     */
    fun thinkingProviders(): Set<String> {
        val saved = try {
            p().getString(KEY_THINKING_PROVIDERS, null)
        } catch (t: Throwable) {
            null
        }?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        return saved ?: AiClient.THINKING_OPTIONS.filter { it.defaultOn }.map { it.id }.toSet()
    }

    fun setThinkingProviders(ids: Set<String>) {
        try {
            prefs?.edit()?.putString(KEY_THINKING_PROVIDERS, ids.joinToString(","))?.apply()
        } catch (t: Throwable) {
            // 写配置失败不影响主流程
        }
    }

    fun logRetentionDays(): Int =
        p().getInt(KEY_LOG_RETENTION_DAYS, DEFAULT_LOG_RETENTION_DAYS).coerceIn(0, 3650)

    fun logMaxSizeMb(): Int =
        p().getInt(KEY_LOG_MAX_SIZE_MB, DEFAULT_LOG_MAX_SIZE_MB).coerceIn(0, 4096)

    fun logToken(): String = try {
        p().getString(KEY_LOG_TOKEN, null).orEmpty()
    } catch (t: Throwable) {
        "" // 未初始化时按无令牌处理（查看服务不应因配置未就绪而崩溃）
    }

    /**
     * 宿主端状态上报（诊断用）：LogServer 启动成功/失败时写入，
     * 模块 App 的查看页读取后展示，用于区分"宿主没运行新代码"与"服务起不来"。
     * 若宿主端写入未能同步到模块端，本字段为空——本身也是一种诊断信号。
     */
    fun setLogServerStatus(status: String) {
        try {
            prefs?.edit()?.putString(KEY_LOG_SERVER_STATUS, status)?.apply()
        } catch (t: Throwable) {
            // 上报失败不影响服务运行
        }
    }

    /**
     * 宿主端真实数据库路径上报：模块 App 的 root 直读通道用它定位 hge_logs.db，
     * 避免猜路径（多用户 / 应用分身场景下 /data/data 并不一定是宿主的实际目录）。
     */
    fun setLogDbPath(path: String) {
        try {
            prefs?.edit()?.putString(KEY_LOG_DB_PATH, path)?.apply()
        } catch (t: Throwable) {
            // 上报失败不影响存储运行
        }
    }
}
