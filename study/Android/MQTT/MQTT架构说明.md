# Android MQTT 实时数据通道架构

> IoT 设备通信场景 | Eclipse Paho | 基础收发 + 自动重连

---

## 一、整体架构

```
┌──────────────────────────────────────────────────┐
│                   Application                     │
│  ┌─────────────────────────────────────────────┐ │
│  │              MqttManager (单例)               │ │
│  │  ┌─────────┐ ┌──────────┐ ┌──────────────┐ │ │
│  │  │ connect  │ │ publish  │ │   subscribe  │ │ │
│  │  │disconnect│ │          │ │ unsubscribe  │ │ │
│  │  └─────────┘ └──────────┘ └──────────────┘ │ │
│  │  ┌─────────────────┐ ┌───────────────────┐ │ │
│  │  │  自动重连模块     │ │  网络监听模块      │ │ │
│  │  │  指数退避         │ │  网络恢复自动重连  │ │ │
│  │  └─────────────────┘ └───────────────────┘ │ │
│  │  ┌─────────────────────────────────────────┐│ │
│  │  │         Topic 订阅记录（重连恢复）        ││ │
│  │  └─────────────────────────────────────────┘│ │
│  └─────────────────────────────────────────────┘ │
│                        ↓                          │
│  ┌─────────────────────────────────────────────┐ │
│  │           Paho MqttAndroidClient             │ │
│  └─────────────────────────────────────────────┘ │
│                        ↓                          │
│  ┌─────────────────────────────────────────────┐ │
│  │           Paho MqttService (后台)            │ │
│  └─────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────┘
                        ↓
                TCP / WebSocket
                        ↓
┌──────────────────────────────────────────────────┐
│                  MQTT Broker                      │
│         (HiveMQ / Mosquitto / EMQX)              │
└──────────────────────────────────────────────────┘
                        ↓
┌──────────────────────────────────────────────────┐
│                 IoT 设备 / 其他客户端              │
└──────────────────────────────────────────────────┘
```

---

## 二、文件结构

```
mqtt/
├── MqttManager.kt         核心管理类（单例）
├── MqttConfig.kt           连接配置数据类
├── MqttCallback.kt         回调接口
└── DeviceMessage.kt        IoT 消息封装
```

| 文件 | 职责 |
|---|---|
| MqttManager | 连接管理、消息收发、自动重连、网络监听、Topic 记录 |
| MqttConfig | Broker 地址、ClientId、用户名密码、重连参数、心跳参数 |
| MqttCallback | onConnected / onDisconnected / onMessageReceived / onConnectionFailed |
| DeviceMessage | IoT 消息格式封装（JSON 序列化/反序列化） |

---

## 三、核心类设计

### 3.1 MqttConfig

```kotlin
data class MqttConfig(
    val brokerUrl: String,              // tcp://broker.example.com:1883
    val clientId: String,               // 设备唯一 ID
    val username: String? = null,       // 认证用户名
    val password: String? = null,       // 认证密码
    val autoReconnect: Boolean = true,  // 是否自动重连
    val reconnectInterval: Long = 3000, // 初始重连间隔 3 秒
    val maxReconnectInterval: Long = 60000, // 最大重连间隔 60 秒
    val keepAliveInterval: Int = 60,    // 心跳 60 秒
    val cleanSession: Boolean = true,   // 是否清除会话
    val connectionTimeout: Int = 10     // 连接超时 10 秒
)
```

### 3.2 MqttCallback

```kotlin
interface MqttCallback {
    fun onConnected()                                    // 连接成功
    fun onDisconnected()                                 // 连接断开
    fun onMessageReceived(topic: String, message: String) // 收到消息
    fun onConnectionFailed(error: String)                // 连接失败
}
```

### 3.3 DeviceMessage

```kotlin
data class DeviceMessage(
    val deviceId: String,    // 设备 ID
    val type: String,        // status / command / data
    val payload: String,     // JSON 数据
    val timestamp: Long      // 时间戳
) {
    fun toJson(): String           // 序列化
    companion object {
        fun fromJson(json: String): DeviceMessage  // 反序列化
    }
}
```

---

## 四、自动重连机制

