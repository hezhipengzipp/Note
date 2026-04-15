# Socket 与上下层通信机制——epoll 回调是怎么找回 Dart 代码的

---

## 一、先厘清两个概念

在 IO 模型的讨论中，容易混淆"网络连接"和"上下层通信"。它们是两件完全不同的事：

```
Socket（网络连接）：
  你的手机 ←──── 跨海大桥 ────→ 云端服务器
  TCP/UDP 连接，数据经过网卡、基站、路由器
  是向外连接互联网的纽带

Dart ↔ C++ ↔ OS（上下层通信）：
  Dart 代码 ←── FFI/内存绑定 ──→ C++ Engine ←── JNI ──→ Android
  全部在同一个进程的内存里完成
  不走网络，不走 Socket
```

---

## 二、三种通信方式各司其职

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  Dart 代码                                                   │
│    │                                                        │
│    ├─ 向外通信（网络请求）                                     │
│    │    └─ Socket（TCP/UDP）                                 │
│    │       经过网卡 → 基站 → 路由器 → 服务器                    │
│    │                                                        │
│    ├─ 向下通信（调用 C++ 引擎）                                 │
│    │    └─ FFI / Native Binding                              │
│    │       同进程内存函数调用，速度极快                           │
│    │       类比：Java 的 JNI                                  │
│    │                                                        │
│    └─ 向下通信（调用 Android 原生）                             │
│         └─ Platform Channel                                  │
│            Dart → FFI → C++ → JNI → Java/Kotlin             │
│            全在内存里，不走网络                                  │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 三、串起来：一次网络请求的完整接力赛

```
底层 OS → C++ 引擎 → Dart Event Loop 的接力过程：

① 建桥（网络层）
   Dart 代码发起 http.get()
   → C++ 引擎调用 OS API
   → 建立和服务器的 Socket 连接（生成 fd）
   → 数据经网卡发出去

② 盯梢（系统层）
   C++ 引擎的 IO 线程调用 epoll_wait
   把 Socket fd 交给 Android 内核
   "帮我盯着这个 Socket，服务器回数据了叫我"

③ 接力（上下层通信）
   服务器数据到达手机网卡 → 进入 Socket 缓冲区
   → Android 内核唤醒 epoll_wait 的 C++ 线程

④ 通知（到达 Dart）
   C++ 线程拿到数据
   → 通过内存绑定机制（FFI）
   → 往 Dart Event Loop 的队列里塞入一个事件

⑤ 执行（业务层）
   Dart 代码从 await 挂起状态恢复
   继续执行后续业务逻辑
```

**类比**：

```
Socket     = 你点外卖的那家"海底捞门店"（远端网络节点）
epoll      = 小区的"门卫大爷"（系统内核调度器），帮你看外卖小哥到没到
FFI/Event Loop = 门卫大爷用"对讲机"（内存消息队列）呼叫楼上的你
```

---

## 四、核心问题：回调是怎么找回来的

当 epoll 通知"数据到了"，底层怎么知道该通知哪个 Dart Future？

答案：**唯一标识符（Handle / FD）贯穿整个链条**。

### 4.1 注册时：埋下"身份牌"

```
Dart 层（发起请求的那一刻）：

  http.get(url)
      │
      ▼
  创建 Completer 对象
  ┌──────────────────────────────────────┐
  │  completer = Completer<Response>()   │
  │  future = completer.future           │ ← 你 await 的就是这个
  └──────────────────────────────────────┘
      │
      ▼
  分配唯一标识（fd / handle）
      │
      ▼
  记录映射表
  ┌──────────────────────────────────────────────────┐
  │  Map<Handle, Callback>                            │
  │                                                  │
  │  handle: 10  →  () { completer.complete(data); } │
  │  handle: 11  →  () { completer2.complete(data); }│
  │  handle: 12  →  () { completer3.complete(data); }│
  │  ...                                             │
  └──────────────────────────────────────────────────┘
  
  Handle 和 Callback 死死绑定在一起
```

### 4.2 等待时：C++ 层的"中转站"

