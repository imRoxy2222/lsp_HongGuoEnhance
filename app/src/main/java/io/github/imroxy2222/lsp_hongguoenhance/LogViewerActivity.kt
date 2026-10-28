package io.github.imroxy2222.lsp_hongguoenhance

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.imroxy2222.lsp_hongguoenhance.hook.LogServer
import io.github.imroxy2222.lsp_hongguoenhance.hook.ModuleConfig
import io.github.imroxy2222.lsp_hongguoenhance.ui.theme.HongGuoEnhanceTheme
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

/**
 * "评论词条"日志查看页（模块 App 进程）。
 *
 * 两级结构：
 *   一级 —— 按剧列表（剧名优先，未拿到剧名时显示 seriesId）；
 *   二级 —— 全部抓取到的评论（默认 2 行，点击展开；左侧竖条长度随行数伸缩，
 *           绿色 = 交给 AI 提取词条，红色 = 被过滤丢弃），
 *           右下角 FAB 进入 AI 调用记录页（请求/响应，KV 解析 + 复制原文）。
 *
 * 数据来源（两条通道）：
 *   1. [RootLogReader]（优先）：模块被授予 root（KernelSU/Magisk 均可）时，
 *      用 `su -c cat` 把宿主私有 SQLite 拉到本模块缓存目录后本地查询——
 *      不依赖宿主进程存活，也没有跨进程连接问题；
 *   2. [LogApiClient]（回退）：无 root 时走宿主进程内嵌 [LogServer]
 *      （127.0.0.1 裸 TCP JSON 行协议）。
 * 存储为宿主私有 SQLite（见 doc/评论词条日志.md）。
 */

// ---------------------------------------------------------------- 数据模型

private class SeriesEntry(
    val seriesId: String,
    val name: String?,
    val lastUpdate: Long,
    val commentCount: Int,
    val aiCount: Int,
)

private data class CommentRec(val time: Long, val text: String, val used: Boolean)
private data class AiRec(val time: Long, val baseCount: Int, val request: String?, val response: String?)
private data class ErrorRec(val time: Long, val msg: String)
private data class SeriesLog(
    val comments: List<CommentRec>,
    val ai: List<AiRec>,
    val errors: List<ErrorRec>,
)

/** AI 回复解析失败等场景的 KV 行。 */
private typealias Kv = List<Pair<String, String>>

// ---------------------------------------------------------------- 常量与缓存（避免每帧重复分配）

/** 时间格式化器缓存（仅 UI 线程使用；SimpleDateFormat 非线程安全但此处单线程）。 */
private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
private val itemShape = RoundedCornerShape(8.dp)
private val barShape = RoundedCornerShape(2.dp)
private val listPadding = PaddingValues(16.dp)

// ---------------------------------------------------------------- Root 直读（优先通道）

/**
 * root 直读宿主 SQLite：`su -c cat` 把宿主 DB（含 -wal/-shm）拉到本模块
 * cacheDir/hge_logs/ 后本地查询。模块进程无权限直读宿主私有目录
 * （SELinux 按应用隔离），root 是绕过该隔离的直接手段。
 *
 * 路径定位（按序尝试，任一成功即缓存复用）：
 *   1. 宿主端经 remote preferences 上报的真实路径（[ModuleConfig.KEY_LOG_DB_PATH]，
 *      多用户/应用分身场景下 /data/data 不一定是宿主实际目录）；
 *   2. /data/data/<宿主>/databases/hge_logs.db；
 *   3. /data/user/0/<宿主>/databases/hge_logs.db。
 *
 * su 形态（su -c / su --mount-master -c）同样自动回退：部分 root 实现
 * （KernelSU 等）默认在调用方 App 的受限挂载命名空间内执行命令，
 * /data/user 下的路径可能不可见，--mount-master 切到全局命名空间即可。
 */
private object RootLogReader {
    const val HOST_PKG = "com.phoenix.read"
    private const val DB_REL = "databases/hge_logs.db"

