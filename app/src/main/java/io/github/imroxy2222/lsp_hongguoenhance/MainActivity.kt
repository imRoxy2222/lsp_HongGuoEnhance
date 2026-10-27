package io.github.imroxy2222.lsp_hongguoenhance

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.imroxy2222.lsp_hongguoenhance.hook.ModuleConfig
import io.github.imroxy2222.lsp_hongguoenhance.ui.theme.HongGuoEnhanceTheme
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块设置页：配置 AI 接口信息（baseURL / API Key / 模型）与采集参数。
 *
 * 配置写入 libxposed remote preferences，宿主进程内的 hook 代码
 * 通过 XposedModule.getRemotePreferences 读取同一份配置。
 */
class MainActivity : ComponentActivity() {

    private var service: XposedService? = null
    private val serviceReady = mutableStateOf(false)
    private val initialConfig = mutableStateOf<Map<String, Any>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(s: XposedService) {
                service = s
                serviceReady.value = true
                ensureLogToken(s)
                initialConfig.value = loadPrefs(s)
            }

            override fun onServiceDied(s: XposedService) {
                service = null
                serviceReady.value = false
            }
        })

        setContent {
            HongGuoEnhanceTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SettingsScreen(
                        modifier = Modifier.padding(innerPadding),
                        serviceReady = serviceReady.value,
                        initial = initialConfig.value,
                        onScopeRequest = {
                            val s = service
                            if (s == null) {
                                Toast.makeText(this, "框架服务未连接", Toast.LENGTH_SHORT).show()
                            } else {
                                s.requestScope(
                                    listOf(HookEntry.TARGET_APP),
                                    object : XposedService.OnScopeEventListener {
                                        override fun onScopeRequestApproved(approved: List<String>) {
                                            runOnUiThread {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "作用域申请成功，请重启红果短剧",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }

                                        override fun onScopeRequestFailed(message: String) {
                                            runOnUiThread {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "作用域申请失败: $message",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                )
                            }
                        },
                        onSave = { baseUrl, apiKey, model, targetCount, pageCount, extraPrompt,
                                   logRetentionDays, logMaxSizeMb ->
                            saveConfig(
                                baseUrl, apiKey, model, targetCount, pageCount, extraPrompt,
                                logRetentionDays, logMaxSizeMb
                            )
                        },
                        onOpenLogs = {
                            startActivity(Intent(this, LogViewerActivity::class.java))
                        }
                    )
                }
            }
        }
    }

    /** 生成日志服务访问令牌（仅首次；宿主进程读取同一配置校验请求）。 */
    private fun ensureLogToken(s: XposedService) {
        val p = s.getRemotePreferences(ModuleConfig.PREFS_NAME)
        if (p.getString(ModuleConfig.KEY_LOG_TOKEN, "").isNullOrEmpty()) {
            p.edit()
                .putString(ModuleConfig.KEY_LOG_TOKEN, java.util.UUID.randomUUID().toString())
                .apply()
        }
    }

    private fun loadPrefs(s: XposedService): Map<String, Any> {
        val p = s.getRemotePreferences(ModuleConfig.PREFS_NAME)
        return mapOf(
            ModuleConfig.KEY_BASE_URL to (p.getString(ModuleConfig.KEY_BASE_URL, "") ?: ""),
            ModuleConfig.KEY_API_KEY to (p.getString(ModuleConfig.KEY_API_KEY, "") ?: ""),
            ModuleConfig.KEY_MODEL to (p.getString(ModuleConfig.KEY_MODEL, "") ?: ""),
            ModuleConfig.KEY_TARGET_COUNT to p.getInt(
                ModuleConfig.KEY_TARGET_COUNT, ModuleConfig.DEFAULT_TARGET_COUNT
            ),
            ModuleConfig.KEY_PAGE_COUNT to p.getInt(
                ModuleConfig.KEY_PAGE_COUNT, ModuleConfig.DEFAULT_PAGE_COUNT
            ),
            ModuleConfig.KEY_EXTRA_PROMPT to (p.getString(ModuleConfig.KEY_EXTRA_PROMPT, "") ?: ""),
            ModuleConfig.KEY_LOG_RETENTION_DAYS to p.getInt(
                ModuleConfig.KEY_LOG_RETENTION_DAYS, ModuleConfig.DEFAULT_LOG_RETENTION_DAYS
            ),
            ModuleConfig.KEY_LOG_MAX_SIZE_MB to p.getInt(
                ModuleConfig.KEY_LOG_MAX_SIZE_MB, ModuleConfig.DEFAULT_LOG_MAX_SIZE_MB
            )
        )
    }

    private fun saveConfig(
        baseUrl: String, apiKey: String, model: String,
        targetCount: Int, pageCount: Int, extraPrompt: String,
        logRetentionDays: Int, logMaxSizeMb: Int,
    ) {
        val s = service
        if (s == null) {
            Toast.makeText(this, "LSPosed 服务未连接，无法保存", Toast.LENGTH_LONG).show()
            return
        }
        val prefs = s.getRemotePreferences(ModuleConfig.PREFS_NAME)
        prefs.edit()
            .putString(ModuleConfig.KEY_BASE_URL, baseUrl.trim())
            .putString(ModuleConfig.KEY_API_KEY, apiKey.trim())
            .putString(ModuleConfig.KEY_MODEL, model.trim())
            .putInt(ModuleConfig.KEY_TARGET_COUNT, targetCount)
            .putInt(ModuleConfig.KEY_PAGE_COUNT, pageCount)
            .putString(ModuleConfig.KEY_EXTRA_PROMPT, extraPrompt)
            .putInt(ModuleConfig.KEY_LOG_RETENTION_DAYS, logRetentionDays)
            .putInt(ModuleConfig.KEY_LOG_MAX_SIZE_MB, logMaxSizeMb)
            .apply()
        Toast.makeText(this, "已保存，重启红果短剧后生效", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    serviceReady: Boolean,
    initial: Map<String, Any>?,
    onScopeRequest: () -> Unit,
    onSave: (String, String, String, Int, Int, String, Int, Int) -> Unit,
    onOpenLogs: () -> Unit,
) {
    fun str(key: String, fallback: String): String {
        val v = initial?.get(key) as? String
        return if (v.isNullOrEmpty()) fallback else v
    }

    var baseUrl by remember(initial) { mutableStateOf(str(ModuleConfig.KEY_BASE_URL, "")) }
    var apiKey by remember(initial) { mutableStateOf(str(ModuleConfig.KEY_API_KEY, "")) }
    var model by remember(initial) { mutableStateOf(str(ModuleConfig.KEY_MODEL, "")) }
    var targetCount by remember(initial) {
        mutableStateOf(((initial?.get(ModuleConfig.KEY_TARGET_COUNT) as? Int)
            ?: ModuleConfig.DEFAULT_TARGET_COUNT).toString())
    }
    var pageCount by remember(initial) {
        mutableStateOf(((initial?.get(ModuleConfig.KEY_PAGE_COUNT) as? Int)
            ?: ModuleConfig.DEFAULT_PAGE_COUNT).toString())
    }
    var extraPrompt by remember(initial) { mutableStateOf(str(ModuleConfig.KEY_EXTRA_PROMPT, "")) }
    var logRetentionDays by remember(initial) {
        mutableStateOf(((initial?.get(ModuleConfig.KEY_LOG_RETENTION_DAYS) as? Int)
            ?: ModuleConfig.DEFAULT_LOG_RETENTION_DAYS).toString())
    }
    var logMaxSizeMb by remember(initial) {
        mutableStateOf(((initial?.get(ModuleConfig.KEY_LOG_MAX_SIZE_MB) as? Int)
            ?: ModuleConfig.DEFAULT_LOG_MAX_SIZE_MB).toString())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("红果短剧增强 · 设置", style = MaterialTheme.typography.titleLarge)
        Text(
            if (serviceReady) "LSPosed 框架服务：已连接" else "LSPosed 框架服务：未连接（保存不可用）",
            style = MaterialTheme.typography.bodySmall,
            color = if (serviceReady) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )

        OutlinedButton(onClick = onScopeRequest, modifier = Modifier.fillMaxWidth()) {
            Text("申请红果短剧作用域 (com.phoenix.read)")
        }

        Text("AI 接口配置（OpenAI 兼容）", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL（如 https://api.deepseek.com/v1）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("模型名（如 deepseek-chat / gpt-4o-mini）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Text("采集参数", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = targetCount,
                onValueChange = { targetCount = it.filter { c -> c.isDigit() } },
                label = { Text("首次采集评论数") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = pageCount,
                onValueChange = { pageCount = it.filter { c -> c.isDigit() } },
                label = { Text("单批加载条数") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
        OutlinedTextField(
            value = extraPrompt,
            onValueChange = { extraPrompt = it },
            label = { Text("附加提示词（可选）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )

        Text("评论词条日志", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
            Text("查看日志（按剧分列）")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = logRetentionDays,
                onValueChange = { logRetentionDays = it.filter { c -> c.isDigit() } },
                label = { Text("保留天数（0=不限）") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = logMaxSizeMb,
                onValueChange = { logMaxSizeMb = it.filter { c -> c.isDigit() } },
                label = { Text("总大小上限 MB（0=不限）") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
        Text(
            "超过保留天数或总大小上限时，最旧的剧集日志将被自动删除。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = {
                onSave(
                    baseUrl.ifBlank { ModuleConfig.DEFAULT_BASE_URL },
                    apiKey,
                    model.ifBlank { ModuleConfig.DEFAULT_MODEL },
                    targetCount.toIntOrNull()?.coerceIn(10, 200) ?: ModuleConfig.DEFAULT_TARGET_COUNT,
                    pageCount.toIntOrNull()?.coerceIn(5, 30) ?: ModuleConfig.DEFAULT_PAGE_COUNT,
                    extraPrompt,
                    logRetentionDays.toIntOrNull()?.coerceIn(0, 3650)
                        ?: ModuleConfig.DEFAULT_LOG_RETENTION_DAYS,
                    logMaxSizeMb.toIntOrNull()?.coerceIn(0, 4096)
                        ?: ModuleConfig.DEFAULT_LOG_MAX_SIZE_MB
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存配置") }

        Text(
            "使用说明：在 LSPosed 中启用本模块并勾选红果短剧作用域 → 强停红果短剧 → 打开任意短剧播放页 → 点击右侧分享按钮，将弹出\"评论词条\"页。",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
