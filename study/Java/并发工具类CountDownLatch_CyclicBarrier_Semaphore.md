# 并发工具类：CountDownLatch / CyclicBarrier / Semaphore

## 一、CountDownLatch 原理

**一次性闭锁**：一个或多个线程等待，直到 N 个事件完成（计数减到 0）才放行。基于 AQS 共享模式实现。

```
   计数器 N=3
       │
   ┌───┴────┬────┬────┐
   │        │    │    │
worker1  worker2 worker3   主线程
   │        │    │           │
countDown countDown countDown await（阻塞）
   │        │    │           │
   └────────┴────┴───────────┘
       N → 2 → 1 → 0
                    │
                    ▼
               主线程被唤醒继续执行
```

## 二、CountDownLatch 典型用法

### 场景 1：主线程等多个子任务完成

```java
public static void main(String[] args) throws InterruptedException {
    int taskCount = 3;
    CountDownLatch latch = new CountDownLatch(taskCount);
    ExecutorService pool = Executors.newFixedThreadPool(taskCount);

    for (int i = 0; i < taskCount; i++) {
        final int id = i;
        pool.submit(() -> {
            try {
                Thread.sleep(1000);
                System.out.println("任务 " + id + " 完成");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();   // 必须放 finally，避免异常导致永久阻塞
            }
        });
    }

    latch.await();              // 阻塞，直到 3 次 countDown
    // 或带超时：latch.await(5, TimeUnit.SECONDS)
    System.out.println("所有任务完成");
    pool.shutdown();
}
```

### 场景 2：多个线程等待同时启动（发令枪）

```java
CountDownLatch start = new CountDownLatch(1);

for (int i = 0; i < 10; i++) {
    new Thread(() -> {
        try {
            start.await();      // 所有线程都卡在这
            // 同时开跑
        } catch (InterruptedException e) {}
    }).start();
}

Thread.sleep(2000);
start.countDown();              // 一声枪响，全部出发
```

---

## 三、CyclicBarrier（循环屏障）

**作用**：N 个线程**互相等待**，全部到达后一起继续。**可重用**。

```
线程1 ──▶ await() ─┐
线程2 ──▶ await() ─┼──▶ 全部到达 → 一起继续
线程3 ──▶ await() ─┘                  │
                                       ▼
                                  (可选) 执行 barrierAction
                                       │
                                       ▼
                                   下一轮再用
```

```java
// 3 个线程到齐后，先执行 barrier 动作，再继续
CyclicBarrier barrier = new CyclicBarrier(3, () -> {
    System.out.println("全员到齐，开始下一阶段");
});

for (int i = 0; i < 3; i++) {
    final int id = i;
    new Thread(() -> {
        try {
            System.out.println("线程 " + id + " 准备");
            barrier.await();         // 等其他线程
            System.out.println("线程 " + id + " 出发");
        } catch (Exception e) {}
    }).start();
}
```

**常用方法**：

| 方法              | 作用                                    |
| ----------------- | --------------------------------------- |
| `await()`         | 等待到齐                                |
| `await(timeout)`  | 超时等待                                |
| `reset()`         | 重置屏障（已等待线程会抛 BrokenBarrier）|
| `getNumberWaiting()` | 当前等待线程数                      |
| `isBroken()`      | 屏障是否破损（有线程中断/超时）         |

---

## 四、Semaphore（信号量）

**作用**：控制**同时访问某资源的线程数**（限流）。

```
   Semaphore(3)
   ┌──────────┐
   │ 许可: 3  │
   └──────────┘
       │
   ┌───┴────┬────┬────┬────┐
   │        │    │    │    │
  T1       T2   T3   T4   T5
acquire acquire acquire (阻塞) (阻塞)
   │        │    │    
release release release
                   │
                   ▼
               T4 获得许可继续
```

```java
// 模拟 3 个停车位，10 辆车
Semaphore parking = new Semaphore(3);

for (int i = 0; i < 10; i++) {
    final int car = i;
    new Thread(() -> {
        try {
            parking.acquire();        // 拿许可，没车位则阻塞
            System.out.println("车 " + car + " 入位");
            Thread.sleep(2000);
            System.out.println("车 " + car + " 离开");
        } catch (InterruptedException e) {
        } finally {
            parking.release();        // 释放许可
        }
    }).start();
}
```

**公平 vs 非公平**：

