# Release适配版本下载指引

## 背景

不再随 Release 附带宿主 APK（130 MB 文件需 Git LFS 且有免费额度限制），
改为在 Release 正文**最顶部**固定写一行适配版本下载指引，指引用户
自行到豌豆荚下载已验证适配的红果短剧版本。

## 指引内容

```markdown
适配红果版本7.2.5.32, [点击下载](https://m.wandoujia.com/apps/8396801/history_v72532)
```

## 生效位置

两个 Release 均生效：

1. **本仓库 Release**（`Create Release (this repo)` 步骤）；
2. **LSPosed 模块仓库 Release**（`Publish Release to LSPosed module repo` 步骤）。

## 实现方式（`.github/workflows/release.yml`）

- 指引文本定义在 **Generate changelog** 步骤的 `HOST_LINE` 环境变量中；
- 写入 `notes` 输出时，`HOST_LINE` 位于第一行，其后才是 `## 更新日志`
  与 commit message 列表——保证指引永远置顶，不被 changelog 压下去；
- 本仓库与 LSPosed 仓库的 Release 正文共用同一份 `notes`，
  新建 Release 与重发 tag（`gh release edit` 刷新正文）两条路径都会带上。

## 维护说明

更换适配的红果版本时，只需修改 `release.yml` 中
**Generate changelog** 步骤里的 `HOST_LINE` 一行
（版本号与下载链接），其余逻辑不动。
