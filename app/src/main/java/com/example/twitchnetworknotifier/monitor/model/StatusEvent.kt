package com.example.twitchnetworknotifier.monitor.model

data class StatusEvent(
    val timestampMillis: Long,
    val fromState: StreamStatus,
    val toState: StreamStatus
)
