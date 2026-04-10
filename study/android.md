# Android 核心知识点（P0 必考）

---

## 一、四大组件

### 1. Activity

#### 生命周期
```
onCreate → onStart → onResume → [运行中] → onPause → onStop → onDestroy
                                              │
                                              ▼ (被部分遮挡)
                                           onPause → onResume
                                              │
                                              ▼ (被完全遮挡/回到前台)
                                     onStop → onRestart → onStart → onResume
```

#### 面试重点
- **onSaveInstanceState / onRestoreInstanceState**：系统回收时保存状态，Bundle 机制
- **横竖屏切换**：默认销毁重建，`configChanges` 配置可避免
- **A 启动 B 的生命周期顺序**：A.onPause → B.onCreate → B.onStart → B.onResume → A.onStop

#### 启动模式（launchMode）

| 模式 | 特点 | 场景 |
|------|------|------|
| standard | 每次新建实例 | 默认 |
| singleTop | 栈顶复用，走 onNewIntent | 通知点击、搜索页 |
| singleTask | 栈内复用，清除其上所有 Activity | 首页 |
| singleInstance | 独占一个任务栈 | 来电页、地图导航 |

- **taskAffinity** 配合 singleTask 使用，决定归属哪个任务栈
- **Intent Flags**：`FLAG_ACTIVITY_NEW_TASK`、`FLAG_ACTIVITY_CLEAR_TOP` 等

### 2. Service

- **startService**：独立运行，手动 stopSelf / stopService
- **bindService**：绑定生命周期，通过 Binder 通信，所有绑定者解绑后销毁
- **前台 Service**：`startForeground()` 提升优先级，Android 8.0+ 必须用 `startForegroundService()`
- **IntentService**：内部 HandlerThread，串行执行任务，执行完自动停止（已废弃，推荐 WorkManager）
- **Android 12+ 限制**：后台启动前台服务受限，需要 `FOREGROUND_SERVICE` 权限

### 3. BroadcastReceiver

- **静态注册 vs 动态注册**：静态注册在 Manifest 中，部分广播 Android 8.0+ 不再支持静态注册
- **有序广播**：按优先级分发，可拦截
- **本地广播**：`LocalBroadcastManager`（已废弃，推荐 LiveData / Flow）

### 4. ContentProvider

- **跨进程数据共享**的标准方案
- **启动时机**：在 Application.onCreate() 之前初始化（很多库利用此特点做初始化，如 Jetpack App Startup）
- **底层通过 Binder 通信**

---

## 二、View 体系（高频重点）

### 1. View 绘制流程

```
ViewRootImpl.performTraversals()
    │
    ├── performMeasure()   → View.measure() → onMeasure()
    │       MeasureSpec = 父容器约束 + 自身 LayoutParams
    │       模式：EXACTLY / AT_MOST / UNSPECIFIED
    │
    ├── performLayout()    → View.layout() → onLayout()
    │       确定 View 的 left/top/right/bottom
    │
    └── performDraw()      → View.draw() → onDraw()
            绘制顺序：背景 → 自身(onDraw) → 子View(dispatchDraw) → 装饰(滚动条等)
```

**面试常问**：
- `getMeasuredWidth()` vs `getWidth()`：前者 measure 后可用，后者 layout 后可用
- `requestLayout()` vs `invalidate()`：前者触发 measure+layout+draw，后者只触发 draw
- 自定义 View 中 `onMeasure()` 的 MeasureSpec 处理

### 2. 事件分发机制

```
Activity.dispatchTouchEvent()
    │
    ▼
ViewGroup.dispatchTouchEvent()
    │
    ├── onInterceptTouchEvent()  → 返回 true 拦截，自己处理
    │
    └── 遍历子 View → child.dispatchTouchEvent()
                            │
                            └── View.onTouchEvent()
                                    │
                                    └── 返回 true 消费，返回 false 回传给父 View
```

**关键结论**：
- 事件传递：Activity → Window → DecorView → ViewGroup → View
- 消费回传：View → ViewGroup → Activity
- `onInterceptTouchEvent` 只有 ViewGroup 有
- 一旦某个 View 消费了 DOWN 事件，后续 MOVE/UP 都直接给它
- `requestDisallowInterceptTouchEvent(true)` 子 View 请求父 View 不要拦截

### 3. RecyclerView（必考）

- **四级缓存**：
  1. `mAttachedScrap` / `mChangedScrap`：屏幕内，不需要 rebind
  2. `mCachedViews`：刚滑出屏幕的，默认缓存 2 个，不需要 rebind
  3. `ViewCacheExtension`：自定义缓存（很少用）
  4. `RecycledViewPool`：按 ViewType 缓存，需要 rebind

- **性能优化**：
  - `DiffUtil` 做局部更新
  - `setHasFixedSize(true)` 避免 requestLayout
  - 预加载：`setItemPrefetchEnabled`
  - 共享 RecycledViewPool

---

## 三、Handler 机制（必考，默写级别）

### 核心组件

```
Handler → 发送消息
    │
    ▼
MessageQueue → 消息队列（单链表，按 when 排序）
    │
    ▼
Looper → 循环取消息（loop()）
    │
    ▼
Handler.dispatchMessage() → handleMessage() 处理
```

### 关键问题

**Q: 一个线程能有几个 Looper？**
> 一个。ThreadLocal 保证，`Looper.prepare()` 重复调用会抛异常。

**Q: Handler 内存泄漏原因和解决？**
> 非静态内部类持有 Activity 引用，Message 持有 Handler 引用，MessageQueue 持有 Message。
> 解决：静态内部类 + WeakReference，或在 onDestroy 中 removeCallbacksAndMessages(null)。

