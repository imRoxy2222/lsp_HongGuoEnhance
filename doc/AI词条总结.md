# AI 词条总结

## 需求

把采集到的剧评交给 AI 总结剧情评价词条（如 `杀伐果断`、`圣母`），
并给出每类词条的出现次数；词条页展示为 `杀伐果断 ×10` 这样的 item，
**按数量降序排列，count 相同时负面词条（negative=true）排在前面**。
AI 的 baseURL / API Key / 模型由用户自行配置。

## 接口

OpenAI 兼容 `/chat/completions`（`HttpURLConnection` 实现，无第三方依赖）：

```
POST {base_url}/chat/completions
Authorization: Bearer {api_key}
Content-Type: application/json

{
  "model": "{model}",
  "temperature": 0.4,
  "enable_thinking": false,
  "thinking": {"type": "disabled"},
  "reasoning": {"enabled": false, "effort": "none"},
  "chat_template_kwargs": {"enable_thinking": false},
  "messages": [
    {"role": "system", "content": "<系统提示词>"},
    {"role": "user",   "content": "剧名：xxx\n以下是 50 条评论：\n1. ...\n2. ..."}
  ]
}
```

任何兼容 OpenAI 协议的服务均可使用（DeepSeek、通义、Kimi、本地 Ollama+代理等），
设置页中 `Base URL` 填到 `/v1` 为止，模块自动拼接 `/chat/completions`。

## 调用进程（AI 进程桥，AiBridge / AiBridgeProvider）

HTTP 请求默认**从模块自身进程发出**而非红果进程（设置页"AI 接口配置"最底部的
**AI 走模块进程** 开关，默认开启）：

- **目的**：使用国外大模型时，代理/VPN 白名单只需放行模块应用；
  红果（视频流量大）无需走代理，节省流量。
- **通道**：宿主进程用 `AiClient.buildRequestText` 组好完整请求体，
  经 `ContentResolver.call()` 发给模块的 `AiBridgeProvider`（authority
  `io.github.imroxy2222.lsp_hongguoenhance.aibridge`），Provider 在模块进程内
  调 `AiClient.summarizeRaw` 执行请求，词条与原始响应同步带回。
  选 ContentProvider 而非 Service/Broadcast：call() 会自动拉起模块进程、
  无广播 10 秒限制、可同步返回。
- **安全**：Provider exported，但 `call()` 内校验调用方 UID 必须为宿主
  `com.phoenix.read`，防止其他应用借用 API Key。
- **失败提示**：模块被系统强停（设置里"强行停止"或省电策略杀后台）时，
  AI 总结报"AI 进程桥不可用"——打开一次模块设置页即可恢复；
  也可关闭开关回到宿主进程直连（`AiClient.summarize`）。
- **日志**：`LogStore.appendTags` 的请求/响应记录与直连模式完全一致
  （请求体由宿主组包后原样回传，响应由 Provider 带回）。

## 关闭思考模式（AiClient.THINKING_OPTIONS 平台适配）

词条总结只需输出固定格式的 JSON，不需要模型深度推理，开启思考只会白白增加
延迟与 token 消耗，故请求体默认关闭思考模式（设置页"AI 接口配置"的
**取消思考** 复选框，默认开启；关闭后请求体不携带任何相关字段）。

OpenAI 兼容协议没有统一的"关闭思考"字段，各家实现不一致；且部分严格服务端
（如 **Groq**）遇到不认识的参数会直接 4xx 报错（如
`property 'chat_template_kwargs' is unsupported`），因此**不做四形状全带**，
而是抽象为平台适配项列表 `AiClient.THINKING_OPTIONS`。设置页"取消思考"行
点击右侧箭头展开，**按平台逐项勾选**要携带的字段（收起后已勾选项仍生效；
全部不勾选即完全关闭；未勾选不携带；默认全选，与旧版行为一致）：

| 选项 id | 字段 | 适用服务端 |
|---|---|---|
| `enable_thinking` | `enable_thinking: false` | 通义/DashScope、SiliconFlow、MiniMax 等 |
| `thinking` | `thinking: {"type": "disabled"}` | **DeepSeek 官方**（[思考模式文档](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode)）、智谱 GLM 等 |
| `reasoning` | `reasoning: {"enabled": false, "effort": "none"}` | **DeepSeek 官方**（`effort: "none"` 即关闭思考）与 OpenRouter（`enabled: false`）两种形状并存 |
| `chat_template_kwargs` | `chat_template_kwargs: {"enable_thinking": false}` | vLLM / SGLang 自部署 |
| `reasoning_effort` | `reasoning_effort: "low"` | **OpenAI 官方**（o 系列推理模型 / GPT-5），**默认不勾选** |

关于 OpenAI 官方的说明：

- **o 系列推理模型（o1/o3/o4 等）无法完全关闭思考**，只能用 `reasoning_effort`
  调低（最低 `low`）；GPT-5 额外支持 `"minimal"`（比 low 更低），需要时可在
  代码中把该选项的值改为 minimal；
- **gpt-4o / gpt-4.1 / chat 系列是非推理模型**，本身没有思考，任何项都不用勾；
- OpenAI 官方接口对未知参数同样会 400 报错（`Unrecognized request argument`），
  使用官方接口时**只勾"OpenAI 官方"一项**，其余平台项全部取消勾选。

**扩展**：新增热门平台只需在 `THINKING_OPTIONS` 列表加一个 `ThinkingOption`
（id / 展示名 / 字段说明 / 字段应用器），设置页 UI 与配置存储自动跟随，无需改动。

