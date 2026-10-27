package io.github.imroxy2222.lsp_hongguoenhance

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * 双通道日志：
 * 1. android.util.Log → logcat（tag: HongGuoEnhance）
 * 2. XposedInterface.log → LSPosed 管理器"模块日志"页
 *
 * 宿主进程内所有模块代码统一使用本类输出，便于调试。
 */
object Logger {
    private const val TAG = HookEntry.TAG

    @Volatile
    private var xposed: XposedInterface? = null

    fun attach(x: XposedInterface) {
        xposed = x
    }

    fun d(msg: String) {
        Log.d(TAG, msg)
        xposed?.log(Log.DEBUG, TAG, msg)
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
        xposed?.log(Log.WARN, TAG, msg)
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        xposed?.log(Log.INFO, TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        if (t != null) {
            xposed?.log(Log.ERROR, TAG, "$msg\n${Log.getStackTraceString(t)}")
        } else {
            xposed?.log(Log.ERROR, TAG, msg)
        }
    }
}
