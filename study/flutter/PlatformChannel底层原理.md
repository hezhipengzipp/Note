# Platform Channel 底层原理

> 不是 IPC，不走 Binder，不走 Socket，而是同一个进程内通过 C++ 层做**内存拷贝 + 线程切换**。

---

## 一、完整调用链路

以 Dart 调用原生获取电量为例：

```
Dart 代码 (UI Thread)                C++ Engine 层                  Java 代码 (Platform Thread / 主线程)
─────────────────                  ──────────────                 ────────────────────────────

channel.invokeMethod
('getBatteryLevel')
        │
    ① Dart 对象序列化
        │
        ▼
  StandardMessageCodec
  .encodeMethodCall()
        │
        │  Dart 对象 → ByteData（二进制字节流）
        │  编码规则：类型标记 + 值
        │
        ▼
  ② dart:ui 调 native 函数
  Window.sendPlatformMessage()
        │
        │  通过 Dart FFI / Native Extension
        │  调用 C++ 函数
        │
        ▼
  ③ C++ Engine 层
  PlatformChannel::Send()
        │
        │  拿到 ByteData 的指针
        │  从 UI Thread 投递任务
        │  到 Platform Thread
        │
        ▼
  ④ 线程切换
  PostTask → Platform Thread
        │
        ▼
  ⑤ JNI 调用 Java                ──→  FlutterJNI.java
                                      handlePlatformMessage()
                                            │
                                            ▼
                                      ⑥ Java 层反序列化
                                      StandardMessageCodec
                                      .decodeMethodCall()
                                      ByteBuffer → Java 对象
                                            │
                                            ▼
                                      ⑦ 调你注册的 Handler
                                      getBatteryLevel()
                                      result.success(85)
                                            │
                                      ⑧ 序列化结果
                                      85 → ByteBuffer
                                            │
                                  ←─────────┘

  ⑨ JNI 回调 C++ Engine
  ⑩ PostTask → UI Thread
  ⑪ C++ 把 ByteData 交给 Dart
  ⑫ Dart 反序列化
     ByteData → int 85
     Future 完成
```

---

## 二、核心机制拆解

### 1. 序列化：StandardMessageCodec

不是 JSON，不是 Protobuf，是 Flutter **自定义的二进制协议**，追求轻量和速度：

```
编码格式：[类型标记 1字节] + [值]

类型标记    类型         编码方式
──────    ─────       ────────
0         null         无额外字节
1         true         无额外字节
2         false        无额外字节
3         int32        4 字节小端
4         int64        8 字节小端
6         float64      8 字节 IEEE 754
7         String       长度 + UTF-8 字节
8         byte[]       长度 + 原始字节
12        List         长度 + 逐个元素递归编码
13        Map          长度 + 逐个 key-value 递归编码

示例：编码 int 85
[0x03] [0x55, 0x00, 0x00, 0x00]
 ↑类型    ↑ 85 的小端 int32 表示

示例：编码 String "getBatteryLevel"
[0x07] [0x0F] [0x67, 0x65, 0x74, 0x42, ...]
 ↑类型  ↑长度15  ↑ UTF-8 字节
```

**为什么不用 JSON？**

| | JSON | StandardMessageCodec |
|---|---|---|
| 格式 | 文本 | 二进制 |
| 编码 int 85 | `"85"` (2字节 ASCII) | `[0x03][4字节]` (5字节但无需解析) |
| 解码方式 | 字符串解析，慢 | 读类型标记 → 直接 memcpy，快 |
| 支持 byte[] | 需 Base64，体积膨胀 33% | 直接传原始字节，零膨胀 |

### 2. 跨语言调用：不是网络通信，是 JNI

```
同一个进程内，三种语言的调用链：

Dart  ──FFI/Native──→  C++  ──JNI──→  Java/Kotlin
       函数指针调用         函数指针调用

全部是函数调用，不是网络通信！
```

- **Dart → C++**：通过 `dart:ffi` 或 Dart 的 Native Extension 机制，本质是调用 C 函数指针
- **C++ → Java**：通过 JNI（`env->CallVoidMethod(...)`），Android 开发者很熟悉
- 反方向同理

### 3. 线程切换：TaskRunner

```
UI Thread                         Platform Thread (Android 主线程)
    │                                     │
    │  platform_task_runner               │
    │     ->PostTask(callback)            │
    │         │                           │
    │     把 callback 投递到 ──────────→  Looper 的 MessageQueue
    │     Platform Thread 的消息队列       │
    │                                     ▼
    │                              取出 callback 执行
    │                              （和 Handler.post() 原理一样）
    │         ←────────────────────────────│
    │     ui_task_runner                  │
    │        ->PostTask(result_callback)  │
    │                                     │
    ▼                                     │
  取出 result_callback 执行               │
  Future 完成                             │
```

**本质就是 Android 的 Handler 机制**：往目标线程的 Looper 里 post 一个任务。

### 4. ByteData 传递：指针还是拷贝？

```
Dart 堆                C++ 层               Java 堆
┌──────────┐          ┌──────────┐         ┌──────────┐
│ ByteData │──拷贝──→│ uint8_t* │──拷贝──→│ByteBuffer│
│ (Dart对象)│          │ (C++内存) │         │(Java对象) │
└──────────┘          └──────────┘         └──────────┘
```

**确实有内存拷贝**，因为三个运行时的内存管理各自独立，不能直接持有对方的指针（GC 可能随时移动对象）。这也是 Platform Channel 传大数据（如图片）性能不好的原因。

传大数据的优化方案：
- **Texture**：共享 GPU 纹理，避免 CPU 侧拷贝
- **dart:ffi**：Dart 直接调 C/C++，绕过 Java 层，减少一次拷贝
- **共享内存**：通过 mmap 让两侧映射同一块物理内存

---

## 三、一张图总结

```
┌─ Platform Channel 底层到底发生了什么 ─────────────────────────┐
│                                                             │
│  Dart                  C++ (libflutter.so)         Java     │
│                                                             │
│  Dart 对象                                        Java 对象  │
│    │                                                ▲       │
│    │ StandardMessageCodec.encode()                   │       │
│    ▼                                                │       │
│  ByteData ──────→ uint8_t* ──────→ ByteBuffer ──────┘       │
│  (Dart堆)  拷贝   (C++内存)  JNI   (Java堆)    decode()     │
│            +FFI             +拷贝                            │
│                                                             │
│  线程切换：UI Thread ──PostTask──→ Platform Thread            │
│                                                             │
│  不走 Binder、不走 Socket、不走网络                             │
│  全部是同进程内的函数调用 + 内存拷贝 + 线程切换                    │
└─────────────────────────────────────────────────────────────┘
```

---

## 四、面试回答模板

**Q: Platform Channel 底层是怎么通信的？**

> Platform Channel 并不是跨进程通信，而是同一进程内的函数调用。Dart 侧通过 StandardMessageCodec 将参数编码为自定义的二进制格式（类型标记 + 值，比 JSON 更快），然后通过 dart:ffi 调用 C++ Engine 层。Engine 通过 TaskRunner 把任务从 UI Thread 切换到 Platform Thread（即 Android 主线程），再通过 JNI 调用 Java 侧注册的 Handler。整个过程涉及两次内存拷贝（Dart 堆 → C++ → Java 堆），因为三个运行时的 GC 各自独立，不能直接共享指针。这也是传大数据时推荐用 Texture 共享或 dart:ffi 绕过 Java 层的原因。
