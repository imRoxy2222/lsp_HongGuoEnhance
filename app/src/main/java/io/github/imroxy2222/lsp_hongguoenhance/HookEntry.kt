package io.github.imroxy2222.lsp_hongguoenhance

import io.github.imroxy2222.lsp_hongguoenhance.hook.Pipeline
import io.github.imroxy2222.lsp_hongguoenhance.hook.SharePanelHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * libxposed 102 API 模块入口。
 *
 * 生命周期：LSPosed 将本模块注入红果短剧（com.phoenix.read）进程后，
 * 依次回调 [onPackageLoaded] / [onPackageReady]，在此安装各 hook 点。
 */
class HookEntry : XposedModule() {

    companion object {
        const val TAG = "HongGuoEnhance"
        const val TARGET_APP = "com.phoenix.read"
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        Logger.attach(this)
        // 采集运行框架信息（LSPosed / LSPatch 免 root 内嵌运行时），供日志与适配判断
        runCatching {
            FrameworkEnv.update(
                frameworkName, frameworkVersion.toString(), frameworkProperties
            )
            Logger.i("framework: ${FrameworkEnv.displayName()} (props=$frameworkProperties)")
        }
        if (param.packageName != TARGET_APP) return
        Logger.i("onPackageLoaded: ${param.packageName}")
        try {
            SharePanelHook.install(this, param.defaultClassLoader)
            Pipeline.installLogBootstrap(this, param.defaultClassLoader)
            Logger.i("all hooks installed")
        } catch (t: Throwable) {
            Logger.e("install hooks failed", t)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName != TARGET_APP) return
        Logger.i("onPackageReady: ${param.packageName}")
        Pipeline.onAppReady(this, param.classLoader)
    }
}
