# 设置界面 UI 优化

## 背景

旧版设置页把每个输入框的用途说明直接塞进 `OutlinedTextField` 的 `label`
参数，提示文字与输入框"融合"在一起，文字较长时排版拥挤，且无法承载多行
说明。

## 改动内容

### 1. 标题与输入框分离 + "?" 帮助徽标

新增组合项 `HelpField`（`MainActivity.kt` 内私有组件）：

- **标题行**：`titleSmall` 字号的独立标题 + 右侧 18dp 圆形
  `secondaryContainer` 底色徽标，徽标内容为 "?"，点击弹出
  `AlertDialog`，正文为该输入框的详细用途说明（含取值范围 / 示例）；
- **输入框**：`OutlinedTextField` 不再使用 `label`，改用 `placeholder`
  展示示例值 / 取值范围，视觉上与标题彻底分离。

各字段的帮助文案：

| 字段 | 说明 |
|---|---|
| Base URL | OpenAI 兼容接口基地址，附示例 |
| API Key | 服务商密钥，仅存本机 |
| 模型名 | 请求使用的模型名称，附示例 |
| 首次采集评论数 | 范围 10~200，默认 50 |
| 单批加载条数 | 范围 5~30，默认 30 |
| 附加提示词 | 可选，追加到系统提示词末尾 |
| 保留天数 | 0=不限，范围 0~3650，默认 30 |
| 总大小上限 MB | 0=不限，范围 0~4096，默认 100 |

"AI 接口配置"分组最底部另有 **取消思考** 复选框（默认开启）与 **AI 走模块进程**
开关（默认开启），标题右侧同样带"?" 徽标：前者说明文案列出可生效平台
（DeepSeek、OpenRouter、通义/DashScope、智谱 GLM、SiliconFlow、MiniMax、
vLLM / SGLang 自部署等；OpenAI 官方接口自动忽略这些字段）；后者说明 AI 请求
改由模块进程发出的用途（代理白名单只需放行模块）与强停后的恢复方式。

### 2. Material3 整体重排

- `CenterAlignedTopAppBar`：居中大标题"红果短剧增强 · 设置"；
- 所有分组改用 `Card`（`surfaceContainerLow` 容器色）包裹：
  框架服务状态 / AI 接口配置 / 采集参数 / 评论词条日志 / 应用；
- 框架服务连接状态由纯文本改为 `primaryContainer` / `errorContainer`
  圆角状态徽标（"已连接" / "未连接"）；
- "隐藏桌面图标"使用 `Switch` 开关（见 [隐藏桌面图标](隐藏桌面图标.md)）；
- 保存按钮保持底部通栏 `Button`。

## 涉及文件

- `app/src/main/java/io/github/imroxy2222/lsp_hongguoenhance/MainActivity.kt`
  （重写 `SettingsScreen`，新增 `SettingsSection`、`HelpField` 私有组件）

## 注意事项

- 帮助对话框状态使用 `rememberSaveable`，屏幕旋转后保持；
- 配置字段、存储 key、校验逻辑均未改动，与旧版完全兼容。
