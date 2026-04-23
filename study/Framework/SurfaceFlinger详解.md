# Android SurfaceFlinger 详解

## 一、SurfaceFlinger 是什么

`SurfaceFlinger` 是 Android 显示系统里的核心合成服务，职责很直接：

- 接收各个窗口提交上来的图形 Buffer
- 维护系统里所有可见 `Layer`
- 在每次 `VSync` 到来时决定这一帧怎么合成
- 调用 `HWC`（Hardware Composer）或 `GPU` 完成合成
- 把最终结果送到屏幕显示

可以把它理解成 Android 的“总画面调度员”。

App 自己只负责把内容画到自己的 `Surface` 上，真正把状态栏、导航栏、App 内容、弹窗等图层拼成最终一帧的是 `SurfaceFlinger`。

---

## 二、几个关键对象先分清

### 1. Surface

`Surface` 是 App 侧拿来绘制的目标。

App 可以通过：

- `Canvas`
- `OpenGL ES`
- `Vulkan`
- `MediaCodec`

把内容写入 `Surface` 背后的 Buffer。

### 2. SurfaceControl

`SurfaceControl` 更偏“控制面”，用于描述一个图层：

- 位置
- 大小
- Z 顺序
- 透明度
- 裁剪区域
- 变换矩阵

通常可以简单理解为：

- `Surface` 负责“往哪里画”
- `SurfaceControl` 负责“这个图层怎么参与最终合成”

### 3. Layer

在 `SurfaceFlinger` 内部，窗口最终会对应成一个或多个 `Layer`。

例如：

- App 主窗口是一个 Layer
- 状态栏是一个 Layer
- 导航栏是一个 Layer
- 输入法窗口也可能是一个 Layer

`SurfaceFlinger` 每一帧都会遍历这些 Layer。

### 4. BufferQueue

`BufferQueue` 是生产者-消费者模型：

- App/RenderThread/EGL 是生产者
- `SurfaceFlinger` 是消费者

基本过程：

```text
App dequeueBuffer() -> 拿到空闲 Buffer
App 渲染
App queueBuffer()   -> 提交 Buffer
SF acquireBuffer()  -> 消费最新可用 Buffer
```

这就是 App 和 `SurfaceFlinger` 之间传递图像数据的核心桥梁。

---

## 三、SurfaceFlinger 在显示链路中的位置

```text
App -> Surface/BufferQueue -> SurfaceFlinger -> HWC/GPU -> Display -> 屏幕
```

更完整一点：

```text
WMS 负责窗口管理
  -> 决定窗口大小、层级、可见性
  -> 为窗口创建/管理 SurfaceControl

App 负责内容绘制
  -> 把图像写入 Surface 对应的 Buffer

SurfaceFlinger 负责图层合成
  -> 收集所有 Layer
  -> 选择 HWC 合成还是 GPU 合成
  -> 输出最终帧
```

所以：

- `WMS` 管“窗口规则”
- App 管“内容生产”
- `SurfaceFlinger` 管“最终拼图和送显”

---

## 四、你这条首帧显示链路，可以整理成什么样

你给的流程是对的，下面我按更标准的视角串一下。

### 阶段 1：WMS 决定窗口信息并创建图层控制对象

当 Activity 窗口添加到系统时：

- `ViewRootImpl` 通过 Binder 调用 `WMS`
- `WMS` 创建 `WindowState`
- `WMS` 决定窗口的位置、大小、层级、动画等属性
- `WMS` 通过 `SurfaceControl` 通知 `SurfaceFlinger` 准备对应的 Layer

此时可以理解成：

```text
窗口“壳子”已经建立
但窗口内容还没真正画出来
```

### 阶段 2：App 拿到 Surface，开始渲染第一帧

App 侧拿到 `Surface` 后：

- UI 线程触发首帧绘制
- 硬件加速场景下，通常由 `RenderThread` 配合 GPU 渲染
- OpenGL ES 最终调用 `eglSwapBuffers`
- `eglSwapBuffers` 底层会把渲染完成的 Buffer 通过 `queueBuffer` 提交到 `BufferQueue`

这一步的本质是：

```text
App 已经把“自己的那一层内容”准备好了
并交给 SurfaceFlinger 等待消费
```

### 阶段 3：SurfaceFlinger 等待合适时机取帧

`SurfaceFlinger` 不会在 App 一提交就立刻把屏幕刷新掉，而是通常跟随显示节奏，在 `VSync` 驱动下处理：

- 监听到对应 Layer 有新 Buffer 可用
- 到下一次合成时机时 latch 这帧 Buffer
- 把它纳入本次合成集合

这里要注意：

`queueBuffer` 不等于“屏幕立刻显示”，它只是表示：

```text
新帧已经准备好，可以参与下一次合成
```

### 阶段 4：VSync 到来，SF 开始本帧合成

`VSync` 信号到来后，`SurfaceFlinger` 会做几件事：

1. 遍历当前所有可见 Layer
2. 检查哪些 Layer 有新 Buffer
3. 更新 Layer 状态
4. 生成本帧的 composition 列表
5. 调用 `HWC`

这时参与的 Layer 往往包括：

