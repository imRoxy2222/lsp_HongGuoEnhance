# 免Root框架适配（LSPatch Manager 模式）

## 背景

模块原仅面向 root 环境的 LSPosed。现增加对免 root 方案的支持：
**JingMatrix 维护的 LSPatch 1.x**（基于 Vector 运行时，LSPosed 的社区继任者）。
该版本将框架运行时直接嵌入宿主 APK，无需 root / Zygisk，且支持 libxposed API 102
模块并提供真实的 IXposedService（远程配置可用）。

> 原版 LSPatch（已停更的 0.6.x）对新版 libxposed API 支持不完整，不在适配范围内。

## 支持范围

- **仅支持 Manager 模式**：模块 App 作为普通应用安装在手机上，patched 宿主在运行时
  绑定 LSPatch 管理器。此模式下设置页、AI 进程桥、日志查看全部保留可用。
- **不支持 Embedded 模式**（模块 APK 直接打进宿主、模块 App 不安装）：
  该模式下没有模块 App 进程，设置页与 AI 进程桥均不存在，配置无来源，明确放弃。

## 环境识别（FrameworkEnv）

新增 `FrameworkEnv.kt`（进程级单例），从框架侧采集三类信息：

| 数据 | 宿主进程来源 | 模块 App 进程来源 |
|---|---|---|
| 框架名 | `XposedInterface.getFrameworkName()` | `XposedService.getFrameworkName()` |
| 框架版本 | `getFrameworkVersion()` | `getFrameworkVersion()` |
| 能力位 | `getFrameworkProperties()` | `getFrameworkProperties()` |

判据：

- 框架名含 "lspatch"（不分大小写）→ 免 root 内嵌运行时；
- 否则用 `PROP_CAP_SYSTEM`（=1，能否 hook system_server）能力位区分：
  LSPosed / Vector rootful 必然置位；LSPatch 无此能力。

## 改动清单

1. **`FrameworkEnv.kt`（新增）**：框架信息采集与 root/免 root 判定。
2. **`HookEntry.kt`**：onPackageLoaded 时采集框架信息并写入日志
   （`framework: <名字> <版本> (props=...)`，便于排查运行环境）。
3. **`XposedServiceHolder.kt`**：onServiceBind 时采集框架信息，供设置页使用。
4. **`MainActivity.kt`（设置页）**：
   - 框架服务卡片标题改为动态展示框架名+版本（连接后）；
   - 免 root 环境下不显示"申请作用域"按钮，改为提示"作用域在打包时已固定"；
   - 免 root 环境下禁用"隐藏桌面图标"（无 LSPosed 管理器入口，隐藏后将无法
     重新打开设置页）；
   - 未连接提示文案改为兼容两种框架的说法。
5. **`Pipeline.kt`（AI 进程桥自动回退）**：`aiViaModule` 开启时若进程桥不可用
   （模块 App 被强停 / 免 root Embedded 等原因），自动回退为宿主进程直连 AI，
   并在状态栏提示"进程桥不可用，改为宿主进程直连"，不再作为错误终止。
   该回退同时覆盖 root 环境下模块进程被杀的既有场景。

## 无需改动的部分

- **模块入口 / Hook 本体**：libxposed 102 API 与 LSPlant hook 引擎在 LSPatch 1.x
  与 LSPosed 间通用，`java_init.list`、hook 逻辑原样可用。
- **远程配置**：LSPatch 1.x 提供真实 IXposedService，`getRemotePreferences`
  读写正常（Manager 模式）。
- **日志查看**：本就有"root 直读 → HTTP(LogServer)"双通道；免 root 设备自动
  落到 HTTP 通道，无需修改（root 直读通道在免 root 设备上自然探测失败）。
- **AiBridgeProvider 的 UID 校验**：patched 宿主包名仍为 com.phoenix.read，
  校验照常通过。

## 使用方法（Manager 模式）

1. 安装 LSPatch 管理器（JingMatrix/LSPatch 1.x Release 的 manager.apk）与本模块 APK；
2. 在 LSPatch 中选择红果短剧，打包模式选 **Manager**，并勾选本模块；
3. 安装 patched 后的红果短剧（重签名 APK，无法覆盖官方版，需卸载重装）；
4. 打开本模块设置页正常配置即可。

## 已知限制与风险

- **宿主更新需重新打包**：红果每次发新版都要重新 patch 一次。
- **重签名**：patched APK 与官方签名不同，覆盖安装会失败；需卸载官方版后安装
  （宿主本地数据丢失）。红果若含签名/完整性校验，存在被风控的可能。
- **免 root 设备无法 root 直读日志数据库**：日志查看走 HTTP 通道（功能完整）。
- **Instrumentation bootstrap hook 时机**：Vector 运行时的 Application 启动顺序
  与 LSPosed 不同，LogServer 拉起时机需真机验证。
- **模拟器（MuMu 12）不可用**：见下节。

## MuMu 模拟器崩溃分析（2026-10-28 实测）

在 MuMu 12（x86_64，Android 12）上修补成功、安装成功，但 patched 红果启动即闪退。

崩溃链（来自 lspatch-report 的 tombstone 与 framework log）：

```
ActivityThread.handleBindApplication
→ MuMu 自带 java_patch 注入（ActivityThreadExt.injectJavaPatch
  → com.java.helper.xposed.XposedInit.init_v4）      ← MuMu 魔改 ART 的注入框架
→ LoadedApk.getClassLoader → createAppFactory
→ LSPAppComponentFactoryStub.<clinit>                ← LSPatch 注入的桥接类
→ System.load(管理器 APK 内的 liblspatch.so)
→ nativeLoad 阶段 liblspatch.so 发起 JNI CallObjectMethodV 传入了 NULL jobject
→ ART abort → SIGABRT
```

要点：

1. **与模块无关**：宿主进程日志中无任何 HongGuoEnhance 加载记录，模块代码尚未
   执行；模块 App 进程只是被系统冻结（正常行为）。
2. **修补配置正常**：Manager 模式（useManager=true）、sigBypassLevel=2、
   Shizuku 静默安装、签名替换全部成功。
3. **根因推断**：liblspatch.so 与 MuMu 魔改 ART（自带 java_patch 热修补框架 +
   houdini ARM 转译 + 修改过的 nativeLoad 路径）不兼容；MuMu 的注入框架提前
   触发 appFactory 创建也可能是诱因。LSPatch 1.x 面向 Android 9+ 真机，
   模拟器（尤其魔改 ROM 的 MuMu）不在其支持范围（连 MuMu 自带的
   kernelsu_zygote 进程都在 tombstone 里崩）。

结论：免 root 方案应在**真机**上验证；模拟器环境属框架层限制，模块侧无法规避。
若需跟进，可携 tombstone 向 JingMatrix/LSPatch 提 issue。
