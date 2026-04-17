# Java 非阻塞 IO（NIO）

---

## 一、Java IO 三代演进

```
Java BIO（1.0）        Java NIO（1.4）           Java AIO（1.7）
─────────────         ──────────────           ──────────────
阻塞 IO               非阻塞 IO                 异步 IO
一个连接一个线程        一个线程管多个连接          回调通知
OkHttp 用的            Netty 用的                用得少

read() 阻塞           Selector 轮询              OS 回调通知
线程等数据              线程问"谁好了？"            数据好了自动通知
                      ↑                         ↑
                    和 Dart 的 epoll 原理一样     和 Dart 的 Future 更像
```

---

## 二、BIO 的问题

```java
// 每个连接一个线程
ServerSocket server = new ServerSocket(8080);

while (true) {
    Socket client = server.accept();  // 阻塞等连接

    new Thread(() -> {
        InputStream in = client.getInputStream();
        byte[] buf = new byte[1024];
        in.read(buf);   // 阻塞！线程卡住等数据
        // 处理...
    }).start();
}
```

```
1000 个客户端连接 = 1000 个线程

  Thread-1:   read() ... 等待中 ... 等待中 ...
  Thread-2:   read() ... 等待中 ...
  Thread-3:   read() ... 等待中 ... 等待中 ... 等待中 ...
  ...
  Thread-1000: read() ... 等待中 ...

  问题：
  · 1000 个线程，每个线程栈 ~1MB = 1GB 内存光栈就吃掉了
  · 99% 的时间线程在 sleep（等数据），CPU 没干活
  · 线程切换开销大（上下文切换几千个周期）
```

---

## 三、NIO 三大核心组件

```
┌─ Java NIO ──────────────────────────────────────────┐
│                                                      │
│  ┌─ Channel（通道）─────────────────────────────┐   │
│  │  类比水管，数据的进出通道                       │   │
│  │  SocketChannel / ServerSocketChannel          │   │
│  │  FileChannel / DatagramChannel                │   │
│  │  双向的（可读可写），BIO 的 Stream 是单向的      │   │
│  └───────────────────────────────────────────────┘   │
│                                                      │
│  ┌─ Buffer（缓冲区）───────────────────────────┐    │
│  │  数据的容器，读写都通过 Buffer                  │    │
│  │  ByteBuffer / CharBuffer / IntBuffer          │    │
│  │  有 position、limit、capacity 三个指针管理     │    │
│  └───────────────────────────────────────────────┘   │
│                                                      │
│  ┌─ Selector（选择器）—— 核心！────────────────┐    │
│  │  一个线程监听多个 Channel 的事件               │    │
│  │  底层就是 epoll（Linux）/ kqueue（macOS）     │    │
│  │  和 Dart 的 IO 线程干的事一模一样              │    │
│  └───────────────────────────────────────────────┘   │
│                                                      │
└──────────────────────────────────────────────────────┘
```

---

## 四、Selector 工作原理

```java
// Java NIO 核心代码
Selector selector = Selector.open();

// 注册 3 个 Channel 到 Selector（非阻塞）
channel1.configureBlocking(false);
channel1.register(selector, SelectionKey.OP_READ);

channel2.configureBlocking(false);
channel2.register(selector, SelectionKey.OP_READ);

channel3.configureBlocking(false);
channel3.register(selector, SelectionKey.OP_READ);

// 一个线程轮询所有 Channel
while (true) {
    selector.select();  // 阻塞，等任意 Channel 有事件
                        // 底层就是 epoll_wait！

    Set<SelectionKey> keys = selector.selectedKeys();
    for (SelectionKey key : keys) {
        if (key.isReadable()) {
            SocketChannel ch = (SocketChannel) key.channel();
            ByteBuffer buf = ByteBuffer.allocate(1024);
            ch.read(buf);  // 非阻塞读，数据已经在缓冲区，瞬间返回
            // 处理数据...
        }
    }
    keys.clear();
}
```

### 和 Dart 底层对比

```
Java NIO                              Dart 底层 C++
─────────                            ──────────────

Selector.open()                       epoll_create()

channel.register(selector, OP_READ)   epoll_ctl(epfd, ADD, fd)

selector.select()                     epoll_wait(epfd)
  ↓ 底层调用                             ↓ 就是这个
  epoll_wait()                         epoll_wait()

selectedKeys 遍历处理                   events 遍历处理

完全一样！只是 Java 暴露了 API 给开发者
Dart 把这层藏在了 C++ 引擎里，开发者只看到 Future
```

---

## 五、BIO vs NIO 对比

```
BIO（1000 个连接）：

  ┌─ Thread 1 ─┐ ┌─ Thread 2 ─┐     ┌─ Thread 1000 ─┐
  │ read()阻塞  │ │ read()阻塞  │ ... │ read()阻塞      │
  │ 等数据...   │ │ 等数据...   │     │ 等数据...       │
  └────────────┘ └────────────┘     └────────────────┘
  
  1000 个线程，1000MB 栈内存，大量上下文切换


NIO（1000 个连接）：

  ┌─ 1 个 Selector 线程 ────────────────────────┐
  │                                              │
  │  select() 同时监听 1000 个 Channel           │
  │  有事件的处理，没事件的跳过                     │
  │                                              │
  └──────────────────────────────────────────────┘
  
  1 个线程，1MB 栈内存，无线程切换
```

