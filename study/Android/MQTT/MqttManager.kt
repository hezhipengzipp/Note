package com.example.mqtt

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*

class MqttManager private constructor() {

    companion object {
        private const val TAG = "MqttManager"

        @Volatile
        private var INSTANCE: MqttManager? = null

        fun getInstance(): MqttManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MqttManager().also { INSTANCE = it }
            }
        }
    }

    private var client: MqttAndroidClient? = null
    private var config: MqttConfig? = null
    private var callback: MqttCallback? = null
    private var context: Context? = null

    // 自动重连
    private var reconnectInterval: Long = 0
    private val reconnectRunnable = Runnable { reconnect() }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isManualDisconnect = false

    // 记录已订阅的 Topic（重连后恢复）
    private val subscribedTopics = mutableMapOf<String, Int>() // topic -> qos

    // 网络监听
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * 初始化
     */
    fun init(context: Context, config: MqttConfig) {
        this.context = context.applicationContext
        this.config = config
        this.reconnectInterval = config.reconnectInterval

        client = MqttAndroidClient(context.applicationContext, config.brokerUrl, config.clientId).apply {
            setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String) {
                    Log.i(TAG, "连接成功: reconnect=$reconnect, server=$serverURI")
                    reconnectInterval = config.reconnectInterval // 重置重连间隔
                    isManualDisconnect = false

                    // 重连后恢复订阅
                    if (reconnect) {
                        resubscribeTopics()
                    }

                    callback?.onConnected()
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "连接断开: ${cause?.message}")
                    callback?.onDisconnected()

                    if (!isManualDisconnect && config.autoReconnect) {
                        scheduleReconnect()
                    }
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    val payload = String(message.payload)
                    Log.d(TAG, "收到消息: topic=$topic, payload=$payload")
                    callback?.onMessageReceived(topic, payload)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    Log.d(TAG, "消息投递完成: msgId=${token?.messageId}")
                }
            })
        }

        registerNetworkCallback()
    }

    /**
     * 连接
     */
    fun connect(cb: MqttCallback) {
        val ctx = context ?: throw IllegalStateException("请先调用 init()")
        val cfg = config ?: throw IllegalStateException("请先调用 init()")

        callback = cb
        isManualDisconnect = false

        if (client?.isConnected == true) {
            Log.i(TAG, "已连接，跳过")
            cb.onConnected()
            return
        }

        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = false // 自己管理重连
            isCleanSession = cfg.cleanSession
            connectionTimeout = cfg.connectionTimeout
            keepAliveInterval = cfg.keepAliveInterval

            cfg.username?.let { userName = it }
            cfg.password?.let { password = it.toCharArray() }

            // 遗嘱消息（设备离线时通知）
            setWill(
                "device/${cfg.clientId}/status",
                """{"status":"offline"}""".toByteArray(),
                1,
                true
            )
        }

        try {
            client?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "连接成功")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "连接失败: ${exception?.message}")
                    cb.onConnectionFailed(exception?.message ?: "连接失败")

                    if (cfg.autoReconnect) {
                        scheduleReconnect()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "连接异常: ${e.message}")
            cb.onConnectionFailed(e.message ?: "连接异常")
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        isManualDisconnect = true
        handler.removeCallbacks(reconnectRunnable)

        try {
            client?.disconnect(null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "断开连接成功")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "断开连接失败: ${exception?.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "断开异常: ${e.message}")
        }
    }

    /**
     * 发布消息
     */
    fun publish(topic: String, message: String, qos: Int = 1, retained: Boolean = false) {
        if (client?.isConnected != true) {
            Log.w(TAG, "未连接，无法发布")
            return
        }

        try {
            val mqttMessage = MqttMessage(message.toByteArray()).apply {
                this.qos = qos
                this.isRetained = retained
            }
            client?.publish(topic, mqttMessage, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "发布成功: topic=$topic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "发布失败: topic=$topic, error=${exception?.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "发布异常: ${e.message}")
        }
    }

    /**
     * 发布 IoT 设备消息
     */
    fun publishDeviceMessage(topic: String, message: DeviceMessage, qos: Int = 1) {
        publish(topic, message.toJson(), qos)
    }

    /**
     * 订阅 Topic
     */
    fun subscribe(topic: String, qos: Int = 1) {
        if (client?.isConnected != true) {
            Log.w(TAG, "未连接，无法订阅: $topic")
            // 记录，连接后恢复
            subscribedTopics[topic] = qos
            return
        }

        try {
            client?.subscribe(topic, qos, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "订阅成功: topic=$topic, qos=$qos")
                    subscribedTopics[topic] = qos
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "订阅失败: topic=$topic, error=${exception?.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "订阅异常: ${e.message}")
        }
    }

    /**
     * 取消订阅
     */
    fun unsubscribe(topic: String) {
        try {
            client?.unsubscribe(topic, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "取消订阅成功: topic=$topic")
                    subscribedTopics.remove(topic)
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "取消订阅失败: topic=$topic, error=${exception?.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "取消订阅异常: ${e.message}")
        }
    }

    /**
     * 是否已连接
     */
    fun isConnected(): Boolean = client?.isConnected == true

    // ==================== 自动重连 ====================

    private fun scheduleReconnect() {
        Log.i(TAG, "将在 ${reconnectInterval}ms 后重连")
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, reconnectInterval)

        // 指数退避
        val cfg = config ?: return
        reconnectInterval = (reconnectInterval * 2).coerceAtMost(cfg.maxReconnectInterval)
    }

    private fun reconnect() {
        if (client?.isConnected == true) return
        if (isManualDisconnect) return

        if (!isNetworkAvailable()) {
            Log.w(TAG, "网络不可用，等待网络恢复")
            return
        }

        Log.i(TAG, "开始重连...")
        val cfg = config ?: return

        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = false
            isCleanSession = cfg.cleanSession
            connectionTimeout = cfg.connectionTimeout
            keepAliveInterval = cfg.keepAliveInterval
            cfg.username?.let { userName = it }
            cfg.password?.let { password = it.toCharArray() }
        }

        try {
            client?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "重连成功")
                    reconnectInterval = cfg.reconnectInterval
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "重连失败: ${exception?.message}")
                    scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "重连异常: ${e.message}")
            scheduleReconnect()
        }
    }

    private fun resubscribeTopics() {
        subscribedTopics.forEach { (topic, qos) ->
            try {
                client?.subscribe(topic, qos)
                Log.i(TAG, "恢复订阅: topic=$topic, qos=$qos")
            } catch (e: Exception) {
                Log.e(TAG, "恢复订阅失败: topic=$topic, error=${e.message}")
            }
        }
    }

    // ==================== 网络监听 ====================

    private fun registerNetworkCallback() {
        connectivityManager = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "网络恢复")
                if (client?.isConnected != true && !isManualDisconnect) {
                    handler.removeCallbacks(reconnectRunnable)
                    handler.postDelayed(reconnectRunnable, 1000) // 网络恢复后 1 秒重连
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "网络断开")
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "注册网络回调失败: ${e.message}")
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = connectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * 释放资源
     */
    fun release() {
        disconnect()
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        handler.removeCallbacks(reconnectRunnable)
        client = null
        callback = null
        context = null
        subscribedTopics.clear()
        INSTANCE = null
    }
}
