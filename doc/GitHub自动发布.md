# GitHub 自动发布 (tag → APK → Release → LSPosed 仓库)

## 流程总览

```
push 代码到 master ──→ (不触发任何构建)
                          │
你觉得可以发版了 ──→ git tag v<N> && git push origin v<N>
                          │
                          ▼
              GitHub Actions 自动执行:
              1. 打包 APK (versionCode/versionName = commit 次数)
              2. 在本仓库创建 Release, 上传 HongGuoEnhance-v<N>.apk
              3. 在 LSPosed 为你创建的模块仓库里创建同名 Release 并上传同一 APK
```

LSPosed 仓库不放代码，只放 Release 文件、README、summary 等少量信息；所有版本检测都基于 APK 内 `META-INF/xposed/module.prop` 的 `versionCode`。

## 版本号机制

- `versionCode` / `versionName` = 当前分支 commit 次数（见 [版本号自动升级](版本号自动升级.md)）。
- `module.prop` 由构建任务 `generateModuleProp` 在打包时生成，同样写入 `versionCode=<commit 次数>`，并与 APK 一起进产物。**这是 LSPosed 仓库/管理器识别新版本的唯一依据。**
- 因此 **tag 强烈建议命名为 `v<commit 次数>`**（如第 11 次提交打 `v11`）。Action 会自动校验 tag 与 versionCode 是否一致，不一致仅在日志中告警，不会阻断发布。

## 触发方式

```bash
# 日常开发
git push origin master

# 想发版时 (N = 当时的 commit 次数, 可先执行 git rev-list --count HEAD 确认)
git tag v<N>
git push origin v<N>
```

只有 `v*` 形式的 tag 会触发工作流（`.github/workflows/release.yml`），普通 push 不构建，节省 Actions 时长。

## 打包与签名策略

工作流按是否配置签名 secrets 二选一：

| 条件 | 构建产物 | 说明 |
| --- | --- | --- |
| 未配置签名 secrets（默认） | `app-debug.apk` | 用 CI 运行器的 debug key 签名，可直接安装，但 **runner 镜像更新后 debug key 可能变化，届时无法覆盖安装旧版** |
| 配置了签名 secrets | `app-release.apk` | 用你的固定 keystore 签名，**推荐**，可稳定覆盖升级 |

### 推荐：配置固定签名（一次性操作）

1. 本地生成 keystore（放在项目外，勿提交）：

   ```bash
   keytool -genkeypair -v -keystore hongguo-release.jks \
     -keyalg RSA -keysize 2048 -validity 10000 -alias hongguo
   ```

2. 转 base64（Windows Git Bash）：

   ```bash
   base64 -w0 hongguo-release.jks > keystore.b64
   ```

3. 在 GitHub 仓库 **Settings → Secrets and variables → Actions** 添加 4 个 secrets：

   | Secret 名 | 内容 |
   | --- | --- |
   | `KEYSTORE_BASE64` | `keystore.b64` 的全部内容 |
   | `RELEASE_STORE_PASSWORD` | keystore 密码 |
   | `RELEASE_KEY_ALIAS` | `hongguo` |
   | `RELEASE_KEY_PASSWORD` | key 密码 |

   `app/build.gradle.kts` 的 release 构建会自动读取 `RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD` 环境变量完成签名（工作流负责把 secrets 解码成这些变量）。

> ⚠️ 签名一旦对外发布就不要再换：Android 要求覆盖安装必须同签名，换签名 = 用户必须卸载重装。

### 本地构建与 Release 同签名的包

`app/build.gradle.kts` 仅在环境变量 `RELEASE_STORE_FILE` 存在时对 release 签名，本地直接跑 `installRelease` 会产出**未签名 APK**（无法安装）。要本地构建与 GitHub Release 完全同签名的包（Git Bash）：

```bash
export RELEASE_STORE_FILE=hongguo-release.jks   # 相对项目根目录解析, 也可用绝对路径
export RELEASE_STORE_PASSWORD=你的库密码
export RELEASE_KEY_ALIAS=hongguo
export RELEASE_KEY_PASSWORD=你的key密码
./gradlew :app:installRelease "-Dorg.gradle.java.home=C:/Users/Frank2222/.vfox/sdks/java"
```

