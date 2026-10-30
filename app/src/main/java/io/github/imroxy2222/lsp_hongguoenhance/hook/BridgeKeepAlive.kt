package io.github.imroxy2222.lsp_hongguoenhance.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.github.imroxy2222.lsp_hongguoenhance.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI 进程桥保活（宿主进程侧）。
 *
 * 以 `bindService(BIND_AUTO_CREATE)` 绑定模块的 [AiKeepAliveService]
 * （空服务），效果：
 * - 绑定会让系统自动拉起模块进程（无需模块常驻前台服务、无通知）；
 * - 宿主存活期间保持绑定，模块进程优先级随宿主提升：宿主前台时模块
 *   进程不进入 cached 状态 → 不会被缓存冻结（Android 12+ cached app
 *   freezer，冻结进程上的 binder 调用易超时），系统/ROM 清理后台时
 *   也不按"缓存进程"回收；
 * - 对用户完全无感：无通知、无前台服务、无电池白名单要求。
 *
 * 无法覆盖的场景：模块 App 处于 stopped 状态（被"强行停止"或 ROM 省
 * 电策略强停）——系统禁止拉起任何 stopped 包的组件，绑定会失败并被
 * 静默忽略；此时由 [AiBridge] 的瞬时失败重试兜底，仍失败则按既有逻辑
 * 回退宿主直连（彻底恢复需打开一次模块设置页）。
 */
object BridgeKeepAlive {

    private const val MODULE_PACKAGE = "io.github.imroxy2222.lsp_hongguoenhance"
    private const val SERVICE_CLS = "io.github.imroxy2222.lsp_hongguoenhance.hook.AiKeepAliveService"

    /** 绑定是否已成功受理（bindService 返回 true）。失败后允许重试。 */
    private val requested = AtomicBoolean(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Logger.i("bridge keepalive connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // 模块进程被杀：BIND_AUTO_CREATE 下系统会自动重建进程并重连，无需处理
            Logger.w("bridge keepalive disconnected")
        }
    }

    /**
     * 幂等绑定：已受理则直接返回；此前失败可再次尝试（模块刚装好 /
     * 刚被拉起后重试即可成功）。所有异常静默——保活只是优化，绝不影响
     * AI 主流程（ContentResolver.call 本身也能拉起进程）。
     */
    fun ensureBound(app: Context?) {
        if (app == null || requested.get()) return
        runCatching {
            val ok = app.bindService(
                Intent().setClassName(MODULE_PACKAGE, SERVICE_CLS),
                connection,
                Context.BIND_AUTO_CREATE
            )
            if (ok) {
                requested.set(true)
                Logger.i("bridge keepalive bound")
            } else {
                Logger.w("bridge keepalive bind rejected（模块 App 不存在或处于 stopped 状态）")
            }
        }.onFailure {
            Logger.w("bridge keepalive bind failed: ${it.message}")
        }
    }
}
