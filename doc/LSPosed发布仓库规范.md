# LSPosed 发布仓库规范

> 日期：2026-10-28
> 位置：`lsp_repo_git/`（对应 `Xposed-Modules-Repo/io.github.imroxy2222.lsp_hongguoenhance`）

## 仓库角色

`lsp_repo_git/` 是 LSPosed 模块仓库（发布页），**不放源码**；源码托管在 `imRoxy2222/lsp_HongGuoEnhance`，由 `SOURCE_URL` 文件指向。

## 官方要求的元文件（已具备）

| 文件 | 作用 | 状态 |
| --- | --- | --- |
| `README.md` | 模块详情页全文（管理器与 modules.lsposed.org 渲染） | ✅ 已优化 |
| `SUMMARY` | 列表页一句话简介，不支持任何格式化 | ✅ |
| `SOURCE_URL` | 源码仓库链接，内容为一行 URL | ✅ |
| `SCOPE` | JSON 数组，模块未自带作用域时的推荐配置 | ✅ `["com.phoenix.read"]` |
| `ADDITIONAL_AUTHORS` | 额外作者（JSON 数组） | 可选，暂不需要 |
| `HIDE` | 存在则从网站/管理器隐藏模块 | 可选 |

## 仓库有效性硬性要求

1. 仓库名 = 模块包名（`io.github.imroxy2222.lsp_hongguoenhance`）✅
2. 仓库 Description 非空，内容为**模块名称**（在 GitHub 仓库设置中填写）⬜ 需网页端设置
3. 至少一个有效 Release（**在该模块仓库上创建**，bot 只读这里的 Release）：
   - Release 资产中至少包含一个 **APK 文件**
   - Tag 名格式必须为 `VersionCode-VersionName`（如 versionCode=15 → tag `15-15`）
   - Release 标题 = 版本名（VersionName），正文 = 更新日志
   - 最佳实践：创建 Release 时直接附带 APK，bot 会自动修正 Tag；**只改资产不改正文不会触发 bot** ⬜ 待首个版本发布

## 其他设置

- Support/Discussion URL：建议填源码仓库 Issues 链接（仓库设置 Home Page）⬜
- 包名前缀 `io.github.{用户名}` 可免域名 TXT 验证，与当前包名一致 ✅
- 仓库更新后 5–60 分钟内自动同步到 modules.lsposed.org

## 版本发布流程（约定）

1. 源码仓库打 tag（`GitTagVersionSource` 读取 git tag 生成 versionCode/Name，versionName = tag 数字）
2. push v* tag 触发 GitHub Actions（`release.yml`）：
   - 校验 tag 递增 → 构建签名 APK → 校验 APK versionCode 与 tag 一致
   - 本仓库创建 Release（tag 仍为 `vN`）
   - 同步到 LSPosed 模块仓库时，**tag 自动转换为 `VersionCode-VersionName`**（如 `15-15`），标题为版本名
3. 无需手动在模块仓库建 Release；重复运行时若已存在同 tag release 仅覆盖 APK 资产（此情况不会触发 bot 更新，需注意）

> 历史遗留：2026-10-28 前的工作流曾直接用 `v15` 作为模块仓库 tag，被 bot 判定格式无效；已修复为自动转换，并会在发布时自动删除旧格式 release 重建。