```java
new Semaphore(3);        // 非公平（默认），吞吐高
new Semaphore(3, true);  // 公平，FIFO 顺序，避免饥饿
```

---

## 五、三者对比

| 维度       | CountDownLatch         | CyclicBarrier              | Semaphore        |
| ---------- | ---------------------- | -------------------------- | ---------------- |
| **作用**   | 一组线程等另一组完成   | 一组线程互相等齐           | 限制并发数       |
| **方向**   | 等待者 ≠ 触发者        | 等待者 = 触发者            | 占用/释放资源    |
| **可重用** | ❌ 一次性               | ✅ 可循环                   | ✅ 重复 acquire   |
| **核心方法** | `await` / `countDown` | `await`                   | `acquire` / `release` |
| **底层**   | AQS 共享模式           | ReentrantLock + Condition  | AQS 共享模式     |
| **典型场景** | 主线程等子任务         | 多阶段并行任务、赛跑       | 连接池、限流     |

---

## 六、扩展：Phaser（阶段器，JDK 7+）

比 CyclicBarrier 更灵活，支持**动态注册/注销参与者**、**多阶段屏障**。

```java
Phaser phaser = new Phaser(3);     // 初始 3 个参与者

for (int i = 0; i < 3; i++) {
    final int id = i;
    new Thread(() -> {
        System.out.println("阶段 1 - 线程 " + id);
        phaser.arriveAndAwaitAdvance();  // 等齐进入下一阶段
        System.out.println("阶段 2 - 线程 " + id);
        phaser.arriveAndAwaitAdvance();
        phaser.arriveAndDeregister();    // 退出
    }).start();
}
```

用得较少，了解即可。

---

## 七、面试常考点

### Q1：CountDownLatch 和 CyclicBarrier 区别？

1. **方向不同**：CountDownLatch 是 A 等 B（B 调 countDown，A 调 await）；CyclicBarrier 是 A、B、C 互相等。
2. **重用性**：CountDownLatch 计数到 0 就废了；CyclicBarrier 可以 `reset()` 重用。
3. **回调**：CyclicBarrier 支持构造时传 `barrierAction`，到达屏障时执行。

### Q2：CountDownLatch 的计数能再加回去吗？

不能。计数器是 AQS 的 `state`，只减不增，归零后所有 await 立即返回。需要重用就用 CyclicBarrier。

### Q3：Semaphore 公平锁怎么开？

```java
new Semaphore(3, true);   // true = 公平
```

公平模式按 FIFO 顺序获取许可，避免线程饥饿，但吞吐量会降低。

### Q4：`countDown` 必须放 finally 吗？

必须。如果任务抛异常没 `countDown`，主线程的 `await()` 会**永久阻塞**。建议配合 `await(timeout, TimeUnit)` 加超时兜底。

### Q5：CountDownLatch 底层原理？

基于 AQS 共享模式：
- 构造时把 AQS 的 `state` 设为 N
- `countDown` → `releaseShared` → CAS 减 1，归零唤醒所有等待者
- `await` → `acquireSharedInterruptibly`，state ≠ 0 就挂起

### Q6：CyclicBarrier 的 `barrierAction` 由谁执行？

由**最后一个到达屏障的线程**执行，在它释放其他线程之前执行。因此这个动作会阻塞其他线程进入下一阶段。

### Q7：Semaphore 和锁的区别？

- **锁**：只允许 1 个线程进入（互斥）
- **Semaphore**：允许 N 个线程进入（可视为"N 把锁"）
- Semaphore 常用于**限流**而非互斥

### Q8：CyclicBarrier 中一个线程中断会发生什么？

屏障变成 **broken** 状态，所有正在等待的线程会抛 `BrokenBarrierException`，需调 `reset()` 才能重新使用。这就是为什么生产代码要捕获该异常。

### Q9：哪些场景用 CountDownLatch？

- 服务启动时等多个资源初始化完成
- 并发请求聚合（同时发 N 个请求，等全部返回）
- 单元测试里模拟并发场景
- 主线程等待子任务收尾后再退出

### Q10：CyclicBarrier 能退化成 CountDownLatch 吗？

不能完全等价。CyclicBarrier 是**互相等待**，参与等待的线程自己也要到屏障；而 CountDownLatch 的 await 线程和 countDown 线程可以完全分离。勉强可以用 `CyclicBarrier(1)` 模拟单次屏障，但语义不同。
