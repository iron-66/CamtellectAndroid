package com.example.camtellect.realtime

import android.util.Log
import com.example.camtellect.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "RTRTC"

data class RealtimeSessionRequest(
    val model: String = RealtimeBuildConfig.model,
    val voice: String = RealtimeBuildConfig.voice,
    val enableVideo: Boolean = true
)

data class RealtimeIceServer(
    val urls: List<String>,
    val username: String?,
    val credential: String?
)

data class RealtimeSessionInfo(
    val clientSecret: String,
    val model: String,
    val voice: String,
    val iceServers: List<RealtimeIceServer>
)

class RealtimeSessionRepository(
    private val endpoint: String = RealtimeBuildConfig.sessionUrl
) {
    suspend fun fetchSession(request: RealtimeSessionRequest = RealtimeSessionRequest()): RealtimeSessionInfo =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("model", request.model)
                put("voice", request.voice)
                put("modalities", JSONArray().apply {
                    put("text")
                    put("audio")
                    if (request.enableVideo) {
                        put("video")
                    }
                })
            }

            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url(endpoint)
                .post(body)
                .build()

            val response: Response = Http.client.newCall(req).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string()
                    Log.e(TAG, "Ephemeral token request failed: code=${resp.code} body=$errorBody")
                    throw IllegalStateException("Unable to create realtime session: ${resp.code}")
                }
                val text = resp.body?.string().orEmpty()
                val json = try {
                    JSONObject(text)
                } catch (err: JSONException) {
                    Log.e(TAG, "Invalid realtime session response: $text", err)
                    throw IllegalStateException("Realtime session response is not JSON")
                }
                val clientSecret = json.optString("client_secret")
                if (clientSecret.isNullOrEmpty()) {
                    throw IllegalStateException("Realtime session missing client_secret")
                }
                val model = json.optString("model", request.model)
                val voice = json.optString("voice", request.voice)
                val iceServers = mutableListOf<RealtimeIceServer>()
                val iceArray = json.optJSONArray("ice_servers")
                if (iceArray != null) {
                    for (i in 0 until iceArray.length()) {
                        val item = iceArray.optJSONObject(i) ?: continue
                        val urls = when (val urlsValue = item.opt("urls")) {
                            is JSONArray -> buildList {
                                for (j in 0 until urlsValue.length()) {
                                    val url = urlsValue.optString(j)
                                    if (!url.isNullOrEmpty()) add(url)
                                }
                            }
                            is String -> listOf(urlsValue)
                            else -> emptyList()
                        }
                        iceServers += RealtimeIceServer(
                            urls = urls,
                            username = item.optString("username", null),
                            credential = item.optString("credential", null)
                        )
                    }
                }
                RealtimeSessionInfo(
                    clientSecret = clientSecret,
                    model = model,
                    voice = voice,
                    iceServers = iceServers
                )
            }
        }
}

private object RealtimeBuildConfig {
    private const val DEFAULT_SESSION_URL = "https://devicio.org/realtime-session"
    private const val DEFAULT_MODEL = "gpt-4o-realtime-preview"
    private const val DEFAULT_VOICE = "verse"

    private fun readField(name: String, fallback: String): String {
        return runCatching {
            val field = com.example.camtellect.BuildConfig::class.java.getField(name)
            (field.get(null) as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: fallback
    }

    val sessionUrl: String = readField("REALTIME_SESSION_URL", DEFAULT_SESSION_URL)
    val model: String = readField("REALTIME_MODEL", DEFAULT_MODEL)
    val voice: String = readField("REALTIME_VOICE", DEFAULT_VOICE)
}
