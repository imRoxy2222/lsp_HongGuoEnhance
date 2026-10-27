# Release 包无法启动修复（R8 packageScope 问题）

## 现象

- debug 包可正常打开；release 包安装后点图标闪退，app 完全无法启动。
- 宿主（红果短剧）是否受影响取决于模块进程是否先崩溃——本 bug 崩在模块自身进程启动阶段。

## 崩溃日志（关键部分）

```
FATAL EXCEPTION: main
Process: io.github.imroxy2222.lsp_hongguoenhance
java.lang.RuntimeException: Unable to get provider androidx.startup.InitializationProvider:
  n51: java.lang.IllegalAccessError:
  Class kotlin.collections.g extended by class ig is inaccessible
  at androidx.startup.InitializationProvider.onCreate(...)
```

## 根因分析

1. `androidx.startup.InitializationProvider` 在 Application 创建前初始化，通过 `Class.forName` 实例化各 Initializer；类链接阶段即抛 `IllegalAccessError`，属于"启动即崩"，与任何业务代码无关。
2. mapping.txt 显示混淆映射：
   - `kotlin.collections.CollectionsKt__IterablesKt` → `kotlin.collections.g`
   - `kotlin.collections.CollectionsKt__MutableCollectionsJVMKt` → `ig`（**根包**）
3. 后者被 R8 重打包到根包，而父类 `kotlin.collections.g` 的访问级别未随之放宽为 public，跨包继承在类加载校验时失败（`IllegalAccessError`）。
4. 根包重打包来自 `app/build.gradle.kts` release 块中的新 DSL：

   ```kotlin
   optimization {
       enable = true
       packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")   // 问题源头
   }
   ```

   AGP 9.4.1 的 `packageScope` 局部混淆会作用域外的类重命名/重打包到根包，且不保证访问级别一致性——这是该 DSL 的坑，属于不稳定的非默认路径。

## 修复

`app/build.gradle.kts` 移除 `packageScope`，仅保留 `optimization { enable = true }`，回到默认全量 R8（混淆后类名保留原包前缀，不跨包重打包，经过全行业验证的稳定路径）：

```kotlin
release {
    optimization {
        enable = true
        // 不要设置 packageScope，见 doc/Release包无法启动修复.md
    }
    ...
}
```

模块自身代码改为全量混淆后仍安全，原因：

- 无对模块自身类的反射（`CommentFetcher` / `SharePanelHook` 的反射目标全部是宿主类：`com.dragon.read.*`、`ek2.o`、`com.dragon.community.*`、`android.app.*`、`io.reactivex.*`）。
- 入口类 `HookEntry` 已由 keepRules 显式 keep；`META-INF/xposed/java_init.list` 由 `-adaptresourcefilecontents` 同步；`io.github.libxposed.service.**` 已 keep。
- `MainActivity` / `LogViewerActivity` 由 Manifest 引用，AGP 自动 keep。

## 验证

- 重新构建签名 release 包（同 hongguo-release.jks），覆盖安装后启动：
  进程存活（`pidof` 有返回），logcat 无 `AndroidRuntime` / `FATAL` 记录。

## 备注

- 若未来重新引入 `packageScope`，必须先在真机验证启动 + androidx.startup 初始化链路。
- R8 出错时可用 `app/build/outputs/mapping/release/mapping.txt` 反查混淆名对应的原始类。
