# AI 词条总结

## 需求

把采集到的剧评交给 AI 总结剧情评价词条（如 `杀伐果断`、`圣母`），
并给出每类词条的出现次数；词条页展示为 `杀伐果断 ×10` 这样的 item，
**按数量降序**排列。AI 的 baseURL / API Key / 模型由用户自行配置。

## 接口

OpenAI 兼容 `/chat/completions`（`HttpURLConnection` 实现，无第三方依赖）：

```
POST {base_url}/chat/completions
Authorization: Bearer {api_key}
Content-Type: application/json

{
  "model": "{model}",
  "temperature": 0.4,
  "messages": [
    {"role": "system", "content": "<系统提示词>"},
    {"role": "user",   "content": "剧名：xxx\n以下是 50 条评论：\n1. ...\n2. ..."}
  ]
}
```

任何兼容 OpenAI 协议的服务均可使用（DeepSeek、通义、Kimi、本地 Ollama+代理等），
设置页中 `Base URL` 填到 `/v1` 为止，模块自动拼接 `/chat/completions`。

## 提示词设计（AiClient.buildSystemPrompt）

系统提示词要求：

- 角色：短剧评论分析助手；
- 提炼**剧情/人设类**评价词条，并统计每类被提及或体现的次数；
- **只输出 JSON**：`{"tags":[{"tag":"词条","count":10}]}`，按 count 降序、最多 20 条；
- 用户可在设置页追加自定义要求（`extra_prompt`）。

## 解析与容错

1. 取 `choices[0].message.content`；
2. `extractJson`：截取首个 `{` 到最后一个 `}`，兼容模型输出 markdown 围栏或前后缀说明；
3. 逐项取 `tag` / `count`，空 tag 跳过；
4. **客户端强制 `sortedByDescending { count }`**，不依赖模型自觉守序；
5. 解析失败或 tags 为空抛 `AiException`，词条页显示错误并提供"重试"。

## 触发时机

- **首次**：点分享 → 采集完成（≥50 条或无更多）→ 自动调用 AI；
- **继续加载**（**增量总结**）：只把**本批新增**的评论发给 AI（旧评论不再重复
  发送、不重复消耗 token），返回后与旧词条**按文本合并 count**（同名词条次数
  相加）并重新按数量降序——等价于全量重总结的累计结果，但 AI 输入量从
  累计 N 条降为每批 ≤ 单批加载条数；本批无新增（全部与历史重复）时保留
  原词条直接结束，不调 AI。

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
     **全程不调用 AI**；未命中才发起采集与总结；
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
| HTTP 非 2xx / JSON 异常 | 词条页状态栏显示错误信息 + 重试按钮 |