**Q: MessageQueue 的阻塞唤醒机制？**
> 基于 Linux 的 epoll 机制。没有消息时 `nativePollOnce()` 阻塞，有新消息时 `nativeWake()` 唤醒。

**Q: 同步屏障（Sync Barrier）？**
> `postSyncBarrier()` 插入一个 target 为 null 的 Message，之后 MessageQueue 只处理异步消息。
> 用途：ViewRootImpl 的 `scheduleTraversals()` 使用，保证 UI 绘制优先执行。

**Q: IdleHandler 是什么？**
> MessageQueue 空闲时执行的回调。适合做延迟初始化、GC 等低优先级任务。

---

## 四、性能优化（资深必考）

### 1. 启动优化

- **启动类型**：冷启动、温启动、热启动
- **测量方式**：`adb shell am start -W`、Systrace、`reportFullyDrawn()`
- **优化手段**：
  - 任务分级：主线程只做必要初始化，其余异步/延迟
  - 启动框架：有向无环图（DAG）拓扑排序，并行初始化
  - 减少 Application.onCreate 耗时
  - 避免主线程 IO
  - 布局优化：减少层级、延迟加载（ViewStub）

### 2. 内存优化

- **内存泄漏检测**：LeakCanary 原理（WeakReference + ReferenceQueue）
- **常见泄漏场景**：Handler、静态持有 Context、未注销监听、单例持有 Activity
- **Bitmap 优化**：inSampleSize 采样、inBitmap 复用、RGB_565 格式
- **内存抖动**：频繁创建销毁对象 → GC 频繁 → 卡顿，用对象池解决

### 3. 卡顿优化

- **根因**：主线程单帧超过 16.6ms（60fps）
- **工具**：Systrace、Perfetto、BlockCanary（Looper.printer 监控）
- **优化方向**：
  - 布局层级优化（ConstraintLayout 减少嵌套）
  - 主线程避免 IO、避免锁竞争
  - 列表优化（RecyclerView 缓存策略）
  - 异步布局（AsyncLayoutInflater）

### 4. 包体积优化

- ProGuard / R8 混淆与优化
- 资源压缩：`shrinkResources`、WebP 替代 PNG
- So 库按 ABI 分包（arm64-v8a 优先）
- 动态下发：Feature On Demand、插件化

### 5. 网络优化

- 连接复用（HTTP/2 多路复用）
- 数据压缩（Gzip、Protocol Buffers）
- 缓存策略（OkHttp Cache / 自定义缓存）
- 弱网优化：重试策略、降级方案

---

## 五、Jetpack 组件原理

### 1. Lifecycle

- **实现原理**：通过注入一个空的 `ReportFragment`，在其生命周期回调中分发事件
- **观察者模式**：LifecycleOwner（被观察者）+ LifecycleObserver（观察者）

### 2. ViewModel

- **存储位置**：`ViewModelStore`（存在 Activity/Fragment 的 `NonConfigurationInstances` 中）
- **为什么横竖屏不销毁**：`onRetainNonConfigurationInstance()` 在销毁前保存，重建后恢复
- **作用域**：Activity 级别 / Fragment 级别 / Navigation Graph 级别

### 3. LiveData

- **粘性事件问题**：新观察者会收到最后一次 setValue 的数据（version 机制）
- **解决方案**：SingleLiveEvent / Event 包装器 / SharedFlow
- **和 Flow 的对比**：LiveData 感知生命周期但功能有限；Flow 功能强大但需要手动管理生命周期（`repeatOnLifecycle`）

### 4. Compose（趋势，建议了解）

- **声明式 UI**：描述 UI 应该是什么样，而不是怎么变
- **重组（Recomposition）**：状态变化时只重新执行受影响的 Composable 函数
- **Slot Table**：Compose 运行时数据结构，存储 Composable 的状态和组合结果
- **和传统 View 对比**：没有 XML、没有 findViewById、没有 View 层级

---

## 六、进程间通信（IPC）

| 方式 | 特点 | 场景 |
|------|------|------|
| Binder | Android 特有，高效（一次拷贝），C/S 架构 | AIDL、系统服务 |
| Socket | 通用，跨网络 | Zygote 通信 |
| 共享内存 | 零拷贝，最快 | 大数据传输 |
| ContentProvider | 标准化接口 | 跨进程数据共享 |
| Messenger | 基于 Binder 的轻量方案 | 简单 IPC |
| 文件/MMKV | 简单但需同步 | 配置共享 |

### Binder 机制简述
- **用户空间不能直接访问另一个进程**，需要通过内核空间中转
- Binder 驱动在内核空间，通过 `mmap` 在内核和接收方之间建立映射，实现一次拷贝
- **AIDL** 是生成 Binder 通信模板代码的工具，核心是 `Stub`（服务端）和 `Proxy`（客户端）

---

## 七、开源框架原理（选择性深入）

### OkHttp
- **拦截器链**：责任链模式，RetryAndFollowUp → Bridge → Cache → Connect → CallServer
- **连接池**：复用 TCP 连接，默认 5 个空闲连接，5 分钟超时

### Retrofit
- **动态代理**：`Proxy.newProxyInstance()` 解析注解生成请求
- 本质是 OkHttp 的封装，通过 Converter 和 CallAdapter 实现解耦

### Glide
- **三级缓存**：活跃资源（WeakReference）→ LruCache → 磁盘缓存
- **生命周期绑定**：注入空 Fragment 监听，和 Lifecycle 类似的思路
- **BitmapPool**：Bitmap 复用，减少内存分配

### LeakCanary
- **原理**：Activity onDestroy 后，用 WeakReference 包裹，延迟 5s 检查 ReferenceQueue，如果没被回收就可能泄漏
- 通过 `Debug.dumpHprofData()` dump 内存，shark 库分析引用链
