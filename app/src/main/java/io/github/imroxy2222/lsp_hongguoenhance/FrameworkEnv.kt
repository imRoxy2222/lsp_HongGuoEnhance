package io.github.imroxy2222.lsp_hongguoenhance

/**
 * 运行框架环境（进程级单例）。
 *
 * 模块可能运行在两类框架下：
 * - root 框架（LSPosed / Vector rootful）：可 hook system_server，
 *   框架属性带 [CAP_SYSTEM] 能力位；
 * - 免 root 内嵌运行时（LSPatch 1.x，基于 Vector）：运行时被嵌进宿主 APK，
 *   只能 hook 被 patch 的那个应用，无 [CAP_SYSTEM] 能力位。
 *
 * 判据优先级：框架名含 "lspatch" 直接判定为免 root；否则以 CAP_SYSTEM
 * 能力位区分（LSPosed/Vector rootful 必然 hook system_server，该位稳定为真）。
 *
 * 数据来源：
 * - 宿主进程：HookEntry 在 onPackageLoaded 里从 XposedInterface 采集；
 * - 模块 App 进程：XposedServiceHolder 在 onServiceBind 里从 XposedService 采集。
 * 两个进程各自独立持有，互不通信。
 */
object FrameworkEnv {

    /** IXposedService.PROP_CAP_SYSTEM（未直接依赖 service 接口的常量，避免模块进程强耦合）。 */
    const val CAP_SYSTEM = 1L shl 0

    @Volatile
    private var frameworkName: String? = null

    @Volatile
    private var frameworkVersion: String? = null

    @Volatile
    private var props: Long = 0L

    /** 采集框架信息（两侧入口各自调用一次；重复调用以后到者为准）。 */
    fun update(name: String?, version: String?, frameworkProps: Long) {
        frameworkName = name
        frameworkVersion = version
        props = frameworkProps
    }

    /** 是否为免 root 内嵌运行时（LSPatch）。未知框架（信息未采集到）返回 false。 */
    fun isLspatch(): Boolean {
        val name = frameworkName ?: return false
        if (name.contains("lspatch", ignoreCase = true)) return true
        // Vector 名下也可能是 LSPatch（嵌入运行时与 rootful 同名），用能力位兜底
        return name.contains("vector", ignoreCase = true) && props and CAP_SYSTEM == 0L
    }

    /** 是否为 root 框架（可 hook 系统进程）。 */
    fun isRootFramework(): Boolean = props and CAP_SYSTEM != 0L

    /** 展示用框架标识，如 "LSPosed 1.9.2(102)"、"LSPatch 1.2"；未知时返回 null。 */
    fun displayName(): String? {
        val name = frameworkName ?: return null
        val v = frameworkVersion
        return if (v.isNullOrEmpty()) name else "$name $v"
    }
}
