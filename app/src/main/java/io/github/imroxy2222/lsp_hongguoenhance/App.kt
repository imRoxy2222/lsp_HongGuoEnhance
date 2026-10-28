package io.github.imroxy2222.lsp_hongguoenhance

import android.app.Application

/**
 * 模块 App 进程入口：尽早注册 LSPosed 框架服务监听（全进程仅一次，
 * 见 [XposedServiceHolder] 类注释说明的单 listener 限制）。
 *
 * 时序：XposedProvider（框架投递 binder，先于本回调缓存）→ Application.onCreate
 * （注册 listener，回放缓存的 binder）→ 各 Activity 订阅。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        XposedServiceHolder.register()
    }
}
