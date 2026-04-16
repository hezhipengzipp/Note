# 阻塞 IO vs 非阻塞 IO——为什么 Dart 一个线程就能并发下载

---

## 一、两种 IO 模型的根本区别

```
Java（阻塞 IO）：
─────────────────────────────────────
  Thread 1:  read(socket1)  ← 卡在这里！线程被阻塞
                               线程啥都干不了
                               只能等数据来
                               ↓
                            数据到了，返回

  read() 是阻塞调用
  调用后线程就"定住了"，直到数据来了才能动
  一个线程同一时间只能等一个 socket
  所以 3 个下载 = 3 个线程


Dart（非阻塞 IO）：
─────────────────────────────────────
  IO Thread:  epoll_register(socket1)  ← 注册完立刻返回！不阻塞
              epoll_register(socket2)  ← 注册完立刻返回！
              epoll_register(socket3)  ← 注册完立刻返回！
              epoll_wait()             ← 等通知（一次等所有）
                               ↓
                            fd2 有数据了，处理
                            继续 epoll_wait

  注册是非阻塞的，一瞬间注册完 3 个
  然后一次 epoll_wait 同时等所有 socket
  所以 1 个线程就够了
```

---

## 二、用代码对比

### Java：3 个下载 = 3 个线程

```java
ExecutorService pool = Executors.newFixedThreadPool(3);

pool.submit(() -> {
    // Thread-1
    InputStream in1 = url1.openStream();  // 阻塞！线程卡住
    in1.read(buffer);                      // 阻塞！线程卡住
    // 数据来了才能往下走
});

pool.submit(() -> {
    // Thread-2
    InputStream in2 = url2.openStream();  // 阻塞！线程卡住
    in2.read(buffer);                      // 阻塞！线程卡住
});

pool.submit(() -> {
    // Thread-3
    InputStream in3 = url3.openStream();  // 阻塞！线程卡住
    in3.read(buffer);                      // 阻塞！线程卡住
});

// 为什么需要 3 个线程？
// 因为 read() 会把线程卡住
// 线程卡住了就不能去做第二件事
// 所以每个下载占一个线程
```

### Dart：3 个下载 = 1 个 IO 线程

```cpp
// Dart 底层 C++ 做的事（伪代码）：
epoll_ctl(epoll_fd, ADD, socket1);  // 注册，立刻返回
epoll_ctl(epoll_fd, ADD, socket2);  // 注册，立刻返回
epoll_ctl(epoll_fd, ADD, socket3);  // 注册，立刻返回

while (true) {
    // 一次调用同时等所有 socket
    events = epoll_wait(epoll_fd);  // 哪个好了就返回哪个
    
    for (event in events) {
        read(event.fd, buffer);  // 数据已经在缓冲区了
                                 // read 瞬间返回（微秒级）
        postTaskToDart(buffer);
    }
}

// 为什么 1 个线程就够？
// 因为 epoll_wait 可以同时等 N 个 socket
// 不需要每个 socket 占一个线程
```

---

## 三、关键区别：read 的行为不同

```
Java 的 read()（阻塞）：

  线程调 read(socket)
      │
      ▼
  数据还没到？→ 线程睡眠，让出 CPU
                 ↓
              等待...（线程被卡住，啥都干不了）
                 ↓
              数据到了 → 内核唤醒线程
                 ↓
              read 返回数据
  
  线程在等待期间完全不能做其他事
  → 3 个 socket 就需要 3 个线程


Dart 底层的 epoll_wait（非阻塞）：

  线程调 epoll_wait(fd1, fd2, fd3)
      │
      ▼
  任意一个有数据？→ 线程睡眠，让出 CPU
                     ↓
                  等待...
                     ↓
                  fd2 数据到了 → 内核唤醒线程
                     ↓
                  处理 fd2
                  回到 epoll_wait 继续等 fd1、fd3
  
  一次等待就能覆盖所有 socket
  → 1 个线程管 N 个 socket
```

---

## 四、真正下载数据的是网卡和内核

IO 线程根本不"下载"，它只是个通知中转站：

