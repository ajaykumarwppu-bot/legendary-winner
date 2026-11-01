package com.example.gemini.data.agent.opencode

import com.example.gemini.data.preferences.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP client for bridge `/api/opencode/` routes (which proxy to local `opencode serve`).
 */
class OpenCodeBridgeClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val jsonType = "application/json".toMediaType()

    private fun url(path: String): String =
        AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/opencode" + path

    private suspend fun request(builder: Request.Builder): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(text) }.getOrElse {
                    error("HTTP ${resp.code}: ${text.take(200)}")
                }
                if (resp.code == 503 && obj.optBoolean("disabled")) {
                    error("OpenCode is turned off in the app. Enable it in Settings → Agents and restart.")
                }
                if (!resp.isSuccessful && !obj.optBoolean("success", false)) {
                    error(obj.optString("error").ifBlank { "HTTP ${resp.code}" })
                }
                obj
            }
        }
    }

    suspend fun status(): Result<JSONObject> =
        request(Request.Builder().url(url("/status")).get())

    suspend fun installInfo(): Result<JSONObject> =
        request(Request.Builder().url(url("/install")).get())

    suspend fun startInstall(): Result<JSONObject> =
        request(Request.Builder().url(url("/install")).post("{}".toRequestBody(jsonType)))

    suspend fun createSession(title: String = "OpenCode Chat"): Result<String> {
        val body = JSONObject().put("title", title).toString()
        return request(
            Request.Builder().url(url("/sessions")).post(body.toRequestBody(jsonType))
        ).mapCatching { obj ->
            obj.optJSONObject("session")?.optString("id")
                ?.takeIf { it.isNotBlank() }
                ?: error(obj.optString("error").ifBlank { "no session id" })
        }
    }

    suspend fun deleteSession(id: String): Result<Unit> {
        return request(Request.Builder().url(url("/sessions/$id")).delete()).map { }
    }

    suspend fun abortSession(id: String): Result<Unit> {
        return request(
            Request.Builder().url(url("/sessions/$id/abort")).post("{}".toRequestBody(jsonType))
        ).map { }
    }

    suspend fun listMessages(sessionId: String): Result<org.json.JSONArray> {
        return request(Request.Builder().url(url("/sessions/$sessionId/messages")).get()).mapCatching { obj ->
            obj.optJSONArray("messages") ?: org.json.JSONArray()
        }
    }

    /** Send prompt and wait for full assistant reply (Antigravity-level, non-streaming first). */
    suspend fun sendMessage(sessionId: String, content: String): Result<String> {
        val body = JSONObject().put("content", content).toString()
        return request(
            Request.Builder().url(url("/sessions/$sessionId/message")).post(body.toRequestBody(jsonType))
        ).mapCatching { obj ->
            if (!obj.optBoolean("success", true) && obj.has("error")) {
                error(obj.optString("error"))
            }
            obj.optString("text").ifBlank {
                // fallback: concatenate text parts if present
                val parts = obj.optJSONArray("parts")
                buildString {
                    if (parts != nil) {
                        for (i in 0 until parts.length()) {
                            val p = parts.optJSONObject(i) ?: continue
                            if (p.optString("type") == "text") {
                                val t = p.optString("text")
                                if (t.isNotBlank()) {
                                    if (isNotEmpty()) append('\n')
                                    append(t)
                                }
                            }
                        }
                    }
                }.ifBlank { error(obj.optString("error").ifBlank { "empty reply from OpenCode" }) }
            }
        }
    }
}
