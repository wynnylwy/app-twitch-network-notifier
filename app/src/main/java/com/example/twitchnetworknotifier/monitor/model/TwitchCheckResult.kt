package com.example.twitchnetworknotifier.monitor.model

sealed interface TwitchCheckResult {
    data object Live : TwitchCheckResult
    data object Offline : TwitchCheckResult
    data class Failure(val message: String) : TwitchCheckResult
}
