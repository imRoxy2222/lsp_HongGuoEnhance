package io.github.imroxy2222.lsp_hongguoenhance.hook

import android.content.Context
import io.github.imroxy2222.lsp_hongguoenhance.HookEntry
import io.github.imroxy2222.lsp_hongguoenhance.Logger
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 日志查看服务（宿主进程内嵌，裸 TCP）。
 *
 * 模块 App 进程无权限读取宿主（com.phoenix.read）私有 SQLite 数据库，
 * 因此由宿主进程内的本服务在 127.0.0.1 上提供只读查询。
 *
 * 【重要】刻意不使用 HTTP：Android NetworkSecurityConfig 的 cleartext 管控
 * 只作用于 HTTP 栈（HttpURLConnection 等），对裸 java.net.Socket 无效；
 * 上一版走 HTTP 时曾出现"无法连接"（NSC 对 IP 字面量 127.0.0.1 的
 * 明文放行不可靠），故改为最简单的 JSON 行协议：
 *
 *   请求（UTF-8，一行 JSON + '\n'）：
 *     {"op":"ping"}
 *     {"op":"list"}
 *     {"op":"log","seriesId":"123"}
 *   响应（一行 JSON + '\n'，随后关闭连接）：
 *     {"ok":true,"version":2}
 *     {"ok":true,"series":[{seriesId,name,lastUpdate,commentCount,aiCount}...]}
 *     {"ok":true,"data":{"comments":[...],"ai":[...],"errors":[...]}}
 *     {"ok":false,"error":"..."}
 *
 * 安全约束：
 * - 仅绑定 loopback，设备外部无法访问；
 * - 设置页生成的访问令牌经 remote preferences 下发（[ModuleConfig.logToken]），
 *   令牌非空时请求必须携带同值 token 字段。
 *
 * 端口从 [PORT_BASE] 起依次尝试（冲突时 +1），模块端扫描相同区间。
 */
object LogServer {
    private const val TAG = HookEntry.TAG
    const val PORT_BASE = 38080
    const val PORT_TRIES = 12
    private const val VERSION = 2

    @Volatile
    private var started = false

    /**
     * 当前是否为宿主主进程（进程名 == 包名）。
     *
     * Xposed 会注入宿主的全部进程（:sandboxed_process0、:push 等子进程），
     * 若不加限制，所有进程都会抢绑同一批端口——沙箱进程是短命进程，
     * 绑上端口后被系统回收会导致端口忽有忽无；且子进程失败会把
     * "bind_failed" 覆盖写进状态上报，掩盖主进程的真实状态。
     * 因此存储与服务只在主进程启动，子进程一律跳过。
     */
    fun isMainProcess(context: Context): Boolean {
        val pn = try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentProcessName").invoke(null) as? String
        } catch (t: Throwable) {
            runCatching { context.packageName }.getOrNull() // 取不到进程名时保守放行
        }
        return pn == null || pn == context.packageName
    }

    /** 幂等启动：宿主主进程存活期间常驻一个 daemon 线程；非主进程直接跳过。 */
    fun ensureStarted(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            if (!isMainProcess(context)) {
                Logger.i("log server skip: non-main process")
                started = true // 标记为已处理，避免每次调用都重复判断/打日志
                return
            }
            started = true
        }
        // 路径上报多兜底一次（LogStore.init 可能早于 ModuleConfig 初始化而跳过）
        runCatching { LogStore.reportDbPath(context) }
        Thread({
            startLoop()
        }, "hge-log-server").apply { isDaemon = true }.start()
    }

    private fun startLoop() {
        for (i in 0 until PORT_TRIES) {
            val port = PORT_BASE + i
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", port))
                Logger.i("log server listening on 127.0.0.1:$port")
                ModuleConfig.setLogServerStatus("listening:$port")
                serve(ss)
                return
            } catch (t: Throwable) {
                Logger.w("log server bind $port failed: ${t.message}")
            }
        }
        Logger.e("log server: no available port in $PORT_BASE..${PORT_BASE + PORT_TRIES - 1}")
        ModuleConfig.setLogServerStatus("bind_failed")
    }

    private fun serve(ss: ServerSocket) {
        while (true) {
            val socket = try {
                ss.accept()
            } catch (t: Throwable) {
                Logger.e("log server accept failed", t)
                return
            }
            try {
                handle(socket)
            } catch (t: Throwable) {
                Logger.e("log server handle failed", t)
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val line = reader.readLine() ?: return
            val req = try {
                JSONObject(line)
            } catch (t: Throwable) {
                null
            }

            val resp: JSONObject = when {
                req == null -> err("bad request")
                else -> {
                    // ModuleConfig 可能尚未 init（bootstrap 先于 onPackageReady 时），按无令牌处理
                    val expected = runCatching { ModuleConfig.logToken() }.getOrDefault("")
                    if (expected.isNotEmpty() && req.optString("token") != expected) {
                        err("forbidden")
                    } else {
                        when (req.optString("op")) {
                            "ping" -> JSONObject().put("ok", true).put("version", VERSION)
                            "list" -> JSONObject().put("ok", true).put("series", LogStore.listSeries())
                            "log" -> {
                                val seriesId = req.optString("seriesId")
                                if (seriesId.isEmpty()) {
                                    err("missing seriesId")
                                } else {
                                    val data = LogStore.readLog(seriesId)
                                    if (data == null) err("not found")
                                    else JSONObject().put("ok", true).put("data", data)
                                }
                            }
                            else -> err("unknown op: ${req.optString("op")}")
                        }
                    }
                }
            }

            val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            w.write(resp.toString())
            w.write("\n")
            w.flush()
        }
    }

    private fun err(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)
}