    class RootUnavailableException(msg: String) : Exception(msg)

    /** 宿主数据库读取失败；message 携带各候选路径的失败原因与目录诊断。 */
    class HostDbMissingException(msg: String) : Exception(msg)

    @Volatile
    private var rootOk = false

    /** 宿主端上报的真实数据库路径（remote preferences 同步，见 Activity）。 */
    @Volatile
    var hostDbPath: String = ""

    /** 上次成功的 su 形态（0=普通，1=--mount-master），命中后优先复用。 */
    @Volatile
    private var workingVariant = 0

    /**
     * root 可用性。只在**成功**时缓存——失败不缓存，否则用户在 KernelSU
     * 弹窗里授权后，本进程内永远拿不到 root（首屏自动加载会先探测一次）。
     */
    fun available(): Boolean {
        if (rootOk) return true
        val ok = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.contains("uid=0")
        } catch (t: Throwable) {
            false
        }
        if (ok) rootOk = true
        return ok
    }

    private fun suCmd(mountMaster: Boolean, cmd: String): Array<String> =
        if (mountMaster) arrayOf("su", "--mount-master", "-c", cmd)
        else arrayOf("su", "-c", cmd)

    /** cat 一个文件；两种 su 形态依次尝试（成功的形态优先）。失败抛异常（携带 stderr）。 */
    private fun suCat(path: String): ByteArray {
        var lastErr = ""
        for (v in listOf(workingVariant, 1 - workingVariant).distinct()) {
            val p = Runtime.getRuntime().exec(suCmd(v == 1, "cat '$path'"))
            val bytes = p.inputStream.readBytes()
            val code = p.waitFor()
            if (code == 0) {
                workingVariant = v
                return bytes
            }
            lastErr = p.errorStream.bufferedReader().readText().trim().take(200)
        }
        throw IllegalStateException("cat $path 失败: $lastErr")
    }

    private fun candidatePaths(): List<String> {
        val l = mutableListOf<String>()
        if (hostDbPath.isNotBlank()) l.add(hostDbPath)
        l.add("/data/data/$HOST_PKG/$DB_REL")
        l.add("/data/user/0/$HOST_PKG/$DB_REL")
        return l.distinct()
    }

    private fun openDb(context: Context): SQLiteDatabase {
        if (!available()) {
            throw RootUnavailableException("模块未获得 root 权限（请在 KernelSU/Magisk 中授权 HongGuoEnhance）")
        }
        var lastErr = ""
        for (path in candidatePaths()) {
            try {
                val bytes = suCat(path)
                if (bytes.size < 100) {
                    lastErr = "宿主数据库读取异常（仅 ${bytes.size} 字节）: $path"
                    continue
                }
                val dir = File(context.cacheDir, "hge_logs").apply { mkdirs() }
                val db = File(dir, "hge_logs.db")
                db.writeBytes(bytes)
                // WAL 模式下主文件可能不含最新写入，一并拉取（缺失则忽略）
                runCatching { File(dir, "hge_logs.db-wal").writeBytes(suCat("$path-wal")) }
                runCatching { File(dir, "hge_logs.db-shm").writeBytes(suCat("$path-shm")) }
                return SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            } catch (t: Throwable) {
                lastErr = "${t.message ?: t.javaClass.simpleName}"
            }
        }
        // 全部失败：列各候选所在目录作诊断
        val lsDiag = runCatching {
            candidatePaths().map { it.substringBeforeLast('/') }.distinct().mapNotNull { d ->
                for (v in listOf(workingVariant, 1 - workingVariant).distinct()) {
                    val p = Runtime.getRuntime().exec(suCmd(v == 1, "ls -a '$d'"))
                    val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
                    val code = p.waitFor()
                    if (code == 0) {
                        return@mapNotNull "$d → ${out.trim().replace('\n', '、').take(150)}"
                    }
                }
                null
            }
        }.getOrDefault(emptyList())
        throw HostDbMissingException(
            ("最后错误：$lastErr" + lsDiag.joinToString("；", prefix = "；目录诊断：")).take(500)
        )
    }

    /** 剧列表：按最近更新降序，附评论数与 AI 调用次数。 */
    fun listSeries(context: Context): List<SeriesEntry> =
        openDb(context).use { d ->
            val out = ArrayList<SeriesEntry>()
            d.rawQuery(
                "SELECT s.series_id, s.name, s.last_update," +
                        " (SELECT COUNT(*) FROM comment c WHERE c.series_id = s.series_id)," +
                        " (SELECT COUNT(*) FROM ai_log a WHERE a.series_id = s.series_id)" +
                        " FROM series s ORDER BY s.last_update DESC",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        SeriesEntry(
                            seriesId = c.getString(0),
                            name = c.getString(1)?.takeIf { it.isNotBlank() },
                            lastUpdate = c.getLong(2),
                            commentCount = c.getInt(3),
                            aiCount = c.getInt(4),
                        )
                    )
                }
            }
            out
        }

    /** 单剧全部日志（剧不存在时返回空结构，UI 显示"没有评论记录"）。 */
    fun readLog(context: Context, seriesId: String): SeriesLog =
        openDb(context).use { d ->
            val comments = ArrayList<CommentRec>()
            d.rawQuery(
                "SELECT time, text, used FROM comment WHERE series_id=? ORDER BY _id ASC",
                arrayOf(seriesId)
            ).use { c ->
                while (c.moveToNext()) {
                    comments.add(CommentRec(c.getLong(0), c.getString(1), c.getLong(2) != 0L))
                }
            }
            val ai = ArrayList<AiRec>()
            d.rawQuery(
                "SELECT time, base_count, request, response FROM ai_log WHERE series_id=? ORDER BY _id ASC",
                arrayOf(seriesId)
            ).use { c ->
                fun str(i: Int) = if (c.isNull(i)) null else c.getString(i)
                while (c.moveToNext()) {
                    ai.add(AiRec(c.getLong(0), c.getInt(1), str(2), str(3)))
                }
            }
            val errors = ArrayList<ErrorRec>()
            d.rawQuery(
                "SELECT time, msg FROM error_log WHERE series_id=? ORDER BY _id ASC",
                arrayOf(seriesId)
            ).use { c ->
                while (c.moveToNext()) errors.add(ErrorRec(c.getLong(0), c.getString(1) ?: ""))
            }
            SeriesLog(comments, ai, errors)
        }
}

