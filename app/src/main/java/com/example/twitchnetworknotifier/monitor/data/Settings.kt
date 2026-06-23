package com.example.twitchnetworknotifier.monitor.data

data class Settings(
    val channelName: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val monitoringEnabled: Boolean = false
)
