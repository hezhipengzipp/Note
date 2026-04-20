# Android 频繁 GC 导致卡顿详解

## 一、核心原因：STW（Stop The World）

GC 执行时，**所有应用线程（包括主线程）必须暂停**，等 GC 完成才能继续。对主线程而言，这段暂停就是"卡住"。

```
理想帧（60 fps，预算 16.67ms）
┌──────────────────────────────┐
│ 处理输入 │ 动画 │ 测量/绘制 │ 上屏 │
└──────────────────────────────┘
   ✅ 按时显示下一帧

频繁 GC 时
┌────────────┬━━━━GC STW━━━━┬─────┐
│ 处理输入   │ 主线程暂停   │绘制│ ... 来不及
└────────────┴━━━━━━━━━━━━━━┴─────┘
     5ms         12ms         超了
                               │
                               ▼
                          ⚠️ 丢帧 / 卡顿
```

---

## 二、ART GC 的暂停阶段

Android 5.0+ 的 ART 使用 **Concurrent Copying GC（CC GC）**，大部分并发，但仍有两次 STW：

```
┌─────────────────────────────────────────────────────────┐
│ 阶段                         │ STW？│ 耗时             │
├─────────────────────────────────────────────────────────┤
│ 1. 初始标记 (Initial Mark)   │ ✅   │ 1-3ms            │
│ 2. 并发标记 (Concurrent)     │ ❌   │ 与 app 并行       │
│ 3. 重标记 (Remark/Pause)     │ ✅   │ 2-10ms (关键!)   │
│ 4. 并发清理/复制              │ ❌   │ 与 app 并行       │
└─────────────────────────────────────────────────────────┘
```

**单次 GC 暂停 10ms 看似小，但一帧只有 16.67ms，连续几次 GC 就爆了**。

### GC 触发类型

| 类型               | 触发时机                      | STW 代价     |
| ------------------ | ----------------------------- | ------------ |
| `Background GC`    | 空闲时后台主动回收             | 最轻        |
| `Concurrent GC`    | 分配达到阈值                  | 轻          |
| `Alloc GC`         | 分配失败时同步触发              | 中（明显卡顿）|
| `Explicit GC`      | `System.gc()` 调用            | 重          |
| `Full GC`          | 大对象 OOM 前兜底              | 最重（~100ms+）|

---

## 三、卡顿的四个维度

### 1. STW 直接打断主线程

主线程在 `onDraw`、`onMeasure` 中分配对象触发 GC → 主线程自己就被暂停。

### 2. CPU 被 GC 线程抢占

GC 不是免费的，扫描+复制对象要耗 CPU。主线程即使没 STW，也会因 CPU 争抢而变慢。

### 3. 内存抖动（Memory Churn）

```
分配 → 进入 Young Gen → 短时间内回收
  ↑                                │
  └────── 频繁循环 ────────────────┘
                │
                ▼
       Young GC 触发频率激增
       每次小 GC 也有 STW
```

**Android Profiler 看 Memory 曲线呈锯齿状** = 典型内存抖动。

### 4. Full GC 雪崩

大对象（Bitmap）分配失败 → 触发 Full GC → 堆整理 → 可能 STW 几十到几百 ms，直接出现明显卡顿甚至 ANR。

---

## 四、日志如何识别

```
Logcat 过滤 "art" 或 "GC"：

I/art: Background concurrent copying GC freed 2345(118KB) AllocSpace objects,
       3(60KB) LOS objects, 49% free, 3MB/6MB,
       paused 1.234ms total 45.678ms
                 ↑              ↑
              STW 时间       整个 GC 耗时
```

**关键字段**：
- `paused` — STW 时间，**超过 5ms 要警惕**
- `Alloc GC` — 分配失败触发（最差）
- `Explicit` — 代码调用 `System.gc()` 触发（禁止！）
- `Background` — 后台并发（最轻）
- `LOS` — Large Object Space（大对象空间，Bitmap 常在这）