// ---------------------------------------------------------------- TCP 客户端

private object LogApiClient {

    /** 单次请求：连接 → 写一行 JSON → 读一行 JSON → 断开。 */
    private fun request(port: Int, token: String, req: JSONObject): JSONObject {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 1000)
            s.soTimeout = 10000
            val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            w.write(req.put("token", token).toString())
            w.write("\n")
            w.flush()
            val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val line = r.readLine() ?: throw IllegalStateException("服务端无响应")
            return JSONObject(line)
        }
    }

    /**
     * 扫描端口区间找到宿主日志服务。失败抛出带诊断信息的异常
     * （区分"连接被拒绝"= 服务未启动 与其他错误）。
     */
    fun connect(token: String): Int {
        var lastErr = "未知错误"
        var allRefused = true
        for (i in 0 until LogServer.PORT_TRIES) {
            val port = LogServer.PORT_BASE + i
            try {
                val resp = request(port, token, JSONObject().put("op", "ping"))
                if (resp.optBoolean("ok")) return port
                lastErr = resp.optString("error", "服务返回异常")
            } catch (e: java.net.ConnectException) {
                lastErr = "连接被拒绝（$port 无服务监听）"
            } catch (e: Exception) {
                allRefused = false
                lastErr = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
        throw IllegalStateException(
            "无法连接红果短剧进程内的日志服务（端口 ${LogServer.PORT_BASE}.." +
                    "${LogServer.PORT_BASE + LogServer.PORT_TRIES - 1}）。\n" +
                    "最后错误：$lastErr\n\n" +
                    (if (allRefused)
                        "通常是宿主端服务未启动，请依次确认：\n" +
                                "1. 红果短剧正在运行（前台或后台驻留均可，刚被系统杀掉则需重新打开）；\n" +
                                "2. 更新模块 APK 后，是否强停红果短剧并重新打开过（旧进程没有新代码）；\n" +
                                "3. 模块代码会在红果启动时自动拉起服务，无需点分享；\n" +
                                "4. 可在 LSPosed 管理器 → 日志 → 模块日志 中搜索 \"log server\"，" +
                                "确认服务是否拉起及绑定的端口。\n" +
                                "完成后回到本页点击\"刷新\"。"
                    else
                        "端口有响应但通信失败：$lastErr")
        )
    }

    fun list(port: Int, token: String): List<SeriesEntry> {
        val resp = request(port, token, JSONObject().put("op", "list"))
        if (!resp.optBoolean("ok")) throw IllegalStateException(resp.optString("error", "list 失败"))
        val arr = resp.optJSONArray("series") ?: JSONArray()
        val out = ArrayList<SeriesEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                SeriesEntry(
                    seriesId = o.getString("seriesId"),
                    name = o.optString("name").takeIf { it.isNotBlank() },
                    lastUpdate = o.optLong("lastUpdate"),
                    commentCount = o.optInt("commentCount"),
                    aiCount = o.optInt("aiCount"),
                )
            )
        }
        return out
    }

    fun log(port: Int, token: String, seriesId: String): SeriesLog {
        val enc = URLEncoder.encode(seriesId, "UTF-8")
        // 裸 TCP 协议直接传原值即可，URL 编码仅保持与旧接口一致的防御习惯
        val resp = request(port, token, JSONObject().put("op", "log").put("seriesId", enc))
        if (!resp.optBoolean("ok")) {
            throw NoSuchElementException(resp.optString("error", "日志获取失败"))
        }
        val data = resp.getJSONObject("data")

        fun textOf(o: JSONObject, key: String): String? =
            if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotEmpty() }

        val comments = ArrayList<CommentRec>()
        data.optJSONArray("comments")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                comments.add(CommentRec(o.optLong("t"), o.optString("text"), o.optBoolean("used")))
            }
        }
        val ai = ArrayList<AiRec>()
        data.optJSONArray("ai")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                ai.add(AiRec(o.optLong("t"), o.optInt("base"), textOf(o, "request"), textOf(o, "response")))
            }
        }
        val errors = ArrayList<ErrorRec>()
        data.optJSONArray("errors")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                errors.add(ErrorRec(o.optLong("t"), o.optString("msg")))
            }
        }
        return SeriesLog(comments, ai, errors)
    }

    /** 解析 AI 原始响应为 KV 展示行；失败时抛异常（调用方回退为复制原文）。 */
    fun parseAiKv(raw: String): Kv {
        val outer = JSONObject(raw)
        val out = ArrayList<Pair<String, String>>()
        outer.optString("model").takeIf { it.isNotBlank() }?.let { out.add("模型" to it) }
        outer.optJSONObject("usage")?.let { u ->
            out.add("输入 tokens" to u.optInt("prompt_tokens").toString())
            out.add("输出 tokens" to u.optInt("completion_tokens").toString())
        }
        val content = outer.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content")
        val s = content.indexOf('{')
        val e = content.lastIndexOf('}')
        if (s < 0 || e <= s) throw IllegalArgumentException("AI 回复内容不是 JSON")
        val arr = JSONObject(content.substring(s, e + 1)).optJSONArray("tags") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(o.optString("tag") to "×${o.optInt("count")}")
        }
        if (arr.length() == 0) out.add("(AI 未返回词条)" to "-")
        return out
    }
}

