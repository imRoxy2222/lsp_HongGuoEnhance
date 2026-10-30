package io.github.imroxy2222.lsp_hongguoenhance.hook

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * AI 进程桥保活锚点（模块进程侧，空服务）。
 *
 * 本服务没有任何业务逻辑，唯一作用是给宿主进程（红果，经 hook 内的
 * [BridgeKeepAlive]）提供 bindService 目标：`BIND_AUTO_CREATE` 绑定会让
 * 系统自动拉起本模块进程，并在宿主存活期间维持绑定——模块进程优先级
 * 随宿主提升，不进入 cached 状态（不会被 Android 12+ 缓存冻结，
 * 清理后台时也不按缓存进程回收），"AI 走模块进程"因此始终可用。
 *
 * exported 必须为 true（跨应用绑定要求）；服务无任何可交互接口，
 * onBind 恒返回 null，任意应用绑定它亦无副作用。
 */
class AiKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
