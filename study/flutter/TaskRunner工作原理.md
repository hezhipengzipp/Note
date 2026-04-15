# Flutter TaskRunner 工作原理

---

## 一、用 Android 类比建立直觉

```
Android                                Flutter Engine
──────                                ──────────────
主线程 + Looper + MessageQueue    ←→   Platform TaskRunner + MessageLoop
RenderThread                     ←→   Raster TaskRunner
AsyncTask / 线程池                ←→   IO TaskRunner
（没有对应）                       ←→   UI TaskRunner（Dart 代码运行在这里）
```

**本质**：每个 TaskRunner 就是一个**线程 + 消息循环**，和 Android 的 Handler/Looper 机制几乎一样。

---

## 二、四个 TaskRunner 全景图

```
┌─ Flutter Engine 内部（同一个 App 进程）─────────────────────────────┐
│                                                                   │
│  ┌─ Platform Runner ──────────────────────────────────────────┐   │
│  │  就是 Android 主线程（UI Thread）                             │   │
│  │  职责：                                                     │   │
│  │  · 接收系统事件（触摸、键盘、生命周期）                         │   │
│  │  · Platform Channel 的原生侧回调                             │   │
│  │  · 插件（Plugin）的原生代码执行                                │   │
│  │  约束：不能做耗时操作，否则 ANR                                │   │
│  └────────────────────────────────────────────────────────────┘   │
│                                                                   │
│  ┌─ UI Runner ────────────────────────────────────────────────┐   │
│  │  Dart VM 的主 Isolate 运行在这里                              │   │
│  │  职责：                                                     │   │
│  │  · 执行 Dart 代码（build、setState、动画计算）                  │   │
│  │  · 构建 Widget → Element → RenderObject                     │   │
│  │  · Layout + Paint → 生成 Layer Tree                         │   │
│  │  · 把 Layer Tree 提交给 Raster Runner                       │   │
│  │  约束：这里卡了 → 掉帧（build 方法不能做耗时计算）              │   │
│  └────────────────────────────────────────────────────────────┘   │
│                                                                   │
│  ┌─ Raster Runner（旧称 GPU Runner）──────────────────────────┐   │
│  │  职责：                                                     │   │
│  │  · 接收 UI Runner 提交的 Layer Tree                         │   │
│  │  · 通过 Skia/Impeller 光栅化为 GPU 指令                      │   │
│  │  · 调用 OpenGL/Vulkan/Metal 把像素提交到 Surface             │   │
│  │  约束：这里卡了 → 掉帧（复杂渲染、大量 saveLayer）             │   │
│  └────────────────────────────────────────────────────────────┘   │
│                                                                   │
│  ┌─ IO Runner ────────────────────────────────────────────────┐   │
│  │  职责：                                                     │   │
│  │  · 图片解码（ui.instantiateImageCodec）                      │   │
│  │  · 文件读写                                                 │   │
│  │  · 其他不该阻塞 UI/Raster 的耗时 IO 操作                     │   │
│  │  特点：和 Raster Runner 共享 GPU Context                     │   │
│  │        解码后的图片纹理可以直接传给 Raster，无需拷贝            │   │
│  └────────────────────────────────────────────────────────────┘   │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
```

---

## 三、单个 TaskRunner 的内部结构

和 Android 的 Looper 机制对比：

```
Android Handler 机制                 Flutter TaskRunner 机制
──────────────────                  ────────────────────────

  Thread                              Thread
    │                                   │
    ▼                                   ▼
  Looper.loop()                      MessageLoop::Run()
    │                                   │
    ▼                                   ▼
  MessageQueue                       TaskQueue（优先级队列，按时间排序）
  ┌──────────────┐                   ┌──────────────┐
  │ Message       │                   │ Task (闭包)   │
  │ Message       │                   │ Task (闭包)   │
  │ Message       │                   │ Task (闭包)   │
  └──────────────┘                   └──────────────┘
    │                                   │
    ▼                                   ▼
  msg.target.handleMessage()         task.Run()（执行闭包）
    │                                   │
    ▼                                   ▼
  取下一条 Message                    取下一个 Task
  （无限循环）                         （无限循环）
```

用伪代码表示：

```cpp
// Flutter Engine 中 MessageLoop 的核心逻辑（简化）
// 和 Android Looper.loop() 本质一样
class MessageLoop {
    TaskQueue task_queue_;  // 按执行时间排序的优先级队列

    void Run() {
        while (running_) {
            // 1. 等待直到有 Task 到达执行时间
            auto task = task_queue_.GetNextTaskToRun();

            // 2. 没到时间就阻塞（类似 epoll_wait / MessageQueue.next()）
            if (task.target_time > now()) {
                WaitUntil(task.target_time);
                continue;
            }

            // 3. 执行 Task
            task.Run();
        }
    }
};

// 投递任务（类似 Handler.post / Handler.postDelayed）
class TaskRunner {
    void PostTask(closure) {
        task_queue_.Push(Task{
            closure: closure,
            target_time: now()       // 立即执行
        });
        Wake();  // 唤醒 MessageLoop
    }

    void PostDelayedTask(closure, delay) {
        task_queue_.Push(Task{
            closure: closure,
            target_time: now() + delay  // 延迟执行
        });
        Wake();
    }
};
```

---

## 四、四个 Runner 如何协作——一帧的生命周期

