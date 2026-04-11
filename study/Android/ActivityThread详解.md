# ActivityThread 详解

## 一、是什么

ActivityThread 是 **Android 应用的主线程入口**，每个 App 进程启动时，就是从 `ActivityThread.main()` 开始执行的。虽然名字里有"Thread"，但它本身**不是一个线程**，而是运行在主线程上的一个管理类。

## 二、在哪一步被创建

```
点击 App 图标
    │
    ▼
Launcher 通知 AMS（ActivityManagerService）
    │
    ▼
AMS 发现目标 App 进程不存在
    │
    ▼
Zygote fork 出新进程
    │
    ▼
新进程执行 ActivityThread.main()    ← 一切从这里开始
    │
    ├── 1. 创建主线程 Looper（Looper.prepareMainLooper()）
    ├── 2. 创建 ActivityThread 实例
    ├── 3. 关联 ApplicationThread（Binder 通信桩）
    ├── 4. 通知 AMS "我准备好了"
    └── 5. 开启消息循环（Looper.loop()）← 主线程永远在这里转
```

```java
// 简化版 ActivityThread.main()
public static void main(String[] args) {
    Looper.prepareMainLooper();          // 1. 创建主线程 Looper

    ActivityThread thread = new ActivityThread();  // 2. 创建实例
    thread.attach(false);                // 3. 关联 AMS

    Looper.loop();                       // 4. 开启消息循环，永不退出

    throw new RuntimeException("Main thread loop unexpectedly exited");
}
```

## 三、核心职责

```
┌─────────────────────────────────────────────────────┐
│                   ActivityThread                     │
│                                                     │
│  1. App 进程的入口（main 方法）                        │
│  2. 管理四大组件的生命周期                              │
│  3. 主线程消息循环的驱动者                              │
│  4. 通过 ApplicationThread 接收 AMS 的指令             │
└─────────────────────────────────────────────────────┘
```

## 四、与 AMS 的通信机制

```
App 进程                                    system_server 进程
┌──────────────────────┐                  ┌──────────────┐
│  ActivityThread       │                  │  AMS          │
│    │                  │                  │              │
│    │  持有             │     Binder       │              │
│    ├── ApplicationThread ─────────────→  │  AMS 调用它   │
│    │   (Binder Stub)  │    （AMS → App） │  来控制 App   │
│    │                  │                  │              │
│    ├── AMS 代理  ←──────────────────────  │              │
│    │   (Binder Proxy) │    （App → AMS） │              │
└──────────────────────┘                  └──────────────┘

ApplicationThread：AMS 通知 App 的通道（"该启动 Activity 了"）
AMS 代理：App 请求 AMS 的通道（"我要启动另一个 Activity"）
```

## 五、四大组件生命周期调度

AMS 通过 ApplicationThread 发来指令，最终转成主线程的 Handler 消息：

```
AMS 发来指令："启动 ActivityA"
    │
    ▼
ApplicationThread.scheduleLaunchActivity()  ← Binder 线程收到
    │
    ▼
发送 Message 到主线程 Handler（H）
    │
    ▼
主线程 Handler 处理消息
    │
    ▼
ActivityThread.handleLaunchActivity()
    │
    ├── performLaunchActivity()
    │      ├── Instrumentation.newActivity()   ← 反射创建 Activity 实例
    │      ├── Activity.attach()               ← 关联 Window、Context
    │      └── Activity.onCreate()             ← 生命周期回调
    │
    └── handleResumeActivity()
           ├── Activity.onResume()
           └── WindowManager.addView()         ← 界面上屏
```

**不只是 Activity，四大组件都由 ActivityThread 调度**：

```
ActivityThread
    │
    ├── handleLaunchActivity()      → Activity.onCreate()
    ├── handleResumeActivity()      → Activity.onResume()
    ├── handlePauseActivity()       → Activity.onPause()
    ├── handleStopActivity()        → Activity.onStop()
    ├── handleDestroyActivity()     → Activity.onDestroy()
    │
    ├── handleCreateService()       → Service.onCreate()
    ├── handleBindService()         → Service.onBind()
    │
    ├── handleReceiver()            → BroadcastReceiver.onReceive()
    │
    └── installProvider()           → ContentProvider.onCreate()
```

## 六、关键内部类 H（Handler）

ActivityThread 内部有一个 Handler 叫 **H**，所有生命周期回调都是通过它调度的：

```java
class H extends Handler {
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case LAUNCH_ACTIVITY:
                handleLaunchActivity(msg);
                break;
            case PAUSE_ACTIVITY:
                handlePauseActivity(msg);
                break;
            case RESUME_ACTIVITY:
                handleResumeActivity(msg);
                break;
            // ... 几十种消息类型
        }
    }
}
```

这就是为什么**主线程不能做耗时操作**：所有生命周期回调、UI 更新、触摸事件都排在同一个消息队列里，一个消息卡住，后面全部等着。

## 七、与界面显示流程的衔接

```
ActivityThread.handleLaunchActivity()
    │
    ├── Activity.onCreate()
    │      └── setContentView() → 创建 View 树
    │
    ▼
ActivityThread.handleResumeActivity()
    │
    ├── Activity.onResume()
    │
    └── WindowManager.addView(decorView)
           └── 创建 ViewRootImpl → 触发 measure/layout/draw → 屏幕显示
```

---

## 八、面试高频问题

### Q1: ActivityThread 是线程吗？

- 不是。它是一个普通 Java 类，运行在主线程上。名字有"Thread"是历史原因
- 真正的主线程是 Zygote fork 进程时创建的，ActivityThread.main() 是主线程的入口方法

### Q2: App 的入口是 Application.onCreate() 吗？

- 不是。真正的入口是 `ActivityThread.main()`
- 调用链：`ActivityThread.main()` → `thread.attach()` → `AMS.attachApplication()` → 回调 `bindApplication()` → `Application.onCreate()`
- Application 也是由 ActivityThread 创建和管理的

### Q3: 主线程的 Looper 为什么不会 ANR？

- `Looper.loop()` 本身是一个死循环，没有消息时会通过 `epoll` 机制休眠，不消耗 CPU
- ANR 不是因为 Looper 在循环，而是某一条消息处理时间**太长**（Activity 5 秒、BroadcastReceiver 10 秒）
- Looper 循环 = 主线程活着；Looper 退出 = App 进程结束

### Q4: AMS 怎么通知 App 启动 Activity？

```
AMS (system_server 进程)
    │ Binder 调用
    ▼
ApplicationThread (App 进程 Binder 线程)
    │ 发送 Message
    ▼
H (Handler, 主线程)
    │ handleMessage
    ▼
ActivityThread.handleLaunchActivity() (主线程)
    │
    ▼
Activity.onCreate()
```

跨进程用 Binder，进程内用 Handler 切换到主线程。

### Q5: 为什么四大组件都不能做耗时操作？

- 四大组件的生命周期回调都在主线程执行（都由 ActivityThread 的 Handler H 调度）
- 主线程的消息队列是串行的，一个消息阻塞，后续的 UI 绘制、触摸事件、其他组件回调全部排队等待
- 系统对主线程阻塞有监控：Activity 5s、BroadcastReceiver 10s、Service 20s 未完成就 ANR
