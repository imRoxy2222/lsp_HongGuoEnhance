package io.github.imroxy2222.lsp_hongguoenhance

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import io.github.imroxy2222.lsp_hongguoenhance.hook.AiClient
import io.github.imroxy2222.lsp_hongguoenhance.hook.ModuleConfig
import io.github.imroxy2222.lsp_hongguoenhance.ui.theme.HongGuoEnhanceTheme
import io.github.libxposed.service.XposedService

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
    private val launcherHidden = mutableStateOf(false)

    /** 订阅进程级服务状态（XposedServiceHelper 只支持单一 listener，禁止页面各自注册）。 */
    private val serviceListener = object : XposedServiceHolder.Listener {
        override fun onServiceChanged(s: XposedService?) {
            service = s
            serviceReady.value = s != null
            if (s != null) {
                ensureLogToken(s)
                initialConfig.value = loadPrefs(s)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        launcherHidden.value = LauncherVisibility.isHidden(this)

        XposedServiceHolder.addListener(serviceListener)

        setContent {
            HongGuoEnhanceTheme {
                SettingsScreen(
                    serviceReady = serviceReady.value,
                    launcherHidden = launcherHidden.value,
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
                               logRetentionDays, logMaxSizeMb, disableThinking, aiViaModule,
                               thinkingProviders ->
                        saveConfig(
                            baseUrl, apiKey, model, targetCount, pageCount, extraPrompt,
                            logRetentionDays, logMaxSizeMb, disableThinking, aiViaModule,
                            thinkingProviders
                        )
                    },
                    onOpenLogs = {
                        startActivity(Intent(this, LogViewerActivity::class.java))
                    },
                    onOpenFilterRules = {
                        startActivity(Intent(this, FilterRulesActivity::class.java))
                    },
                    onHideIconChange = { hide ->
                        LauncherVisibility.setHidden(this, hide)
                        launcherHidden.value = LauncherVisibility.isHidden(this)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        XposedServiceHolder.removeListener(serviceListener)
        super.onDestroy()
    }

    /** 设置页注册的 onPause 兜底保存（输入框未失焦就离开页面时，仍补一次自动保存）。 */
    internal var pauseSaveHook: (() -> Unit)? = null

    override fun onPause() {
        pauseSaveHook?.invoke()
        super.onPause()
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
            ),
            ModuleConfig.KEY_DISABLE_THINKING to p.getBoolean(
                ModuleConfig.KEY_DISABLE_THINKING, ModuleConfig.DEFAULT_DISABLE_THINKING
            ),
            ModuleConfig.KEY_THINKING_PROVIDERS to (p.getString(
                ModuleConfig.KEY_THINKING_PROVIDERS, null
            ) ?: ""),
            ModuleConfig.KEY_AI_VIA_MODULE to p.getBoolean(
                ModuleConfig.KEY_AI_VIA_MODULE, ModuleConfig.DEFAULT_AI_VIA_MODULE
            )
        )
    }

    private fun saveConfig(
        baseUrl: String, apiKey: String, model: String,
        targetCount: Int, pageCount: Int, extraPrompt: String,
        logRetentionDays: Int, logMaxSizeMb: Int,
        disableThinking: Boolean, aiViaModule: Boolean,
        thinkingProviders: Set<String>,
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
            .putBoolean(ModuleConfig.KEY_DISABLE_THINKING, disableThinking)
            .putBoolean(ModuleConfig.KEY_AI_VIA_MODULE, aiViaModule)
            .putString(ModuleConfig.KEY_THINKING_PROVIDERS, thinkingProviders.joinToString(","))
            .apply()
        Toast.makeText(this, "已自动保存", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    serviceReady: Boolean,
    launcherHidden: Boolean,
    initial: Map<String, Any>?,
    onScopeRequest: () -> Unit,
    onSave: (String, String, String, Int, Int, String, Int, Int, Boolean, Boolean, Set<String>) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenFilterRules: () -> Unit,
    onHideIconChange: (Boolean) -> Unit,
) {
    fun str(key: String, fallback: String): String {
        val v = initial?.get(key) as? String
        return if (v.isNullOrEmpty()) fallback else v
    }

    var baseUrl by remember(initial) { mutableStateOf(str(ModuleConfig.KEY_BASE_URL, "")) }
    var apiKey by remember(initial) { mutableStateOf(str(ModuleConfig.KEY_API_KEY, "")) }
    var model by remember(initial) { mutableStateOf(str(ModuleConfig.KEY_MODEL, "")) }
    var targetCount by remember(initial) {
        mutableStateOf(
            ((initial?.get(ModuleConfig.KEY_TARGET_COUNT) as? Int)
                ?: ModuleConfig.DEFAULT_TARGET_COUNT).toString()
        )
    }
    var pageCount by remember(initial) {
        mutableStateOf(
            ((initial?.get(ModuleConfig.KEY_PAGE_COUNT) as? Int)
                ?: ModuleConfig.DEFAULT_PAGE_COUNT).toString()
        )
    }
    var extraPrompt by remember(initial) { mutableStateOf(str(ModuleConfig.KEY_EXTRA_PROMPT, "")) }
    var thinkingProviders by remember(initial) {
        // 旧配置兼容：以前把"取消思考"显式关闭过（disable_thinking=false）视为全不勾
        val masterOn = (initial?.get(ModuleConfig.KEY_DISABLE_THINKING) as? Boolean)
            ?: ModuleConfig.DEFAULT_DISABLE_THINKING
        val saved = (initial?.get(ModuleConfig.KEY_THINKING_PROVIDERS) as? String)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        mutableStateOf(
            when {
                saved != null -> saved
                masterOn -> AiClient.THINKING_OPTIONS.filter { it.defaultOn }.map { it.id }.toSet()
                else -> emptySet()
            }
        )
    }
    var thinkingExpanded by rememberSaveable { mutableStateOf(false) }
    var aiViaModule by remember(initial) {
        mutableStateOf(
            (initial?.get(ModuleConfig.KEY_AI_VIA_MODULE) as? Boolean)
                ?: ModuleConfig.DEFAULT_AI_VIA_MODULE
        )
    }
    var logRetentionDays by remember(initial) {
        mutableStateOf(
            ((initial?.get(ModuleConfig.KEY_LOG_RETENTION_DAYS) as? Int)
                ?: ModuleConfig.DEFAULT_LOG_RETENTION_DAYS).toString()
        )
    }
    var logMaxSizeMb by remember(initial) {
        mutableStateOf(
            ((initial?.get(ModuleConfig.KEY_LOG_MAX_SIZE_MB) as? Int)
                ?: ModuleConfig.DEFAULT_LOG_MAX_SIZE_MB).toString()
        )
    }
    var showHideConfirm by rememberSaveable { mutableStateOf(false) }

    // ── 自动保存（脱离焦点 / 开关变更 / 离开页面时触发，签名去重） ──────────
    val focusManager = LocalFocusManager.current

    /** 当前表单按落盘口径归一化后的签名（与保存逻辑同一套默认值/范围收敛）。 */
    fun savedSig(): String = listOf(
        baseUrl.ifBlank { ModuleConfig.DEFAULT_BASE_URL },
        apiKey.trim(),
        model.ifBlank { ModuleConfig.DEFAULT_MODEL },
        targetCount.toIntOrNull()?.coerceIn(10, 200) ?: ModuleConfig.DEFAULT_TARGET_COUNT,
        pageCount.toIntOrNull()?.coerceIn(5, 30) ?: ModuleConfig.DEFAULT_PAGE_COUNT,
        extraPrompt,
        logRetentionDays.toIntOrNull()?.coerceIn(0, 3650)
            ?: ModuleConfig.DEFAULT_LOG_RETENTION_DAYS,
        logMaxSizeMb.toIntOrNull()?.coerceIn(0, 4096)
            ?: ModuleConfig.DEFAULT_LOG_MAX_SIZE_MB,
        thinkingProviders.sorted().joinToString(","),
        aiViaModule
    ).joinToString("\u0000")

    /** 与初始加载配置对齐的基线签名：内容未变时不触发保存与 Toast。 */
    var lastSavedSig by remember(initial) { mutableStateOf(savedSig()) }

    fun autoSave() {
        if (!serviceReady) return
        val sig = savedSig()
        if (sig == lastSavedSig) return
        lastSavedSig = sig
        onSave(
            baseUrl.ifBlank { ModuleConfig.DEFAULT_BASE_URL },
            apiKey,
            model.ifBlank { ModuleConfig.DEFAULT_MODEL },
            targetCount.toIntOrNull()?.coerceIn(10, 200)
                ?: ModuleConfig.DEFAULT_TARGET_COUNT,
            pageCount.toIntOrNull()?.coerceIn(5, 30)
                ?: ModuleConfig.DEFAULT_PAGE_COUNT,
            extraPrompt,
            logRetentionDays.toIntOrNull()?.coerceIn(0, 3650)
                ?: ModuleConfig.DEFAULT_LOG_RETENTION_DAYS,
            logMaxSizeMb.toIntOrNull()?.coerceIn(0, 4096)
                ?: ModuleConfig.DEFAULT_LOG_MAX_SIZE_MB,
            thinkingProviders.isNotEmpty(),  // 主开关 = 是否勾选了任一平台
            aiViaModule,
            thinkingProviders
        )
    }

    // 离开页面（onPause）时兜底保存：输入框尚未失焦的编辑内容不丢失
    val activity = LocalContext.current as? MainActivity
    DisposableEffect(Unit) {
        activity?.pauseSaveHook = { autoSave() }
        onDispose { activity?.pauseSaveHook = null }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("红果短剧增强 · 设置") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                // 点击空白处清除输入焦点 → 触发失焦自动保存
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { focusManager.clearFocus() },
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 框架服务状态 ────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "LSPosed 框架服务",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (serviceReady) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                if (serviceReady) "已连接" else "未连接",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (serviceReady) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Text(
                        if (serviceReady) "配置读写可用 · 修改后自动保存"
                        else "未检测到 LSPosed 服务，自动保存不可用；请确认已在 LSPosed 中启用本模块",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = onScopeRequest, modifier = Modifier.fillMaxWidth()) {
                        Text("申请红果短剧作用域 (com.phoenix.read)")
                    }
                }
            }

            // ── AI 接口配置 ────────────────────────────────────────────
            SettingsSection(title = "AI 接口配置（OpenAI 兼容）") {
                HelpField(
                    title = "Base URL",
                    help = "AI 接口基地址，需兼容 OpenAI Chat Completions 格式。\n例如：https://api.deepseek.com",
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    placeholder = "如 https://api.deepseek.com",
                    onFocusLost = { autoSave() }
                )
                HelpField(
                    title = "API Key",
                    help = "AI 服务商提供的密钥，仅保存在本机配置中，用于调用接口时的身份鉴权。",
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = "sk-...",
                    onFocusLost = { autoSave() }
                )
                HelpField(
                    title = "模型名",
                    help = "请求 AI 接口时使用的模型名称。\n例如：deepseek-flash、gpt-4o-mini。",
                    value = model,
                    onValueChange = { model = it },
                    placeholder = "如 deepseek-flash",
                    onFocusLost = { autoSave() }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("取消思考", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(6.dp))
                    HelpBadge(
                        title = "取消思考",
                        help = "点击右侧箭头展开，按你使用的 AI 服务勾选要携带的\"关闭思考\"字段；" +
                                "收起后已勾选的项仍会在请求时携带，全部不勾选即完全关闭。\n\n" +
                                "注意：部分服务端（如 Groq、OpenAI 官方）遇到不认识的参数会直接报错" +
                                "（invalid_request_error），只勾选你的平台即可；\n" +
                                "· Groq 无对应字段，全部不勾选；\n" +
                                "· OpenAI 官方只勾\"OpenAI 官方\"一项（o 系列只能调低思考，" +
                                "GPT-5 支持更低；gpt-4o 等非推理模型无需勾选任何项）。"
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (thinkingProviders.isEmpty()) "已关闭"
                        else "已选 ${thinkingProviders.size} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 展开箭头：点击展开/收起平台勾选列表
                    val chevronRotation by animateFloatAsState(
                        targetValue = if (thinkingExpanded) 180f else 0f,
                        label = "thinkingChevron"
                    )
                    IconButton(onClick = { thinkingExpanded = !thinkingExpanded }) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (thinkingExpanded) "收起" else "展开",
                            modifier = Modifier.rotate(chevronRotation)
                        )
                    }
                }
                AnimatedVisibility(visible = thinkingExpanded) {
                    // 平台适配项：逐项勾选要携带的"关闭思考"字段（AiClient.THINKING_OPTIONS）
                    Column {
                        for (opt in AiClient.THINKING_OPTIONS) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 24.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(opt.label, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        opt.fields,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Checkbox(
                                    checked = opt.id in thinkingProviders,
                                    onCheckedChange = { checked ->
                                        thinkingProviders =
                                            if (checked) thinkingProviders + opt.id
                                            else thinkingProviders - opt.id
                                        autoSave()
                                    }
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AI 走模块进程", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(6.dp))
                    HelpBadge(
                        title = "AI 走模块进程",
                        help = "开启后 AI 请求由本模块自身的进程发出，而不是红果短剧进程。\n\n" +
                                "用途：使用国外大模型时，代理/VPN 白名单只需放行本模块应用，" +
                                "红果（视频流量大）无需走代理，节省流量。\n\n" +
                                "注意：若本模块被系统强停（设置里\"强行停止\"或部分省电策略杀后台），" +
                                "AI 总结会报\"进程桥不可用\"——打开一次模块设置页即可恢复；" +
                                "关闭本开关则回到旧的宿主进程直连方式。"
                    )
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = aiViaModule,
                        onCheckedChange = {
                            aiViaModule = it
                            autoSave()
                        }
                    )
                }
            }

            // ── 采集参数 ───────────────────────────────────────────────
            SettingsSection(title = "采集参数") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HelpField(
                        title = "首次采集评论数",
                        help = "进入新剧后首次触发AI总结时,交给AI的有效评论数。\n范围 10 ~ 200，默认 50。",
                        value = targetCount,
                        onValueChange = { targetCount = it.filter { c -> c.isDigit() } },
                        placeholder = "10 ~ 200，默认 50",
                        modifier = Modifier.weight(1f),
                        onFocusLost = { autoSave() }
                    )
                    HelpField(
                        title = "单批加载条数",
                        help = "每次加载评论的分批条数，批越大单次请求耗时越长。\n范围 5 ~ 30，默认 30。",
                        value = pageCount,
                        onValueChange = { pageCount = it.filter { c -> c.isDigit() } },
                        placeholder = "5 ~ 30，默认 30",
                        modifier = Modifier.weight(1f),
                        onFocusLost = { autoSave() }
                    )
                }
                HelpField(
                    title = "附加提示词",
                    help = "可选。追加到系统提示词末尾的额外要求，用于自定义 AI 总结的风格与侧重点。",
                    value = extraPrompt,
                    onValueChange = { extraPrompt = it },
                    placeholder = "可选，追加到系统提示词末尾",
                    singleLine = false,
                    minLines = 5,
                    onFocusLost = { autoSave() }
                )
                // 过滤规则入口：跳转二级页面管理排除型正则（该页面自行即时保存，与主页自动保存独立）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenFilterRules),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("过滤规则", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.width(6.dp))
                            HelpBadge(
                                title = "过滤规则",
                                help = "采用子串包含模式,命中任一规则,评论将被丢弃,不会交给AI处理"
                            )
                        }
                        Text(
                            "排除型正则，命中即不交给 AI · 点击管理",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "›",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── 评论词条日志 ───────────────────────────────────────────
            SettingsSection(title = "评论词条日志") {
                OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
                    Text("查看日志（按剧分列）")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HelpField(
                        title = "保留天数",
                        help = "日志保留天数，超过后最旧的剧集日志将被自动删除。\n填 0 表示不按天清理。范围 0 ~ 3650，默认 30。",
                        value = logRetentionDays,
                        onValueChange = { logRetentionDays = it.filter { c -> c.isDigit() } },
                        placeholder = "0 = 不限，默认 30",
                        modifier = Modifier.weight(1f),
                        onFocusLost = { autoSave() }
                    )
                    HelpField(
                        title = "总大小上限 MB",
                        help = "日志数据库总大小上限，超出后最旧的剧集日志将被自动删除。\n填 0 表示不按大小清理。范围 0 ~ 4096，默认 100。",
                        value = logMaxSizeMb,
                        onValueChange = { logMaxSizeMb = it.filter { c -> c.isDigit() } },
                        placeholder = "0 = 不限，默认 100",
                        modifier = Modifier.weight(1f),
                        onFocusLost = { autoSave() }
                    )
                }
                Text(
                    "超过保留天数或总大小上限时，最旧的剧集日志将被自动删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── 应用 ───────────────────────────────────────────────────
            SettingsSection(title = "应用") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("隐藏桌面图标", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (launcherHidden) "已隐藏 · 可在 LSPosed 管理器中重新打开"
                            else "隐藏后在 LSPosed 管理器中打开本模块",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = launcherHidden,
                        onCheckedChange = { checked ->
                            if (checked) showHideConfirm = true else onHideIconChange(false)
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showHideConfirm) {
        AlertDialog(
            onDismissRequest = { showHideConfirm = false },
            title = { Text("隐藏桌面图标？") },
            text = {
                Text(
                    "隐藏后将无法从桌面打开本应用。\n\n" +
                            "重新打开方式：在 LSPosed 管理器的模块页面打开本模块。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showHideConfirm = false
                    onHideIconChange(true)
                }) { Text("隐藏") }
            },
            dismissButton = {
                TextButton(onClick = { showHideConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 带标题分组的设置区块（Material3 卡片样式）。 */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

/**
 * 独立标题 + 输入框的设置项：标题行右方有 "?" 帮助徽标，
 * 点击后弹出对话框说明该输入框的用途。
 */
@Composable
private fun HelpField(
    title: String,
    help: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    onFocusLost: (() -> Unit)? = null,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(6.dp))
            HelpBadge(title = title, help = help)
        }
        Spacer(Modifier.height(6.dp))
        var hadFocus by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    // 仅在"持有焦点 → 失去焦点"的跳变时触发，避开首次组合的初始回调
                    if (hadFocus && !it.isFocused) onFocusLost?.invoke()
                    hadFocus = it.isFocused
                },
            placeholder = { Text(placeholder) },
            singleLine = singleLine,
            minLines = minLines,
            shape = MaterialTheme.shapes.medium
        )
    }
}

/**
 * 标题行右方的 "?" 帮助徽标：18dp 圆形 secondaryContainer 底色，
 * 点击弹出对话框展示 [help] 说明文字。
 */
@Composable
private fun HelpBadge(title: String, help: String) {
    var showHelp by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable { showHelp = true },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "?",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(title) },
            text = { Text(help) },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) { Text("知道了") }
            }
        )
    }
}