- App 内容层
- 状态栏
- 导航栏
- 壁纸
- 输入法
- 动画层或截图层

### 阶段 5：HWC 返回合成策略

`HWC` 会根据硬件能力和图层特征，返回本帧怎么合成。

常见有两类：

#### 1. Device Composition

由 `HWC` 直接使用硬件 Overlay 完成合成。

优点：

- 功耗低
- 延迟低
- 减少 GPU 压力

#### 2. Client Composition

由 `SurfaceFlinger` 走 GPU 路径合成，`HWC` 只负责最终送显。

通常出现在：

- 图层过多
- 某些混合模式硬件不支持
- 旋转/缩放/颜色空间等条件复杂

因此你提到的这句：

```text
状态栏 + 导航栏用 HWC 覆盖层
App 用 GPU 合成
或者全用 HWC
```

这是成立的。实际方案由硬件能力和当前 Layer 状态共同决定。

### 阶段 6：执行合成并送显

`SurfaceFlinger` 根据 `HWC` 返回的方案：

- 能交给 `HWC` 的部分交给 `HWC`
- 需要 GPU 的部分先做 client composition
- 最终调用显示硬件 present

然后屏幕在这一刷新周期显示出新的画面。

---

## 五、用一张时序图看首帧显示

```text
App 进程                         system_server / SF / HWC
---------------------------------------------------------------
WindowManager.addView()
  -> ViewRootImpl.setView()
      -> Binder 调用 WMS
                                 WMS 创建 WindowState
                                 WMS 创建/更新 SurfaceControl
                                 SF 内部准备 Layer

App 首次 measure/layout/draw
RenderThread / GPU 渲染
eglSwapBuffers()
  -> queueBuffer()
                                 SF 收到该 Layer 有新 Buffer

                                 等待下一次 VSync
                                 VSync 到来
                                 SF 遍历所有 Layer
                                 SF latch 新 Buffer
                                 SF 调用 HWC 校验合成方案
                                 HWC 返回 Device/Client Composition
                                 SF 执行合成
                                 present 到 Display

屏幕显示第一帧
```

---

## 六、为什么一定要经过 SurfaceFlinger

如果没有 `SurfaceFlinger`，每个 App 都直接往屏幕写，会出现几个问题：

- 多窗口内容无法统一排序
- 状态栏、导航栏、弹窗无法统一叠加
- 容易画面撕裂
- 无法统一跟随 `VSync`
- 硬件合成能力无法集中利用

所以 Android 采用的是：

```text
每个生产者只负责自己的 Buffer
由 SurfaceFlinger 做统一消费与合成
```

这是显示系统能稳定工作的关键。

---

## 七、SurfaceFlinger 和 WMS 的分工

这两个类经常一起出现，但职责不同。

| 组件 | 核心职责 |
|---|---|
| `WindowManagerService` | 管窗口，决定谁在前谁在后、窗口大小、焦点、动画、可见性 |
| `SurfaceFlinger` | 管图层合成，决定这一帧怎么把各个 Layer 拼起来并显示 |

可以记一句：

```text
WMS 决定“应该怎么摆”
SF 决定“这一帧怎么画出来”
```

---

## 八、SurfaceFlinger 和 HWC 的关系

`SurfaceFlinger` 不是直接操作显示面板的所有细节，它会和 `HWC` 配合。

### SurfaceFlinger 负责

- 维护 Layer 树
- 收集 Buffer
- 做合成决策
- 在需要时走 GPU 合成

### HWC 负责

- 利用显示硬件的 Overlay/Plane 能力
- 决定哪些层可以直接硬件合成
- 最终把结果 present 到屏幕

所以两者关系更像：

```text
SurfaceFlinger = 合成调度者
HWC = 显示硬件执行者
```

---

## 九、常见面试点

### 1. `queueBuffer` 之后为什么不是立刻显示

因为显示要跟随 `VSync` 节奏，`queueBuffer` 只是把新帧放进 `BufferQueue`，要等 `SurfaceFlinger` 在下一次合成周期中消费。

### 2. `SurfaceFlinger` 为什么能减少撕裂

因为它统一在显示节奏下做 Buffer latch 和合成，避免 App 在屏幕扫描过程中直接改正在显示的数据。

### 3. 为什么有时走 HWC，有时走 GPU

因为不是所有图层场景都适合硬件 Overlay。复杂混合、缩放、透明、特效较多时，通常需要 client composition。

### 4. 第一帧慢通常卡在哪

常见位置：

- App 首次布局和绘制慢
- GPU 首帧渲染慢
- Buffer 还没准备好就错过了本次 `VSync`
- `SurfaceFlinger` 合成负载高

---

## 十、一句话总结

`SurfaceFlinger` 是 Android 显示系统中的统一合成器：它消费各个窗口提交的 Buffer，在 `VSync` 节奏下联合 `HWC` 或 GPU 完成图层合成，并把最终结果显示到屏幕上。

如果你从首帧链路来记，可以直接记成：

```text
WMS 建窗口和 Layer 控制信息
App 渲染并 queueBuffer
SF 在 VSync 到来时收集所有 Layer
HWC/GPU 完成合成
屏幕显示新的一帧
```
