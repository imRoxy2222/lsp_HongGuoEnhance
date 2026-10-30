package io.github.imroxy2222.lsp_hongguoenhance.hook

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import io.github.imroxy2222.lsp_hongguoenhance.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 进程桥（宿主进程侧客户端）。
 *
 * 宿主组好完整请求体后经 ContentResolver.call() 发给模块进程的
 * [AiBridgeProvider]，由模块进程执行 HTTP 请求并同步返回结果——
 * AI 流量从模块进程发出，代理白名单只需放行模块应用。
 */
object AiBridge {
    /** 进程桥专用错误：提示用户恢复手段。 */
    class BridgeException(message: String) : Exception(message)

    fun summarize(
        comments: List<String>,
        seriesName: String?,
        previousTags: List<String> = emptyList(),
    ): AiClient.AiResult {
        val requestText = AiClient.buildRequestText(comments, seriesName, previousTags)
        val baseUrl = ModuleConfig.baseUrl().trimEnd('/')
        val apiKey = ModuleConfig.apiKey()

        val req = JSONObject()
            .put(AiBridgeProvider.KEY_BASE_URL, baseUrl)
            .put(AiBridgeProvider.KEY_API_KEY, apiKey)
            .put(AiBridgeProvider.KEY_REQUEST, requestText)
        val extras = Bundle().apply { putString(AiBridgeProvider.EXTRA_REQUEST, req.toString()) }

        val app = context()
        // 顺手确保保活绑定存在（幂等、失败静默；即便绑定失败，
        // 下面的 ContentResolver.call 也会自动拉起模块进程）
        BridgeKeepAlive.ensureBound(app)
        val reply = callBridge(app, extras)

        reply?.getString(AiBridgeProvider.EXTRA_ERROR)?.let { err ->
            // 模块进程内执行失败（接口 4xx/5xx/网络错误/解析失败），原样向上抛
            throw AiClient.AiException(err)
        }
        val tagsJson = reply?.getString(AiBridgeProvider.EXTRA_TAGS)
            ?: throw BridgeException("进程桥返回为空，请重试或改用宿主进程直连")
        val response = reply.getString(AiBridgeProvider.EXTRA_RESPONSE).orEmpty()

        val tags = ArrayList<AiClient.Tag>()
        val arr = JSONArray(tagsJson)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val neg = o.optBoolean("negative", false)
            tags.add(
                AiClient.Tag(
                    o.getString("tag"), o.getInt("count"), neg,
                    !neg && o.optBoolean("neutral", false)
                )
            )
        }
        // 六维评分：桥应答里的 {"剧情":8.5,...}，缺失/损坏为 null（调用方本地回退）
        val dims: FloatArray? = reply.getString(AiBridgeProvider.EXTRA_DIMS)?.let { dj ->
            try {
                val o = JSONObject(dj)
                FloatArray(DimensionAnalyzer.LABELS.size) { i ->
                    o.getDouble(DimensionAnalyzer.LABELS[i]).toFloat().coerceIn(1f, 10f)
                }
            } catch (t: Throwable) {
                null
            }
        }
        Logger.i("ai summarize via module process: tags=${tags.size}, dims=${dims != null}")
        return AiClient.AiResult(tags, requestText, response, dims)
    }

    /**
     * 单次进程桥调用；瞬时失败（模块进程刚被冻结 / 冷启动竞态）等待
     * 800ms 后自动重试一次，两次都失败才判定桥不可用。
     */
    private fun callBridge(app: android.content.Context, extras: Bundle): Bundle? {
        val call: () -> Bundle? = {
            app.contentResolver.call(authorityUri(), AiBridgeProvider.METHOD_SUMMARIZE, null, extras)
        }
        return try {
            call()
        } catch (first: Exception) {
            if (first !is IllegalArgumentException &&
                first !is SecurityException &&
                first !is IllegalStateException
            ) throw first
            Logger.w("进程桥首次调用失败（${first.javaClass.simpleName}），等待 800ms 重试一次")
            try {
                Thread.sleep(800)
                call()
            } catch (e: Exception) {
                throw bridgeUnavailable(e)
            }
        }
    }

    private fun authorityUri(): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority(AiBridgeProvider.AUTHORITY)
        .build()

    private fun bridgeUnavailable(e: Exception): BridgeException =
        BridgeException(
            "AI 进程桥不可用（${e.javaClass.simpleName}）。" +
                "请打开一次模块设置页以激活模块进程后重试，" +
                "或在设置页关闭\"AI 走模块进程\"改为宿主直连。"
        )

    private fun context(): android.content.Context =
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as? android.content.Context
            ?: throw BridgeException("无法获取宿主 Context，无法发起进程桥调用")
}