### 脚本化分析

```bash
adb logcat | grep -E "art.*GC" | awk '{print $(NF-2), $(NF-3)}'
```

---

## 五、代码层常见 GC 触发源

| 反模式                          | 正确做法                         |
| ------------------------------- | -------------------------------- |
| `onDraw` 里 `new Paint()`       | 成员变量复用                     |
| 循环里 `str1 + str2`            | `StringBuilder`                  |
| 频繁 autoboxing `Integer`       | 用基本类型 `int`                 |
| `getViewById` 每帧调用           | `ViewHolder` 缓存                |
| 频繁创建 `Handler.post(Runnable)` | 复用 Runnable                  |
| `for (T t : list)` 对数组使用    | 改 `for (int i...)` 避免 Iterator|
| Bitmap 反复 decode               | `BitmapPool` / Glide             |
| `HashMap<Integer, X>`            | `SparseArray<X>`（免装箱）       |
| lambda 捕获外部变量              | 用 method reference              |
| 匿名内部类持有外部               | 抽出 static class                |

### 典型例子：onDraw 地雷

```kotlin
// ❌ 每次绘制都 new 对象
override fun onDraw(canvas: Canvas) {
    val paint = Paint()                    // GC 地雷
    paint.color = Color.RED
    val rect = Rect(0, 0, 100, 100)       // GC 地雷
    canvas.drawRect(rect, paint)
}

// ✅ 成员变量复用
private val paint = Paint().apply { color = Color.RED }
private val rect = Rect()

override fun onDraw(canvas: Canvas) {
    rect.set(0, 0, 100, 100)
    canvas.drawRect(rect, paint)
}
```

60fps 下第一种写法一秒 new 60 次 `Paint`、60 次 `Rect`，是内存抖动的典型来源。

### 典型例子：RecyclerView onBindViewHolder

```kotlin
// ❌ 每次 bind 都 new
override fun onBindViewHolder(holder: VH, position: Int) {
    val formatter = SimpleDateFormat("yyyy-MM-dd")  // 重型对象
    holder.tv.text = formatter.format(data[position].date)
}

// ✅ 共享实例
companion object {
    private val FORMATTER = SimpleDateFormat("yyyy-MM-dd")
}
override fun onBindViewHolder(holder: VH, position: Int) {
    holder.tv.text = FORMATTER.format(data[position].date)
}
```

---

## 六、排查工具

| 工具                           | 看什么                          |
| ------------------------------ | ------------------------------- |
| **Android Profiler → Memory** | Allocation 曲线是否锯齿状       |
| **Logcat 过滤 `art`**         | GC 次数、paused 时间            |
| **Record Memory Allocations** | 找出频繁分配的对象类型          |
| **Perfetto**                  | 找 HeapTaskDaemon 线程活动时段  |
| **LeakCanary**                | 内存泄漏导致 GC 压力            |
| **JankStats**                 | 帧耗时中 GC 贡献                |

### Profiler 识别内存抖动

```
内存曲线类型：
┌──────────────────────────┐
│   正常：缓慢上升           │
│   └─▲───▲───▲─── 平稳     │
├──────────────────────────┤
│   抖动：高频锯齿           │
│   ┐▲┐▲┐▲┐▲┐▲┐▲┐▲ 不正常  │
└──────────────────────────┘
```

### Perfetto 定位 GC

```
主线程时间轴
[正常执行]──[GC STW 空白]──[继续]
                ↑
           HeapTaskDaemon
           同时段在活跃
```

---

## 七、实战优化案例

### 案例 1：滑动列表卡顿

**现象**：RecyclerView 滑动帧率 30 fps 左右

**Profiler**：Memory 曲线锯齿状，每秒几次 GC

**定位**：onBindViewHolder 里每次都 `new SimpleDateFormat` + 字符串拼接

**优化**：格式化器单例 + StringBuilder 复用，帧率回到 58 fps

