# 分享按钮 Hook

## 需求

在短剧播放页 `com.dragon.read.component.shortvideo.impl.ShortSeriesActivity` 中，
点击分享按钮（view id `hq0`）后出现的分享面板，替换为本模块的"评论词条"页面。

## 逆向分析结论（基于 jadx 反编译）

### hq0 的真实形态

资源 id：`0x7f112dba`（`res/values/public.xml`）。它出现在 **两个布局** 中：

| 布局 | 位置 | 控件 |
|---|---|---|
| `res/layout/d8w.xml` | 播放页右侧竖排栏 | FrameLayout（46dp×46dp，分享图标 + 文本），由 `rightview/SeriesShareView` inflate |
| `res/layout/c96.xml` | 选集弹窗底部横条 | LinearLayout"分享"按钮，由选集弹窗控制器 `impl/catalog/u2` inflate |

两处的点击事件**最终都汇聚到同一调用**：

```java
// com.dragon.read.component.biz.api.NsShareProxy（Kotlin object，类名未混淆）
public void showShortSeriesSharePanel(
    Activity activity,        // 当前 Activity
    ek2.o shortSeriesShareRequestModel,  // 分享请求模型（构造首参 = seriesId）
    ISharePanel iSharePanel,  // bytedance 分享 SDK 面板
    lm3.g gVar)
```

> `u2.t()`（选集弹窗路径）与 `SeriesShareView.a(String,String)`（右侧栏路径）
> 分别构建分享模型后调用该方法。

### 拦截点选择

考虑过的方案对比：

| 方案 | 缺点 |
|---|---|
| hook `View.setOnClickListener` / 点击监听 | 两处按钮分别由混淆类 `u2`、`SeriesShareView` 的 lambda 设置，类名/方法名随混淆映射变化，脆弱 |
| hook `u2.t()` 等私有方法 | 混淆名，版本更迭后失效 |
| **hook `NsShareProxy.showShortSeriesSharePanel`（采用）** | 类名未混淆、签名稳定；一个 hook 点覆盖两个入口；不调用 `proceed()` 即完整替换分享面板 |

校验条件：`activity instanceof ShortSeriesActivity`，保证只影响短剧播放页的分享，
其余页面的分享行为不受影响。若无法取到 seriesId，则放行原分享面板（降级保护）。

## 实现（SharePanelHook.kt）

```kotlin
xposed.hook(method).intercept { chain ->
    val activity = chain.getArg(0) as? Activity
    if (activity != null && shortSeriesCls.isInstance(activity)) {
        val seriesId = ShareModelStore.seriesIdOf(chain.getArg(1)) ?: SeriesIdHolder.current
        if (!seriesId.isNullOrEmpty()) {
            activity.runOnUiThread { Pipeline.onShareClicked(activity, seriesId) }
            return@intercept null      // 替换：不调用 chain.proceed()
        }
    }
    chain.proceed()                    // 非目标页面 / 拿不到 id：放行原逻辑
}
```

## seriesId 采集（三路冗余）

| 优先级 | 来源 | 说明 |
|---|---|---|
| 1 | `ek2.o` 构造函数 hook | 分享模型构造首参即 seriesId，构造后以 WeakHashMap 绑定实例→id，分享拦截时反查 |
| 2 | `SaasVideoData.getSeriesId()` hook | 播放页数据源 getter（类名未混淆），调用频繁，实时记录最近值 |
| 3 | 剧评页 `CSSPlayletCommentListActivity.onCreate` | 读取 Intent extra `seriesId` / `seriesName`，覆盖用户先打开过剧评页的场景 |

三路均写入 `SeriesIdHolder.current`，任一路命中即可弹词条页。

## 已知风险

- `ek2.o` 为混淆类名，app 更新后混淆映射可能变化。方案 2/3 与其相互独立，
  即使方案 1 失效仍可工作；三路全失效时模块放行原分享面板，不会导致 app 异常。
