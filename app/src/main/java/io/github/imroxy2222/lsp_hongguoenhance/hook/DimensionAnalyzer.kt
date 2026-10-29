package io.github.imroxy2222.lsp_hongguoenhance.hook

/**
 * 六维评分本地分析器。
 *
 * 用关键词对已采集评论做正/负倾向投票，以"整体评论平均分"为基线，
 * 推导六个维度的 1~10 分。纯本地规则计算，**不额外消耗 AI 调用**。
 *
 * 六维（雷达图六角）：
 * - 剧情：故事/情节/反转等
 * - 音乐：BGM/配乐/插曲等
 * - 爽感：打脸/逆袭/解气等（短剧核心情绪）
 * - 期待：追更/催更/期待续作等
 * - 画面：颜值/特效/妆造等
 * - 节奏：紧凑/拖沓/注水等（补足第六角，与剧情区分）
 *
 * 评分公式：dim = baseline + K × (正票-负票) / max(提及数, M)
 * - baseline = 整体评论平均分（scoreSum / rawCount，10 分制）；
 * - 净倾向占比 ∈ [-1, 1]，按提及条数收敛（最少按 M=8 条计）——
 *   单条评论提及不会大幅拉动，提及越多越有置信度偏离基线；
 * - 从未被提及的维度直接取基线（整体平均分），雷达图呈"平边"。
 */
object DimensionAnalyzer {

    /** 分析结果：labels/scores/mentions 等长（6）。scores ∈ [1,10]。 */
    class Result(
        val labels: List<String>,
        val scores: List<Float>,
        val mentions: List<Int>,
        val baseline: Float,
        val sampleSize: Int,
    )

    private class Dim(val label: String, val pos: List<String>, val neg: List<String>)

    val LABELS: List<String> by lazy { DIMENSIONS.map { it.label } }

    /** 关键词按小写子串匹配（匹配前评论统一 lowercase）。 */
    private val DIMENSIONS = listOf(
        Dim(
            "剧情",
            listOf("剧情", "故事", "情节", "反转", "编剧", "脑洞", "伏笔", "立意", "走心", "有深度"),
            listOf("降智", "烂尾", "狗血", "离谱", "逻辑不通", "看不懂", "尬", "剧情拉胯")
        ),
        Dim(
            "音乐",
            listOf("bgm", "音乐", "配乐", "插曲", "主题曲", "好听", "这歌", "歌声"),
            listOf("难听", "吵死", "吵得")
        ),
        Dim(
            "爽感",
            listOf("爽", "解气", "打脸", "逆袭", "过瘾", "上头", "痛快", "虐哭", "扬眉吐气"),
            listOf("憋屈", "气死", "膈应", "窝囊", "受气")
        ),
        Dim(
            "期待",
            listOf("期待", "追更", "催更", "等更新", "追剧", "第二季", "下一部", "蹲后续", "天天蹲"),
            listOf("弃了", "弃剧", "不想看了", "看不下去", "不看了")
        ),
        Dim(
            "画面",
            listOf("画面", "颜值", "好看", "滤镜", "特效", "妆造", "服装", "质感", "高清", "帅哥", "美女", "漂亮"),
            listOf("太土", "丑", "廉价", "画质差", "辣眼睛")
        ),
        Dim(
            "节奏",
            listOf("节奏", "紧凑", "停不下来", "一集接一集", "一口气看完", "不拖"),
            listOf("拖沓", "注水", "磨叽", "太慢", "快进", "拖后腿", "无聊")
        ),
    )

    /** 净倾向对分值的最大拉动幅度（占比 1.0 时 +2.5 分）。 */
    private const val CONFIDENCE_K = 2.5f

    /** 提及条数的置信下限：少于该数按该数折算，防单条评论大幅拉动。 */
    private const val MIN_MENTIONS = 8

    /**
     * @param comments AI 参考评论（过滤后的评论文本）
     * @param scoreSum 已采集评论评分总和（未评分计 0，与 Pipeline 口径一致）
     * @param rawCount 总采集条数（含被过滤丢弃的）
     */
    fun analyze(comments: List<String>, scoreSum: Double, rawCount: Int): Result {
        val baseline =
            if (rawCount > 0) (scoreSum / rawCount).toFloat().coerceIn(1f, 10f) else 6f
        val lowered = comments.map { it.lowercase() }
        val scores = ArrayList<Float>(DIMENSIONS.size)
        val mentions = ArrayList<Int>(DIMENSIONS.size)
        for (d in DIMENSIONS) {
            var pos = 0
            var neg = 0
            for (c in lowered) {
                if (d.pos.any { c.contains(it) }) pos++
                if (d.neg.any { c.contains(it) }) neg++
            }
            val net = pos - neg
            val total = pos + neg
            mentions.add(total)
            scores.add(
                if (total == 0) baseline
                else (baseline + CONFIDENCE_K * net / maxOf(total, MIN_MENTIONS))
                    .coerceIn(1f, 10f)
            )
        }
        return Result(LABELS, scores, mentions, baseline, comments.size)
    }
}