```
连接断开
    │
    ├── 手动断开？ → 不重连
    │
    └── 非手动断开 → 检查 autoReconnect
            │
            ├── false → 不重连
            │
            └── true → 检查网络状态
                    │
                    ├── 网络可用 → 延迟重连
                    │       │
                    │       ├── 成功 → 重置间隔 → 恢复订阅
                    │       │
                    │       └── 失败 → 指数退避 → 再次重连
                    │               3s → 6s → 12s → 24s → 60s(上限)
                    │
                    └── 网络不可用 → 监听网络变化
                            │
                            └── 网络恢复 → 1 秒后重连
```

### 重连参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| autoReconnect | true | 是否自动重连 |
| reconnectInterval | 3000ms | 初始重连间隔 |
| maxReconnectInterval | 60000ms | 最大重连间隔 |
| 退避策略 | ×2 | 每次失败间隔翻倍 |

---

## 五、消息收发流程

### 5.1 发布消息

```
业务层调用 publish(topic, message)
    │
    ├── 检查连接状态
    │   └── 未连接 → 打印日志，丢弃
    │
    └── 已连接 → 创建 MqttMessage
            │
            ├── 设置 QoS（0/1/2）
            ├── 设置 retained 标记
            │
            └── client.publish()
                    │
                    ├── 成功 → 回调 onSuccess
                    └── 失败 → 回调 onFailure
```

### 5.2 订阅消息

```
业务层调用 subscribe(topic, qos)
    │
    ├── 已连接 → client.subscribe()
    │           ├── 成功 → 记录到 subscribedTopics
    │           └── 失败 → 打印日志
    │
    └── 未连接 → 记录到 subscribedTopics（连接后自动订阅）
```

### 5.3 接收消息

```
Broker 推送消息
    │
    └── Paho Client 回调 messageArrived(topic, message)
            │
            └── MqttManager 转发给 MqttCallback
                    │
                    └── 业务层处理消息
```

---

## 六、Topic 设计规范（IoT 场景）

```
设备状态：device/{deviceId}/status        设备上线/离线
设备命令：device/{deviceId}/command       向设备发送命令
设备数据：device/{deviceId}/data          设备上报数据
传感器：  sensor/{sensorId}/data          传感器数据

通配符：
  +  匹配单层  device/+/status → device/001/status
  #  匹配多层  device/# → device/001/status, device/001/data
```

---

## 七、QoS 等级

| QoS | 含义 | 场景 |
|---|---|---|
| 0 | 最多一次（可能丢失） | 传感器实时数据（允许丢失） |
| 1 | 至少一次（可能重复） | 设备状态（推荐） |
| 2 | 恰好一次（最慢） | 金融/命令（严格场景） |

---

## 八、遗嘱消息

```
设备连接时设置遗嘱（Will Message）：

Topic:   device/{clientId}/status
Payload: {"status":"offline"}
QoS:     1
Retain:  true

效果：
  设备异常断开 → Broker 自动发布遗嘱消息 → 其他订阅者收到离线通知
```

---

## 九、网络监听模块

```
ConnectivityManager.NetworkCallback
    │
    ├── onAvailable(network)
    │   └── 网络恢复 → 1 秒后触发重连
    │
    ├── onLost(network)
    │   └── 网络断开 → 等待恢复
    │
    └── isNetworkAvailable()
        └── 检查 NET_CAPABILITY_INTERNET
```

---

## 十、生命周期管理

```
Application.onCreate()
    └── MqttManager.init(context, config)

Activity.onCreate()
    └── MqttManager.connect(callback)
        └── 订阅 Topic

Activity.onDestroy()
    └── MqttManager.release()
        ├── disconnect()
        ├── 注销网络回调
        ├── 移除重连任务
        └── 置空引用
```

---

## 十一、依赖配置

### build.gradle

```gradle
dependencies {
    implementation 'org.eclipse.paho:org.eclipse.paho.android.service:1.1.1'
    implementation 'org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5'
}
```

### AndroidManifest.xml

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<application>
    <!-- Paho MQTT 服务（必须） -->
    <service android:name="org.eclipse.paho.android.service.MqttService" />
