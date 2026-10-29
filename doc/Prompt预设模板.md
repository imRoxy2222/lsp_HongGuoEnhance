# Prompt 预设模板

## 功能说明

设置页「采集参数」分组中，"附加提示词"上方为**提示词预设下拉选择框**：

- **自定义**（默认）：不使用模板，沿用你自己输入的"附加提示词"，行为与旧版一致；
- **安利向**：提炼亮点与高光剧情，词条偏褒义，适合分享种草；
- **避雷向**：提炼雷点与劝退点，词条偏贬义并标注负面；
- **深度解析**：侧重叙事结构、剧情逻辑与人物塑造，弱化情绪化表达；
- **无剧透**：不包含具体剧情走向、关键反转与结局。

选中预设后，模板文本**替代**自定义"附加提示词"，以"用户补充要求：xxx"的形式
追加到 AI 系统提示词末尾（复用原有拼接机制），并显示一行说明；
切回「自定义」则恢复使用输入框里的文本（输入内容不会丢失）。
切换即时自动保存，下次 AI 调用即生效，无需重启红果。

## 模板编辑（下拉项编辑图标）

下拉列表**每一项右侧都有编辑图标**，点击弹出编辑弹窗：

| 元素 | 行为 |
|---|---|
| 文本框 | 预载当前生效文本：预设 = 改写文本（无改写则内置模板）；自定义 = 附加提示词 |
| **保存**（右下角） | 预设 → 改写文本存入 `prompt_preset_overrides`（下拉项标注"已修改"）；自定义 → 写入 `extra_prompt` |
| **默认值**（右下角） | 需**二次确认**弹窗：预设 → 删除改写条目，恢复内置模板（文本框即时刷新）；自定义 → 清空附加提示词 |

点弹窗外部 = 取消，不保存任何修改。

## 代码抽象（如何新增模板）

实现在 `hook/PromptPresets.kt`，模板是**纯数据**：

```kotlin
data class PromptPreset(
    val id: String,          // 配置存储标识（唯一）
    val label: String,       // 设置页下拉项显示名（2~4 字）
    val description: String, // 设置页说明行
    val template: String,    // 追加到系统提示词末尾的指令文本（内置默认值）
)
```

**新增模板只需在 `PRESETS` 列表加一个条目**——设置页下拉项、编辑弹窗、
说明行、配置存取、AI 提示词解析全部自动生效，无需改动其他任何代码。

## 数据流

```
设置页选下拉项 ──autoSave──> remote prefs: prompt_preset = <id>
编辑弹窗保存 ─────────────> remote prefs: prompt_preset_overrides = {<id>: 改写文本}
编辑弹窗恢复默认 ──────────> 删除 overrides 中的 <id> 条目
                                        │
AI 请求组包（宿主进程）                  ▼
AiClient.buildSystemPrompt
  └─ PromptPresets.resolve(promptPresetId(), extraPrompt())
       └─ effectiveTemplate(id)：改写文本 ?? 内置模板
            ├─ 选中预设 → 生效模板文本
            └─ 未选中   → 自定义 extra_prompt 文本
  └─ 拼入系统提示词"用户补充要求：xxx"
```

进程桥模式（AI 走模块进程）下请求体由宿主组好后透传，模板与改写同样生效。

## 实现位置

| 文件 | 职责 |
|---|---|
| `hook/PromptPresets.kt` | 模板数据列表 + `byId` / `effectiveTemplate` / `resolve`（唯一需要改动的扩展点） |
| `hook/ModuleConfig.kt` | `KEY_PROMPT_PRESET`（选中项）、`KEY_PROMPT_PRESET_OVERRIDES`（改写 JSON map）读写 |
| `hook/AiClient.kt` | `buildSystemPrompt` 中经 `resolve` 取得最终补充要求 |
| `MainActivity.kt` | 下拉选择框（ExposedDropdownMenuBox）+ 每项编辑图标 + 编辑弹窗 + 默认值二次确认 |

## 注意

- 模板与自定义文本互斥：选中预设时自定义文本不发送（但保留在输入框中）；
- 未配置过 `prompt_preset` 时默认 `none`（自定义），老用户升级无感知；
- 配置了未知 id（如降级安装）时 `resolve` 回退自定义文本，不会崩溃；
- 改写按模板 id 存储：代码里后续迭代内置模板文案，**未被改写**的用户能直接吃到更新；
  已改写的用户保留自己的版本（属预期行为），可随时一键恢复默认。
