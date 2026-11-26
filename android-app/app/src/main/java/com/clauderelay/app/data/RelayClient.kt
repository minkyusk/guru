package com.clauderelay.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Talks to the relay server on the Mac mini: REST for lists/history, one WebSocket for live events.
 * The WebSocket reconnects automatically with backoff and re-subscribes to sessions.
 */
class RelayClient(private val scope: CoroutineScope) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private var baseUrl = ""
    private var token = ""
    private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    private var wantConnected = false
    private var attempt = 0
    private val subscriptions = mutableSetOf<String>()

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state

    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 512)
    val events: SharedFlow<JSONObject> = _events

    fun configure(url: String, token: String) {
        this.baseUrl = url.trim().trimEnd('/')
        this.token = token.trim()
    }

    val isConfigured get() = baseUrl.isNotEmpty() && token.isNotEmpty()

    // ------------------------------------------------------------ REST
    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (res.code == 401) throw IOException("인증 실패: 토큰을 확인하세요")
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}: $body")
            JSONObject(body)
        }
    }

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/api/health").build()
        http.newCall(req).execute().use { it.isSuccessful }
    }

    suspend fun sessions(): Pair<List<SessionInfo>, List<String>> {
        val o = getJson("/api/sessions?limit=200")
        return o.optJSONArray("sessions").mapObjects { SessionInfo.from(it) } to
            o.optJSONArray("permissionModes").toStringList()
    }

    suspend fun projects(): List<Project> =
        getJson("/api/projects").optJSONArray("projects").mapObjects {
            Project(it.getString("cwd"), it.optString("updatedAt"))
        }

    /** Returns (messages, file offset, running). */
    suspend fun history(sessionId: String, limit: Int = 400): Triple<List<ChatMessage>, Long, Boolean> {
        val o = getJson("/api/sessions/$sessionId/messages?limit=$limit")
        return Triple(
            o.optJSONArray("messages").mapObjects { ChatMessage.from(it) },
            o.optLong("offset", 0),
            o.optBoolean("running", false),
        )
    }

    // ------------------------------------------------------------ WebSocket
    fun connect() {
        wantConnected = true
        if (!isConfigured || ws != null) return
        openSocket()
    }

    fun disconnect() {
        wantConnected = false
        reconnectJob?.cancel()
        ws?.close(1000, "bye")
        ws = null
        _state.value = ConnState.DISCONNECTED
    }

    fun reconnectNow() {
        ws?.cancel()
        ws = null
        attempt = 0
        if (wantConnected) openSocket()
    }

    private fun wsUrl(): String {
        val u = baseUrl.toHttpUrl()
        val scheme = if (u.isHttps) "wss" else "ws"
        val port = if (u.port == (if (u.isHttps) 443 else 80)) "" else ":${u.port}"
        return "$scheme://${u.host}$port/ws?token=${java.net.URLEncoder.encode(token, "UTF-8")}"
    }

    private fun openSocket() {
        _state.value = ConnState.CONNECTING
        val req = try {
            Request.Builder().url(wsUrl()).build()
        } catch (e: IllegalArgumentException) {
            _state.value = ConnState.DISCONNECTED
            emitLocalError("서버 주소가 올바르지 않습니다: $baseUrl")
            return
        }
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                _state.value = ConnState.CONNECTED
                synchronized(subscriptions) {
                    subscriptions.forEach { sendRaw(JSONObject().put("type", "subscribe").put("sessionId", it)) }
                }
                _events.tryEmit(JSONObject().put("type", "_connected"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try { _events.tryEmit(JSONObject(text)) } catch (_: Exception) { }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 401) emitLocalError("인증 실패: 토큰을 확인하세요")
                onClosedInternal(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosedInternal(webSocket)
        })
    }

    private fun onClosedInternal(socket: WebSocket) {
        if (ws !== socket) return
        ws = null
        _state.value = ConnState.DISCONNECTED
        if (!wantConnected) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val waitMs = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
            attempt++
            delay(waitMs)
            if (wantConnected && ws == null) openSocket()
        }
    }

    private fun emitLocalError(msg: String) {
        _events.tryEmit(JSONObject().put("type", "error").put("error", msg))
    }

    private fun sendRaw(o: JSONObject): Boolean = ws?.send(o.toString()) ?: false

    fun subscribe(sessionId: String, fromOffset: Long?) {
        synchronized(subscriptions) { subscriptions.add(sessionId) }
        val o = JSONObject().put("type", "subscribe").put("sessionId", sessionId)
        if (fromOffset != null) o.put("fromOffset", fromOffset)
        sendRaw(o)
    }

    fun unsubscribe(sessionId: String) {
        synchronized(subscriptions) { subscriptions.remove(sessionId) }
        sendRaw(JSONObject().put("type", "unsubscribe").put("sessionId", sessionId))
    }

    /** sessionId == null starts a new session in [cwd]. */
    fun send(sessionId: String?, cwd: String?, text: String, permissionMode: String?, model: String?): Boolean {
        val o = JSONObject().put("type", "send").put("text", text)
        if (sessionId != null) o.put("sessionId", sessionId)
        if (cwd != null) o.put("cwd", cwd)
        if (!permissionMode.isNullOrBlank()) o.put("permissionMode", permissionMode)
        if (!model.isNullOrBlank()) o.put("model", model)
        return sendRaw(o)
    }

    fun interrupt(sessionId: String) =
        sendRaw(JSONObject().put("type", "interrupt").put("sessionId", sessionId))
}
