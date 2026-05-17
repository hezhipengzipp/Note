package com.example.mqtt

interface MqttCallback {
    fun onConnected()
    fun onDisconnected()
    fun onMessageReceived(topic: String, message: String)
    fun onConnectionFailed(error: String)
}
