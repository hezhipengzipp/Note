# Handler 机制详解

## 一、是什么

Handler 是 Android 的**线程间通信机制**。核心问题：Android 规定**只有主线程能更新 UI**，子线程做完耗时操作后怎么把结果传回主线程？答案就是 Handler。

## 二、四个核心角色

```
┌─────────────────────────────────────────────────────┐
│                    主线程                              │
│                                                     │
│  ┌───────────┐    ┌──────────────┐    ┌───────────┐ │
│  │  Handler   │───>│ MessageQueue │<───│  Looper   │ │
│  │  发送消息   │    │  消息队列      │    │  循环取消息│ │
│  └───────────┘    └──────────────┘    └───────────┘ │
│       │                  │                  │       │
│       │                  │                  │       │
│  sendMessage()      存储 Message        loop()不断取  │
│  post(Runnable)     按时间排序          取出后交给      │
│                                      Handler处理      │
└─────────────────────────────────────────────────────┘
```

| 角色 | 职责 | 数量关系 |
|------|------|---------|
| **Message** | 消息载体，携带 what、obj、data 等 | 多个 |
| **MessageQueue** | 消息队列，按时间排序的单链表 | 每个线程最多 1 个 |
| **Looper** | 死循环，不断从 MessageQueue 取消息 | 每个线程最多 1 个 |
| **Handler** | 发送消息 + 处理消息 | 可以有多个 |

## 三、一条消息的完整生命周期

```
子线程                              主线程
  │                                  │
  │ 1. handler.sendMessage(msg)      │
  │    或 handler.post(runnable)     │
  │         │                        │
  │         ▼                        │
  │    msg.target = this (Handler)   │
  │         │                        │
  │         ▼                        │
  │    MessageQueue.enqueueMessage() │
  │    按 msg.when 插入链表            │
  │         │                        │
  │         └──────────────────────→ │
  │                                  │  2. Looper.loop() 死循环
  │                                  │     │
  │                                  │     ▼
  │                                  │  queue.next() 取出消息
  │                                  │  （没消息就 epoll 休眠）
  │                                  │     │
  │                                  │     ▼
  │                                  │  3. msg.target.dispatchMessage(msg)
  │                                  │     即 Handler.dispatchMessage()
  │                                  │     │
  │                                  │     ▼
  │                                  │  4. handleMessage(msg)
  │                                  │     在主线程执行！
```

## 四、消息分发顺序

```java
// Handler.dispatchMessage()
public void dispatchMessage(Message msg) {
    if (msg.callback != null) {
        // 1. 优先执行 Message 自带的 Runnable（handler.post(runnable)）
        msg.callback.run();
    } else {
        if (mCallback != null) {
            // 2. 其次执行 Handler 构造时传入的 Callback
            if (mCallback.handleMessage(msg)) {
                return;
            }
        }
        // 3. 最后执行 Handler 子类重写的 handleMessage
        handleMessage(msg);
    }
}
```

```
优先级：msg.callback (Runnable) > mCallback > handleMessage()
```

## 五、MessageQueue 的数据结构

**不是真正的队列，是按时间排序的单链表**：

```
head → msg(when=100) → msg(when=200) → msg(when=350) → null
        ↑
     最早要执行的在前面

插入 msg(when=150)：
head → msg(when=100) → msg(when=150) → msg(when=200) → msg(when=350) → null
                        ↑ 插到这里
```

## 六、Looper.loop() 核心逻辑

```java
// 简化版
public static void loop() {
    final MessageQueue queue = myLooper().mQueue;

    for (;;) {  // 死循环
        Message msg = queue.next();  // 取消息，没有就阻塞
        if (msg == null) return;     // null 表示 Looper 退出

        msg.target.dispatchMessage(msg);  // 交给 Handler 处理
        msg.recycleUnchecked();           // 回收 Message 到对象池
    }
}
```

## 七、MessageQueue.next() 的阻塞机制

