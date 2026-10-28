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

        val reply = try {
            context().contentResolver.call(authorityUri(), AiBridgeProvider.METHOD_SUMMARIZE, null, extras)
        } catch (e: IllegalArgumentException) {
            // authority 不存在：模块未安装新版本 / 被强停 / Provider 未生效
            throw bridgeUnavailable(e)
        } catch (e: SecurityException) {
            throw bridgeUnavailable(e)
        } catch (e: IllegalStateException) {
            throw bridgeUnavailable(e)
        }

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
            tags.add(AiClient.Tag(o.getString("tag"), o.getInt("count"), o.optBoolean("negative", false)))
        }
        Logger.i("ai summarize via module process: tags=${tags.size}")
        return AiClient.AiResult(tags, requestText, response)
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
