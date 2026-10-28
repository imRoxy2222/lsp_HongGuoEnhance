package io.github.imroxy2222.lsp_hongguoenhance

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 桌面图标（Launcher 入口）显示/隐藏控制。
 *
 * 实现方式：LAUNCHER intent-filter 挂在 [MainActivity] 的 activity-alias
 * （`.LauncherAlias`）上，隐藏 = 通过 PackageManager 把该 alias 置为
 * DISABLED，恢复 = 置回 ENABLED。组件启用状态由系统持久化，
 * 重启 / 覆盖安装后保持，无需额外存储。
 *
 * 隐藏后重新打开：在 LSPosed 管理器中打开本模块。
 */
object LauncherVisibility {

    private fun aliasComponent(context: Context): ComponentName =
        ComponentName(context.packageName, "${context.packageName}.LauncherAlias")

    /** 桌面图标当前是否处于隐藏状态。 */
    fun isHidden(context: Context): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(aliasComponent(context))
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    /** 隐藏或恢复桌面图标。 */
    fun setHidden(context: Context, hidden: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            aliasComponent(context),
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
