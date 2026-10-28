package io.github.imroxy2222.lsp_hongguoenhance.hook

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.imroxy2222.lsp_hongguoenhance.HookEntry
import io.github.imroxy2222.lsp_hongguoenhance.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * "评论词条"日志存储（宿主进程侧，SQLite）。
 *
 * 数据库位于宿主私有目录 filesDir/databases/hge_logs.db，四张表：
 *
 *   series    —— 剧索引：seriesId、剧名（可为 null）、created、last_update
 *   comment   —— 抓取到的**全部**评论（used=1 交给 AI 提取词条，used=0 被过滤丢弃，
 *                drop_reason 记录丢弃原因：如"命中过滤器2,规则更新"）
 *   ai_log    —— 每次 AI 调用：发送给 AI 的完整请求体、AI 返回的原始响应
 *   error_log —— 流程异常
 *
 * 读取侧由 [LogServer]（宿主内嵌 127.0.0.1 服务）查询后以 JSON 返回模块 App。
 *
 * 自动清理（可配置，0 = 禁用）：
 *   按天：删除 last_update 早于 N 天的剧（级联删三张明细表）；
 *   按大小：全部日志内容字符总量超限时，从最旧的剧开始级联删除。
 *
 * 写入只在 Pipeline 的单线程 executor 内发生，无需额外加锁。
 */
object LogStore {
    private const val TAG = HookEntry.TAG
    private const val DB_NAME = "hge_logs.db"
    private const val DB_VERSION = 4

    /** 清理检查最小间隔：避免每次追加都全表扫描。 */
    private const val RETENTION_CHECK_INTERVAL_MS = 60 * 60 * 1000L

    @Volatile
    private var helper: Db? = null

    @Volatile
    private var lastRetentionCheck: Long = 0L