| | BIO | NIO |
|---|---|---|
| 线程数 | 1 连接 = 1 线程 | **1 线程管 N 连接** |
| 1000 连接内存 | ~1GB（栈） | **~1MB（栈）** |
| 等待方式 | 线程阻塞在 read | **Selector 统一等待** |
| CPU 利用率 | 低（大量线程睡眠） | **高（只有 1 个线程）** |
| 编程复杂度 | 简单直观 | 复杂（Buffer 管理） |
| 底层实现 | 系统调用 read（阻塞） | **epoll（事件通知）** |
| 适用场景 | 连接少、简单业务 | **高并发服务器** |

---

## 六、Netty——NIO 的实用封装

原生 NIO 太难用（Buffer 操作繁琐、拆包粘包、异常处理），实际项目用 Netty：

```
原生 NIO 的痛点：

  · ByteBuffer 的 flip/clear/compact 容易搞混
  · 半包/粘包要自己处理
  · Selector 空轮询 bug（JDK 的坑）
  · 异常处理和资源释放很容易出错

Netty 解决了这些，并且性能更好
```

```java
// Netty 代码（简洁很多）
EventLoopGroup bossGroup = new NioEventLoopGroup(1);    // 1 个线程接收连接
EventLoopGroup workerGroup = new NioEventLoopGroup(4);  // 4 个线程处理 IO

ServerBootstrap b = new ServerBootstrap();
b.group(bossGroup, workerGroup)
 .channel(NioServerSocketChannel.class)
 .childHandler(new ChannelInitializer<SocketChannel>() {
     @Override
     protected void initChannel(SocketChannel ch) {
         ch.pipeline().addLast(new MyHandler());
     }
 });

b.bind(8080).sync();
```

### Netty 的线程模型（Reactor 模式）

```
  Boss EventLoop（1 个线程）
  ┌─────────────────────────┐
  │  只负责 accept 新连接     │
  │  把新连接分配给 Worker    │
  └────────────┬────────────┘
               │ 分配
    ┌──────────┼──────────┐
    ▼          ▼          ▼
  Worker 1   Worker 2   Worker 3   Worker 4
  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐
  │Selector│ │Selector│ │Selector│ │Selector│
  │管250个  │ │管250个  │ │管250个  │ │管250个  │
  │ Channel│ │ Channel│ │ Channel│ │ Channel│
  └────────┘ └────────┘ └────────┘ └────────┘

  4 个 Worker 线程管 1000 个连接
  每个 Worker 内部还是单线程 + Selector（和 Dart 一样）
```

---

## 七、和 Dart / Flutter 的对应关系

```
Netty                              Flutter Engine
─────                             ──────────────

Boss EventLoop                     Platform Runner
（接收连接/事件）                    （接收系统事件）

Worker EventLoop                   UI Runner + IO EventLoop
（处理 IO）                         （处理 IO）

NioSocketChannel                   Socket fd
（对 socket 的封装）                 （操作系统 socket）

Selector                           epoll_wait
（多路复用）                         （多路复用）

ChannelPipeline                    Dart Stream / Future
（事件处理链）                       （异步回调链）

ByteBuf                            ByteData / Uint8List
（缓冲区）                          （缓冲区）
```

```
本质上同一个模式：

  Java Netty:   少量线程 + Selector(epoll) + 事件回调
  Dart/Flutter:  IO 线程 + epoll + Future 回调
  Node.js:      1 个线程 + libuv(epoll) + callback

  都是"事件驱动、非阻塞 IO"模型
  只是暴露给开发者的 API 不同：
    Netty → Channel + Handler
    Dart  → Future + await
    Node  → callback / Promise
```

---

## 八、Android 开发者需要了解到什么程度

```
Android 日常开发：
  OkHttp（BIO）→ 线程池管理并发 → 你不需要碰 NIO
  Retrofit → 封装了 OkHttp → 更不需要

什么时候会碰到 NIO：
  · 面试问底层原理
  · 自己写长连接 / WebSocket / IM
  · 理解 Flutter 的 IO 模型
  · 服务端开发（Netty / Vert.x）
  
面试只需要理解到：
  BIO = 一个连接一个线程，read 阻塞
  NIO = Selector + Channel，一个线程管多个连接
  底层都是 epoll
  和 Dart 的 IO 模型原理一样
```

---

## 九、面试回答模板

**Q: Java NIO 和 BIO 有什么区别？**

> BIO 的 read 是阻塞调用，线程调了就卡住直到数据到达，所以一个连接需要一个线程，1000 个连接就要 1000 个线程，内存开销和线程切换开销都很大。NIO 引入了 Selector、Channel、Buffer 三个核心组件，Channel 设为非阻塞模式后注册到 Selector 上，一个线程通过 Selector.select() 就能同时监听所有 Channel 的事件，底层实现就是 Linux 的 epoll。这样几个线程就能管理几万个连接。实际项目中原生 NIO 太复杂，一般用 Netty 封装，它的 EventLoop 就是 Selector + 线程的封装。Dart/Flutter 底层的 IO 模型和 Netty 原理完全一样，都是事件驱动的非阻塞 IO。

**Q: Netty 的线程模型是怎样的？**

> Netty 用 Reactor 模式，分 Boss 和 Worker 两组 EventLoop。Boss 线程（通常 1 个）只负责 accept 新连接，然后把连接分配给 Worker 线程。Worker 线程（通常 CPU 核数个）每个内部维护一个 Selector，管理分配到的所有 Channel。每个 Worker 线程是单线程 + Selector 的模式，和 Dart 的 IO 线程原理一致。这样少量线程就能管理大量并发连接，既利用了多核 CPU，又避免了线程切换开销。
