# Android SurfaceFlinger 双缓冲 / 三缓冲机制

---

## 一、先理解问题：没有缓冲会怎样

```
屏幕刷新过程（无缓冲 ❌）：

  App 直接往屏幕显存写像素
  
  显示器扫描到第 200 行时
  App 正好在改第 150 行的数据
      │
      ▼
  上半部分：新画面
  ──────────── 第 200 行（扫描位置）
  下半部分：旧画面
  
  → 画面撕裂（Tearing）！上下两半不一致
```

---

## 二、双缓冲（Double Buffering）

用两块 Buffer 解决撕裂：

```
┌─ Front Buffer ──────────┐    ┌─ Back Buffer ───────────┐
│                          │    │                          │
│  显示器正在读这块          │    │  App/GPU 正在往这块写     │
│  展示给用户看              │    │  画下一帧                 │
│                          │    │                          │
└──────────────────────────┘    └──────────────────────────┘
         │                                │
         │         VSync 信号到来时         │
         │←───────── 交换 ────────────────→│
         │         （指针互换，不是拷贝数据）  │
```

完整流程：

```
时间线 ──────────────────────────────────────────────→
        16.6ms          16.6ms          16.6ms
VSync ────┤──────────────┤──────────────┤──────────

帧 N：
  Back Buffer:   App 绘制帧 N
  Front Buffer:  显示器显示帧 N-1
                      │
                   VSync ← 交换！
                      │
帧 N+1：
  Back Buffer:   现在是之前的 Front，App 开始画帧 N+1
  Front Buffer:  现在是之前的 Back（帧 N），显示器显示
```

**关键规则**：只在 VSync 时刻交换 Buffer，显示器永远读完整的一帧，不会撕裂。

---

## 三、双缓冲的问题——Jank（掉帧）

```
理想情况（CPU/GPU 都在 16.6ms 内完成）：

VSync     VSync     VSync     VSync
  │         │         │         │
  ▼         ▼         ▼         ▼
  ├─ 帧1 ──┤─ 帧2 ──┤─ 帧3 ──┤
  显示 帧0   显示 帧1   显示 帧2   显示 帧3
  
  完美，每帧都按时交付 ✅


掉帧情况（CPU/GPU 某帧超时）：

VSync     VSync     VSync     VSync
  │         │         │         │
  ▼         ▼         ▼         ▼
  ├─ 帧1 ──┤         │         │
  │         ├── 帧2 ──────────┤  ← CPU/GPU 画帧2超过16.6ms
  │         │  Back还没画完    │
  │         │  不能交换！       │
  │         │         │         │
  显示 帧0   显示 帧1   显示 帧1   显示 帧2
                       ↑
                  帧1 显示了两次 = 用户感觉卡了一下（Jank）
```

**双缓冲掉帧的根本原因**：

```
VSync 到来
    │
    ▼
  交换 Buffer（Front ↔ Back）
    │
    ▼
  CPU 开始画下一帧 ← 必须等交换完才能开始！
  GPU 光栅化       ← 也得等 CPU 给数据
    │
    如果 CPU 或 GPU 任一环节超时
    下一个 VSync 到来时 Back Buffer 还没好
    → 无法交换 → 屏幕重复显示上一帧
    
  而且更严重的是：
  在等待交换期间，CPU 是空闲的！
  明明可以提前干活，但 Back Buffer 被占着用不了

  VSync     VSync     VSync
    │         │         │
    │  ┌ CPU 空闲 ┐     │
    │  │ 等 Buffer│     │
    │  └─────────┘     │
    │         ├─ CPU ──│── GPU 超时 ──→ 又掉一帧
    │         │         │
  这就是"连锁掉帧"：一帧慢 → 后面都跟着慢
```

---

## 四、三缓冲（Triple Buffering）——解决连锁掉帧

加一块 Buffer，让 CPU 不再空等：

```
┌─ Front Buffer ─┐  ┌─ Back Buffer 1 ─┐  ┌─ Back Buffer 2 ─┐
│                 │  │                  │  │                  │
│  显示器正在读    │  │  GPU 正在光栅化   │  │  CPU 可以提前画   │
│  展示给用户      │  │  上一帧的数据     │  │  下一帧           │
│                 │  │                  │  │                  │
└─────────────────┘  └──────────────────┘  └──────────────────┘
```

