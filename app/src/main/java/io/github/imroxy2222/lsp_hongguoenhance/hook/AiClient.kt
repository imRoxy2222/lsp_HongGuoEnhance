package io.github.imroxy2222.lsp_hongguoenhance.hook

import android.util.Log
import io.github.imroxy2222.lsp_hongguoenhance.HookEntry
import io.github.imroxy2222.lsp_hongguoenhance.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * AI 词条总结客户端（OpenAI 兼容 /chat/completions 接口）。
 *
 * baseURL / apiKey / 模型名 / 附加提示词均由用户在模块设置页配置，
 * 通过 libxposed remote preferences 在设置页进程与本模块进程间共享。
 */
object AiClient {
    private const val TAG = HookEntry.TAG
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 120_000

    /**
     * 词条：negative=true 表示负面评价词条（AI 未标注时为 false）。
     */
    data class Tag(val text: String, val count: Int, val negative: Boolean = false)

    /** 一次 AI 调用的完整结果：词条 + 发送的请求体 + 原始响应（日志用）。 */
    data class AiResult(val tags: List<Tag>, val request: String, val response: String)

    class AiException(message: String) : Exception(message)

    fun summarize(
        comments: List<String>,
        seriesName: String?,
        previousTags: List<String> = emptyList(),
    ): AiResult {
        val requestText = buildRequestText(comments, seriesName, previousTags)
        val baseUrl = ModuleConfig.baseUrl().trimEnd('/')
        val apiKey = ModuleConfig.apiKey()
        return summarizeRaw(baseUrl, apiKey, requestText)
    }