```
时间线 ──────────────────────────────────────────────────────→

VSync 信号到达（每 16.6ms 一次，60fps）
    │
    ▼
Platform Runner
    │  收到 VSync 回调
    │  PostTask 到 UI Runner
    │
    ▼
UI Runner ─────────────────────────────────────
    │
    │  ① 执行动画回调（Ticker）
    │  ② 执行 Build（dirty Element rebuild）
    │  ③ Layout（RenderObject.performLayout）
    │  ④ Paint（RenderObject.paint → Layer Tree）
    │  ⑤ Compositing（合成 Scene）
    │
    │  PostTask 到 Raster Runner，提交 Layer Tree
    │
    ▼
Raster Runner ─────────────────────────────────
    │
    │  ⑥ 接收 Layer Tree
    │  ⑦ Skia/Impeller 光栅化
    │  ⑧ GPU 指令提交（OpenGL/Vulkan/Metal）
    │  ⑨ SwapBuffers → 上屏
    │
    ▼
用户看到画面更新


IO Runner（独立工作，不在主帧循环内）───────────
    │
    │  图片加载请求进来
    │  → 读文件/网络
    │  → 解码为纹理
    │  → 通知 Raster Runner 纹理就绪
```

---

## 五、Runner 之间如何投递任务

```
UI Runner                           Platform Runner
    │                                     │
    │  // Dart 调用 Platform Channel       │
    │  platform_task_runner              │
    │     ->PostTask(callback)            │
    │         │                           │
    │     投递 Task 到 ──────────────→  TaskQueue
    │     Platform Runner 的队列          │
    │                                     ▼
    │                              MessageLoop 取出执行
    │                              JNI 调用 Java Handler
    │                              result.success(85)
    │                                     │
    │         ←────────────────────────────│
    │     ui_task_runner                  │
    │        ->PostTask(result_callback)  │
    │                                     │
    ▼                                     │
  MessageLoop 取出执行                    │
  Future 完成                             │
```

**关键点**：Runner 之间的通信就是往对方的 TaskQueue 里塞一个 Task（闭包），然后唤醒对方的 MessageLoop。**没有锁竞争**（每个 Task 在自己的线程里串行执行），**没有 IPC**（同一进程内的函数调用）。

---

## 六、Pipeline 流水线机制

UI Runner 和 Raster Runner 通过 Pipeline 实现**生产者-消费者**模式：

```
UI Runner (生产者)                    Raster Runner (消费者)
    │                                      │
    │  第 1 帧                              │
    │  Build + Layout + Paint              │
    │  → 生成 Layer Tree ──────────────→  光栅化第 1 帧
    │                                      │
    │  第 2 帧                              │  还在光栅化...
    │  Build + Layout + Paint              │
    │  → Layer Tree 准备好了                │
    │  → 但 Pipeline 满了（depth=1）        │
    │  → 阻塞等待！                         │
    │         │                            │
    │         │←── 第 1 帧光栅化完成 ────────│
    │         │    Pipeline 空出位置         │
    │         ▼                            │
    │  提交第 2 帧 ────────────────────→  光栅化第 2 帧
    │                                      │

Pipeline depth 默认为 1：
· UI Runner 最多只能领先 Raster Runner 一帧
· 防止 UI Runner 过快生产导致内存暴涨
· 如果 Raster 太慢 → UI Runner 被阻塞 → 掉帧
```

---

## 七、和 Android 对比总结

| | Android | Flutter TaskRunner |
|---|---|---|
| 主线程消息循环 | `Looper.loop()` + `MessageQueue` | `MessageLoop::Run()` + `TaskQueue` |
| 投递任务 | `Handler.post(Runnable)` | `TaskRunner::PostTask(closure)` |
| 延迟任务 | `Handler.postDelayed()` | `TaskRunner::PostDelayedTask()` |
| 阻塞等待 | `epoll_wait` (Linux) | 同样基于 `epoll_wait` |
| 帧驱动 | `Choreographer` 接收 VSync | Engine 接收 VSync，PostTask 到 UI Runner |
| 渲染线程 | `RenderThread` (硬件加速) | `Raster Runner` |
| 线程间通信 | `Handler.post` 到目标线程 | `PostTask` 到目标 Runner 的 TaskQueue |

---

## 八、面试回答模板

**Q: Flutter 的线程模型是怎样的？TaskRunner 怎么工作？**

> Flutter Engine 内部有四个 TaskRunner，每个本质上是一个线程加一个消息循环，和 Android 的 Handler/Looper 机制原理一致。Platform Runner 就是 Android 主线程，负责系统事件和 Platform Channel；UI Runner 运行 Dart 代码，负责 build、layout、paint 并生成 Layer Tree；Raster Runner 接收 Layer Tree 通过 Skia 光栅化为 GPU 指令；IO Runner 处理图片解码等耗时 IO。Runner 之间通过向对方的 TaskQueue 投递闭包通信，同进程内无需 IPC。UI Runner 和 Raster Runner 之间通过 Pipeline 机制做流量控制，深度为 1，防止 UI 生产过快导致内存暴涨。

**Q: 为什么 Flutter 要分这么多线程？**

> 核心是流水线并行。UI Runner 在准备第 N+1 帧的同时，Raster Runner 在光栅化第 N 帧，IO Runner 在解码下一张要显示的图片。三者并行工作，最大化利用多核 CPU，保证 60fps 甚至 120fps 的流畅体验。如果全部串行放在一个线程，Build + Layout + Paint + 光栅化 + IO 全算在一帧里，很容易超过 16.6ms 导致掉帧。