```
next() {
    for (;;) {
        nativePollOnce(ptr, nextPollTimeoutMillis)  // 关键！
        //
        // nextPollTimeoutMillis:
        //   -1  → 永久休眠，直到有新消息 enqueue 唤醒
        //    0  → 不休眠，立即返回
        //   >0  → 休眠指定毫秒（等最近的延迟消息到期）
        //
        // 底层用 Linux 的 epoll 机制实现
        // 休眠时不消耗 CPU

        // 醒来后检查队头消息
        if (now >= msg.when) {
            return msg;  // 到时间了，取出
        } else {
            nextPollTimeoutMillis = msg.when - now;  // 还没到，继续睡
        }
    }
}
```

```
消息队列空了 → epoll 休眠（不消耗 CPU）
    │
    ▼
其他线程 sendMessage() → enqueueMessage() → nativeWake() 唤醒
    │
    ▼
next() 返回消息 → dispatchMessage() 处理
```

## 八、常见使用方式

```kotlin
// 方式 1：sendMessage
val handler = object : Handler(Looper.getMainLooper()) {
    override fun handleMessage(msg: Message) {
        when (msg.what) {
            1 -> textView.text = msg.obj as String
        }
    }
}
// 子线程
thread {
    val result = doHeavyWork()
    handler.sendMessage(Message.obtain().apply {
        what = 1
        obj = result
    })
}

// 方式 2：post（更简洁）
val handler = Handler(Looper.getMainLooper())
thread {
    val result = doHeavyWork()
    handler.post {
        textView.text = result  // 这个 Runnable 在主线程执行
    }
}

// 方式 3：延迟执行
handler.postDelayed({ doSomething() }, 3000)

// 方式 4：View.post（内部也是 Handler）
textView.post {
    // 在主线程执行，且保证 View 已经 measure/layout 完成
}
```

## 九、Message 对象池

Message 内部维护了一个**链表对象池**，避免频繁创建对象：

```
Message.obtain()      ← 从池中取，没有才 new
msg.recycle()         ← 用完放回池中

对象池结构（链表，最多 50 个）：
sPool → msg → msg → msg → null
```

```java
// 源码
public static Message obtain() {
    synchronized (sPoolSync) {
        if (sPool != null) {
            Message m = sPool;
            sPool = m.next;
            m.next = null;
            sPoolSize--;
            return m;       // 复用
        }
    }
    return new Message();   // 池空了才 new
}
```

**永远用 `Message.obtain()` 或 `handler.obtainMessage()` 获取 Message，不要 `new Message()`。**

## 十、ThreadLocal 详解

### 解决什么问题

Handler 机制要求每个线程最多只能有一个 Looper。怎么存储这个"每个线程一份"的 Looper？

```
方案 1：用一个全局 Map<Thread, Looper> ？
  → 需要加锁，多线程竞争，性能差
  → 线程结束后还要手动清理，容易内存泄漏

方案 2：ThreadLocal ✅
  → 每个线程访问自己的副本，天然线程安全，无需加锁
  → 线程销毁时自动回收
```

### ThreadLocal 是什么

**ThreadLocal 不是锁，不是线程，它是一个"每个线程各存一份数据"的容器。** 同一个 ThreadLocal 对象，在不同线程中 get() 拿到的值不同：

```
ThreadLocal<Looper> sThreadLocal = new ThreadLocal<>();

主线程调用 sThreadLocal.set(looper_main)
子线程A调用 sThreadLocal.set(looper_A)
子线程B调用 sThreadLocal.set(looper_B)

主线程调用 sThreadLocal.get() → looper_main
子线程A调用 sThreadLocal.get() → looper_A
子线程B调用 sThreadLocal.get() → looper_B
```

### 在 Looper 中的使用

```java
public class Looper {
    static final ThreadLocal<Looper> sThreadLocal = new ThreadLocal<>();

    public static void prepare() {
        if (sThreadLocal.get() != null) {
            throw new RuntimeException("Only one Looper may be created per thread");
        }
        sThreadLocal.set(new Looper());  // 存到当前线程
    }

    public static Looper myLooper() {
        return sThreadLocal.get();  // 从当前线程取
    }
}
```