- 本地 release 包 ↔ CI Release 包：同 jks，签名一致，可互相覆盖安装。
- 任何包 ↔ debug 包（`installDebug` 或未配 secrets 时 CI 的产物）：签名冲突，不能覆盖安装。
- keystore 文件（`.jks` / `.b64`）已在 `.gitignore` 中，放置于项目根目录不会被提交，但建议移到代码区外并备份。

## 同步发布到 LSPosed 模块仓库

前提（对应你申请通过后 LSPosed 为你建的仓库）：

1. 在本仓库 **Settings → Secrets and variables → Actions → Variables** 添加变量：

   | Variable 名 | 内容 |
   | --- | --- |
   | `LSPOSED_REPO` | LSPosed 仓库的 `owner/name`，如 `LSPosed-Modules/io.github.imroxy2222.lsp_hongguoenhance` |

2. 添加 secret `LSPOSED_TOKEN`：一个对上述仓库有 **Contents 读写** 权限的 PAT（Fine-grained token 只勾选该仓库的 `Contents: Read and write`；classic token 勾 `repo`）。默认的 `GITHUB_TOKEN` 无权跨仓库发布，所以需要 PAT。

配置后每次打 tag，工作流最后一步会在 LSPosed 仓库自动创建同名 tag + Release 并上传 APK（Release 描述为镜像说明）。若未配置 `LSPOSED_REPO`，该步自动跳过，仅发布到本仓库。

> README / summary 等少量静态信息建议直接在 LSPosed 仓库里维护（Action 不会覆盖它们，只动 Release）。

## LSPosed 收录申请（你自己操作的流程备忘）

1. 确保包名符合自动审批规则：`io.github.<用户名>.<模块名>`（本项目 `io.github.imroxy2222.lsp_hongguoenhance` 符合，其余包名原则上不予批准）。
2. 按 LSPosed 模块仓库的申请入口提交模块信息（包名、源码地址、作用域 `com.phoenix.read` 等）。
3. 审批通过后 LSPosed 创建模块仓库并授予管理员权限 → 把该仓库的 `owner/name` 填入上面的 `LSPOSED_REPO`，PAT 填入 `LSPOSED_TOKEN`。
4. 此后本仓库每次发 tag，LSPosed 仓库自动出现对应 Release，模块仓库通过 APK 内 `module.prop` 的 `versionCode` 向用户推送更新。

## 常见问题

| 现象 | 原因与处理 |
| --- | --- |
| Actions 报 `./gradlew: Permission denied` | Windows 提交的 `gradlew` 缺少可执行位（git 索引为 `100644`）。修复：`git update-index --chmod=+x gradlew` 后提交推送。注意：**旧 tag 指向的提交仍是坏权限，必须在新提交上重新打 tag**，重推旧 tag 会再次失败 |
| 想重新发同一个 tag | 先删远端 tag 与 Release（`gh release delete v<N> --yes && git push origin :refs/tags/v<N>`），重新打 tag 推送；或 Action 会自动走"Release 已存在 → 覆盖上传 APK"分支 |
| tag 与 versionCode 不一致告警 | 正常，仅提醒命名规范。仓库更新检测以 APK 内 versionCode 为准；但请保证新 tag 的 versionCode **大于**上一个，否则 LSPosed 管理器不会提示更新 |
| 用户覆盖安装失败提示签名不一致 | 发布签名中途换过 keystore。固定 keystore 并从第一次发布起使用 |
| Actions 里 Gradle 报 SDK 缺失 | runner 自带 Android SDK，无需 `local.properties`；若 AGP 升级要求更高 SDK 版本，`gradle/actions/setup-gradle` 前加 `android-actions/setup-android@v3` |
| LSPosed 仓库发布步骤被跳过 | 未配置 `LSPOSED_REPO` 变量或其为空，属预期行为 |
| 本地构建 release 报 `jlink.exe does not exist` | Gradle 守护进程用了不含 jlink 的精简 JRE（如 VS Code Red Hat Java 扩展自带 JRE）。本机解决：`./gradlew :app:assembleRelease "-Dorg.gradle.java.home=C:/Users/Frank2222/.vfox/sdks/java"`（指向 vfox 的完整 JDK）。CI 用 temurin 21 完整 JDK，不受影响 |

## 涉及文件

- `.github/workflows/release.yml` — 工作流本体
- `app/build.gradle.kts` — `generateModuleProp` 任务（生成 module.prop）+ CI 签名支持
- `doc/版本号自动升级.md` — versionCode 来源
