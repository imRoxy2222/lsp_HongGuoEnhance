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

    data class Tag(val text: String, val count: Int)

    /** 一次 AI 调用的完整结果：词条 + 发送的请求体 + 原始响应（日志用）。 */
    data class AiResult(val tags: List<Tag>, val request: String, val response: String)

    class AiException(message: String) : Exception(message)

    fun summarize(comments: List<String>, seriesName: String?): AiResult {
        val baseUrl = ModuleConfig.baseUrl().trimEnd('/')
        val apiKey = ModuleConfig.apiKey()
        if (apiKey.isEmpty()) throw AiException("尚未配置 API Key，请打开模块设置页配置")

        val systemPrompt = buildSystemPrompt()
        val userPrompt = buildUserPrompt(comments, seriesName)

        val body = JSONObject().apply {
            put("model", ModuleConfig.model())
            put("temperature", 0.4)
            put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt)))
        }
        val msg = JSONObject().put("role", "user").put("content", userPrompt)
        body.getJSONArray("messages").put(msg)
        val requestText = body.toString()

        // 网络层偶发断连（服务端/中间链路未返回任何响应字节即断开，
        // 如 keep-alive 僵尸连接、代理重置）自动重试一次；仍失败才向上抛。
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

    private fun buildSystemPrompt(): String {
        val extra = ModuleConfig.extraPrompt()
        return "你是一名短剧评论分析助手。用户会给你一批某部短剧的观众评论，" +
                "请你从中提炼出描述剧情与人设特点的评价词条，" +
                "例如：杀伐果断、圣母、无脑爽文、逻辑漏洞、演技在线、节奏拖沓 等。" +
                "不需要每条评论都提取词条,提取词条为单个词语,不要出现用斜杠分割的多个词语,如\"家人善良/非白眼狼\",同类词语互相合并" +
                "并统计每个词条被评论提及或体现的次数（count）。" +
                "只输出 JSON，不要任何解释、不要 markdown 代码块，格式：" +
                "{\"tags\":[{\"tag\":\"词条\",\"count\":10}]}，按 count 从高到低排列，" +
                "最多输出 10 个词条。" +
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
            tags.add(Tag(t, o.optInt("count", 0)))
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