### 底层实现原理

**数据不是存在 ThreadLocal 里，而是存在每个 Thread 的 ThreadLocalMap 中：**

```java
// Thread 对象内部
class Thread {
    ThreadLocal.ThreadLocalMap threadLocals;  // 每个线程自带一个 Map
}
```

```
┌─────────────────────────────────────────────────┐
│  主线程 Thread 对象                                │
│  threadLocals (ThreadLocalMap):                   │
│  ┌──────────────────┬──────────────┐             │
│  │  Key             │  Value        │             │
│  ├──────────────────┼──────────────┤             │
│  │  sThreadLocal    │  Looper_main  │             │
│  │  其他ThreadLocal  │  其他值        │             │
│  └──────────────────┴──────────────┘             │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│  子线程 A Thread 对象                              │
│  threadLocals (ThreadLocalMap):                   │
│  ┌──────────────────┬──────────────┐             │
│  │  Key             │  Value        │             │
│  ├──────────────────┼──────────────┤             │
│  │  sThreadLocal    │  Looper_A     │             │
│  └──────────────────┴──────────────┘             │
└─────────────────────────────────────────────────┘
```

### set() 和 get() 的流程

```java
// ThreadLocal.set()
public void set(T value) {
    Thread t = Thread.currentThread();          // 1. 拿到当前线程
    ThreadLocalMap map = t.threadLocals;         // 2. 拿到线程自带的 Map
    if (map != null) {
        map.set(this, value);                   // 3. key=this(ThreadLocal对象), value=Looper
    } else {
        t.threadLocals = new ThreadLocalMap(this, value);
    }
}

// ThreadLocal.get()
public T get() {
    Thread t = Thread.currentThread();          // 1. 拿到当前线程
    ThreadLocalMap map = t.threadLocals;         // 2. 拿到线程自带的 Map
    if (map != null) {
        Entry e = map.getEntry(this);           // 3. 用 this 作为 key 查找
        if (e != null) return (T) e.value;
    }
    return setInitialValue();
}
```

```
关键理解：
  ThreadLocal 对象是 Key
  数据存在 Thread 的 Map 里

  所以同一个 ThreadLocal 对象：
  线程 A 调 get() → 去线程 A 的 Map 里找 → 拿到 A 的数据
  线程 B 调 get() → 去线程 B 的 Map 里找 → 拿到 B 的数据
```

### ThreadLocalMap 的 Key 是弱引用

```java
static class Entry extends WeakReference<ThreadLocal<?>> {
    Object value;
    Entry(ThreadLocal<?> k, Object v) {
        super(k);    // key 是弱引用
        value = v;   // value 是强引用
    }
}
```

```
Entry:
  key (WeakReference) ───弱引用──→ ThreadLocal 对象
  value (强引用) ───强引用──→ Looper 对象

如果 ThreadLocal 对象没有其他强引用了：
  GC 时 key 会被回收 → Entry 的 key 变成 null
  但 value 还在 → 内存泄漏！

所以 ThreadLocal 用完后应该调用 remove()
不过 Looper 的 sThreadLocal 是 static final 的，不会被回收，不存在这个问题
```

### 为什么不用 synchronized 而用 ThreadLocal？

```
synchronized 方案：
  private static final Map<Thread, Looper> looperMap = new HashMap<>();

  public static synchronized void prepare() {
      looperMap.put(Thread.currentThread(), new Looper());
  }

  public static synchronized Looper myLooper() {
      return looperMap.get(Thread.currentThread());
  }

  问题：
  - 每次 get/set 都要竞争锁
  - Looper.myLooper() 在消息循环中每条消息都会调用
  - 高频加锁 = 性能瓶颈

ThreadLocal 方案：
  - 每个线程读写自己的 Map，完全不需要加锁
  - 空间换时间，适合"每个线程一份数据"的场景
```

### ThreadLocal 面试总结

