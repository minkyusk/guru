package com.clauderelay.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauderelay.app.data.Block
import com.clauderelay.app.data.BlockKind
import com.clauderelay.app.data.ChatMessage
import com.clauderelay.app.data.ConnState
import com.clauderelay.app.data.SessionInfo
import java.time.Duration
import java.time.Instant

// ================================================================ shared
@Composable
private fun ConnDot(state: ConnState) {
    val color = when (state) {
        ConnState.CONNECTED -> Color(0xFF3FB950)
        ConnState.CONNECTING -> Color(0xFFD29922)
        ConnState.DISCONNECTED -> Color(0xFFF85149)
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun Snackbars(ui: UiState, vm: AppViewModel, host: SnackbarHostState) {
    LaunchedEffect(ui.snackbar) {
        ui.snackbar?.let {
            host.showSnackbar(it)
            vm.consumeSnackbar()
        }
    }
}

private fun relativeTime(iso: String): String = try {
    val d = Duration.between(Instant.parse(iso), Instant.now())
    when {
        d.toMinutes() < 1 -> "방금"
        d.toHours() < 1 -> "${d.toMinutes()}분 전"
        d.toDays() < 1 -> "${d.toHours()}시간 전"
        else -> "${d.toDays()}일 전"
    }
} catch (_: Exception) { "" }

// ================================================================ settings
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(ui: UiState, vm: AppViewModel) {
    var url by rememberSaveable { mutableStateOf(ui.serverUrl) }
    var token by rememberSaveable { mutableStateOf(ui.token) }
    var model by rememberSaveable { mutableStateOf(ui.model) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("연결 설정") },
            navigationIcon = {
                if (ui.serverUrl.isNotBlank()) IconButton(onClick = vm::back) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로")
                }
            },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "맥미니에서 실행 중인 relay-server의 주소와 토큰을 입력하세요. " +
                    "서버 로그(~/.claude-relay/server.log)에 표시됩니다. " +
                    "외부에서 접속하려면 Tailscale 주소(예: http://100.x.y.z:8787)를 권장합니다.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(url, { url = it }, label = { Text("서버 주소") }, placeholder = { Text("http://100.101.102.103:8787") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("토큰") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("모델 (선택, 비우면 기본값)") }, placeholder = { Text("opus / sonnet / haiku") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("도구 호출/결과 표시", Modifier.weight(1f))
                Switch(ui.showTools, vm::setShowTools)
            }
            TextButton(
                onClick = { vm.saveSettings(url, token, model) },
                enabled = url.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.align(Alignment.End),
            ) { Text("저장하고 연결") }
        }
    }
}

// ================================================================ session list
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(ui: UiState, vm: AppViewModel) {
    val snack = remember { SnackbarHostState() }
    Snackbars(ui, vm, snack)
    var showNew by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConnDot(ui.conn)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Claude 세션")
                            ui.host?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.reconnect(); vm.refreshSessions() }) { Icon(Icons.Default.Refresh, "새로고침") }
                    IconButton(onClick = vm::openSettings) { Icon(Icons.Default.Settings, "설정") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.loadProjects(); showNew = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("새 세션") },
            )
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = ui.sessionsLoading,
            onRefresh = vm::refreshSessions,
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            if (ui.sessions.isEmpty() && !ui.sessionsLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("세션이 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.sessions, key = { it.id }) { s ->
                    SessionRow(s, s.id in ui.running) { vm.openSession(s.id) }
                }
            }
        }
    }

    if (showNew) NewSessionDialog(ui, onDismiss = { showNew = false }) { cwd, text ->
        if (vm.startNewSession(cwd, text)) showNew = false
    }
}

