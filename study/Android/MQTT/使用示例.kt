package com.example.mqtt

import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

// ==================== Application 初始化 ====================

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        MqttManager.getInstance().init(this, MqttConfig(
            brokerUrl = "tcp://broker.hivemq.com:1883",  // 公共测试 Broker
            clientId = "android_${System.currentTimeMillis()}",
            autoReconnect = true,
            reconnectInterval = 3000,
            maxReconnectInterval = 60000,
            keepAliveInterval = 60
        ))
    }
}

// ==================== Activity 使用 ====================

class MqttDemoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 连接
        MqttManager.getInstance().connect(object : MqttCallback {
            override fun onConnected() {
                Log.i("Demo", "MQTT 已连接")

                // 订阅设备状态
                MqttManager.getInstance().subscribe("device/+/status", 1)

                // 订阅传感器数据
                MqttManager.getInstance().subscribe("sensor/+/data", 1)
            }

            override fun onDisconnected() {
                Log.w("Demo", "MQTT 已断开，自动重连中...")
            }

            override fun onMessageReceived(topic: String, message: String) {
                Log.i("Demo", "收到消息: topic=$topic, msg=$message")

                when {
                    topic.contains("/status") -> {
                        // 处理设备状态
                        val deviceMsg = DeviceMessage.fromJson(message)
                        handleDeviceStatus(deviceMsg)
                    }
                    topic.contains("/data") -> {
                        // 处理传感器数据
                        handleSensorData(topic, message)
                    }
                }
            }

            override fun onConnectionFailed(error: String) {
                Log.e("Demo", "连接失败: $error")
            }
        })

        // 发布命令示例
        findViewById<android.view.View>(R.id.btn_turn_on).setOnClickListener {
            MqttManager.getInstance().publish(
                "device/001/command",
                """{"action":"turn_on","param":{}}"""
            )
        }

        findViewById<android.view.View>(R.id.btn_turn_off).setOnClickListener {
            MqttManager.getInstance().publish(
                "device/001/command",
                """{"action":"turn_off","param":{}}"""
            )
        }

        // 发布设备消息
        findViewById<android.view.View>(R.id.btn_send).setOnClickListener {
            val msg = DeviceMessage(
                deviceId = "android_001",
                type = "command",
                payload = """{"action":"set_temperature","value":25}"""
            )
            MqttManager.getInstance().publishDeviceMessage("device/001/command", msg)
        }
    }

    private fun handleDeviceStatus(message: DeviceMessage) {
        // 更新 UI 显示设备在线/离线
        runOnUiThread {
            // updateDeviceUI(message)
        }
    }

    private fun handleSensorData(topic: String, data: String) {
        // 处理传感器数据
        runOnUiThread {
            // updateSensorUI(topic, data)
        }
    }

    override fun onDestroy() {
        MqttManager.getInstance().release()
        super.onDestroy()
    }
}
