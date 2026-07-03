package com.example.twitchnetworknotifier.monitor.model

data class StatusEvent(
    val timestampMillis: Long,
    val fromState: StreamStatus,
    val toState: StreamStatus,
    // 1-based position of this notification within the current problem streak
    // (OFFLINE / CONNECTION_ISSUE). 0 for events that aren't repeat problem alerts.
    val attempt: Int = 0
)
