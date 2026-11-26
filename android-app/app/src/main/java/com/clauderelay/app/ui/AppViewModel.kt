package com.clauderelay.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.clauderelay.app.Notifier
import com.clauderelay.app.data.ChatMessage
import com.clauderelay.app.data.ConnState
import com.clauderelay.app.data.Prefs
import com.clauderelay.app.data.Project
import com.clauderelay.app.data.RelayClient
import com.clauderelay.app.data.SessionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed interface Screen {
    data object Settings : Screen
    data object Sessions : Screen
    data class Chat(val sessionId: String) : Screen
}

data class ChatState(
    val sessionId: String,
    val title: String = "",
    val cwd: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val partial: String = "",
    val loading: Boolean = true,
    val lastRunInfo: String? = null,
)

data class UiState(
    val screen: Screen = Screen.Sessions,
    val conn: ConnState = ConnState.DISCONNECTED,
    val sessions: List<SessionInfo> = emptyList(),
    val sessionsLoading: Boolean = false,
    val running: Set<String> = emptySet(),
    val chat: ChatState? = null,
    val projects: List<Project> = emptyList(),
    val permissionModes: List<String> = listOf("acceptEdits", "default", "plan", "bypassPermissions"),
    val permissionMode: String = "acceptEdits",
    val model: String = "",
    val showTools: Boolean = true,
    val serverUrl: String = "",
    val token: String = "",
    val host: String? = null,
    val snackbar: String? = null,
    val pendingNewSession: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val client = RelayClient(viewModelScope)
    private val notifier = Notifier(app)

    private val _ui = MutableStateFlow(
        UiState(
            serverUrl = prefs.serverUrl,
            token = prefs.token,
            permissionMode = prefs.permissionMode,
            model = prefs.model,
            showTools = prefs.showTools,
        )
    )
    val ui: StateFlow<UiState> = _ui

    /** File offset of the history we loaded, per session (for gap replay on subscribe). */
    private var historyOffset = 0L

    init {
        viewModelScope.launch { client.state.collect { s -> _ui.update { it.copy(conn = s) } } }
        viewModelScope.launch { client.events.collect { handleEvent(it) } }
        if (prefs.serverUrl.isBlank() || prefs.token.isBlank()) {
            _ui.update { it.copy(screen = Screen.Settings) }
        } else {
            client.configure(prefs.serverUrl, prefs.token)
            client.connect()
            refreshSessions()
        }
    }

    // ------------------------------------------------------------ settings
    fun saveSettings(url: String, token: String, model: String) {
        var u = url.trim()
        if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        prefs.serverUrl = u
        prefs.token = token.trim()
        prefs.model = model.trim()
        _ui.update { it.copy(serverUrl = u, token = token.trim(), model = model.trim(), screen = Screen.Sessions) }
        client.disconnect()
        client.configure(u, token)
        client.connect()
        refreshSessions()
    }

    fun setPermissionMode(mode: String) {
        prefs.permissionMode = mode
        _ui.update { it.copy(permissionMode = mode) }
    }

    fun setShowTools(v: Boolean) {
        prefs.showTools = v
        _ui.update { it.copy(showTools = v) }
    }

    fun openSettings() = _ui.update { it.copy(screen = Screen.Settings) }
    fun consumeSnackbar() = _ui.update { it.copy(snackbar = null) }
    fun reconnect() = client.reconnectNow()
    private fun toast(msg: String) = _ui.update { it.copy(snackbar = msg) }

    // ------------------------------------------------------------ sessions
    fun refreshSessions() {
        if (!client.isConfigured) return
        _ui.update { it.copy(sessionsLoading = true) }
        viewModelScope.launch {
            try {
                val (list, modes) = client.sessions()
                _ui.update { s ->
                    s.copy(
                        sessions = list,
                        sessionsLoading = false,
                        running = s.running + list.filter { it.running }.map { it.id },
                        permissionModes = modes.ifEmpty { s.permissionModes },
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(sessionsLoading = false) }
                toast("세션 목록을 불러오지 못했습니다: ${e.message}")
            }
        }
    }

    fun loadProjects() {
        viewModelScope.launch {
            try {
                val p = client.projects()
                _ui.update { it.copy(projects = p) }
            } catch (e: Exception) {
                toast("프로젝트 목록 오류: ${e.message}")
            }
        }
    }

    fun openSession(id: String) {
        val info = _ui.value.sessions.firstOrNull { it.id == id }
        _ui.value.chat?.let { if (it.sessionId != id) client.unsubscribe(it.sessionId) }
        _ui.update {
            it.copy(screen = Screen.Chat(id), chat = ChatState(sessionId = id, title = info?.title ?: "새 세션", cwd = info?.cwd))
        }
        loadHistory(id, subscribe = true)
    }

    private fun loadHistory(id: String, subscribe: Boolean) {
        viewModelScope.launch {
            try {
                val (msgs, offset, running) = client.history(id)
                historyOffset = offset
                updateChat(id) { c -> c.copy(messages = merge(c.messages, msgs), loading = false) }
                if (running) _ui.update { it.copy(running = it.running + id) }
                if (subscribe) client.subscribe(id, offset)
            } catch (e: Exception) {
                updateChat(id) { it.copy(loading = false) }
                if (subscribe) client.subscribe(id, null)
                toast("기록을 불러오지 못했습니다: ${e.message}")
            }
        }
    }

    fun back() {
        when (_ui.value.screen) {
            is Screen.Chat -> {
                _ui.value.chat?.let { client.unsubscribe(it.sessionId) }
                _ui.update { it.copy(screen = Screen.Sessions, chat = null) }
                refreshSessions()
            }
            Screen.Settings -> if (client.isConfigured) _ui.update { it.copy(screen = Screen.Sessions) }
            Screen.Sessions -> Unit
        }
    }

    // ------------------------------------------------------------ sending
    fun sendToCurrent(text: String): Boolean {
        val chat = _ui.value.chat ?: return false
        if (text.isBlank()) return false
        if (chat.sessionId in _ui.value.running) {
            toast("아직 실행 중입니다. 끝난 뒤 보내거나 중지하세요.")
            return false
        }
        val ok = client.send(chat.sessionId, null, text, _ui.value.permissionMode, _ui.value.model)
        if (!ok) toast("서버에 연결되어 있지 않습니다")
        return ok
    }

    fun startNewSession(cwd: String, text: String): Boolean {
        if (cwd.isBlank() || text.isBlank()) return false
        val ok = client.send(null, cwd.trim(), text, _ui.value.permissionMode, _ui.value.model)
        if (ok) _ui.update { it.copy(pendingNewSession = true) } else toast("서버에 연결되어 있지 않습니다")
        return ok
    }

    fun interruptCurrent() {
        _ui.value.chat?.let { client.interrupt(it.sessionId) }
    }

    // ------------------------------------------------------------ events
    private fun handleEvent(e: JSONObject) {
        val sid = e.optString("sessionId", "")
        when (e.optString("type")) {
            "_connected" -> {
                refreshSessions()
                _ui.value.chat?.let { loadHistory(it.sessionId, subscribe = false) }
            }
            "hello" -> {
                val running = e.optJSONArray("running")
                val set = mutableSetOf<String>()
                if (running != null) for (i in 0 until running.length()) set.add(running.getString(i))
                _ui.update { it.copy(running = set, host = e.optString("host").ifBlank { null }) }
            }
            "message" -> {
                val m = ChatMessage.from(e.getJSONObject("message"))
                updateChat(sid) { c ->
                    c.copy(
                        messages = merge(c.messages, listOf(m)),
                        partial = if (m.role == "assistant") "" else c.partial,
                    )
                }
            }
            "delta" -> updateChat(sid) { c -> c.copy(partial = c.partial + e.optString("text")) }
            "run_started" -> {
                _ui.update { it.copy(running = it.running + sid) }
                updateChat(sid) { it.copy(lastRunInfo = null) }
            }
            "sent" -> if (e.optBoolean("isNew") && _ui.value.pendingNewSession) {
                _ui.update {
                    it.copy(
                        pendingNewSession = false,
                        screen = Screen.Chat(sid),
                        chat = ChatState(sessionId = sid, title = "새 세션", cwd = e.optString("cwd"), loading = false),
                    )
                }
                client.subscribe(sid, null)
            }
            "run_finished" -> {
                _ui.update { it.copy(running = it.running - sid) }
                val ok = e.optBoolean("ok")
                val interrupted = e.optBoolean("interrupted")
                val cost = if (e.isNull("costUsd")) null else e.optDouble("costUsd")
                val secs = e.optLong("durationMs") / 1000
                val info = buildString {
                    append(if (interrupted) "중지됨" else if (ok) "완료" else "오류")
                    append(" · ${secs}s")
                    if (cost != null) append(" · $" + String.format("%.4f", cost))
                    if (!ok && !interrupted) e.optString("error").takeIf { it.isNotBlank() && it != "null" }?.let { append("\n$it") }
                }
                updateChat(sid) { it.copy(partial = "", lastRunInfo = info) }
                val inBackground = !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                val viewing = (_ui.value.screen as? Screen.Chat)?.sessionId == sid
                if (inBackground || !viewing) {
                    val title = _ui.value.sessions.firstOrNull { it.id == sid }?.title ?: e.optString("title")
                    notifier.runFinished(sid, title, info)
                }
                if (_ui.value.screen == Screen.Sessions) refreshSessions()
            }
            "error" -> {
                _ui.update { it.copy(pendingNewSession = false) }
                toast(e.optString("error", "오류"))
            }
        }
    }

    private fun updateChat(sessionId: String, f: (ChatState) -> ChatState) {
        _ui.update { s ->
            val c = s.chat
            if (c != null && c.sessionId == sessionId) s.copy(chat = f(c)) else s
        }
    }

    private fun merge(old: List<ChatMessage>, new: List<ChatMessage>): List<ChatMessage> {
        if (new.isEmpty()) return old
        val seen = old.mapTo(HashSet()) { it.uuid }
        val add = new.filter { seen.add(it.uuid) }
        if (add.isEmpty()) return old
        val combined = old + add
        // keep chronological order (ISO timestamps sort lexicographically)
        return if (add.any { a -> old.isNotEmpty() && (a.ts ?: "") < (old.last().ts ?: "") }) combined.sortedBy { it.ts ?: "" } else combined
    }

    fun openFromNotification(sessionId: String?) {
        if (sessionId != null && client.isConfigured) openSession(sessionId)
    }

    override fun onCleared() {
        client.disconnect()
    }
}
