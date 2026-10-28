package io.github.imroxy2222.lsp_hongguoenhance

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * LSPosed 框架服务的进程级单例持有者。
 *
 * 为什么不能每个 Activity 各自调 [XposedServiceHelper.registerListener]：
 * libxposed-service 102 的 XposedServiceHelper 内部只保存**单个** listener
 * （后注册者直接覆盖前者），且框架投递过来的 binder 在首次 registerListener
 * 回放后即清空缓存、不会重新投递——第二个注册的页面永远收不到 onServiceBind
 * （表象：设置页显示"已连接"，后续打开的页面显示"未连接"）。
 *
 * 因此在 Application onCreate 里**全进程只注册一次**，各页面通过
 * [addListener]/[removeListener] 订阅状态变化；注册时若服务已就绪会立即回调。
 */
object XposedServiceHolder {

    /** 页面侧订阅接口：服务连接/断开（含初始状态）时回调，主线程或 binder 线程均可能触发。 */
    interface Listener {
        fun onServiceChanged(service: XposedService?)
    }

    @Volatile
    var service: XposedService? = null
        private set

    private val listeners = CopyOnWriteArraySet<Listener>()

    /** Application onCreate 调用，全进程仅此一处调用 XposedServiceHelper.registerListener。 */
    fun register() {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(s: XposedService) {
                service = s
                // 采集框架信息（LSPosed / LSPatch 免 root 内嵌运行时），供设置页适配展示
                runCatching {
                    FrameworkEnv.update(s.frameworkName, s.frameworkVersion, s.frameworkProperties)
                }
                listeners.forEach { runCatching { it.onServiceChanged(s) } }
            }

            override fun onServiceDied(s: XposedService) {
                service = null
                listeners.forEach { runCatching { it.onServiceChanged(null) } }
            }
        })
    }

    /**
     * 页面订阅：若服务当前已连接，立即同步回调一次（等价于既有
     * "registerListener 时回放缓存 binder" 的行为）。
     */
    fun addListener(l: Listener) {
        listeners.add(l)
        service?.let { s -> runCatching { l.onServiceChanged(s) } }
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }
}
