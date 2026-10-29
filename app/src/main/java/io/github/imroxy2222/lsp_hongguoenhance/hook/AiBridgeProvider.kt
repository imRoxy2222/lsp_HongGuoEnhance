package io.github.imroxy2222.lsp_hongguoenhance.hook

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 进程桥（模块自身进程侧）。
 *
 * 宿主进程（红果）把已组装好的 /chat/completions 请求体经
 * ContentResolver.call() 发到本 Provider，在**模块进程**内执行 HTTP 请求——
 * 这样国外大模型的流量从模块进程发出，代理白名单只需放行模块应用，
 * 无需让红果（视频流量大）走代理。
 *
 * 选择 ContentProvider 而非 Service/Broadcast：
 * - call() 自动拉起模块进程（无需常驻前台服务）；
 * - 无广播 10 秒执行上限，AI 请求 120s 读超时可安全阻塞在 binder 线程；
 * - 同步返回结果，宿主无需再注册回程接收器。
 *
 * 安全：Provider exported 但在 call() 内校验调用方 UID 必须为宿主
 * （com.phoenix.read），防止其他应用借用用户 API Key 白嫖请求。
 */
class AiBridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_SUMMARIZE) throw IllegalArgumentException("unknown method: $method")

        // 调用方校验：仅允许宿主进程
        val caller = context?.packageManager?.getNameForUid(Binder.getCallingUid())
        if (caller != HOST_PACKAGE) throw SecurityException("caller not allowed: $caller")

        val json = extras?.getString(EXTRA_REQUEST)
            ?: throw IllegalArgumentException("missing request")

        val req = JSONObject(json)
        val baseUrl = req.getString(KEY_BASE_URL)
        val apiKey = req.getString(KEY_API_KEY)
        val requestText = req.getString(KEY_REQUEST)

        return try {
            val result = AiClient.summarizeRaw(baseUrl, apiKey, requestText)
            Bundle().apply {
                putString(EXTRA_TAGS, JSONArray().apply {
                    for (t in result.tags) put(
                        JSONObject().put("tag", t.text).put("count", t.count).put("negative", t.negative)
                    )
                }.toString())
                // 六维评分透传（null = AI 未返回，宿主侧走本地回退）
                result.dims?.takeIf { it.size == 6 }?.let {
                    putString(EXTRA_DIMS, JSONObject().apply {
                        DimensionAnalyzer.LABELS.forEachIndexed { i, label -> put(label, it[i].toDouble()) }
                    }.toString())
                }
                putString(EXTRA_RESPONSE, result.response)
            }
        } catch (t: Throwable) {
            // 异常跨进程不可靠（非白名单类型会被吞成 RuntimeException），
            // 统一改为错误应答由宿主侧还原
            Bundle().apply { putString(EXTRA_ERROR, t.message ?: t.javaClass.simpleName) }
        }
    }

    // 未用到的抽象方法实现（Provider 只暴露 call()）
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String? = null

    companion object {
        const val AUTHORITY = "io.github.imroxy2222.lsp_hongguoenhance.aibridge"
        const val METHOD_SUMMARIZE = "summarize"
        const val EXTRA_REQUEST = "request"
        const val EXTRA_TAGS = "tags"
        const val EXTRA_DIMS = "dims"
        const val EXTRA_RESPONSE = "response"
        const val EXTRA_ERROR = "error"
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_REQUEST = "request"

        /** 宿主包名（与 HookEntry.TARGET_APP 一致；HookEntry 继承 XposedModule，
         *  模块进程可能缺 libxposed 运行时不宜加载，故用独立常量）。 */
        const val HOST_PACKAGE = "com.phoenix.read"
    }
}