```
硬件层（真正的并行，物理层面同时进行）：

  网卡
  ┌──────────────────────────────────────────┐
  │  TCP 连接 1 ←──── 光纤/WiFi ────→ 服务器 A │  ← 电信号在传输
  │  TCP 连接 2 ←──── 光纤/WiFi ────→ 服务器 B │  ← 同时！
  │  TCP 连接 3 ←──── 光纤/WiFi ────→ 服务器 C │  ← 同时！
  │                                          │
  │  网卡硬件天然支持同时收发多个连接的数据包     │
  │  这是物理层面的并行，和线程无关              │
  └──────────────────────────────────────────┘
        │  │  │
        ▼  ▼  ▼  数据包到达网卡

  内核协议栈
  ┌──────────────────────────────────────────┐
  │  收到数据包 → 按 TCP 连接分拣              │
  │  放入对应 socket 的接收缓冲区              │
  │                                          │
  │  fd1 的缓冲区: [数据包][数据包]            │
  │  fd2 的缓冲区: [数据包][数据包][数据包]     │
  │  fd3 的缓冲区: [数据包]                    │
  │                                          │
  │  通知 epoll：fd2 可读了！                  │
  └──────────────────────────────────────────┘
        │
        ▼

  IO 线程（只是最后取一下货）
  ┌──────────────────────────────────────────┐
  │  epoll_wait 被唤醒                        │
  │  read(fd2, buffer) → 从内核缓冲区拷贝数据  │ ← 耗时极短（微秒级）
  │  PostTask 回 Dart                         │
  │  继续 epoll_wait                          │
  └──────────────────────────────────────────┘
```

```
类比：快递驿站

  快递公司（网卡 + 内核）：
    北京 → 你的城市   ← 同时在路上
    上海 → 你的城市   ← 同时在路上
    广州 → 你的城市   ← 同时在路上
    三个快递真正并行运输，互不影响

  驿站（IO 线程）：
    快递到了 → 驿站收货 → 通知你取件
    驿站只有 1 个人
    但不影响 3 个快递同时运输
    因为驿站不负责运输，只负责"到了通知你"

  你（Dart Event Loop）：
    收到通知 → 去取快递 → 处理
```

---

## 五、Java 也有非阻塞模型

Java 不全是"一个下载一个线程"：

```
  Java BIO（阻塞 IO）：
    OkHttp / HttpURLConnection 用的
    一个连接一个线程
    Android 开发者最熟悉的方式

  Java NIO（非阻塞 IO）：
    Netty / Vert.x 用的
    底层也是 epoll！和 Dart 一模一样
    一个线程管几千个连接
```

```
              Java 世界                    Dart 世界
            ────────────                  ──────────

  OkHttp    BIO（阻塞）                   Dart http
  ↓         一个连接一个线程                ↓
  线程池     ThreadPool(64)               NIO（非阻塞）
                                          一个线程 + epoll

  Netty     NIO（非阻塞）
  ↓         一个线程 + epoll               ← 和 Dart 底层一样！
  EventLoop 几个线程管几万连接
```

---

## 六、一张图总结

```
Java OkHttp（阻塞 IO）：

  download(url1)  →  Thread-1: read() 阻塞等数据...
  download(url2)  →  Thread-2: read() 阻塞等数据...
  download(url3)  →  Thread-3: read() 阻塞等数据...

  3 个线程各等各的，因为 read 会卡住线程


Dart / Netty（非阻塞 IO）：

  download(url1)  →  ┐
  download(url2)  →  ├→ 1 个线程: epoll_wait(fd1, fd2, fd3)
  download(url3)  →  ┘   同时等所有，谁好了处理谁

  1 个线程等所有，因为 epoll_wait 不绑定单个 socket


两者的网络传输速度完全一样！
区别只是"等数据"的方式不同：
  Java BIO: 每个连接派一个人守着（多线程阻塞等）
  Dart/NIO: 一个人盯着所有连接的通知屏幕（单线程事件通知）
```

```
                   真正下载数据的时间分配：

  ┌─────────────────────────────────────────────────┐
  │████████████████████████████████████████          │ 网卡传输 (99.9%)
  │█                                                │ 内核协议栈处理 (0.09%)
  │                                                 │ IO 线程 read (0.01%)
  └─────────────────────────────────────────────────┘

  IO 线程参与的部分不到 0.01%
  所以一个线程够了
```

---

## 七、面试回答模板

**Q: Dart 单线程怎么实现网络并发？和 Java 多线程有什么区别？**

> Java 传统的 BIO 模型中 read() 是阻塞调用，线程调了 read 就卡住直到数据到达，所以每个连接需要一个线程。Dart 底层用的是非阻塞 IO 模型（epoll），IO 线程把所有 socket fd 注册到 epoll 后一次 epoll_wait 就能同时监听所有连接，哪个有数据就处理哪个，一个线程就能管几千个并发连接。真正的数据传输发生在网卡和内核协议栈层面，是硬件级别的并行，IO 线程只负责在数据到达后通知 Dart。这和 Java NIO / Netty 的原理完全一样，Netty 的 EventLoop 也是少量线程加 epoll 管理大量连接。两种模型的网络传输速度一样，区别只是等待方式不同——多线程阻塞等 vs 单线程事件通知。
