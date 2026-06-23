package com.example.twitchnetworknotifier.monitor.network

import com.example.twitchnetworknotifier.monitor.model.TwitchCheckResult

interface TwitchApiClient {
    suspend fun getStreamStatus(channelName: String, clientId: String, clientSecret: String): TwitchCheckResult
}