    class Db(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE series(" +
                    "series_id TEXT PRIMARY KEY NOT NULL," +
                    "name TEXT," +
                    "created INTEGER NOT NULL," +
                    "last_update INTEGER NOT NULL," +
                    "cursor TEXT)"
            )
            db.execSQL(
                "CREATE TABLE comment(" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "series_id TEXT NOT NULL," +
                    "time INTEGER NOT NULL," +
                    "text TEXT NOT NULL," +
                    "used INTEGER NOT NULL DEFAULT 0," +
                    "score REAL," +
                    "drop_reason TEXT)"
            )
            db.execSQL("CREATE INDEX idx_comment_series ON comment(series_id, _id)")
            db.execSQL(
                "CREATE TABLE ai_log(" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "series_id TEXT NOT NULL," +
                    "time INTEGER NOT NULL," +
                    "base_count INTEGER NOT NULL DEFAULT 0," +
                    "request TEXT," +
                    "response TEXT," +
                    "tags TEXT)"
            )
            db.execSQL("CREATE INDEX idx_ai_series ON ai_log(series_id, _id)")
            db.execSQL(
                "CREATE TABLE error_log(" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "series_id TEXT NOT NULL," +
                    "time INTEGER NOT NULL," +
                    "msg TEXT)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v2：ai_log 增加 tags 结构化列（词条持久缓存，跨 app 重启复用）
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE ai_log ADD COLUMN tags TEXT")
            }
            // v3：comment 增加评分列（平均得分统计）；series 增加分页游标（恢复会话续拉）
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE comment ADD COLUMN score REAL")
                db.execSQL("ALTER TABLE series ADD COLUMN cursor TEXT")
            }
            // v4：comment 增加丢弃原因列（如"命中过滤器2,规则更新"，日志页展示用）
            if (oldVersion < 4) {
                db.execSQL("ALTER TABLE comment ADD COLUMN drop_reason TEXT")
            }
        }
    }

    /** 幂等初始化：拿到 Context 时调用（app ready 或首次分享点击）。仅主进程生效。 */
    fun init(context: Context) {
        if (!LogServer.isMainProcess(context)) return
        synchronized(this) {
            if (helper == null) helper = Db(context.applicationContext)
        }
        reportDbPath(context)
        maybeEnforceRetention()
    }

    /** 上报真实数据库绝对路径（模块 App 的 root 直读通道据此定位，避免猜路径）。 */
    fun reportDbPath(context: Context) {
        ModuleConfig.setLogDbPath(context.getDatabasePath(DB_NAME).absolutePath)
    }

    private fun db(): Db? = helper

    // ---------------------------------------------------------------- 写入

    /** 会话开始 / 剧名更新：upsert series，last_update 刷新为当前时间。 */
    fun appendMeta(seriesId: String, seriesName: String?) {
        val d = db() ?: return
        try {
            val now = System.currentTimeMillis()
            val w = d.writableDatabase
            w.beginTransaction()
            try {
                w.rawQuery("SELECT name FROM series WHERE series_id=?", arrayOf(seriesId)).use { c ->
                    if (c.moveToFirst()) {
                        val old = c.getString(0)
                        w.execSQL(
                            "UPDATE series SET last_update=?, name=? WHERE series_id=?",
                            arrayOf(now, seriesName ?: old, seriesId)
                        )
                    } else {
                        w.execSQL(
                            "INSERT INTO series(series_id, name, created, last_update) VALUES(?,?,?,?)",
                            arrayOf(seriesId, seriesName, now, now)
                        )
                    }
                }
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
            maybeEnforceRetention()
        } catch (t: Throwable) {
            Logger.e("log appendMeta failed: $seriesId", t)
        }
    }

    /**
     * 写入一批抓取到的评论（全部，含被过滤丢弃的）。
     * @param texts 评论文本列表
     * @param used  与 texts 一一对应：true = 通过过滤、已进入 AI 输入
     * @param scores 与 texts 一一对应：评分（reply.expand.score），未评分为 null
     * @param reasons 与 texts 一一对应：丢弃原因（null = 未丢弃；如"命中过滤器2,规则更新"）
     */
    fun appendComments(
        seriesId: String,
        texts: List<String>,
        used: List<Boolean>,
        scores: List<Float?> = emptyList(),
        reasons: List<String?> = emptyList(),
    ) {
        if (texts.isEmpty()) return
        val d = db() ?: return
        try {
            val now = System.currentTimeMillis()
            val w = d.writableDatabase
            w.beginTransaction()
            try {
                touchSeries(w, seriesId, null, now)
                val stmt = w.compileStatement(
                    "INSERT INTO comment(series_id, time, text, used, score, drop_reason) VALUES(?,?,?,?,?,?)"
                )
                for (i in texts.indices) {
                    stmt.clearBindings()
                    stmt.bindString(1, seriesId)
                    stmt.bindLong(2, now)
                    stmt.bindString(3, texts[i])
                    stmt.bindLong(4, if (i < used.size && used[i]) 1L else 0L)
                    val s = if (i < scores.size) scores[i] else null
                    if (s != null) stmt.bindDouble(5, s.toDouble()) else stmt.bindNull(5)
                    val r = if (i < reasons.size) reasons[i] else null
                    if (r != null) stmt.bindString(6, r) else stmt.bindNull(6)
                    stmt.executeInsert()
                }
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
            maybeEnforceRetention()
        } catch (t: Throwable) {
            Logger.e("log appendComments failed: $seriesId", t)
        }
    }

    /** 写入一次 AI 词条总结（含发送的完整请求体与 AI 原始响应）。 */
    fun appendTags(
        seriesId: String,
        tags: List<AiClient.Tag>,
        baseCount: Int,
        request: String?,
        response: String?,
    ) {
        val d = db() ?: return
        try {
            val now = System.currentTimeMillis()
            val arr = JSONArray()
            for (t in tags) arr.put(
                JSONObject().put("tag", t.text).put("count", t.count).put("negative", t.negative)
            )
            val w = d.writableDatabase
            w.beginTransaction()
            try {
                touchSeries(w, seriesId, null, now)
                w.execSQL(
                    "INSERT INTO ai_log(series_id, time, base_count, request, response, tags) VALUES(?,?,?,?,?,?)",
                    arrayOf(seriesId, now, baseCount, request, response, arr.toString())
                )
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        } catch (t: Throwable) {
            Logger.e("log appendTags failed: $seriesId", t)
        }
    }

    // ---------------------------------------------------------------- 回读（词条持久缓存）

    /** 可跨 app 重启复用的历史总结。 */
    class CachedSummary(val tags: List<AiClient.Tag>, val baseCount: Int, val time: Long)

    /** 持久化分页游标：恢复会话后"继续加载"能从正确位置续拉。 */
    fun saveCursor(seriesId: String, cursor: String?) {
        val d = db() ?: return
        try {
            d.writableDatabase.execSQL(
                "UPDATE series SET cursor=? WHERE series_id=?",
                arrayOf(cursor, seriesId)
            )
        } catch (t: Throwable) {
            Logger.e("log saveCursor failed: $seriesId", t)
        }
    }

    /** 恢复会话所需的全部状态（配合 [loadLatestSummary] 使用）。 */
    class SessionState(
        val usedComments: List<String>,  // 已交给 AI 的评论文本（按采集顺序）
        val rawTexts: Set<String>,       // 全部采集过的文本（含被过滤的，供运行期去重）
        val rawCount: Int,               // 总采集条数
        val scoreSum: Double,            // 评分总和（未评分评论计 0）
        val cursor: String?,             // 分页游标
    )

    /** 一次性回读会话状态：评论/去重集/统计/游标。库未初始化或无记录返回 null。 */
    fun loadSessionState(seriesId: String): SessionState? {
        val d = db() ?: return null
        return try {
            var exists = false
            val usedComments = ArrayList<String>()
            val rawTexts = HashSet<String>()
            var rawCount = 0
            var scoreSum = 0.0
            d.readableDatabase.rawQuery(
                "SELECT text, used, score FROM comment WHERE series_id=? ORDER BY _id",
                arrayOf(seriesId)
            ).use { c ->
                while (c.moveToNext()) {
                    exists = true
                    val text = c.getString(0)
                    rawTexts.add(text)
                    rawCount++
                    if (c.getInt(1) == 1) usedComments.add(text)
                    if (!c.isNull(2)) scoreSum += c.getDouble(2)
                }
            }
            if (!exists) return null
            var cursor: String? = null
            d.readableDatabase.rawQuery(
                "SELECT cursor FROM series WHERE series_id=?", arrayOf(seriesId)
            ).use { c ->
                if (c.moveToFirst()) cursor = c.getString(0)
            }
            SessionState(usedComments, rawTexts, rawCount, scoreSum, cursor)
        } catch (t: Throwable) {
            Logger.e("log loadSessionState failed: $seriesId", t)
            null
        }
    }

    /**
     * 读取该剧最近一次结构化落库的词条总结（无则 null）。
     * 供 Pipeline 在内存缓存未命中时恢复，避免重复调用 AI。
     */
    fun loadLatestSummary(seriesId: String): CachedSummary? {
        val d = db() ?: return null
        return try {
            d.readableDatabase.rawQuery(
                "SELECT tags, base_count, time FROM ai_log " +
                    "WHERE series_id=? AND tags IS NOT NULL ORDER BY _id DESC LIMIT 1",
                arrayOf(seriesId)
            ).use { c ->
                if (!c.moveToFirst()) return null
                val tagsJson = c.getString(0) ?: return null
                val arr = JSONArray(tagsJson)
                val tags = ArrayList<AiClient.Tag>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    tags.add(
                        AiClient.Tag(
                            o.getString("tag"), o.getInt("count"),
                            o.optBoolean("negative", false)
                        )
                    )
                }
                // 恢复时重排：count 降序、count 相同负面在前（历史记录落库时可能未做负面平局排序）
                CachedSummary(
                    tags.sortedWith(AiClient.Tag.COMPARATOR),
                    c.getInt(1),
                    c.getLong(2)
                )
            }
        } catch (t: Throwable) {
            Logger.e("log loadLatestSummary failed: $seriesId", t)
            null
        }
    }

    fun appendError(seriesId: String, msg: String) {        val d = db() ?: return
        try {
            val now = System.currentTimeMillis()
            val w = d.writableDatabase
            w.beginTransaction()
            try {
                touchSeries(w, seriesId, null, now)
                w.execSQL(
                    "INSERT INTO error_log(series_id, time, msg) VALUES(?,?,?)",
                    arrayOf(seriesId, now, msg)
                )
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        } catch (t: Throwable) {
            Logger.e("log appendError failed: $seriesId", t)
        }
    }

    /**
     * 回填剧名：只更新已存在的 series 行（不凭空建行——没产生过日志的剧
     * 不应出现在日志列表里）。
     */
    fun updateSeriesName(seriesId: String, name: String) {
        val d = db() ?: return
        try {
            d.writableDatabase.execSQL(
                "UPDATE series SET name=? WHERE series_id=?",
                arrayOf(name, seriesId)
            )
        } catch (t: Throwable) {
            Logger.e("log updateSeriesName failed: $seriesId", t)
        }
    }

    private fun touchSeries(w: SQLiteDatabase, seriesId: String, name: String?, now: Long) {        w.rawQuery("SELECT name FROM series WHERE series_id=?", arrayOf(seriesId)).use { c ->
            if (c.moveToFirst()) {
                val old = c.getString(0)
                w.execSQL(
                    "UPDATE series SET last_update=?, name=? WHERE series_id=?",
                    arrayOf(now, name ?: old, seriesId)
                )
            } else {
                w.execSQL(
                    "INSERT INTO series(series_id, name, created, last_update) VALUES(?,?,?,?)",
                    arrayOf(seriesId, name, now, now)
                )
            }
        }
    }

    // ---------------------------------------------------------------- 清理

    private fun maybeEnforceRetention() {
        val now = System.currentTimeMillis()
        if (now - lastRetentionCheck < RETENTION_CHECK_INTERVAL_MS) return
        lastRetentionCheck = now
        enforceRetention()
    }

    /** 按配置执行清理：保留天数与总大小上限独立生效，均从最旧的剧开始级联删除。 */
    fun enforceRetention() {
        val d = db() ?: return
        try {
            val w = d.writableDatabase

            // 按天清理
            val days = ModuleConfig.logRetentionDays()
            if (days > 0) {
                val cutoff = System.currentTimeMillis() - days * 24L * 3600_000L
                val ids = ArrayList<String>()
                w.rawQuery(
                    "SELECT series_id FROM series WHERE last_update < ?", arrayOf(cutoff.toString())
                ).use { c ->
                    while (c.moveToNext()) ids.add(c.getString(0))
                }
                ids.forEach { deleteSeries(w, it) }
                if (ids.isNotEmpty()) Logger.i("log retention: deleted ${ids.size} series (older than $days days)")
            }

            // 按大小清理（字符量估算：中文按 ~2 字节/字符）
            val maxMb = ModuleConfig.logMaxSizeMb()
            if (maxMb > 0) {
                val limitChars = maxMb * 1024L * 1024L / 2
                fun totalChars(): Long = w.rawQuery(
                    "SELECT (SELECT COALESCE(SUM(LENGTH(text)),0) FROM comment)" +
                        " + (SELECT COALESCE(SUM(LENGTH(request))+COALESCE(SUM(LENGTH(response)),0),0) FROM ai_log)" +
                        " + (SELECT COALESCE(SUM(LENGTH(msg)),0) FROM error_log)",
                    null
                ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

                var total = totalChars()
                if (total > limitChars) {
                    val ids = ArrayList<String>()
                    w.rawQuery(
                        "SELECT series_id FROM series ORDER BY last_update ASC", null
                    ).use { c ->
                        while (c.moveToNext()) ids.add(c.getString(0))
                    }
                    for (id in ids) {
                        if (total <= limitChars) break
                        deleteSeries(w, id)
                        total = totalChars()
                        Logger.i("log retention: size limit $maxMb MB, deleted series $id")
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.e("log retention failed", t)
        }
    }

    private fun deleteSeries(w: SQLiteDatabase, seriesId: String) {
        w.execSQL("DELETE FROM comment WHERE series_id=?", arrayOf(seriesId))
        w.execSQL("DELETE FROM ai_log WHERE series_id=?", arrayOf(seriesId))
        w.execSQL("DELETE FROM error_log WHERE series_id=?", arrayOf(seriesId))
        w.execSQL("DELETE FROM series WHERE series_id=?", arrayOf(seriesId))
    }

    // ---------------------------------------------------------------- 读取（LogServer 用）

    /** 剧列表：按最近更新降序，附评论数与 AI 调用次数。 */
    fun listSeries(): JSONArray {
        val arr = JSONArray()
        val d = db() ?: return arr
        try {
            d.readableDatabase.rawQuery(
                "SELECT s.series_id AS sid, s.name, s.last_update AS lu," +
                    " (SELECT COUNT(*) FROM comment c WHERE c.series_id = s.series_id) AS cc," +
                    " (SELECT COUNT(*) FROM ai_log a WHERE a.series_id = s.series_id) AS ac" +
                    " FROM series s ORDER BY lu DESC",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    arr.put(
                        JSONObject()
                            .put("seriesId", c.getString(0))
                            .put("name", c.getString(1) ?: JSONObject.NULL)
                            .put("lastUpdate", c.getLong(2))
                            .put("commentCount", c.getLong(3))
                            .put("aiCount", c.getLong(4))
                    )
                }
            }
        } catch (t: Throwable) {
            Logger.e("log listSeries failed", t)
        }
        return arr
    }

    /** 单剧全部日志（JSON），不存在返回 null。 */
    fun readLog(seriesId: String): JSONObject? {
        val d = db() ?: return null
        return try {
            val w = d.readableDatabase
            var exists = false
            w.rawQuery("SELECT 1 FROM series WHERE series_id=?", arrayOf(seriesId)).use { c ->
                exists = c.moveToFirst()
            }
            if (!exists) return null

            val out = JSONObject()
            val comments = JSONArray()
            w.rawQuery(
                "SELECT time, text, used, drop_reason FROM comment WHERE series_id=? ORDER BY _id ASC",
                arrayOf(seriesId)
            ).use { c ->
                while (c.moveToNext()) {
                    val o = JSONObject()
                        .put("t", c.getLong(0))
                        .put("text", c.getString(1))
                        .put("used", c.getLong(2) != 0L)
                    if (!c.isNull(3)) o.put("reason", c.getString(3))
                    comments.put(o)
                }
            }
            val ai = JSONArray()
            w.rawQuery(
                "SELECT time, base_count, request, response FROM ai_log WHERE series_id=? ORDER BY _id ASC",
                arrayOf(seriesId)
            ).use { c ->
                while (c.moveToNext()) {
                    ai.put(
                        JSONObject()
                            .put("t", c.getLong(0))
                            .put("base", c.getInt(1))
                            .put("request", c.getString(2) ?: JSONObject.NULL)
                            .put("response", c.getString(3) ?: JSONObject.NULL)
                    )
                }
            }
            val errors = JSONArray()
            w.rawQuery(
                "SELECT time, msg FROM error_log WHERE series_id=? ORDER BY _id ASC",
                arrayOf(seriesId)
            ).use { c ->
                while (c.moveToNext()) {
                    errors.put(JSONObject().put("t", c.getLong(0)).put("msg", c.getString(1)))
                }
            }
            out.put("comments", comments).put("ai", ai).put("errors", errors)
        } catch (t: Throwable) {
            Logger.e("log readLog failed: $seriesId", t)
            null
        }
    }
}
