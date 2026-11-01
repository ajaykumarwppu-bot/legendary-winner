package com.example.gemini.data.agent.opencode

import android.app.Application
import android.util.Log
import com.example.gemini.data.agent.AgentChatBackend
import com.example.gemini.data.agent.ChatSessionStore
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * OpenCode chat backend (Antigravity-level depth).
 *
 * Talks to the Go bridge which supervises a single `opencode serve` process.
 * Sessions / prompts / SSE events will be mapped here in later steps.
 * For now this provides a working AgentChatBackend shell so the agent can be
 * selected and the UI stays consistent with AGY / Claude.
 */
class OpenCodeChatBackend(
    private val application: Application,
    private val backendScope: CoroutineScope,
    private val store: ChatSessionStore,
    private val client: OpenCodeBridgeClient = OpenCodeBridgeClient(),
) : AgentChatBackend {

    override val kind = AgentKind.OPENCODE

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    override val currentConversation: StateFlow<Conversation?> = store.currentConversation.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _isLoadingConversation = MutableStateFlow(false)
    override val isLoadingConversation: StateFlow<Boolean> = _isLoadingConversation.asStateFlow()

    private val _conversationError = MutableStateFlow<String?>(null)
    override val conversationError: StateFlow<String?> = _conversationError.asStateFlow()

    private val _bridgeStatus = MutableStateFlow<String?>("checking")
    /** Live status from bridge `/api/opencode/status` (idle/starting/online/error/stopped). */
    val bridgeStatus: StateFlow<String?> = _bridgeStatus.asStateFlow()

    /** Maps app conversation id → OpenCode server session id. */
    private val remoteSessionIds = mutableMapOf<String, String>()
    private var statusJob: Job? = null
    private var activePromptJob: Job? = null

    init {
        statusJob = backendScope.launch(Dispatchers.IO) {
            refreshStatus()
        }
    }

    private suspend fun refreshStatus() {
        client.status()
            .onSuccess { obj ->
                _bridgeStatus.value = obj.optString("status").ifBlank { "unknown" }
            }
            .onFailure {
                _bridgeStatus.value = "error"
                Log.w(TAG, "status: ${it.message}")
            }
    }

    override fun startNewChat() {
        val id = UUID.randomUUID().toString()
        val conv = Conversation(
            id = id,
            title = "OpenCode Chat",
            sessionId = id,
            agent = AgentKind.OPENCODE
        )
        _conversations.value = listOf(conv) + _conversations.value
        store.currentConversation.value = conv
        _messages.value = emptyList()
        _conversationError.value = null
        _isStreaming.value = false
        remoteSessionIds.remove(id)
        Log.d(TAG, "startNewChat id=$id")
    }

    override fun selectConversation(id: String) {
        val conv = _conversations.value.find { it.id == id }
            ?: Conversation(id = id, title = "OpenCode Chat", sessionId = id, agent = AgentKind.OPENCODE)
        store.currentConversation.value = conv
        _isLoadingConversation.value = true
        backendScope.launch {
            try {
                val remoteId = remoteSessionIds[id] ?: conv.sessionId.takeIf { it.isNotBlank() && it != id }
                if (!remoteId.isNullOrBlank()) {
                    remoteSessionIds[id] = remoteId
                    val arr = client.listMessages(remoteId).getOrNull()
                    _messages.value = if (arr != null) parseRemoteMessages(conv.id, arr) else emptyList()
                } else {
                    _messages.value = emptyList()
                }
            } catch (e: Exception) {
                Log.w(TAG, "history load failed", e)
                _messages.value = emptyList()
            } finally {
                _isLoadingConversation.value = false
            }
        }
    }

    private fun parseRemoteMessages(conversationId: String, arr: org.json.JSONArray): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val info = item.optJSONObject("info")
            val parts = item.optJSONArray("parts")
            val roleStr = (info?.optString("role") ?: item.optString("role")).lowercase()
            val role = when {
                roleStr.contains("user") -> MessageRole.USER
                roleStr.contains("assistant") -> MessageRole.ASSISTANT
                roleStr.contains("system") -> MessageRole.SYSTEM
                else -> MessageRole.ASSISTANT
            }
            val text = buildString {
                if (parts != null) {
                    for (j in 0 until parts.length()) {
                        val p = parts.optJSONObject(j) ?: continue
                        if (p.optString("type") == "text") {
                            val t = p.optString("text")
                            if (t.isNotBlank()) {
                                if (isNotEmpty()) append('\n')
                                append(t)
                            }
                        }
                    }
                }
                if (isEmpty()) {
                    append(item.optString("content").ifBlank { info?.optString("content").orEmpty() })
                }
            }
            if (text.isBlank()) continue
            out += ChatMessage(
                id = info?.optString("id")?.ifBlank { null } ?: UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = role,
                content = text,
            )
        }
        return out
    }

    override fun sendMessage(content: String) {
        val conv = store.currentConversation.value
            ?: run {
                startNewChat()
                store.currentConversation.value
            } ?: return

        if (content.isBlank()) return

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conv.id,
            role = MessageRole.USER,
            content = content,
        )
        _messages.value = _messages.value + userMsg
        _isStreaming.value = true
        _conversationError.value = null

        // Update title from first prompt
        if (conv.title == "OpenCode Chat" || conv.title == "New Chat") {
            val short = content.trim().take(48).let { if (content.trim().length > 48) "$it…" else it }
            val updated = conv.copy(title = short.ifBlank { conv.title }, updatedAt = System.currentTimeMillis())
            store.currentConversation.value = updated
            _conversations.value = _conversations.value.map { if (it.id == conv.id) updated else it }
        }

        activePromptJob?.cancel()
        activePromptJob = backendScope.launch {
            try {
                refreshStatus()
                if (_bridgeStatus.value != "online") {
                    // try once more after a short wait (serve may still be starting)
                    kotlinx.coroutines.delay(1500)
                    refreshStatus()
                }
                if (_bridgeStatus.value != "online") {
                    error(
                        "OpenCode serve is not online (status=${_bridgeStatus.value}). " +
                            "Install `opencode` on the device and enable OpenCode in Settings → Agents."
                    )
                }

                var remoteId = remoteSessionIds[conv.id]
                if (remoteId.isNullOrBlank()) {
                    remoteId = client.createSession(conv.title).getOrThrow()
                    remoteSessionIds[conv.id] = remoteId
                    // keep sessionId on conversation for later history
                    val linked = (store.currentConversation.value ?: conv).copy(sessionId = remoteId)
                    store.currentConversation.value = linked
                    _conversations.value = _conversations.value.map { if (it.id == conv.id) linked else it }
                }

                val reply = client.sendMessage(remoteId, content).getOrThrow()
                val assistantMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    conversationId = conv.id,
                    role = MessageRole.ASSISTANT,
                    content = reply,
                )
                _messages.value = _messages.value + assistantMsg
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage failed", e)
                _conversationError.value = e.message ?: "OpenCode error"
            } finally {
                _isStreaming.value = false
            }
        }
    }

    override fun stopStreaming() {
        _isStreaming.value = false
        activePromptJob?.cancel()
        val remoteId = store.currentConversation.value?.let { remoteSessionIds[it.id] }
        if (!remoteId.isNullOrBlank()) {
            backendScope.launch {
                client.abortSession(remoteId)
            }
        }
    }

    override fun deleteConversation(id: String) {
        val remoteId = remoteSessionIds.remove(id)
        _conversations.value = _conversations.value.filter { it.id != id }
        if (store.currentConversation.value?.id == id) {
            store.currentConversation.value = null
            _messages.value = emptyList()
        }
        if (!remoteId.isNullOrBlank()) {
            backendScope.launch { client.deleteSession(remoteId) }
        }
    }

    override fun forkConversation(id: String) {
        // TODO: fork via OpenCode API when available
        Log.d(TAG, "forkConversation stub id=$id")
    }

    override fun retryConnections() {
        _conversationError.value = null
        backendScope.launch(Dispatchers.IO) {
            _bridgeStatus.value = "retrying"
            refreshStatus()
        }
    }

    override fun onAppForegrounded() {
        // lightweight refresh
        retryConnections()
    }

    /** Called when OpenCode is enabled in agent settings. */
    fun start() {
        Log.d(TAG, "OpenCode backend start")
        retryConnections()
    }

    /** Called when OpenCode is disabled in agent settings. */
    fun stop() {
        Log.d(TAG, "OpenCode backend stop")
        stopStreaming()
        _bridgeStatus.value = "stopped"
    }

    /** Leave this agent so another agent can own the screen. */
    fun detach() {
        stopStreaming()
    }

    private val _installInfo = MutableStateFlow<org.json.JSONObject?>(null)
    val installInfo: StateFlow<org.json.JSONObject?> = _installInfo.asStateFlow()

    /** Refresh install / version info from the bridge. */
    fun refreshInstallInfo() {
        backendScope.launch(Dispatchers.IO) {
            client.installInfo()
                .onSuccess { _installInfo.value = it }
                .onFailure { Log.w(TAG, "installInfo: ${it.message}") }
            refreshStatus()
        }
    }

    /**
     * Start managed install: official Linux binary, or native Android aarch64 build on phone.
     * Poll [installInfo] for job progress / errors (shown on OpenCode settings screen).
     */
    fun startInstall() {
        backendScope.launch(Dispatchers.IO) {
            client.startInstall()
                .onSuccess {
                    _installInfo.value = it
                    // Large Android builds can take several minutes
                    repeat(300) {
                        kotlinx.coroutines.delay(1000)
                        val info = client.installInfo().getOrNull() ?: return@repeat
                        _installInfo.value = info
                        val job = info.optJSONObject("job")
                        if (job != null && !job.optBoolean("running") && job.optBoolean("done")) {
                            refreshStatus()
                            return@launch
                        }
                    }
                }
                .onFailure { err ->
                    Log.e(TAG, "startInstall failed", err)
                    _conversationError.value = err.message
                    // Surface on settings UI even when HTTP fails before a job starts
                    val prev = _installInfo.value ?: org.json.JSONObject()
                    val job = org.json.JSONObject()
                        .put("running", false)
                        .put("done", true)
                        .put("error", err.message ?: "install failed")
                        .put("log", "ERROR: ${err.message ?: "install failed"}")
                    prev.put("job", job)
                    _installInfo.value = prev
                }
        }
    }

    companion object {
        private const val TAG = "OpenCodeChatBackend"
    }
}