    /**
     * 组装完整的 /chat/completions 请求体（读取 ModuleConfig）。
     * 独立出来供宿主进程组包后经进程桥发给模块进程执行。
     */
    fun buildRequestText(
        comments: List<String>,
        seriesName: String?,
        previousTags: List<String> = emptyList(),
    ): String {
        val apiKey = ModuleConfig.apiKey()
        if (apiKey.isEmpty()) throw AiException("尚未配置 API Key，请打开模块设置页配置")

        val systemPrompt = buildSystemPrompt(previousTags)
        val userPrompt = buildUserPrompt(comments, seriesName)

        val body = JSONObject().apply {
            put("model", ModuleConfig.model())
            put("temperature", 0.1) // 调低会让结果趋于稳定
            if (ModuleConfig.disableThinking()) putDisableThinking()
            put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt)))
        }
        val msg = JSONObject().put("role", "user").put("content", userPrompt)
        body.getJSONArray("messages").put(msg)
        return body.toString()
    }

    /**
     * 用现成的请求体执行总结（不读配置，任何进程可调用）。
     * 网络层偶发断连（服务端/中间链路未返回任何响应字节即断开，
     * 如 keep-alive 僵尸连接、代理重置）自动重试一次；仍失败才向上抛。
     */
    fun summarizeRaw(baseUrl: String, apiKey: String, requestText: String): AiResult {
        var lastErr: IOException? = null
        repeat(2) { attempt ->
            try {
                return doSummarize(baseUrl, apiKey, requestText)
            } catch (e: AiException) {
                throw e // 接口有明确响应（4xx/5xx/解析失败）：不重试
            } catch (e: IOException) {
                lastErr = e
                if (attempt == 0) {
                    Logger.w("ai summarize network error, retrying once: ${e.message}")
                }
            }
        }
        throw lastErr ?: AiException("AI 请求失败")
    }

    /** 单次请求（不重试）。 */
    private fun doSummarize(baseUrl: String, apiKey: String, requestText: String): AiResult {
        val conn = URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.doOutput = true
            // 禁用 keep-alive 复用：避免 OkHttp 连接池中的旧连接被服务端
            // 提前关闭导致 "unexpected end of stream"（本请求频率极低，无复用收益）
            conn.setRequestProperty("Connection", "close")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(requestText.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw AiException("AI 接口返回 $code: ${text.take(300)}")
            }
            return AiResult(parseTags(text), requestText, text)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 关闭思考模式。OpenAI 兼容协议本身没有统一的"关闭思考"字段，
     * 各家实现不一致，故同时携带多个常见字段，兼容服务端会忽略不认识的字段：
     * - enable_thinking=false                            通义/DashScope、SiliconFlow、MiniMax 等
     * - thinking={"type":"disabled"}                     DeepSeek 官方（api-docs/guides/thinking_mode）、智谱 GLM 等
     * - reasoning={"enabled":false,"effort":"none"}       DeepSeek 官方（effort=none 即关闭）与
     *                                                    OpenRouter（enabled=false）两种形状并存
     * - chat_template_kwargs={"enable_thinking":false}   vLLM / SGLang 自部署
     */
    private fun JSONObject.putDisableThinking() {
        put("enable_thinking", false)
        put("thinking", JSONObject().put("type", "disabled"))
        put("reasoning", JSONObject()
            .put("enabled", false)
            .put("effort", "none"))
        put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
    }

    private fun buildSystemPrompt(previousTags: List<String>): String {
        val extra = ModuleConfig.extraPrompt()
        // "继续加载"增量模式下带上已有词条：优先沿用同名词条，方便累计合并；
        // 仅是优先而非强制，避免新特点被旧词条集合限死
        val prefer = if (previousTags.isEmpty()) "" else
            "此前已总结出以下词条：${previousTags.joinToString("、")}。\n" +
            "本次总结请优先沿用上述词条：若新评论体现的特点与已有词条相同，" +
            "必须使用完全相同的词条名，以便累计合并计数；" +
            "但这只是优先而非强制——若新评论体现了已有词条未覆盖的新特点，" +
            "可以总结新词条。\n"
        return "你是一名短剧评论分析助手。用户会给你一批某部短剧的观众评论，" +
                "请你从中提炼出描述剧情与人设特点的评价词条，" +
                "例如：杀伐果断、圣母、无脑爽文、逻辑漏洞、演技在线、节奏拖沓 等。" +
                "不需要每条评论都提取词条,提取词条为单个词语,不要出现用斜杠分割的多个词语,如\"家人善良/非白眼狼\",同类词语互相合并" +
                "并统计每个词条被评论提及或体现的次数（count）。" +
                "每个词条用对象表示，除 tag 与 count 外，" +
                "若该词条属于负面评价（差评、缺点、槽点类），额外加 negative 字段且值为 true；" +
                "正面或中性词条可省略 negative 字段，或填 false。" +
                "只输出 JSON，不要任何解释、不要 markdown 代码块，格式：" +
                "{\"tags\":[{\"tag\":\"词条\",\"count\":10,\"negative\":false}]}，按 count 从高到低排列，" +
                "最多输出 10 个词条。" +
                (if (prefer.isNotEmpty()) "\n$prefer" else "") +
                (if (extra.isNotEmpty()) "\n用户补充要求：$extra" else "")
    }

    private fun buildUserPrompt(comments: List<String>, seriesName: String?): String {
        val sb = StringBuilder()
        if (!seriesName.isNullOrEmpty()) sb.append("剧名：").append(seriesName).append('\n')
        sb.append("以下是 ").append(comments.size).append(" 条评论：\n")
        comments.forEachIndexed { i, c ->
            sb.append(i + 1).append(". ").append(c.replace('\n', ' ')).append('\n')
        }
        return sb.toString()
    }

    /** 从模型返回中提取 JSON 并解析词条，按 count 降序。 */
    private fun parseTags(raw: String): List<Tag> {
        val content = JSONObject(raw)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
        val json = extractJson(content)
        val arr = JSONObject(json).getJSONArray("tags")
        val tags = ArrayList<Tag>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = o.optString("tag").trim()
            if (t.isEmpty()) continue
            tags.add(Tag(t, o.optInt("count", 0), o.optBoolean("negative", false)))
        }
        if (tags.isEmpty()) throw AiException("AI 未返回有效词条: ${content.take(200)}")
        Log.i(TAG, "AI tags: ${tags.size}")
        return tags.sortedByDescending { it.count }
    }

    /** 容错提取 JSON（去掉 markdown 围栏/前后说明文字）。 */
    private fun extractJson(s: String): String {
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiException("AI 返回内容不是 JSON: ${s.take(200)}")
        return s.substring(start, end + 1)
    }

    /** 测试连通性（设置页用）。 */
    fun testConnection(): String {
        val start = System.nanoTime()
        val r = summarize(
            listOf("这部剧很好看，主角杀伐果断不拖泥带水，强烈推荐大家观看。"), null
        )
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        return "连接成功，耗时 ${ms}ms，词条 ${r.tags.size} 个"
    }
}