</application>
```

---

## 十二、使用示例

```kotlin
// 1. 初始化（Application）
MqttManager.getInstance().init(this, MqttConfig(
    brokerUrl = "tcp://broker.hivemq.com:1883",
    clientId = "android_${System.currentTimeMillis()}"
))

// 2. 连接（Activity）
MqttManager.getInstance().connect(object : MqttCallback {
    override fun onConnected() {
        MqttManager.getInstance().subscribe("device/+/status", 1)
    }
    override fun onMessageReceived(topic: String, message: String) {
        // 处理消息
    }
    override fun onDisconnected() { }
    override fun onConnectionFailed(error: String) { }
})

// 3. 发布命令
MqttManager.getInstance().publish(
    "device/001/command",
    """{"action":"turn_on"}"""
)

// 4. 发布 IoT 消息
val msg = DeviceMessage(
    deviceId = "android_001",
    type = "command",
    payload = """{"action":"set_temp","value":25}"""
)
MqttManager.getInstance().publishDeviceMessage("device/001/command", msg)

// 5. 释放（onDestroy）
MqttManager.getInstance().release()
```

---

## 十三、面试常问

### 1. MQTT 和 WebSocket 区别？

| | MQTT | WebSocket |
|---|---|---|
| 协议 | 二进制，轻量 | 文本/二进制 |
| 消息模式 | 发布/订阅 | 全双工 |
| 适用场景 | IoT、弱网 | 网页实时通信 |
| QoS | 支持（0/1/2） | 不支持 |
| 离线消息 | 支持（Broker 缓存） | 不支持 |

### 2. 为什么用 MQTT 而不用 WebSocket？

```
IoT 场景：
  设备资源受限 → MQTT 头部只有 2 字节，极轻量
  网络不稳定 → MQTT QoS 保证消息送达
  离线场景 → Broker 可缓存离线消息
  发布/订阅模式 → 设备和 App 解耦
```

### 3. QoS 怎么选？

```
QoS 0：传感器实时数据（丢一两条无所谓）
QoS 1：设备状态、一般命令（推荐，至少送达一次）
QoS 2：关键命令（如远程开门，恰好一次，但最慢）
```

### 4. 自动重连为什么用指数退避？

```
防止 Broker 压力过大：
  如果所有设备同时断线后都立刻重连 → Broker 被打爆
  指数退避让重连请求分散开
  3s → 6s → 12s → 24s → 60s
```

### 5. MQTT 报文结构

```
固定头部（2 字节）+ 可变头部 + 负载

固定头部：
  ┌─────────────┬──────────────┐
  │ 报文类型(4bit) │ 标志(4bit)    │
  ├─────────────┴──────────────┤
  │ 剩余长度（1~4 字节）         │
  └────────────────────────────┘

报文类型（14 种）：
  1  CONNECT      客户端连接请求
  2  CONNACK      连接确认
  3  PUBLISH      发布消息
  4  PUBACK       发布确认（QoS 1）
  5  PUBREC       发布收到（QoS 2 第一步）
  6  PUBREL       发布释放（QoS 2 第二步）
  7  PUBCOMP      发布完成（QoS 2 第三步）
  8  SUBSCRIBE    订阅请求
  9  SUBACK       订阅确认
  10 UNSUBSCRIBE  取消订阅
  11 UNSUBACK     取消订阅确认
  12 PINGREQ      心跳请求
  13 PINGRESP     心跳响应
  14 DISCONNECT   断开连接
```

### 6. QoS 1 和 QoS 2 的传输过程

```
QoS 1（至少一次）：
  Client → PUBLISH → Broker
  Client ← PUBACK  ← Broker
  如果没收到 PUBACK，Client 重发 PUBLISH
  可能重复，但不会丢失

QoS 2（恰好一次）：
  Client → PUBLISH  → Broker   （存储消息）
  Client ← PUBREC   ← Broker
  Client → PUBREL   → Broker   （可以删除消息）
  Client ← PUBCOMP  ← Broker
  三步握手，保证不重复不丢失
```

### 7. cleanSession 的作用？

```
cleanSession = true（默认）：
  每次连接都是全新会话
  Broker 不保存离线消息
  断线期间的消息全部丢失