@Composable
private fun SessionRow(s: SessionInfo, running: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(s.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(relativeTime(s.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            s.cwd?.let {
                Text(it.replace(Regex("^/Users/[^/]+"), "~"), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            s.lastPrompt?.takeIf { it != s.title }?.let {
                Text("› $it", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NewSessionDialog(ui: UiState, onDismiss: () -> Unit, onStart: (String, String) -> Unit) {
    var cwd by rememberSaveable { mutableStateOf(ui.projects.firstOrNull()?.cwd ?: "") }
    var text by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(ui.projects) { if (cwd.isBlank()) ui.projects.firstOrNull()?.let { cwd = it.cwd } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 세션 시작") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedTextField(
                        cwd, { cwd = it }, label = { Text("작업 폴더 (맥미니 절대경로)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { if (ui.projects.isNotEmpty()) TextButton(onClick = { menu = true }) { Text("목록") } },
                    )
                    DropdownMenu(menu, { menu = false }) {
                        ui.projects.take(30).forEach { p ->
                            DropdownMenuItem(text = { Text(p.cwd, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }, onClick = { cwd = p.cwd; menu = false })
                        }
                    }
                }
                OutlinedTextField(text, { text = it }, label = { Text("첫 메시지") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Text("권한 모드: ${ui.permissionMode}", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(cwd, text) }, enabled = !ui.pendingNewSession && cwd.isNotBlank() && text.isNotBlank()) {
                Text(if (ui.pendingNewSession) "시작 중…" else "시작")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

// ================================================================ chat
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(ui: UiState, vm: AppViewModel, sessionId: String) {
    val chat = ui.chat ?: return
    val running = sessionId in ui.running
    val snack = remember { SnackbarHostState() }
    Snackbars(ui, vm, snack)
    var input by rememberSaveable(sessionId) { mutableStateOf("") }
    val listState = rememberLazyListState()

    val visible = remember(chat.messages, ui.showTools) {
        if (ui.showTools) chat.messages
        else chat.messages.mapNotNull { m ->
            val b = m.blocks.filter { it.kind == BlockKind.TEXT }
            if (b.isEmpty()) null else m.copy(blocks = b)
        }
    }
    val itemCount = visible.size + (if (chat.partial.isNotEmpty()) 1 else 0) + (if (chat.lastRunInfo != null) 1 else 0)
    LaunchedEffect(itemCount, chat.partial.length / 200) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                title = {
                    Column {
                        Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        chat.cwd?.let { Text(it.replace(Regex("^/Users/[^/]+"), "~"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                    }
                },
                actions = { ConnDot(ui.conn); Spacer(Modifier.width(12.dp)) },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (running || chat.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.uuid }) { m -> MessageItem(m) }
                if (chat.partial.isNotEmpty()) item("partial") {
                    Bubble(isUser = false) { Text(chat.partial, style = MaterialTheme.typography.bodyMedium) }
                }
                chat.lastRunInfo?.let { info ->
                    item("runinfo") {
                        Text(info, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(4.dp))
                    }
                }
            }
            HorizontalDivider()
            ModeRow(ui, vm)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).navigationBarsPadding(),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    input, { input = it },
                    placeholder = { Text(if (running) "실행 중…" else "Claude에게 보낼 메시지") },
                    modifier = Modifier.weight(1f).heightIn(max = 180.dp),
                    maxLines = 8,
                )
                Spacer(Modifier.width(6.dp))
                if (running) {
                    FilledIconButton(onClick = vm::interruptCurrent) { Icon(Icons.Default.Stop, "중지") }
                } else {
                    FilledIconButton(
                        onClick = { if (vm.sendToCurrent(input)) input = "" },
                        enabled = input.isNotBlank() && ui.conn == ConnState.CONNECTED,
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "보내기") }
                }
            }
        }
    }
}

@Composable
private fun ModeRow(ui: UiState, vm: AppViewModel) {
    val labels = mapOf("acceptEdits" to "편집 자동승인", "default" to "기본", "plan" to "계획", "bypassPermissions" to "모든 권한")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ui.permissionModes.forEach { m ->
            FilterChip(selected = ui.permissionMode == m, onClick = { vm.setPermissionMode(m) }, label = { Text(labels[m] ?: m, fontSize = 12.sp) })
        }
    }
}

@Composable
private fun Bubble(isUser: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { content() }
        }
    }
}

@Composable
private fun MessageItem(m: ChatMessage) {
    if (m.isToolOutput) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { m.blocks.forEach { ToolBlock(it) } }
        return
    }
    val isUser = m.role == "user"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        m.blocks.forEach { b ->
            when (b.kind) {
                BlockKind.TEXT -> Bubble(isUser) {
                    SelectionContainer { Text(b.text, style = MaterialTheme.typography.bodyMedium) }
                }
                BlockKind.THINKING -> CollapsibleBlock("💭 생각", b.text, MaterialTheme.colorScheme.onSurfaceVariant)
                else -> ToolBlock(b)
            }
        }
    }
}

@Composable
private fun ToolBlock(b: Block) {
    when (b.kind) {
        BlockKind.TOOL_USE -> CollapsibleBlock("🔧 ${b.name ?: "tool"}", b.text, MaterialTheme.colorScheme.tertiary, previewLines = 2)
        BlockKind.TOOL_RESULT -> CollapsibleBlock(
            if (b.isError) "⚠️ 결과 (오류)" else "↳ 결과", b.text,
            if (b.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Unit
    }
}

@Composable
private fun CollapsibleBlock(label: String, text: String, color: Color, previewLines: Int = 0) {
    var open by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable { open = !open },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
            if (text.isNotBlank() && (open || previewLines > 0)) {
                SelectionContainer {
                    Text(
                        text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = if (open) Int.MAX_VALUE else previewLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
