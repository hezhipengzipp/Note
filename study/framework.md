# Android Framework 原理（P0 资深必考）

> 10年经验面试，Framework 层是拉开差距的关键。面试官会从应用层追到系统层。

---

## 一、系统启动流程

```
Boot ROM → Bootloader → Linux Kernel
    │
    ▼
init 进程（pid=1）
    ├── 解析 init.rc
    ├── 启动 ServiceManager（Binder 大管家）
    └── fork Zygote 进程
            │
            ▼
        Zygote 进程
            ├── 预加载类和资源（加速 App 启动）
            ├── 创建 JVM（art/dalvik）
            ├── 启动 SystemServer
            │       │
            │       ▼
            │   SystemServer 进程
            │       ├── ActivityManagerService (AMS)
            │       ├── WindowManagerService (WMS)
            │       ├── PackageManagerService (PMS)
            │       ├── PowerManagerService
            │       └── ... 90+ 系统服务
            │
            └── 等待 Socket 连接，fork 应用进程
```

**面试要点**：
- init 是用户空间第一个进程，Zygote 是 Java 世界的第一个进程
- Zygote 用 **fork** 而不是 exec，子进程继承父进程的预加载资源（共享内存，Copy-on-Write）
- Zygote 和 AMS 之间通过 **Socket** 通信（不用 Binder，因为 fork 后 Binder 线程不会复制）

---

## 二、App 启动流程（冷启动全链路）

```
点击图标
    │
    ▼
Launcher → startActivity()
    │
    ▼
AMS（通过 Binder 跨进程）
    ├── 检查权限、解析 Intent
    ├── 目标进程不存在？→ 通知 Zygote fork 新进程
    │
    ▼
Zygote fork 新进程
    │
    ▼
ActivityThread.main()        ← App 进程入口
    ├── Looper.prepareMainLooper()
    ├── 创建 ActivityThread 实例
    ├── thread.attach() → 通过 Binder 告诉 AMS "我启动了"
    │
    ▼
AMS 回调
    ├── bindApplication → Application.onCreate()
    ├── scheduleLaunchActivity → Activity 创建
    │       │
    │       ▼
    │   handleLaunchActivity()
    │       ├── Activity.onCreate()
    │       ├── Activity.onStart()
    │       └── Activity.onResume()
    │               │
    │               ▼
    │           ViewRootImpl.performTraversals()
    │               → measure → layout → draw → 首帧渲染
    │
    ▼
用户看到界面
```

---

## 三、AMS（ActivityManagerService）

### 核心职责
- 管理四大组件的生命周期
- 管理进程（优先级、回收）
- 管理任务栈（Task / Back Stack）

### Activity 启动流程中 AMS 的角色

```
App 进程                              system_server 进程
   │                                        │
   │  startActivity()                       │
   │ ──── Binder IPC ────────────────────> │
   │                                   AMS.startActivity()
   │                                        │
   │                                   解析 Intent
   │                                   权限检查
   │                                   查找/创建 Task
   │                                   暂停当前 Activity
   │                                        │
   │ <── schedulePauseActivity ──────────  │
   │  onPause()                             │
   │ ──── activityPaused ────────────────> │
   │                                        │
   │                                   目标进程存在？
   │                                        │
   │ <── scheduleLaunchActivity ─────────  │
   │  创建 Activity，走生命周期               │
```

### 进程优先级（OOM_ADJ）

| 优先级 | 类型 | 说明 |
|--------|------|------|
| 最高 | 前台进程 | 正在交互的 Activity、前台 Service |
| 高 | 可见进程 | 可见但不可交互（如弹出对话框的背景 Activity） |
| 中 | 服务进程 | 运行着 Service |
| 低 | 后台进程 | onStop 的 Activity |
| 最低 | 空进程 | 没有活跃组件，缓存用 |

- LowMemoryKiller 根据 OOM_ADJ 值回收进程
- 保活手段：前台 Service、双进程守护、JobScheduler（但系统限制越来越严格）

---

## 四、WMS（WindowManagerService）

### Window 的类型

| 类型 | 层级 | 示例 |
|------|------|------|
| Application Window | 1-99 | Activity |
| Sub Window | 1000-1999 | PopupWindow、Dialog |
| System Window | 2000-2999 | Toast、状态栏、导航栏 |

### Window 添加流程

```
Activity.setContentView()
    │
    ▼
PhoneWindow.setContentView()
    ├── installDecor() → 创建 DecorView
    └── 将布局 inflate 到 DecorView 的 ContentParent 中
    
Activity.onResume() 之后
    │
    ▼
WindowManagerImpl.addView(decorView, params)
    │
    ▼
WindowManagerGlobal.addView()
    ├── 创建 ViewRootImpl
    └── ViewRootImpl.setView()
            │
            ├── requestLayout() → 触发绘制
            └── Session.addToDisplay() → Binder 到 WMS
                    │
                    ▼
                WMS.addWindow()
                    ├── 创建 WindowState
                    ├── 计算窗口层级
                    └── 分配 Surface
```

