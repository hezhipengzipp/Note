package com.example.mqtt

import org.json.JSONObject

data class DeviceMessage(
    val deviceId: String,
    val type: String,       // status / command / data
    val payload: String,    // JSON 数据
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("deviceId", deviceId)
            put("type", type)
            put("payload", payload)
            put("timestamp", timestamp)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): DeviceMessage {
            val obj = JSONObject(json)
            return DeviceMessage(
                deviceId = obj.getString("deviceId"),
                type = obj.getString("type"),
                payload = obj.getString("payload"),
                timestamp = obj.getLong("timestamp")
            )
        }
    }
}