```
Dart 线程不管了，C++ 引擎接手：

C++ 层
┌──────────────────────────────────────┐
│                                      │
│  把 fd: 10 注册到 epoll              │
│  保留指向 Dart 端回调的引用            │
│                                      │
│  epoll 监听列表：                     │
│  ┌────────────────────────────────┐  │
│  │  fd: 10 → Dart callback 指针   │  │
│  │  fd: 11 → Dart callback 指针   │  │
│  │  fd: 12 → Dart callback 指针   │  │
│  └────────────────────────────────┘  │
│                                      │
│  epoll_wait(...)  // 阻塞等待        │
│                                      │
└──────────────────────────────────────┘
```

### 4.3 到达时：通过 ID 找回回调

```
服务器返回数据，内核唤醒 epoll：

步骤 ①  内核告知 FD
─────────────────────────────────────
  OS 内核："fd: 10 这个通道有数据了！"

步骤 ②  C++ 查找映射
─────────────────────────────────────
  C++ IO 线程拿到 fd: 10
  在映射表中搜索："谁注册了 fd: 10？"
  找到 → 对应 Dart 层某个特定回调

步骤 ③  封包进入队列
─────────────────────────────────────
  C++ 把数据和对应的闭包函数指针
  封装成一个 Message（事件）

步骤 ④  塞入 Event Loop
─────────────────────────────────────
  Message 被塞进 Dart 线程的 Event Queue

步骤 ⑤  Dart 执行回调
─────────────────────────────────────
  Event Loop 取出这个 Message
      │
      ▼
  执行闭包 → completer.complete(data)
      │
      ▼
  Future 状态变为 completed
  await 后面的代码放入 Microtask Queue
      │
      ▼
  Event Loop 清空 Microtask
  你的业务代码从 await 之后继续跑
```

完整流程一张图：

```
Dart 层               C++ 层                 OS 内核              服务器
────────             ────────               ────────             ──────

http.get()
  │
  ├ 创建 Completer
  ├ 分配 handle: 10
  ├ 映射表记录：
  │  10 → callback
  │
  └─ FFI 调用 ──→  注册 fd:10 到 epoll
                        │
     await 挂起          │
     去干别的             epoll_wait(fd:10)
                        │                    监听 socket
                        │                       │
                        │                    数据到达！
                        │                       │
                    epoll 被唤醒 ←───────────────┘
                    "fd:10 有数据"
                        │
                    查映射表：fd:10 → callback
                    封装 Message(data + callback)
                        │
  Event Queue ←─── PostTask ──┘
     │
  取出 Message
  执行 callback
  completer.complete(data)
     │
  Microtask Queue ← await 后续代码
     │
  执行：你的业务代码继续
```

---

## 五、类比：医院取号

```
注册回调 = 挂号
  你去挂号（发请求），护士给你流水号 007（Handle）
  告诉你去候诊区盯着大屏幕（Future）

异步等待 = 候诊
  护士把你的档案（回调代码）放在桌上
  你（Dart 线程）可以刷手机看报纸，不用站窗口死等

epoll 触发 = 医生呼叫
  医生（服务器）看完上一个，按了呼叫器

寻找关联 = 大屏幕叫号
  大屏幕显示"请 007 号到 1 号诊室"
  007 就是那个唯一 ID

通知到位 = 继续看病
  你看到 007，知道轮到自己了（找回了回调）
  起身走进诊室继续看病（继续执行后续代码）
```

---

## 六、面试回答模板

**Q: Dart 异步 IO 底层的 Socket 是什么？和 Platform Channel 是一回事吗？**

> 不是一回事。Socket 是操作系统提供的网络通信端点，用于 App 和外部服务器之间的 TCP/UDP 连接，数据真正经过网卡发到互联网。而 Dart 和 C++ 引擎之间的通信靠 FFI 内存绑定，Dart 和 Android 原生之间靠 Platform Channel，这些全在同一进程内存里完成，不走网络。

**Q: epoll 通知数据到了，怎么找回对应的 Dart 回调？**

> 靠唯一标识符贯穿全链。发起请求时 Dart 创建 Completer 并分配一个 handle，把 handle 和回调闭包记录在映射表里。C++ 层把对应的 fd 注册到 epoll 监听。数据到达时内核告知 fd 编号，C++ 通过 fd 在映射表中找到对应的 Dart 回调，把数据和回调封装成 Message 投递到 Event Queue。Event Loop 取出执行 completer.complete()，触发 await 恢复。整个过程通过 handle/fd 这个唯一 ID 精准匹配，不会搞混。