### 面试常问
- **Activity、Window、DecorView 的关系**：Activity 持有 PhoneWindow，PhoneWindow 持有 DecorView，DecorView 是 View 树的根
- **Dialog 必须用 Activity Context**：因为 Dialog 的 Window 类型是子窗口，需要依附于 Activity 的 Window（Token 校验）
- **Toast 在子线程**：需要 Looper，因为 Toast 内部用 Handler 处理显示/隐藏

---

## 五、Binder 机制（重中之重）

### 为什么用 Binder 而不用传统 IPC

| 方式 | 拷贝次数 | 安全性 | 特点 |
|------|---------|--------|------|
| 共享内存 | 0 | 低 | 需要自己做同步 |
| Binder | 1 | 高（UID/PID校验） | 性能好 + 安全 |
| Socket/管道 | 2 | 低 | 通用但开销大 |

### Binder 通信原理

```
Client 进程            Binder 驱动（内核空间）         Server 进程
    │                        │                          │
    │  transact()            │                          │
    │ ───────────────────>   │                          │
    │                        │  copy_from_user()        │
    │                        │  数据从 Client 拷贝到     │
    │                        │  内核缓冲区               │
    │                        │                          │
    │                        │  mmap 映射到 Server 空间   │
    │                        │ ─────────────────────>   │
    │                        │                     onTransact()
    │                        │                          │
    │                        │ <─────────────────────   │
    │ <───────────────────   │  返回结果                 │
```

**关键点**：
- `mmap` 实现一次拷贝：内核缓冲区和 Server 端的接收缓冲区映射到同一块物理内存
- `ServiceManager`：Binder 的"DNS"，负责注册和查询服务
- **AIDL 生成的代码**：Stub（服务端，继承 Binder）、Proxy（客户端，持有 BinderProxy）

### AIDL 核心流程

```java
// 客户端调用
proxy.getData()
    → Parcel 序列化参数
    → mRemote.transact(CODE, data, reply, 0)  // 跨进程
    → 阻塞等待结果

// 服务端接收
Stub.onTransact(code, data, reply, flags)
    → 反序列化参数
    → 调用实际方法 getData()
    → 序列化结果写入 reply
```

---

## 六、Handler 机制的 Framework 层

> 应用层 Handler 在 [android.md](android.md) 中，这里看更底层的部分

### Native 层消息机制

```
Java 层                              Native 层
MessageQueue                        NativeMessageQueue
    │                                      │
    nativePollOnce()  ──────────>     Looper::pollOnce()
    │                                      │
    │                                 epoll_wait()  ← 阻塞等待
    │                                      │
    nativeWake()  ───────────────>    Looper::wake()
    │                                      │
    │                                 write(mWakeEventFd)  ← 唤醒
```

- **epoll 机制**：Linux IO 多路复用，高效监听多个文件描述符
- **为什么主线程 Looper.loop() 不会 ANR？**：epoll_wait 让出 CPU，不消耗资源；有消息时才唤醒处理

---

## 七、屏幕刷新机制（Choreographer）

```
VSync 信号（硬件每 16.6ms 一次）
    │
    ▼
Choreographer.doFrame()
    │
    ├── 处理 INPUT 事件
    ├── 处理 ANIMATION
    ├── 处理 TRAVERSAL（measure/layout/draw）
    │       │
    │       ▼
    │   ViewRootImpl.performTraversals()
    │       │
    │       ▼
    │   draw → Canvas → 提交到 SurfaceFlinger
    │
    ▼
SurfaceFlinger 合成图层 → 显示
```

**双缓冲/三缓冲**：
- 双缓冲：Front Buffer（显示）+ Back Buffer（绘制），VSync 时交换
- 三缓冲：增加一个缓冲区，减少 Jank（掉帧）

---

## 八、PackageManagerService（PMS）

### APK 安装流程
```
拷贝 APK 到 /data/app/
    │
    ▼
解析 AndroidManifest.xml
    ├── 提取四大组件信息
    ├── 提取权限声明
    └── 注册到 PMS
    │
    ▼
dex2oat 编译 → 生成 oat 文件（AOT 编译）
    │
    ▼
创建数据目录 /data/data/<package>/
    │
    ▼
发送 ACTION_PACKAGE_ADDED 广播
```

### 应用场景
- **插件化**：绕过 PMS 的组件注册限制（Hook Instrumentation / AMS）
- **热修复**：替换 ClassLoader 的 dexElements 或 Hook 方法

---

## 九、面试高频追问

**Q: 为什么 Activity 启动要跨进程到 AMS？**
> Activity 的生命周期由系统统一管理（栈管理、进程优先级调整、ANR 监控等），不能由应用自己管理。

**Q: 为什么 Zygote 用 Socket 不用 Binder？**
> fork 不会复制 Binder 线程池，子进程的 Binder 通信会有问题。而且 Zygote 先于 ServiceManager 启动。

**Q: 应用的 ANR 是怎么检测的？**
> AMS 在发起组件操作时埋一个定时炸弹（Handler.sendMessageDelayed），如果组件没有在规定时间内完成（Activity 5s、BroadcastReceiver 10s、Service 20s），就触发 ANR 弹窗。

**Q: Surface 和 SurfaceFlinger 的关系？**
> 每个 Window 对应一个 Surface（绘制缓冲区），SurfaceFlinger 负责合成所有 Surface 的内容，通过 Hardware Composer 送显。
