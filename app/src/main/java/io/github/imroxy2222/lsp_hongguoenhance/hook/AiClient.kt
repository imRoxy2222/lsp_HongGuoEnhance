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

    /** 输入 token 估算口径：1 token ≈ 2 字符（即 1 个字 ≈ 0.5 token）。 */
    internal const val CHARS_PER_TOKEN = 2

    /**
     * 词条：negative=true 表示负面评价词条，neutral=true 表示中性/客观描述词条
     * （两者互斥；AI 未标注时均为 false，即普通词条）。
     */
    data class Tag(val text: String, val count: Int, val negative: Boolean = false, val neutral: Boolean = false) {
        /** 展示优先级：负面最高 > 普通 > 中性最低（count 相同时的次序）。 */
        internal val rank: Int
            get() = when {
                negative -> 2
                neutral -> 0
                else -> 1
            }

        companion object {
            /** 展示排序：主序 count 降序；count 相同时负面 > 普通 > 中性。 */
            val COMPARATOR = compareByDescending<Tag> { it.count }
                .thenByDescending { it.rank }
        }
    }

    /** 一次 AI 调用的完整结果：词条 + 六维评分 + 发送的请求体 + 原始响应（日志用）。
     *  dims = [剧情,音乐,爽感,期待,画面,节奏] 各 1~10 分；AI 未返回（或部分缺失）为 null。 */
    data class AiResult(
        val tags: List<Tag>,
        val request: String,
        val response: String,
        val dims: FloatArray? = null,
    )

    class AiException(message: String) : Exception(message)

    fun summarize(
        comments: List<String>,
        seriesName: String?,
        previousTags: List<String> = emptyList(),
    ): AiResult {
        val built = buildRequest(comments, seriesName, previousTags)
        if (built.sentComments < built.totalComments) {
            Logger.i(
                "AI 输入超限：${built.totalComments} 条评论发送前 ${built.sentComments} 条" +
                        "（估算 ~${built.estimatedTokens} tokens）"
            )
        }
        val baseUrl = ModuleConfig.baseUrl().trimEnd('/')
        val apiKey = ModuleConfig.apiKey()
        return summarizeRaw(baseUrl, apiKey, built.body)
    }

    /**
     * 一次组包结果：请求体 + 实际发送的评论数（输入超限时少于传入数）
     * + 估算输入 token 数（1 字符 ≈ 0.5 token）。
     */
    data class BuiltRequest(
        val body: String,
        val sentComments: Int,
        val totalComments: Int,
        val estimatedTokens: Int,
    )

    /**
     * 组装完整的 /chat/completions 请求体（读取 ModuleConfig）。
     * 独立出来供宿主进程组包后经进程桥发给模块进程执行。
     *
     * AI 输入长度限制（免费模型常有输入 token 上限，超限报 419/400）：
     * 设置页可配置 token 上限，按 1 字符 ≈ 0.5 token 估算（system 提示词
     * 计入）；超限时保持评论原顺序、从尾部丢弃，直到放得下为止——
     * 条数限制（首次采集评论数/单批加载条数）让位于字符数限制。
     * 连一条完整评论都放不下时，截断首条评论本体（尾部加省略号）。
     */
    fun buildRequest(
        comments: List<String>,
        seriesName: String?,
        previousTags: List<String> = emptyList(),
    ): BuiltRequest {
        val apiKey = ModuleConfig.apiKey()
        if (apiKey.isEmpty()) throw AiException("尚未配置 API Key，请打开模块设置页配置")

        val systemPrompt = buildSystemPrompt(previousTags)

        // 长度限制：二分查找能放下的最大评论条数（每条评论长度固定，组包成本低）
        var sent = comments
        if (ModuleConfig.aiInputTokenLimit() > 0 && comments.isNotEmpty()) {
            val budget = ModuleConfig.aiInputTokenLimit() * CHARS_PER_TOKEN
            fun charsOf(k: Int) = systemPrompt.length + buildUserPrompt(comments.take(k), seriesName).length
            if (charsOf(comments.size) > budget) {
                var lo = 1
                var hi = comments.size - 1
                var best = 0
                while (lo <= hi) {
                    val mid = (lo + hi) / 2
                    if (charsOf(mid) <= budget) {
                        best = mid
                        lo = mid + 1
                    } else {
                        hi = mid - 1
                    }
                }
                sent = if (best > 0) comments.take(best) else {
                    // 连 1 条完整评论都放不下：截断首条评论本体
                    // base = 剧名行 + "以下是 1 条评论：" + "1. " + 换行的骨架长度
                    val base = buildUserPrompt(listOf(""), seriesName).length
                    val room = budget - systemPrompt.length - base - 1
                    val text = comments[0].replace('\n', ' ')
                    listOf(if (room in 1 until text.length) text.take(room - 1) + "…" else text)
                }
            }
        }

        val userPrompt = buildUserPrompt(sent, seriesName)

        val body = JSONObject().apply {
            put("model", ModuleConfig.model())
            put("temperature", 0.1) // 调低会让结果趋于稳定
            if (ModuleConfig.disableThinking()) {
                // 仅携带用户勾选平台的"关闭思考"字段（严格服务端如 Groq 遇未知参数会 4xx）
                val enabled = ModuleConfig.thinkingProviders()
                for (opt in THINKING_OPTIONS) if (opt.id in enabled) opt.apply(this)
            }
            put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt)))
        }
        val msg = JSONObject().put("role", "user").put("content", userPrompt)
        body.getJSONArray("messages").put(msg)
        val totalChars = systemPrompt.length + userPrompt.length
        return BuiltRequest(
            body.toString(), sent.size, comments.size,
            (totalChars + 1) / 2  // ceil(chars / 2)：1 字符 ≈ 0.5 token
        )
    }

    /** 兼容旧签名：仅需要请求体文本时使用（组包与截断逻辑见 [buildRequest]）。 */
    fun buildRequestText(
        comments: List<String>,
        seriesName: String?,
        previousTags: List<String> = emptyList(),
    ): String = buildRequest(comments, seriesName, previousTags).body

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
            val (tags, dims) = parseResult(text)
            return AiResult(tags, requestText, text, dims)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * "关闭思考"平台适配项（抽象化，新增平台只需在此列表加一项）。
     *
     * OpenAI 兼容协议没有统一的"关闭思考"字段，各家实现不一致；
     * 且部分严格服务端（如 Groq）遇到不认识的参数会直接 4xx 报错
     * （如 `property 'chat_template_kwargs' is unsupported`），
     * 故按平台拆分成独立选项，由用户在设置页勾选要携带哪些。
     *
     * @param id        配置存储用的标识（同时是设置页勾选集合的元素）
     * @param label     设置页展示名
     * @param fields    该项携带的字段说明（帮助文案用）
     * @param defaultOn 未配置过勾选集合时的默认状态；新增选项应设为 false，
     *                  避免升级后给使用其他平台的既有用户意外带上新字段
     * @param apply     把该平台的"关闭思考"字段写进请求体
     */
    data class ThinkingOption(
        val id: String,
        val label: String,
        val fields: String,
        val defaultOn: Boolean = true,
        val apply: (JSONObject) -> Unit,
    )

    val THINKING_OPTIONS: List<ThinkingOption> = listOf(
        ThinkingOption(
            id = "enable_thinking",
            label = "通义/DashScope、SiliconFlow、MiniMax",
            fields = "enable_thinking: false",
            apply = { it.put("enable_thinking", false) }
        ),
        ThinkingOption(
            id = "thinking",
            label = "DeepSeek官方(推荐)、智谱GLM",
            fields = "thinking: {\"type\": \"disabled\"}",
            apply = { it.put("thinking", JSONObject().put("type", "disabled")) }
        ),
        ThinkingOption(
            id = "reasoning",
            label = "DeepSeek官(effort)、OpenRouter",
            fields = "reasoning: {\"enabled\": false, \"effort\": \"none\"}",
            apply = { it.put("reasoning", JSONObject().put("enabled", false).put("effort", "none")) }
        ),
        ThinkingOption(
            id = "chat_template_kwargs",
            label = "vLLM、SGLang自部署",
            fields = "chat_template_kwargs: {\"enable_thinking\": false}",
            apply = { it.put("chat_template_kwargs", JSONObject().put("enable_thinking", false)) }
        ),
        // OpenAI 官方：o 系列推理模型无法完全关闭思考，只能调低 effort；
        // gpt-5 额外支持 "minimal"（比 low 更低）。gpt-4o/4.1 等非推理模型
        // 本身无思考，无需勾选任何项。注意 OpenAI 官方对未知参数同样会报
        // 400（Unrecognized request argument），使用官方接口时其他项不要勾选。
        ThinkingOption(
            id = "reasoning_effort",
            label = "OpenAI 官方（o 系列推理模型 / GPT-5）",
            fields = "reasoning_effort: \"low\"（GPT-5 可更低，o 系列最低 low）",
            defaultOn = false,
            apply = { it.put("reasoning_effort", "low") }
        ),
    )

    private fun buildSystemPrompt(previousTags: List<String>): String {
        // 选中预设模板时模板文本替代自定义附加提示词；未选中沿用自定义文本
        val extra = PromptPresets.resolve(ModuleConfig.promptPresetId(), ModuleConfig.extraPrompt())
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
                "不必强求每条评论都提取词条,提取词条为单个词语,不要出现用斜杠分割的多个词语,如\"家人善良/非白眼狼\",同类词语互相合并" +
                "并统计每个词条被评论提及或体现的次数（count）。" +
                "每个词条用对象表示，除 tag 与 count 外，" +
                "若该词条属于负面评价（差评、缺点、槽点类），额外加 negative 字段且值为 true；" +
                "若该词条属于无明显褒贬的客观描述或中性评价（如题材、体量、类型特征类），" +
                "额外加 neutral 字段且值为 true；" +
                "正面词条可省略两个字段，或填 false。negative 与 neutral 不会同时为 true；" +
                "拿不准褒贬时按普通词条处理，不要滥用 neutral。" +
                "对于剧集过少,求下一部等的评论需归为中性评价" +
                "只输出 JSON，不要任何解释、不要 markdown 代码块，格式：" +
                "{\"tags\":[{\"tag\":\"词条\",\"count\":10,\"negative\":false,\"neutral\":false}]," +
                "\"dims\":{\"剧情\":8.5,\"音乐\":7,\"爽感\":8,\"期待\":7.5,\"画面\":8,\"节奏\":8.5}}，" +
                "按 count 从高到低排列，最多输出 10 个词条。" +
                "dims 为六维评分：根据这批评论内容，对剧情、音乐、爽感、期待（追更意愿）、" +
                "画面、节奏六个维度各打 1~10 分（一位小数）；" +
                "评论完全未涉及的维度给 5 分左右的中间分，不要凭空猜测。" +
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

    /**
     * 从模型返回中提取 JSON 并解析词条与六维评分。
     * 词条按 count 降序；dims 缺失、部分维度缺失或解析失败时为 null
     * （调用方回退本地关键词分析，不影响词条主流程）。
     */
    private fun parseResult(raw: String): Pair<List<Tag>, FloatArray?> {
        val content = JSONObject(raw)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
        val json = extractJson(content)
        val obj = JSONObject(json)
        val arr = obj.getJSONArray("tags")
        val tags = ArrayList<Tag>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = o.optString("tag").trim()
            if (t.isEmpty()) continue
            // negative 优先于 neutral：模型若同时标注，按负面处理
            val neg = o.optBoolean("negative", false)
            val neu = !neg && o.optBoolean("neutral", false)
            tags.add(Tag(t, o.optInt("count", 0), neg, neu))
        }
        if (tags.isEmpty()) throw AiException("AI 未返回有效词条: ${content.take(200)}")
        Log.i(TAG, "AI tags: ${tags.size}, dims: ${obj.optJSONObject("dims") != null}")
        val dims = obj.optJSONObject("dims")?.let { d ->
            try {
                FloatArray(DimensionAnalyzer.LABELS.size) { i ->
                    d.getDouble(DimensionAnalyzer.LABELS[i]).toFloat().coerceIn(1f, 10f)
                }
            } catch (t: Throwable) {
                null // 任一维度缺失/类型不对 → 整体视为未返回，走本地回退
            }
        }
        return tags.sortedWith(Tag.COMPARATOR) to dims
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
        return "连接成功，耗时 ${ms}ms，词条 ${r.tags.size} 个" +
                if (r.dims != null) "，六维评分正常" else ""
    }
}