cleanSession = false：
  Broker 保存会话状态
  断线期间的 QoS 1/2 消息会被缓存
  重连后 Broker 推送离线消息
  适合需要可靠投递的场景
```

### 8. 遗嘱消息（Will Message）是什么？

```
设备连接时设置：
  如果设备异常断开（网络中断、崩溃）
  Broker 自动发布遗嘱消息给订阅者
  其他客户端收到"设备离线"通知

类比：
  遗嘱 = "如果我突然死了，帮我告诉大家"
```

### 9. retained 消息的作用？

```
retained = true：
  Broker 保留这条消息
  新订阅者订阅时，立刻收到最后一条 retained 消息
  适合设备状态（上线/离线）

retained = false（默认）：
  消息只推送给当前在线的订阅者
  新订阅者收不到历史消息

场景：
  设备上线 → 发布 retained 消息 {"status":"online"}
  新 App 连接 → 订阅 device/+/status → 立刻收到设备状态
```

### 10. MQTT 3.1.1 vs MQTT 5.0 区别？

| 特性 | MQTT 3.1.1 | MQTT 5.0 |
|---|---|---|
| 用户属性 | 不支持 | 支持（自定义 Header） |
| 请求/响应 | 不支持 | 支持（Response Topic） |
| 消息过期 | 不支持 | 支持（Message Expiry） |
| 共享订阅 | 不支持 | 支持（负载均衡） |
| 会话恢复 | 粗粒度 | 细粒度（Session Expiry） |
| 错误码 | 简单 | 详细的 Reason Code |
| 生态 | 最成熟 | 逐步普及 |

### 11. Android 端 MQTT 如何保活？

```
1. 前台服务（Foreground Service）
   → 提升进程优先级，系统不容易杀

2. WorkManager 定期检查
   → 服务被杀后定期拉起

3. 心跳保活（Keep Alive）
   → MQTT 协议层心跳，防止连接被运营商断开
   → 建议 60 秒

4. 遗嘱消息
   → 异常断开时通知其他设备

5. 引导用户关闭电池优化
   → 小米/华为/OPPO 各厂商适配
```

### 12. MQTT Broker 集群怎么设计？

```
单 Broker 问题：
  单点故障 → 服务不可用
  性能瓶颈 → 连接数/消息量有上限

集群方案：
  ┌─────────┐  ┌─────────┐  ┌─────────┐
  │ Broker1 │──│ Broker2 │──│ Broker3 │
  └────┬────┘  └────┬────┘  └────┬────┘
       │            │            │
       └────────────┼────────────┘
                    │
              消息路由层
              （共享订阅/主题哈希）

主流方案：
  EMQX 集群：节点间 gossip 协议同步
  HiveMQ 集群：基于 Kafka 做消息分发
  Mosquitto 桥接：Bridge 模式连接多个实例
```

### 13. 如何保证 MQTT 消息顺序？

```
MQTT 协议本身：
  同一个 Topic、同一个 Client，QoS 1/2 保证顺序
  不同 Client 发到同一个 Topic，不保证全局顺序

解决方案：
  1. 设备端加序列号
     {"seq": 1, "data": "..."}
     接收端按 seq 排序

  2. 时间戳排序
     {"timestamp": 1684234567890, "data": "..."}
     接收端按 timestamp 排序

  3. 单 Topic 单生产者
     每个设备一个 Topic，保证该设备消息有序
```

### 14. 如何处理大量设备同时上线（连接风暴）？

```
问题：
  10 万设备同时上线 → Broker 被打爆

解决方案：
  1. 客户端错开重连
     随机延迟：delay = random(0, 30s)

  2. Broker 端限流
     限制单 IP 连接速率

  3. 连接代理层
     Gateway 先接收连接，再转发到 Broker

  4. 分区 Topic
     device/{region}/{deviceId}/status
     不同区域连接不同 Broker
```

### 15. 如何保证消息不丢失？

```
三个环节：

1. 发送端 → Broker
   使用 QoS 1/2
   发送失败重试 + 本地缓存

2. Broker 存储
   cleanSession = false
   retained = true（状态消息）
   Broker 持久化配置

3. Broker → 接收端
   QoS 1/2 保证送达
   消息回调确认机制
```