**存储**：`thinking_providers` key 存逗号分隔的选项 id 字符串（remote
preferences 对 StringSet 支持不可靠，故用字符串）；未配置时按各选项
`defaultOn` 取默认（`reasoning_effort` 默认不勾选，其余默认勾选，
与旧版行为一致）。

DeepSeek 官方文档要点（2026-10 核对）：

- OpenAI 格式关闭思考：`{"thinking": {"type": "disabled"}}`，或等价的
  `{"reasoning": {"effort": "none"}}`；思考模式默认打开且 effort 默认 `high`；
- 思考模式不支持 `temperature`（不报错但不生效）——本模块已关闭思考，
  `temperature: 0.1` 可正常生效；
- 思考输出的思维链在 `reasoning_content` 字段（与 `content` 同级）返回；
  本模块请求不带 `tools`，无需回传；`parseTags` 只读 `content`，
  天然不受思维链影响。

Ollama 等本地服务忽略上述字段，由所加载模型自身决定是否输出思考内容
（`extractJson` 的容错提取可兜底剥离夹杂在 content 里的思考文本）。

## 提示词设计（AiClient.buildSystemPrompt）

系统提示词要求：

- 角色：短剧评论分析助手；
- 提炼**剧情/人设类**评价词条，并统计每类被提及或体现的次数；
- **只输出 JSON**：`{"tags":[{"tag":"词条","count":10,"negative":false}]}`，按 count 降序、最多 20 条；
  **negative 属性**：负面评价词条（差评、缺点、槽点类）由 AI 标注 `"negative": true`，
  正面/中性词条可省略或填 false；未标注时模块按 false 处理。词条胶囊的条形
  渐变随之区分：普通词条灰黑→萤绿，负面词条灰黑→暗红；
  **排序以客户端为准**（count 降序、count 相同负面在前），不依赖 AI 输出顺序；
- 用户可在设置页追加自定义要求（`extra_prompt`）。

## 解析与容错

1. 取 `choices[0].message.content`；
2. `extractJson`：截取首个 `{` 到最后一个 `}`，兼容模型输出 markdown 围栏或前后缀说明；
3. 逐项取 `tag` / `count`，空 tag 跳过；
4. **客户端强制排序**（`Tag.COMPARATOR`）：`count` 降序为主序，
   count 相同时负面词条（negative=true）排在前面，不依赖模型自觉守序；
5. 解析失败或 tags 为空抛 `AiException`，词条页显示错误并提供"重试"
   （重试 = 用已采集评论**全量重发 AI**，不拉新评论，见
   [重试按钮语义修正.md](重试按钮语义修正.md)）。

## 触发时机

- **首次**：点分享 → 采集完成（≥50 条或无更多）→ 自动调用 AI；
- **继续加载**（**增量总结**）：只把**本批新增**的评论发给 AI（旧评论不再重复
  发送、不重复消耗 token），并把**已有词条列表**一并发给 AI——提示词要求 AI
  **优先沿用**已有词条名（同名便于累计合并），但**仅是优先而非强制**，
  新评论体现新特点时仍可总结新词条。返回后与旧词条**按文本合并 count**
  （同名词条次数相加，negative 按或合并）并重新排序
  （count 降序、count 相同负面在前，与首次总结同一规则）——等价于全量重总结
  的累计结果，但 AI 输入量从累计 N 条降为每批 ≤ 单批加载条数；本批无新增
  （全部与历史重复）时保留原词条直接结束，不调 AI。

## 词条持久缓存（跨 app 重启）

词条结果有两级缓存，命中即不调用 AI：

1. **内存级**：`Pipeline.SeriesCache.tags`（按 seriesId）——
   同一进程内再次点分享同一部剧直接展示；
2. **持久级**（SQLite，跨 app 重启/杀后台有效）：
   - 每次 AI 总结成功后，`LogStore.appendTags` 将词条以 JSON 结构化写入
     宿主日志库 `hge_logs.db` 的 `ai_log.tags` 列（DB v2 起新增），
     同时历史合格评论存于 `comment` 表（`used=1`）；
   - 点分享时内存未命中，`Pipeline` 先查 `LogStore.loadLatestSummary(seriesId)`：
     命中则通过 `loadSessionState` 一次性恢复**词条 + 已用评论 + 采集统计
     （总x/平均得分z）+ 分页游标**，状态栏显示统计行"总x条评论,参考y条,平均得分z"，
     **全程不调用 AI**；恢复词条时按"count 降序、count 相同负面在前"重排
     （兼容历史记录落库时未做负面平局排序）；未命中才发起采集与总结；
   - 恢复会话后"继续加载"正常工作：游标随总结持久化（`series.cursor`），
     从上次位置续拉（不再误判"已全部加载完"）；新拉评论与历史评论文本去重后
     累计，增量总结合并词条（见下"增量总结"）；
   - 清理联动：日志库按保留天数/大小上限清理时，对应剧的缓存词条随之删除
     （`deleteSeries` 级联）。

## 超时与错误

| 项 | 值 |
|---|---|
| 连接超时 | 15s |
| 读取超时 | 120s（长评论列表推理时间较长） |
| 未配置 API Key | 直接提示"请打开模块设置页配置"，不发请求 |
| HTTP 非 2xx / JSON 异常 | 词条页状态栏显示错误信息 + 重试按钮（重试 = 全量重发已采集评论，不拉新评论） |