// ---------------------------------------------------------------- Activity

class LogViewerActivity : ComponentActivity() {

    private var service: XposedService? = null
    private val token = mutableStateOf("")
    private val hostStatus = mutableStateOf("")
    /** 宿主端上报的真实日志数据库路径（remote prefs 同步，root 直读用）。 */
    private val hostDbPath = mutableStateOf("")
    private val loading = mutableStateOf(false)
    private val error = mutableStateOf<String?>(null)
    private val entries = mutableStateOf<List<SeriesEntry>>(emptyList())
    private val detail = mutableStateOf<Pair<SeriesEntry, SeriesLog>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(s: XposedService) {
                service = s
                val p = s.getRemotePreferences(ModuleConfig.PREFS_NAME)
                token.value = p.getString(ModuleConfig.KEY_LOG_TOKEN, "") ?: ""
                hostStatus.value = p.getString(ModuleConfig.KEY_LOG_SERVER_STATUS, "") ?: ""
                RootLogReader.hostDbPath =
                    p.getString(ModuleConfig.KEY_LOG_DB_PATH, "") ?: ""
            }

            override fun onServiceDied(s: XposedService) {
                service = null
            }
        })

        setContent {
            HongGuoEnhanceTheme {
                val d = detail.value
                when {
                    d == null -> ListScreen()
                    else -> DetailScreen(d.first, d.second) { detail.value = null }
                }
            }
        }

        loadList()
    }

    /** root 直读（优先）→ TCP 回退。 */
    private fun loadList() {
        if (loading.value) return
        loading.value = true
        error.value = null
        Thread {
            var result: List<SeriesEntry>? = null
            var err: String? = null
            try {
                if (RootLogReader.available()) {
                    RootLogReader.hostDbPath = hostDbPath.value
                    try {
                        result = RootLogReader.listSeries(applicationContext)
                    } catch (e: RootLogReader.HostDbMissingException) {
                        // 数据库不存在：展示诊断（目录里实际有什么文件），避免静默"暂无日志"难排查
                        result = emptyList()
                        err = "root 直读正常，但${e.message}"
                    } catch (t: Throwable) {
                        err = "root 读取失败：${t.message ?: t.javaClass.simpleName}"
                    }
                } else {
                    try {
                        val port = LogApiClient.connect(token.value)
                        result = LogApiClient.list(port, token.value)
                    } catch (t: Throwable) {
                        err = withHostStatus(t.message ?: t.javaClass.simpleName)
                    }
                }
            } catch (t: Throwable) {
                err = t.message ?: t.javaClass.simpleName
            }
            runOnUiThread {
                loading.value = false
                if (result != null) entries.value = result!! else error.value = err
            }
        }.start()
    }

    /** 把宿主端上报的服务状态拼进错误信息，帮助定位"宿主没运行新代码 / 服务起不来"。 */
    private fun withHostStatus(msg: String): String {
        val s = hostStatus.value
        val diag = when {
            s.isEmpty() ->
                "宿主端状态：未上报 —— 红果短剧进程内还没有运行本模块的日志代码。" +
                        "请强停红果短剧并重新打开（更新模块后必须），确认 LSPosed 已启用本模块。"

            s.startsWith("listening:") ->
                "宿主端状态：服务已在 ${s.removePrefix("listening:")} 端口监听，但模块端连接失败（异常情况，请反馈）。"

            s == "bind_failed" ->
                "宿主端状态：端口绑定失败——宿主代码在运行，但系统拒绝监听，请到 LSPosed 管理器查看模块日志反馈。"

            else -> "宿主端状态：$s"
        }
        return "$msg\n\n$diag"
    }

    private fun openDetail(entry: SeriesEntry) {
        if (loading.value) return
        loading.value = true
        error.value = null
        Thread {
            var result: Pair<SeriesEntry, SeriesLog>? = null
            var err: String? = null
            try {
                if (RootLogReader.available()) {
                    RootLogReader.hostDbPath = hostDbPath.value
                    result = entry to RootLogReader.readLog(applicationContext, entry.seriesId)
                } else {
                    val port = LogApiClient.connect(token.value)
                    result = entry to LogApiClient.log(port, token.value, entry.seriesId)
                }
            } catch (t: Throwable) {
                err = "读取失败：${t.message ?: t.javaClass.simpleName}"
            }
            runOnUiThread {
                loading.value = false
                if (result != null) detail.value = result!! else error.value = err
            }
        }.start()
    }

    // ---------------------------------------------------------------- 一级：剧列表

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ListScreen() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("评论词条日志") },
                    actions = {
                        Text(
                            "刷新",
                            modifier = Modifier
                                .clickable { loadList() }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
            ) {
                if (loading.value) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在读取日志...", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (entries.value.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            error.value
                                ?: "暂无日志。\n在红果短剧播放页点击分享按钮、使用过\"评论词条\"后，这里就会出现对应剧集。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    error.value?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(entries.value, key = { i, e -> e.seriesId + i }) { _, entry ->
                            SeriesCard(entry) { openDetail(entry) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SeriesCard(entry: SeriesEntry, onClick: () -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    entry.name ?: "剧 ${entry.seriesId}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "ID ${entry.seriesId} · ${formatTime(entry.lastUpdate)} · " +
                            "评论 ${entry.commentCount} 条 · AI ${entry.aiCount} 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // ---------------------------------------------------------------- 二级：单剧评论流

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DetailScreen(entry: SeriesEntry, log: SeriesLog, onBack: () -> Unit) {
        var showAiLog by remember { mutableStateOf(false) }
        // 系统返回手势/返回键逐级返回：AI 记录页(三级) → 单剧评论流(二级) → 剧列表(一级)，
        // 而不是像默认行为那样直接 finish 整个日志 Activity 回到设置主页
        BackHandler {
            if (showAiLog) showAiLog = false else onBack()
        }
        if (showAiLog) {
            AiLogScreen(entry, log) { showAiLog = false }
            return
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(entry.name ?: "剧 ${entry.seriesId}", maxLines = 1) },
                    navigationIcon = {
                        Text(
                            "←",
                            modifier = Modifier
                                .clickable(onClick = onBack)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            fontSize = 20.sp
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(onClick = { showAiLog = true }) {
                    Text("AI 记录 ${log.ai.size}")
                }
            }
        ) { padding ->
            if (log.comments.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "没有评论记录",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Scaffold
            }
            // 分组统计预计算一次（条数 / 交给 AI 条数），不要在渲染时 filter
            val batchStats = remember(log.comments) {
                HashMap<Long, IntArray>().also { m ->
                    log.comments.forEach { r ->
                        val a = m.getOrPut(r.time) { IntArray(2) }
                        a[0]++
                        if (r.used) a[1]++
                    }
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = listPadding,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 同一批采集（时间戳相同）的评论归到一个小节标题下。
                // 注意：分组起点必须从数据确定性判断（当前条与上一条时间不同），
                // 不能用组合期可变变量推进——LazyColumn 只组合可见项、滚动时
                // 会按任意顺序/批次重组条目，可变变量会导致标题重复出现。
                itemsIndexed(
                    log.comments,
                    key = { i, _ -> "c$i" },
                    contentType = { _, _ -> "comment" }
                ) { i, rec ->
                    val batchStart = i == 0 || log.comments[i - 1].time != rec.time
                    if (batchStart) {
                        val stats = batchStats[rec.time] ?: IntArray(2)
                        Text(
                            "采集 ${stats[0]} 条 · 交给 AI ${stats[1]} 条 · ${formatTime(rec.time)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = if (i == 0) 0.dp else 8.dp)
                        )
                    }
                    CommentItem(rec)
                }
            }
        }
    }

    /**
     * 单条评论：
     * - 左侧竖条：宽度固定为 1% 屏宽，高度 = 当前展示的文本实际高度
     *   （默认 2 行，展开后随行数自动延长）；绿色 = 交给 AI，红色 = 丢弃。
     * - 文本默认 2 行（省略），点击展开/收起。
     */
    @Composable
    private fun CommentItem(rec: CommentRec) {
        var expanded by remember(rec.text) { mutableStateOf(false) }
        // IntState：避免每条评论布局时 Integer 装箱；仅本 item 读取，重组范围最小化
        val textHeightPx = remember(rec.text) { mutableIntStateOf(0) }
        val density = LocalDensity.current
        val barWidth = (LocalConfiguration.current.screenWidthDp * 0.01f).dp
        val barColor = if (rec.used) Color(0xFF4CAF50) else Color(0xFFEF5350)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, itemShape)
                .clickable { expanded = !expanded }
                .padding(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(with(density) { textHeightPx.intValue.toDp() })
                    .background(barColor, barShape)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    rec.text,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { textHeightPx.intValue = it.size.height }
                )
                if (expanded) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (rec.used) "已交给 AI 提取词条" else "未交给 AI（被过滤丢弃）",
                        style = MaterialTheme.typography.labelSmall,
                        color = barColor
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- FAB 页：AI 请求/响应

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AiLogScreen(entry: SeriesEntry, log: SeriesLog, onBack: () -> Unit) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("AI 调用记录 · ${entry.name ?: entry.seriesId}", maxLines = 1) },
                    navigationIcon = {
                        Text(
                            "←",
                            modifier = Modifier
                                .clickable(onClick = onBack)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            fontSize = 20.sp
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            if (log.ai.isEmpty() && log.errors.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("还没有 AI 调用记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Scaffold
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = listPadding,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(log.ai, key = { i, _ -> "ai$i" }) { _, rec ->
                    AiCallCard(rec)
                }
                if (log.errors.isNotEmpty()) {
                    item { HorizontalDivider() }
                    item {
                        Text(
                            "流程异常记录",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    itemsIndexed(log.errors, key = { i, _ -> "err$i" }) { _, rec ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    "${formatTime(rec.time)}\n${rec.msg}",
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                CopyButton(rec.msg, context = this@LogViewerActivity)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AiCallCard(rec: AiRec) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "AI 词条总结 · 基于 ${rec.baseCount} 条评论 · ${formatTime(rec.time)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))

                // 发送给 AI 的内容：仅提供复制按钮（原文过长，不在页面内展开）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "发送给 AI 的内容" + (rec.request?.let { "（${it.length} 字）" } ?: "（未记录）"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    CopyButton(rec.request, context = this@LogViewerActivity)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // AI 回复：KV 解析展示 + 原文复制按钮
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AI 回复（已解析）", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    CopyButton(rec.response, label = "复制原文", context = this@LogViewerActivity)
                }
                Spacer(Modifier.height(6.dp))
                val kv: Kv? = rec.response?.let { raw ->
                    runCatching { LogApiClient.parseAiKv(raw) }.getOrNull()
                }
                if (kv != null) {
                    kv.forEachIndexed { i, (k, v) ->
                        Row(modifier = Modifier.padding(vertical = 3.dp)) {
                            Text(
                                k,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(0.55f)
                            )
                            Text(
                                v,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else {
                    Text(
                        "回复解析失败，请复制原文到其他应用查看",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    @Composable
    private fun CopyButton(text: String?, context: Context, label: String = "复制") {
        if (text.isNullOrEmpty()) return
        Text(
            label,
            modifier = Modifier
                .clickable {
                    copyToClipboard(context, text)
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium
        )
    }

    // ---------------------------------------------------------------- 工具

    private fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("HongGuoEnhance", text))
        Toast.makeText(context, "已复制，可到其他应用粘贴查看", Toast.LENGTH_SHORT).show()
    }

    private fun formatTime(ts: Long): String {
        if (ts <= 0) return "未知时间"
        return timeFormat.format(Date(ts))
    }
}
