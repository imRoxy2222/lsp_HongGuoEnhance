# AI 进程桥保活

## 问题

"AI 网络走模块进程"依赖宿主（红果）经 `ContentResolver.call()` 访问模块的
`AiBridgeProvider`。call 本身会**自动拉起**模块进程，普通被杀不构成问题；
但以下情况会导致进程桥调用失败，触发回退宿主直连：

1. **模块 App 处于 stopped 状态**（设置里"强行停止"，或国产 ROM 省电策略/
   深度清理强停）——系统禁止拉起任何 stopped 包的组件，这是 AMS 的安全
   限制，无 API 可绕过；
2. **进程被缓存冻结**（Android 12+ cached app freezer）——冻结进程上的
   binder 调用可能超时/异常；
3. 冷启动竞态等瞬时失败。

## 方案：宿主绑定锚住模块进程（无通知、无耗电）

宿主进程内的 hook 代码以 `bindService(BIND_AUTO_CREATE)` 绑定模块新增的
空服务 `AiKeepAliveService`（`BridgeKeepAlive.ensureBound`）：

- 绑定自动拉起模块进程，宿主存活期间维持绑定；
- 模块进程优先级随宿主提升，不进入 cached 状态：不被冻结、
  清理后台时不按缓存进程回收；
- 对用户完全无感：无前台服务、无通知、无电池白名单要求；
- 绑定幂等（`AtomicBoolean` 去重），失败可重试；所有异常静默，
  绝不影响 AI 主流程（call 本身也能拉起进程，保活只是优化）。

绑定时机（三处兜底，均幂等）：

| 位置 | 说明 |
|---|---|
| `Pipeline.installLogBootstrap` | hook Instrumentation，Application 创建第一时间绑定（最早、最可靠） |
| `Pipeline.onAppReady` | onPackageReady 回调兜底 |
| `AiBridge.summarize` | 每次桥调用前顺手确保（覆盖宿主启动早于模块安装等时序） |

仅当 `ModuleConfig.aiViaModule()` 为 true（默认）才绑定；关闭"AI 走模块
进程"后不产生任何绑定。

## 瞬时失败重试（AiBridge）

`callBridge`：进程桥调用抛 `IllegalArgumentException` / `SecurityException` /
`IllegalStateException` 时，等待 800ms 后**自动重试一次**（覆盖解冻与冷启动
竞态）；两次都失败才抛 `BridgeException`，按既有逻辑回退宿主直连。

## 无法覆盖的场景与用户侧建议

- **stopped 状态拉不起来**（强行停止/省电强停后，绑定与 call 均被系统拒绝）：
  打开一次模块设置页即可恢复（stopped 状态只在用户显式启动后解除）；
- **划掉最近任务卡片**：多数国产 ROM 上等效于强行停止（可用
  `adb shell dumpsys package io.github.imroxy2222.lsp_hongguoenhance | grep stopped`
  验证，`stopped=true` 即强停）——此时回退宿主直连属预期行为，
  建议在最近任务中锁定模块卡片，或接受回退；
- **ROM"自启动"权限**：MIUI/HyperOS 等系统中，模块未授予自启动时，
  其他应用发起的跨应用组件拉起（bind/call）会被 ROM 拦截，即使进程只是
  普通被杀也不会自动复活——需在 ROM 的自启动管理中放行模块 App；
- 强 stopped 往往来自 ROM 的省电/后台策略，建议在系统的电池优化中将本模块
  设为"不允许优化"，并在 ROM 的自启动/后台管理里放行模块 App；
- 免 root Embedded 模式（LSPatch）没有独立模块 App，进程桥必然不可用，
  维持回退宿主直连的设计。

## 相关代码

- `hook/BridgeKeepAlive.kt` —— 宿主侧绑定管理（幂等、静默）
- `hook/AiKeepAliveService.kt` —— 模块侧空锚点服务（`exported="true"`，
  无接口无数据，任意应用绑定无副作用）
- `hook/AiBridge.kt` —— `callBridge` 瞬时失败重试
- `AndroidManifest.xml` —— `<service android:name=".hook.AiKeepAliveService" android:exported="true" />`
