package com.example.mqtt

data class MqttConfig(
    val brokerUrl: String,              // tcp://broker.example.com:1883
    val clientId: String,               // 设备唯一 ID
    val username: String? = null,
    val password: String? = null,
    val autoReconnect: Boolean = true,
    val reconnectInterval: Long = 3000,  // 初始重连间隔 3 秒
    val maxReconnectInterval: Long = 60000, // 最大重连间隔 60 秒
    val keepAliveInterval: Int = 60,     // 心跳 60 秒
    val cleanSession: Boolean = true,
    val connectionTimeout: Int = 10      // 连接超时 10 秒
)
