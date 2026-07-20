package com.example.twitchnetworknotifier.monitor

import com.example.twitchnetworknotifier.monitor.model.TwitchCheckResult
import com.example.twitchnetworknotifier.monitor.network.TwitchApiClient

class FakeTwitchApiClient : TwitchApiClient {
    var callCount: Int = 0
    /** Invoked at the start of every getStreamStatus call; lets tests mutate state mid-check. */
    var onCall: (suspend () -> Unit)? = null
    private val queuedResults = mutableListOf<TwitchCheckResult>()

    fun queueResult(result: TwitchCheckResult) {
        queuedResults.add(result)
    }

    override suspend fun getStreamStatus(
        channelName: String,
        clientId: String,
        clientSecret: String
    ): TwitchCheckResult {
        callCount++
        onCall?.invoke()
        return if (queuedResults.isNotEmpty()) queuedResults.removeAt(0) else TwitchCheckResult.Live
    }
}
