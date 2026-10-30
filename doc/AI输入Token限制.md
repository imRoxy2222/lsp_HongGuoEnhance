# AI 输入 Token 限制

## 需求

免费/低价大模型通常限制单次**输入** token 数，超出会报错（如 419/400）。
现有"首次采集评论数（默认 50）"/"单批加载条数（默认 30）"按**条数**控制
发送量，用自己的付费模型够用；用免费模型时需要按**字符数（token）**兜底。

规则（用户设定口径）：

- **1 个字符 ≈ 0.5 token**（即 1 token ≈ 2 字符）；
- 未超限 → 维持原条数限制（首次采集评论数 / 单批加载条数）；
- 超限 → **字符数限制优先**，自动少发评论直到放得下。

## 设置项

设置页"采集参数"区新增 **AI 输入 Token 上限**（`ai_input_token_limit`，
数字输入，默认 **0 = 不限制**）：填所用模型的输入 token 上限（如 4096）。
system 提示词一并计入估算（输入 token 包含 system 消息）。

## 实现（AiClient.buildRequest）

组包阶段截断，宿主直连与进程桥两条路径都经过此处，无需分别处理：

1. 估算预算：`budget = token 上限 × 2`（字符数）；
2. 先按全量评论组包，若 `systemPrompt.length + userPrompt.length ≤ budget`
   直接通过（未超限，条数限制生效，与旧行为一致）；
3. 超限时**二分查找**能放下的最大评论条数 k（每条评论文本长度固定，
   组包成本低；评论保持原顺序，**从尾部丢弃**）；
4. 连一条完整评论都放不下（极端小预算）：截断**首条评论本体**，
   尾部加 `…`，保证请求仍可发出；
5. 截断后重建 user prompt，头部条数（"以下是 k 条评论："）同步更新；
6. 返回 `BuiltRequest(body, sentComments, totalComments, estimatedTokens)`，
   `estimatedTokens = ceil((system+user 字符数) / 2)`；
   实际发送数 < 总数时 `AiClient.summarize` 记录日志：
   `AI 输入超限：N 条评论发送前 k 条（估算 ~x tokens）`。

注意：

- 估算为**统一口径的粗略值**（汉字/标点/英文混排实际 token 数有差异，
  英文数字实际更省），偏保守方向不会导致报错；若仍遇到 419，可把设置值
  调低一些；
- 截断只影响**发给 AI 的内容**，评论采集、落库、游标、增量合并逻辑均不受
  影响；被丢弃的评论仍在"继续加载"的累计范围内；
- 增量总结时"已有词条"在 system 提示词里，已计入估算。

## 相关代码

| 文件 | 改动 |
|---|---|
| `hook/ModuleConfig.kt` | `KEY_AI_INPUT_TOKEN_LIMIT` / `DEFAULT_AI_INPUT_TOKEN_LIMIT`（0）/ `aiInputTokenLimit()` |
| `hook/AiClient.kt` | `BuiltRequest` + `buildRequest`（二分截断），`buildRequestText` 保留为兼容包装 |
| `MainActivity.kt` | "采集参数"区新增输入框，读写/自动保存/签名去重联动 |

设置页 UI：见 `MainActivity.kt` SettingsScreen"采集参数"区。
