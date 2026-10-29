package io.github.imroxy2222.lsp_hongguoenhance.hook

/**
 * AI 提示词预设模板。
 *
 * **扩展方式**：新增模板只需在 [PRESETS] 列表添加一个 [PromptPreset] 条目，
 * 设置页的单选 chips 与 [resolve] 解析逻辑自动生效，无需改动其他代码。
 *
 * **生效机制**：选中预设时，其 template 文本**替代**"附加提示词"自定义文本，
 * 以"用户补充要求：xxx"的形式追加到 AI 系统提示词末尾
 * （复用 AiClient.buildSystemPrompt 的既有拼接点，宿主/进程桥两条通道天然生效）；
 * 未选中（NONE_ID）时沿用自定义文本，行为与旧版完全一致。
 *
 * **用户改写**：预设模板可在设置页被编辑（[ModuleConfig.KEY_PROMPT_PRESET_OVERRIDES]
 * 按 id 存改写文本），[effectiveTemplate] 保证"改写优先、内置兜底"；
 * 恢复默认即删除对应改写条目。
 */
object PromptPresets {

    /** 未选择任何预设：使用"附加提示词"输入框里用户自定义的文本。 */
    const val NONE_ID = "none"

    /**
     * @param id          配置存储标识（KEY_PROMPT_PRESET 的值，需唯一）
     * @param label       设置页 chip 显示名（建议 2~4 字）
     * @param description 设置页说明行（一句话讲清模板的取向）
     * @param template    追加到系统提示词末尾的模板文本（指令式描述，AI 直接阅读）
     */
    data class PromptPreset(
        val id: String,
        val label: String,
        val description: String,
        val template: String,
    )

    val PRESETS: List<PromptPreset> = listOf(
        PromptPreset(
            id = "hype",
            label = "安利向",
            description = "提炼亮点与高光剧情，词条偏褒义，适合分享种草",
            template = "总结基调偏向安利种草：优先提炼这部剧的亮点、高光剧情与情绪价值，" +
                "词条多用褒义、有感染力的表达；负面词条仅在确实被多条评论提及时保留。",
        ),
        PromptPreset(
            id = "avoid",
            label = "避雷向",
            description = "提炼雷点与劝退点，词条偏贬义并标注负面",
            template = "总结基调偏向避雷排雷：优先提炼这部剧的缺点、雷点与劝退点，" +
                "词条多用贬义表达并标注 negative 字段；优点仅在确实被多条评论提及时保留。",
        ),
        PromptPreset(
            id = "deep",
            label = "深度解析",
            description = "侧重叙事结构与人物塑造，弱化情绪化表达",
            template = "总结基调偏向深度解析：侧重叙事结构、剧情逻辑、人物弧光与演员表现，" +
                "词条偏分析向表达，避免单纯\"好看/难看\"式的情绪化词汇。",
        ),
        PromptPreset(
            id = "no_spoiler",
            label = "无剧透",
            description = "不包含具体剧情走向、关键反转与结局",
            template = "所有词条与评分必须避免剧透：不得出现具体剧情走向、关键反转、" +
                "结局等情节信息，只描述观感、类型特征与制作层面的特点。",
        ),
    )

    fun byId(id: String?): PromptPreset? = PRESETS.firstOrNull { it.id == id }

    /** 该预设当前生效的模板文本：用户改写优先，无改写用内置模板；非预设 id 返回 null。 */
    fun effectiveTemplate(id: String): String? {
        byId(id) ?: return null
        return ModuleConfig.promptPresetOverride(id) ?: byId(id)!!.template
    }

    /**
     * 解析最终生效的"用户补充要求"文本：
     * 选中预设 → 返回该预设生效模板（含改写）；未选中/未知 id → 返回自定义文本。
     */
    fun resolve(presetId: String?, customText: String): String =
        presetId?.let { effectiveTemplate(it) } ?: customText
}
