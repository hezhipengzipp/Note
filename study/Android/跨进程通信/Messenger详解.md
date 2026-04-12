# Messenger 详解

## 一、是什么

Messenger 是基于 **Binder + Handler** 的轻量级 IPC（跨进程通信）方案。本质上是对 AIDL 的简化封装，通过 Message 传递数据，**串行处理**，不需要处理多线程。

## 二、和其他 IPC 方式对比

```
┌─────────────────────────────────────────────────────────┐
│  复杂度低                                     复杂度高    │
│                                                         │
│  Intent    Messenger    ContentProvider    AIDL          │
│  │          │              │                │           │
│  单向传递    双向通信        结构化数据共享     自定义接口    │
│  少量数据    串行Message    增删改查          并发+回调     │
└─────────────────────────────────────────────────────────┘
```

| | Messenger | AIDL |
|--|-----------|------|
| 复杂度 | 简单，基于 Handler | 复杂，需要定义 .aidl 文件 |
| 线程模型 | 串行（Handler 队列） | 并发（Binder 线程池） |
| 数据类型 | 只能传 Message（Bundle） | 自定义接口，支持各种类型 |
| 适用场景 | 简单的跨进程消息传递 | 高并发、复杂接口的 IPC |

## 三、原理

```
客户端 (App进程)                               服务端 (另一个进程)
┌───────────────────┐                       ┌───────────────────┐
│                   │                       │                   │
│  客户端 Messenger  │   Message (Binder)    │  服务端 Handler    │
│  (持有服务端Binder) │ ────────────────────→ │  处理消息          │
│                   │                       │                   │
│  客户端 Handler   │   Message (Binder)     │  服务端 Messenger  │
│  处理回复         │ ←──────────────────── │  (持有客户端Binder) │
│                   │                       │                   │
└───────────────────┘                       └───────────────────┘

Messenger 内部持有一个 IMessenger (AIDL 接口)
本质就是 AIDL，只不过 Google 帮你封装好了
```

## 四、完整使用示例

### 1. 服务端（远程进程中的 Service）

```xml
<!-- AndroidManifest.xml 中声明为独立进程 -->
<service android:name=".RemoteService" android:process=":remote" />
```

```kotlin
class RemoteService : Service() {

    // 服务端 Handler，处理客户端发来的消息
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_SAY_HELLO -> {
                    val name = msg.data.getString("name")
                    Log.d("Server", "收到客户端消息: $name")

                    // 回复客户端（msg.replyTo 就是客户端的 Messenger）
                    msg.replyTo?.let { clientMessenger ->
                        val reply = Message.obtain().apply {
                            what = MSG_REPLY
                            data = Bundle().apply {
                                putString("result", "你好, $name! 我是服务端")
                            }
                        }
                        clientMessenger.send(reply)
                    }
                }
            }
        }
    }

    // 服务端 Messenger（基于上面的 Handler 创建）
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent): IBinder {
        // 返回 Messenger 内部的 Binder 给客户端
        return messenger.binder
    }

    companion object {
        const val MSG_SAY_HELLO = 1
        const val MSG_REPLY = 2
    }
}
```

### 2. 客户端（绑定 Service 并通信）

```kotlin
class ClientActivity : AppCompatActivity() {

    private var serverMessenger: Messenger? = null  // 服务端的 Messenger
    private var isBound = false

    // 客户端 Handler，处理服务端的回复
    private val clientHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                RemoteService.MSG_REPLY -> {
                    val result = msg.data.getString("result")
                    Log.d("Client", "收到服务端回复: $result")
                    textView.text = result
                }
            }
        }
    }

    // 客户端 Messenger（用于接收服务端的回复）
    private val clientMessenger = Messenger(clientHandler)

    // ServiceConnection
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            // 用服务端返回的 Binder 创建 Messenger
            serverMessenger = Messenger(service)
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serverMessenger = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)

        // 绑定远程 Service
        val intent = Intent(this, RemoteService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        // 点击按钮发送消息
        btnSend.setOnClickListener {
            sendMessage()
        }
    }

    private fun sendMessage() {
        if (!isBound) return

        val msg = Message.obtain().apply {
            what = RemoteService.MSG_SAY_HELLO
            data = Bundle().apply {
                putString("name", "张三")
            }
            replyTo = clientMessenger  // 把客户端 Messenger 传过去，用于回复
        }
        serverMessenger?.send(msg)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
```

## 五、通信流程

```
客户端                                   服务端 (:remote 进程)
  │                                        │
  │  1. bindService()                      │
  │  ────────────────────────────────────→ │
  │                                        │ onBind() 返回 Binder
  │  2. onServiceConnected(binder)         │
  │  ←──────────────────────────────────── │
  │     serverMessenger = Messenger(binder)│
  │                                        │
  │  3. 构造 Message                       │
  │     msg.what = MSG_SAY_HELLO           │
  │     msg.data = Bundle("name"="张三")    │
  │     msg.replyTo = clientMessenger      │
  │                                        │
  │  4. serverMessenger.send(msg)          │
  │  ────────────────────────────────────→ │
  │                  Binder 跨进程          │  5. Handler.handleMessage(msg)
  │                                        │     处理消息，拿到 replyTo
  │                                        │
  │  7. clientHandler.handleMessage(reply) │  6. replyTo.send(reply)
  │  ←──────────────────────────────────── │
  │     显示 "你好, 张三! 我是服务端"         │
```

## 六、Message 能传什么数据

```kotlin
val msg = Message.obtain().apply {
    what = 1                    // int 标识符
    arg1 = 100                  // int 参数1
    arg2 = 200                  // int 参数2
    obj = myParcelable          // Parcelable 对象（跨进程必须 Parcelable）

    data = Bundle().apply {     // Bundle 可以传更丰富的数据
        putString("key", "value")
        putInt("count", 42)
        putParcelable("user", user)
        // 不能传大数据（Binder 缓冲区限制约 1MB）
    }

    replyTo = clientMessenger   // 用于双向通信
}
```

## 七、实际使用场景

```
1. 多进程 App 内部通信
   主进程 ←→ 推送进程
   主进程 ←→ 播放器进程

2. App 之间的简单通信
   App A 绑定 App B 的 Service，发送 Message

3. 不需要并发的轻量 IPC
   配置同步、状态查询、简单的命令传递
```

---

## 八、面试高频问题

### Q1: Messenger 的本质是什么？

- Messenger 内部持有 `IMessenger`（一个 AIDL 接口）
- `Messenger.send(msg)` 实际调用的是 `IMessenger.send(msg)`，走的是 Binder 跨进程通信
- 服务端的 Messenger 基于 Handler 创建，消息最终由 Handler 在主线程串行处理

### Q2: Messenger 和 AIDL 怎么选？

| 场景 | 选择 |
|------|------|
| 简单消息传递，不需要并发 | Messenger |
| 需要自定义接口方法（如 `getUser(id)` 返回结果） | AIDL |
| 高并发请求 | AIDL（Binder 线程池处理） |
| 需要回调监听 | AIDL（注册 Listener） |

### Q3: Messenger 为什么是串行的？

- 服务端的 Messenger 基于 Handler 创建
- 所有跨进程消息最终都 post 到 Handler 的 MessageQueue 中
- MessageQueue 是串行处理的，一次只处理一个 Message

### Q4: Messenger 能否实现双向通信？

- 可以。客户端发送 Message 时把自己的 Messenger 放到 `msg.replyTo` 中
- 服务端从 `msg.replyTo` 拿到客户端的 Messenger，就能回发消息