| 问题 | 答案 |
|------|------|
| ThreadLocal 存数据存在哪？ | 存在每个 Thread 对象的 ThreadLocalMap 中 |
| 为什么线程安全？ | 每个线程读写自己的 Map，不存在竞争 |
| Key 是什么？ | ThreadLocal 对象本身（弱引用） |
| 在 Handler 中的作用？ | 保证每个线程只有一个 Looper，且线程间互不干扰 |
| 和 synchronized 的区别？ | synchronized 是多线程竞争同一份数据；ThreadLocal 是每个线程各一份数据，不竞争 |

## 十一、子线程使用 Handler

```kotlin
// 手动方式
val thread = Thread {
    Looper.prepare()    // 1. 创建 Looper
    val handler = Handler(Looper.myLooper()!!)  // 2. 关联 Handler
    Looper.loop()       // 3. 开始循环（阻塞在这里）
    // loop() 之后的代码不会执行，除非调用 looper.quit()
}
thread.start()

// 推荐方式：HandlerThread（封装了上面的步骤）
val handlerThread = HandlerThread("bg-thread")
handlerThread.start()
val bgHandler = Handler(handlerThread.looper)
bgHandler.post { /* 在子线程执行 */ }
// 用完记得 handlerThread.quitSafely()
```

## 十二、同步屏障

```
正常情况：消息按时间顺序执行
  msg1(同步) → msg2(同步) → msg3(同步)

插入同步屏障后：同步消息被挡住，异步消息优先执行
  msg1(同步) → [屏障] → msg2(同步) → msg3(异步)
                         ↑ 被挡住      ↑ 优先执行！

应用场景：UI 绘制
  ViewRootImpl.scheduleTraversals()
      │
      ├── 插入同步屏障
      ├── 发送异步消息（执行 measure/layout/draw）
      └── 绘制完成后移除同步屏障

目的：保证绘制消息优先处理，不被其他消息（如 Activity 生命周期回调）抢占
```

---

## 十三、面试高频问题

### Q1: 一个线程可以有几个 Handler、几个 Looper、几个 MessageQueue？

- Handler：**多个**（都往同一个 MessageQueue 发消息）
- Looper：**1 个**（ThreadLocal 保证，`prepare()` 两次会抛异常）
- MessageQueue：**1 个**（在 Looper 构造时创建，一一对应）

### Q2: Handler 内存泄漏原因和解决方案？

```
泄漏链路：
  Message.target → Handler（内部类）→ 持有外部 Activity 引用
  MessageQueue 持有 Message
  → Activity 关闭了但 Message 还在队列中 → Activity 无法被 GC

解决方案：
  1. 静态内部类 + WeakReference
  2. Activity.onDestroy() 中 handler.removeCallbacksAndMessages(null)
```

### Q3: Looper 死循环为什么不会卡死（ANR）？

- 没消息时 `epoll_wait` 休眠，不消耗 CPU，不是"卡死"
- ANR 是某条消息处理时间太长，不是循环本身的问题
- 没有循环 → 主线程执行完就退出 → App 关闭了

### Q4: post 和 sendMessage 的区别？

- `post(Runnable)` 内部把 Runnable 包装成 `msg.callback`
- `sendMessage(Message)` 直接发 Message
- 最终都进入 MessageQueue，处理时 callback 优先于 handleMessage

### Q5: Handler 的 postDelayed 是怎么实现的？

- 不是真的 sleep，而是算出绝对执行时间 `SystemClock.uptimeMillis() + delayMillis`
- 存入 `msg.when`，按 when 插入链表
- `next()` 取消息时，没到时间就 `epoll_wait` 指定时长

### Q6: 主线程的 Looper 和子线程的 Looper 有什么区别？

| | 主线程 Looper | 子线程 Looper |
|--|-------------|-------------|
| 创建方式 | `prepareMainLooper()`（系统调用） | `prepare()`（手动调用） |
| 能否退出 | 不能（`quit()` 会抛异常） | 可以（`quit()` / `quitSafely()`） |
| 生命周期 | 与 App 进程相同 | 需要手动管理 |
