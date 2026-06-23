package com.example.twitchnetworknotifier.monitor.network

import com.example.twitchnetworknotifier.monitor.model.TwitchCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class TwitchApiClientImpl(
    private val httpClient: OkHttpClient = OkHttpClient()
) : TwitchApiClient {

    private var cachedToken: String? = null
    private var tokenExpiryMillis: Long = 0L

    override suspend fun getStreamStatus(
        channelName: String,
        clientId: String,
        clientSecret: String
    ): TwitchCheckResult = withContext(Dispatchers.IO) {
        try {
            val token = ensureValidToken(clientId, clientSecret)
            val request = Request.Builder()
                .url("https://api.twitch.tv/helix/streams?user_login=$channelName")
                .header("Client-Id", clientId)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext TwitchCheckResult.Failure("Helix streams call failed: HTTP ${response.code}")
                }
                val body = response.body?.string() ?: return@withContext TwitchCheckResult.Failure("Empty response body")
                val data = JSONObject(body).getJSONArray("data")
                if (data.length() > 0) TwitchCheckResult.Live else TwitchCheckResult.Offline
            }
        } catch (e: IOException) {
            TwitchCheckResult.Failure(e.message ?: "Network error")
        } catch (e: org.json.JSONException) {
            TwitchCheckResult.Failure(e.message ?: "Malformed response")
        }
    }

    private fun ensureValidToken(clientId: String, clientSecret: String): String {
        val now = System.currentTimeMillis()
        val token = cachedToken
        if (token != null && now < tokenExpiryMillis) {
            return token
        }
        return fetchAppAccessToken(clientId, clientSecret)
    }

    private fun fetchAppAccessToken(clientId: String, clientSecret: String): String {
        val formBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("grant_type", "client_credentials")
            .build()
        val request = Request.Builder()
            .url("https://id.twitch.tv/oauth2/token")
            .post(formBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Token request failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Empty token response")
            val json = JSONObject(body)
            val token = json.getString("access_token")
            val expiresInSeconds = json.getLong("expires_in")
            cachedToken = token
            tokenExpiryMillis = System.currentTimeMillis() + (expiresInSeconds * 1000L) - 60_000L
            return token
        }
    }
}