### 双缓冲 vs 三缓冲在掉帧时的表现

```
双缓冲（掉帧后连锁卡顿）：

VSync     VSync     VSync     VSync     VSync
  │         │         │         │         │
  ├─ 帧A ──┤         │         │         │
  │    CPU画帧B──GPU帧B超时──┤         │
  │         │ CPU空闲！│         │         │
  │         │ 没Buffer│         │         │
  │         │ 可以用   ├─ 帧B交换 │         │
  │         │         │ CPU才开始画C       │
  │         │         │         ├─ 帧C ──┤
  显示A      显示A      显示A      显示B      显示C
              ↑ 重复     ↑ 又重复
         连续两帧 Jank


三缓冲（掉帧后快速恢复）：

VSync     VSync     VSync     VSync     VSync
  │         │         │         │         │
  ├─ 帧A ──┤         │         │         │
  │    CPU画帧B──GPU帧B超时──┤         │
  │         │CPU画帧C!│ ← 有第三块Buffer  │
  │         │不用等！  │   CPU不空闲了      │
  │         │         ├─ 帧B交换          │
  │         │         │  GPU画帧C ────────┤─ 帧C交换
  显示A      显示A      显示B      显示C      显示D
              ↑ 只掉一帧，马上恢复
```

**三缓冲的核心收益**：GPU 占着 Back Buffer 1 没画完时，CPU 可以用 Back Buffer 2 提前开始下一帧，不用空等。

---

## 五、SurfaceFlinger 的角色

SurfaceFlinger 是 Android 的**合成器**，负责收集所有 App 的 Buffer 并合成最终画面：

```
┌─ App 1 ───────────┐  ┌─ App 2 ───────────┐  ┌─ SystemUI ────────┐
│  Surface           │  │  Surface           │  │  Surface          │
│  (BufferQueue)     │  │  (BufferQueue)     │  │  (BufferQueue)    │
│  ┌──┐┌──┐┌──┐     │  │  ┌──┐┌──┐┌──┐     │  │  ┌──┐┌──┐┌──┐    │
│  │B1││B2││B3│      │  │  │B1││B2││B3│      │  │  │B1││B2││B3│    │
│  └──┘└──┘└──┘     │  │  └──┘└──┘└──┘     │  │  └──┘└──┘└──┘    │
└────────┬──────────┘  └────────┬──────────┘  └────────┬─────────┘
         │                      │                      │
         └──────────┬───────────┘──────────────────────┘
                    │
                    ▼
         ┌─ SurfaceFlinger ────────────────────────┐
         │                                          │
         │  收集各 Surface 的 Front Buffer            │
         │  按 Z-order 合成（HWC 硬件合成 / GPU 合成） │
         │  输出到 FrameBuffer / Display              │
         │                                          │
         │  每个 VSync 周期执行一次                    │
         └──────────────────────────────────────────┘
                    │
                    ▼
               显示器显示
```

---

## 六、BufferQueue——生产者消费者模型

```
App (Producer)                BufferQueue                 SurfaceFlinger (Consumer)
──────────                   ───────────                 ──────────────────────

                         ┌─ Buffer 状态机 ─────────┐
                         │                         │
  dequeueBuffer() ──→    │  FREE → DEQUEUED        │
  拿一块空闲Buffer画      │    (App 拿去画)          │
         │               │                         │
  queueBuffer() ───→     │  DEQUEUED → QUEUED      │
  画完了提交              │    (画完等待消费)         │
                         │                         │
                         │  QUEUED → ACQUIRED ───→   acquireBuffer()
                         │    (SurfaceFlinger拿去合成)  SurfaceFlinger 取走合成
                         │                         │
                         │  ACQUIRED → FREE  ───→    releaseBuffer()
                         │    (合成完归还)             用完归还
                         └─────────────────────────┘

三缓冲时 BufferQueue 里有 3 个 Buffer 轮转：

  ┌────────┐  ┌────────┐  ┌────────┐
  │Buffer 0│  │Buffer 1│  │Buffer 2│
  │ FREE   │  │DEQUEUED│  │ACQUIRED│
  │        │  │App在画  │  │SF在合成 │
  └────────┘  └────────┘  └────────┘
      ↑                        │
      └──── releaseBuffer ─────┘
```

