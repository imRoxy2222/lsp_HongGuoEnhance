# HongGuoEnhance

一个基于 **libxposed API 102** 的 LSPosed 模块，作用于红果短剧（`com.phoenix.read`）。
在短剧播放页点击分享按钮时，不再弹出原分享面板，而是弹出自写的 **"评论词条"** 页面：自动拉取该剧的剧评，交给 AI 总结出如 `杀伐果断 ×10`、`圣母 ×4` 的剧情词条，帮助用户快速判断这部剧的好坏。

## 功能特性

| 功能 | 说明 |
| --- | --- |
| 分享按钮 Hook | 拦截 `NsShareProxy.showShortSeriesSharePanel`，用"评论词条"页替换原分享面板 |
| 评论词条页 | 纯代码构建的深色圆角对话框，词条按数量降序展示，附出现次数 |
| 剧评采集 | 复用 app 自身 RPC（`CommentApiService` / 包装层 `h.e`），默认采集 50 条字数 > 20 的评论，支持"继续加载"分页追加 |
| AI 词条总结 | OpenAI 兼容 `/chat/completions` 接口，Base URL / API Key / 模型名均可自行配置 |
| 结果缓存 | 按剧（seriesId）缓存词条，重复点分享不重复请求 |
| 评论词条日志 | 按剧存 SQLite（评论含"是否交给 AI"标记 + AI 请求/响应原文），模块 App 内两级页面查看（评论点击展开 + 红/绿竖条标记），支持按天数/总大小自动清理 |
| 模块设置页 | Compose 编写的模块自身界面，通过 libxposed remote preferences 跨进程下发配置 |

## 环境要求

- Android 10+，已安装并激活 **LSPosed**（内核需支持 libxposed API 100+，已验证 102）
- 红果短剧（`com.phoenix.read`）
- 一个 OpenAI 兼容的 AI 接口（Base URL + API Key + 模型名）

## 快速开始

1. **编译**：

   ```bash
   ./gradlew assembleDebug
   # 产物：app/build/outputs/apk/debug/app-debug.apk
   ```

2. **安装并启用**：安装 APK，在 LSPosed 管理器中启用本模块，勾选作用域 `com.phoenix.read`（也可在模块设置页点"申请作用域"一键完成）。

3. **配置 AI**：打开模块 App，填写 Base URL / API Key / 模型名，保存。

4. **使用**：强停红果短剧后重新打开，进入任意短剧播放页，点击分享按钮即可看到"评论词条"页。词条不够精确时点"继续加载"追加下一批评论并重新总结。

## 技术方案（要点）

- **Hook 点**：未混淆的 `NsShareProxy.showShortSeriesSharePanel(Activity, ek2.o, ...)`。播放页侧栏与选集弹窗两处分享按钮最终都汇聚到该方法，一个 hook 点覆盖全部入口；拦截后不调用 `proceed()` 即可替换面板。seriesId 通过三路冗余采集（分享模型 / `SaasVideoData.getSeriesId()` / 剧评页 Intent），任一路命中即可。
- **评论采集**：反射调用 app 自身保留名静态方法 `CommentApiService.getCommentListRxJava`，优先走剧评页真实使用的包装层 `h.e`（按方法签名在混淆包内 a–z 自动扫描定位，自动补填 `appID`/`complianceStatus` 公共字段），失败自动降级直连路径。签名与公共参数由 app RPC 框架自动处理，比重放 HTTP 稳健。
- **AI 调用**：纯 JDK HTTP 实现，无第三方网络库，注入环境零额外依赖。
- **API 风格**：libxposed 102 全新链式 API（`xposed.hook(method).intercept { chain -> ... }`）。

## 项目结构

```
HongGuoEnhance/
├── app/src/main/java/io/github/imroxy2222/lsp_hongguoenhance/
│   ├── HookEntry.kt          # 模块入口（xposed_init 指向此类）
│   ├── Logger.kt             # 双通道日志（logcat + LSPosed 管理器）
│   ├── MainActivity.kt       # 模块设置页（Compose）
│   ├── LogViewerActivity.kt  # 评论词条日志查看页（Compose 两级页面）
│   └── hook/
│       ├── SharePanelHook.kt # 分享面板拦截 + seriesId 三路采集
│       ├── SeriesIdHolder.kt # seriesId 全局缓存
│       ├── CommentFetcher.kt # 剧评采集（包装层优先 + 直连兜底，含超时降级）
│       ├── AiClient.kt       # OpenAI 兼容 AI 客户端
│       ├── Pipeline.kt       # 采集→总结→缓存 流程编排
│       ├── TagDialog.kt      # "评论词条"对话框 UI
│       ├── LogStore.kt       # 评论词条日志存储（宿主 SQLite：全量评论 + AI 请求/响应，含自动清理）
│       ├── LogServer.kt      # 宿主进程内嵌 127.0.0.1 日志服务（裸 TCP JSON 行协议）
│       └── ModuleConfig.kt   # 跨进程配置读取
├── app/src/main/assets/xposed_init
├── doc/                      # 功能文档（中文命名）
└── README.md
```

## 文档索引

详细实现说明见 [doc/](doc/) 目录：

- [架构总览](doc/架构总览.md) — 模块整体设计与数据流
- [分享按钮Hook](doc/分享按钮Hook.md) — hook 点选型与 seriesId 采集
- [评论采集](doc/评论采集.md) — RPC 复用、分页与过滤策略
- [AI词条总结](doc/AI词条总结.md) — 提示词设计与结果解析
- [评论词条日志](doc/评论词条日志.md) — 按剧分文件存储、App 内两级查看与自动清理
- [设置页与配置](doc/设置页与配置.md) — 配置项与跨进程共享机制
- [安装与使用](doc/安装与使用.md) — 安装步骤、常见问题与失效排查

## 调试

模块日志同时输出到两个通道：

```bash
# logcat 实时查看（tag 为 HongGuoEnhance）
adb logcat -s HongGuoEnhance

# LSPosed 管理器 → 日志 → 模块日志（持久化，不被轮转冲掉）
```

> 注意：红果 app 自身日志量很大，logcat 主缓冲区轮转极快，长时间不抓取可能漏日志；LSPosed 模块日志持久化，优先看那里。

## 兼容性与维护

- 代码中仅对混淆类 `ek2.o`（分享模型）存在单点依赖，且另有两路 seriesId 采集兜底；模型类、枚举、RPC 服务类均为保留名。
- 红果短剧大版本更新后若 hook 失效：模块设计为**失效即放行原分享面板**，不影响 app 正常使用；按 `doc/安装与使用.md` 的排查步骤重新确认类名/签名即可。

## 声明

本项目仅供学习与研究，请勿用于商业用途或非法用途。
