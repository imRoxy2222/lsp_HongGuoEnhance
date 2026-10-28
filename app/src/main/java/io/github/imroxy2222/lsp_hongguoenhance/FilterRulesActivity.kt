package io.github.imroxy2222.lsp_hongguoenhance

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.imroxy2222.lsp_hongguoenhance.hook.ModuleConfig
import io.github.imroxy2222.lsp_hongguoenhance.hook.ModuleConfig.FilterRule
import io.github.imroxy2222.lsp_hongguoenhance.ui.theme.HongGuoEnhanceTheme
import io.github.libxposed.service.XposedService
import java.util.regex.PatternSyntaxException

/**
 * "过滤规则"二级设置页：管理评论采集的排除型正则规则。
 *
 * 规则按列表顺序编号 1,2,3...（界面固定 3 位数字展示），采集时命中的
 * 规则编号与正则原文会记录在日志库中（日志页展示"未交给AI, 命中过滤器Z,规则XXXX"）。
 *
 * 交互（无页面级保存按钮，**每一步操作即时落盘**，下次拉取评论即生效）：
 *   条目单行展示：编号(3位) | 正则 + 备注灰字 | 编辑按钮 | 删除按钮；
 *   编辑/添加共用 [RuleDialog]（正则 + 备注适用情况），右下角"保存"，
 *   有未保存修改时返回会询问；删除需二次确认；右下角圆形 FAB"+"添加。
 *
 * 配置以 JSON 对象数组写入 libxposed remote preferences
 * （[ModuleConfig.KEY_EXCLUDE_RULES]，元素 {"pattern","note"}，兼容旧纯字符串数组），
 * 宿主进程采集侧每页解析时实时读取。
 */
class FilterRulesActivity : ComponentActivity() {

    private val serviceReady = mutableStateOf(false)
    /** null = 配置尚未加载完成。 */
    private val rules = mutableStateOf<List<FilterRule>?>(null)