---

## 七、VSync 信号与 Choreographer

```
硬件 VSync（屏幕刷新）
    │
    ▼
SurfaceFlinger 接收
    │
    ├─ VSYNC-app ──→ Choreographer ──→ App 开始画下一帧
    │                                  （View.draw / Flutter build）
    │
    └─ VSYNC-sf  ──→ SurfaceFlinger 开始合成
                     （收集各 App 的 Buffer，合成输出）

时间线：
VSync-app          VSync-sf           VSync-app
    │                  │                  │
    ▼                  ▼                  ▼
  App 画帧 N+1      SF 合成帧 N        App 画帧 N+2
  CPU+GPU            HWC/GPU合成        CPU+GPU
    │                  │                  │
    └─ queueBuffer ──→ acquireBuffer     └─ ...

  App 和 SurfaceFlinger 流水线并行
  App 画下一帧的同时，SF 在合成当前帧
```

---

## 八、对比总结

| | 无缓冲 | 双缓冲 | 三缓冲 |
|---|---|---|---|
| 撕裂 | 有 | 无（VSync 交换） | 无 |
| 掉帧恢复 | - | 慢（连锁掉帧） | **快（一帧恢复）** |
| CPU 利用率 | - | 掉帧时空闲 | **不空闲，提前干活** |
| 内存占用 | 1 块 | 2 块 | 3 块（多 ~几 MB） |
| 延迟 | 最低 | 1 帧 | **最多 2 帧**（代价） |
| Android 采用 | 否 | 4.1 之前 | **4.1+ Project Butter** |

```
三缓冲的代价：

  双缓冲：用户看到的画面比 App 画的晚 1 帧
  三缓冲：可能晚 2 帧（多了一块 Buffer 在排队）

  对于 60fps（16.6ms/帧）：
    双缓冲延迟：~16.6ms
    三缓冲延迟：~33.2ms（最坏情况）

  但人眼感知阈值 ~100ms，多 16ms 感觉不到
  而 Jank（卡顿）是能明显感觉到的
  所以三缓冲是值得的
```

---

## 九、面试回答模板

**Q: 说一下 Android 的双缓冲和三缓冲机制？**

> 双缓冲用 Front Buffer 和 Back Buffer 解决画面撕裂：显示器读 Front，App 写 Back，VSync 到来时交换指针。但双缓冲在掉帧时会连锁卡顿——GPU 占着 Back Buffer 没画完，CPU 空闲也没有 Buffer 可以用，下一帧也跟着延迟。三缓冲加了一块 Buffer，GPU 占着一块时 CPU 可以用另一块提前画下一帧，掉帧后一帧就能恢复。代价是多了一块 Buffer 的内存和最多多一帧的延迟。Android 4.1 的 Project Butter 引入了三缓冲 + VSync 同步 + Choreographer 调度，大幅提升了流畅度。SurfaceFlinger 作为合成器，通过 BufferQueue 的生产者消费者模型收集各 App 的 Buffer，每个 VSync 周期合成一次最终画面输出到显示器。

**Q: BufferQueue 的工作流程是怎样的？**

> BufferQueue 是生产者消费者模型。App 作为生产者调用 dequeueBuffer 取一块空闲 Buffer 来画，画完调用 queueBuffer 提交。SurfaceFlinger 作为消费者调用 acquireBuffer 取走已完成的 Buffer 进行合成，合成完调用 releaseBuffer 归还。Buffer 有四个状态轮转：FREE → DEQUEUED（App 在画）→ QUEUED（等待合成）→ ACQUIRED（SF 在合成）→ FREE。三缓冲时有三个 Buffer 在这个状态机里轮转，保证 App、GPU、SurfaceFlinger 可以流水线并行工作。