### 案例 2：首页动画卡顿

**现象**：动画过程中偶发掉帧

**Logcat**：动画期间大量 `art: Alloc concurrent copying GC`

**定位**：ValueAnimator.addUpdateListener 里每帧 `new Matrix()`

**优化**：Matrix 成员变量化，卡顿消失

---

## 八、面试题

### Q1：为什么并发 GC 还是会卡顿？

并发 GC 的"并发"指**标记和清理阶段**与 app 线程并行，但 **初始标记和重标记必须 STW**，主线程仍会被暂停。高频分配对象会让 GC 频繁触发，累积暂停时间，最终表现为卡顿。

### Q2：System.gc() 能主动减少卡顿吗？

**不能，反而更差**。`System.gc()` 触发的是 **Full GC**（Explicit GC），STW 时间最长。生产代码应禁用。真正要优化是**减少对象分配频率**。

### Q3：Bitmap 内存不在 Java 堆上，也会触发 Java GC 吗？

- **Android 8.0 以下**：Bitmap 像素在 Native 堆，Java 堆只有引用头，回收慢
- **Android 8.0+**：Bitmap 像素回到 Java 堆（LOS 大对象空间），分配/释放直接触发 Java GC

所以 8.0+ 后频繁 decode Bitmap 对 GC 压力更大，要用 BitmapPool。

### Q4：如何区分是 GC 卡顿还是其他卡顿？

1. Profiler Memory 面板看是否锯齿状
2. Logcat `paused` 时间是否和卡顿时刻吻合
3. Perfetto 看主线程暂停时段是否对应 HeapTaskDaemon 活动
4. 如果 GC 频繁但暂停总时长小 → 可能是 CPU 抢占而非 STW

### Q5：GC Roots 有哪些？

- 当前栈帧的局部变量、参数
- 所有活动线程的栈
- 静态字段引用的对象
- JNI 全局引用
- 同步锁持有的对象

内存泄漏本质就是"不该活的对象被 GC Root 间接引用"。

### Q6：ART 的 CC GC 和 Dalvik 的 CMS GC 区别？

| 维度          | Dalvik CMS     | ART CC GC               |
| ------------- | -------------- | ----------------------- |
| 堆整理        | ❌              | ✅（复制式，减少碎片）    |
| STW 时间      | 较长           | 短得多（通常 < 5ms）     |
| 吞吐          | 低             | 高                      |
| 后台 GC       | 基本没有        | 支持                    |
| 版本          | Android 4.x    | Android 5.0+            |

### Q7：内存抖动和内存泄漏的区别？

- **内存抖动**：短时间大量分配 + 回收，总内存占用不增加，但 GC 频繁引发卡顿
- **内存泄漏**：对象该回收没回收，总内存持续增长，最终 OOM

抖动表现为**曲线锯齿状**，泄漏表现为**曲线持续上升**。

### Q8：哪些情况不算 GC 卡顿？

- **冷启动前几秒**：热身期间本来 GC 就多，正常
- **进程切换场景**：系统级 GC 与 app 无关
- **低端机基础负载**：堆小、GC 天然频繁
- **Full GC < 30ms**：单次可接受

要结合上下文判断，不能一看到 GC 就优化。

### Q9：GC 时其他线程还能跑吗？

- **STW 阶段**：所有 app 线程暂停
- **并发阶段**：
  - Java 线程：可以跑，但会有读写屏障（Read/Write Barrier）的额外开销
  - Native 线程：完全不受影响

所以密集 JNI 调用的应用 GC 影响反而小。

### Q10：怎么在 CI 里自动化检测内存抖动？

1. Benchmark 框架（如 Jetpack Benchmark）跑关键场景
2. 采集 `alloc count`、`GC count`、`paused time`
3. 与基线对比，超阈值则 fail
4. Matrix Trace Canary 也支持输出 alloc 指标

前提是有稳定的测试脚本和硬件环境。