    /** 订阅进程级服务状态（XposedServiceHelper 只支持单一 listener，禁止页面各自注册）。 */
    private val serviceListener = object : XposedServiceHolder.Listener {
        override fun onServiceChanged(s: XposedService?) {
            serviceReady.value = s != null
            if (s != null && rules.value == null) {
                // 配置项从未写入过 → 以内置默认规则作为编辑起点（与采集侧回退行为一致）
                rules.value = ModuleConfig.excludeRuleItems()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        XposedServiceHolder.addListener(serviceListener)

        setContent {
            HongGuoEnhanceTheme {
                FilterRulesScreen(
                    serviceReady = serviceReady.value,
                    rules = rules.value,
                    onUpdate = { persist(it) },
                )
            }
        }
    }

    override fun onDestroy() {
        XposedServiceHolder.removeListener(serviceListener)
        super.onDestroy()
    }

    /** 任一操作（添加/编辑/删除）的统一落盘入口：写 remote prefs 并刷新界面。 */
    private fun persist(newRules: List<FilterRule>) {
        if (!serviceReady.value) {
            Toast.makeText(this, "LSPosed 服务未连接，无法保存", Toast.LENGTH_LONG).show()
            return
        }
        ModuleConfig.setExcludeRuleItems(newRules)
        rules.value = newRules
        Toast.makeText(this, "已保存，下次采集评论即生效", Toast.LENGTH_SHORT).show()
    }
}

/** 编辑下标占位：表示"添加模式"（新规则编号 = 列表长度 + 1）。 */
private const val ADD_INDEX = -1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRulesScreen(
    serviceReady: Boolean,
    rules: List<FilterRule>?,
    onUpdate: (List<FilterRule>) -> Unit,
) {
    // 编辑中的规则下标；null = 关闭；ADD_INDEX = 添加模式
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("过滤规则", maxLines = 1) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            // 右下角圆形"+"：添加规则
            FloatingActionButton(
                onClick = { editIndex = ADD_INDEX },
                shape = CircleShape,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加规则")
            }
        }
    ) { padding ->
        when {
            rules == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        if (serviceReady) "正在加载配置..."
                        else "未检测到 LSPosed 服务，请确认已在 LSPosed 中启用本模块",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "排除型正则规则",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "评论命中任一规则（子串匹配即命中）即被丢弃，不会交给 AI。" +
                                            "规则按列表顺序编号（过滤器 001、002...），日志页中每条被丢弃的" +
                                            "评论会标注\"未交给AI, 命中过滤器Z,规则XXXX\"。\n\n" +
                                            "添加/编辑/删除均即时保存生效，无需重启红果短剧；" +
                                            "备注用于标注规则的适用情况；清空全部规则 = 不做任何过滤。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!serviceReady) {
                                    Text(
                                        "LSPosed 服务未连接，修改不可保存",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    itemsIndexed(rules, key = { i, _ -> "rule$i" }) { i, rule ->
                        RuleRow(
                            index = i + 1,
                            rule = rule,
                            onEdit = { editIndex = i },
                            onDelete = { deleteIndex = i },
                        )
                    }

                    if (rules.isEmpty()) {
                        item {
                            Text(
                                "暂无规则，评论不会做正则过滤（点击右下角 + 添加）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                    }

                    item { Spacer(Modifier.height(56.dp)) } // 底部留白，避免 FAB 遮挡末条
                }
            }
        }
    }

    // ---- 编辑 / 添加（共用同一个 Dialog） ----
    editIndex?.let { idx ->
        val list = rules ?: return@let
        val isAdd = idx == ADD_INDEX
        val initial = if (isAdd) FilterRule("", "") else list[idx]
        RuleDialog(
            title = if (isAdd) "添加过滤器 ${"%03d".format(list.size + 1)}"
            else "编辑过滤器 ${"%03d".format(idx + 1)}",
            initial = initial,
            onDismiss = { editIndex = null },
            onSave = { saved ->
                onUpdate(
                    if (isAdd) list + saved
                    else list.toMutableList().also { it[idx] = saved }
                )
                editIndex = null
            }
        )
    }

    // ---- 删除二次确认 ----
    deleteIndex?.let { idx ->
        val rule = rules?.getOrNull(idx) ?: run { deleteIndex = null; return@let }
        AlertDialog(
            onDismissRequest = { deleteIndex = null },
            title = { Text("删除过滤器 ${"%03d".format(idx + 1)}？") },
            text = {
                Text("正则：${rule.pattern}\n删除后立即生效，后续规则编号顺延补位。")
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdate(rules.toMutableList().also { it.removeAt(idx) })
                    deleteIndex = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteIndex = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 单条规则条目（单行）：
 *   [编号,固定 3 位] [正则 + 备注(灰色小字,无备注不显示)] [编辑] [删除]
 */
@Composable
private fun RuleRow(
    index: Int,
    rule: FilterRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "%03d".format(index),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(40.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    rule.pattern,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (rule.note.isNotBlank()) {
                    Text(
                        rule.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑过滤器 ${"%03d".format(index)}")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除过滤器 ${"%03d".format(index)}",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 编辑 / 添加规则的通用 Dialog：
 *   - 正则输入（实时校验，非法或为空时"保存"不可点）；
 *   - 备注输入（标注规则适用情况，可选，展示在列表条目第二行灰字）；
 *   - 右下角"保存"按钮；有未保存修改时点返回/浮层外区域会先询问"保存修改？"。
 */
@Composable
private fun RuleDialog(
    title: String,
    initial: FilterRule,
    onDismiss: () -> Unit,
    onSave: (FilterRule) -> Unit,
) {
    var pattern by remember { mutableStateOf(initial.pattern) }
    var note by remember { mutableStateOf(initial.note) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val regexError = remember(pattern) {
        when {
            pattern.isBlank() -> "正则不能为空"
            else -> try {
                Regex(pattern); null
            } catch (t: PatternSyntaxException) {
                "非法正则：${t.description ?: t.message ?: "语法错误"}"
            }
        }
    }
    val changed = pattern != initial.pattern || note != initial.note

    AlertDialog(
        onDismissRequest = {
            if (changed) confirmDiscard = true else onDismiss()
        },
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("正则表达式") },
                    placeholder = { Text("如 第.+季") },
                    singleLine = true,
                    isError = regexError != null,
                    supportingText = regexError?.let {
                        { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    },
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("备注（适用情况，可选）") },
                    placeholder = { Text("如：剧名/续季宣传语") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
            }
        },
        confirmButton = {
            // AlertDialog 的 confirmButton 位于右下角
            TextButton(
                enabled = regexError == null,
                onClick = { onSave(FilterRule(pattern.trim(), note.trim())) }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = {
                if (changed) confirmDiscard = true else onDismiss()
            }) { Text("取消") }
        }
    )

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("保存修改？") },
            text = { Text("当前规则有未保存的修改。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onSave(FilterRule(pattern.trim(), note.trim()))
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmDiscard = false
                        onDismiss()
                    }) { Text("不保存") }
                    TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") }
                }
            }
        )
    }
}
