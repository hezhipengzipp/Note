# LeakCanary 原理详解

## 一、是什么

LeakCanary 是 Square 开源的**内存泄漏自动检测库**。集成后无需任何代码，自动检测 Activity、Fragment、ViewModel、View、Service 的内存泄漏，并展示泄漏的引用链。

## 二、基本使用

```kotlin
// build.gradle 添加依赖，什么代码都不用写
dependencies {
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}

// 不需要在 Application 里初始化
// LeakCanary 通过 ContentProvider 自动初始化
```

## 三、整体流程

```
Activity.onDestroy()
    │
    ▼
LeakCanary 监听到销毁事件
    │
    ▼
用 WeakReference 包装该 Activity
    │
    ▼
等待 5 秒
    │
    ▼
检查 WeakReference 是否被回收？
    │
    ├── 已回收 → 没有泄漏，结束
    │
    └── 未回收 → 触发 GC，再检查
                    │
                    ├── 已回收 → 没有泄漏，结束
                    │
                    └── 仍未回收 → 确认泄漏！
                                    │
                                    ▼
                              dump hprof 文件（堆转储）
                                    │
                                    ▼
                              分析 hprof，找到引用链
                                    │
                                    ▼
                              展示泄漏通知
```

---

## 四、自动初始化（ContentProvider）

```
LeakCanary 怎么做到不写任何初始化代码就能工作？

App 启动
    │
    ▼
Application.onCreate() 之前
    │
    ▼
系统自动创建 AndroidManifest 中声明的 ContentProvider
    │
    ▼
LeakCanary 在库的 AndroidManifest 中声明了：
  <provider
      android:name="leakcanary.internal.MainProcessAppWatcherInstaller"
      android:authorities="${applicationId}.leakcanary-installer" />
    │
    ▼
MainProcessAppWatcherInstaller.onCreate()
    │
    ▼
AppWatcher.manualInstall(application)
    │
    ▼
注册各种 Watcher（Activity、Fragment、ViewModel...）
```

```
利用 ContentProvider 的特性：
  ContentProvider.onCreate() 在 Application.onCreate() 之前执行
  并且可以通过 manifest merge 自动合并
  所以用户只需要加依赖，不需要写任何初始化代码
```

---

## 五、监听对象销毁（ObjectWatcher）

### 监听 Activity

```kotlin
application.registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
    override fun onActivityDestroyed(activity: Activity) {
        // Activity 销毁时，交给 ObjectWatcher 监测
        objectWatcher.expectWeaklyReachable(
            activity,
            "Activity ${activity::class.java.name} was destroyed"
        )
    }
})
```

### 监听 Fragment

```kotlin
fragmentManager.registerFragmentLifecycleCallbacks(object : FragmentLifecycleCallbacks() {
    override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
        objectWatcher.expectWeaklyReachable(f.view, "Fragment view")
    }
    override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
        objectWatcher.expectWeaklyReachable(f, "Fragment")
    }
})
```

### 监测的对象类型

```
LeakCanary 2.x 默认监测：

┌─────────────┬────────────────────────────┐
│ Activity     │ onDestroy 后               │
│ Fragment     │ onDestroy 后               │
│ Fragment View│ onDestroyView 后           │
│ ViewModel   │ onCleared 后               │
│ Service     │ onDestroy 后               │
│ RootView    │ View 从 WindowManager 移除后 │
└─────────────┴────────────────────────────┘
```

---

## 六、泄漏判定（WeakReference + ReferenceQueue）

### 核心机制

```java
// Java 基础知识：
// WeakReference 关联一个 ReferenceQueue
// 当 WeakReference 引用的对象被 GC 回收后
// 这个 WeakReference 会被自动加入 ReferenceQueue

ReferenceQueue<Object> queue = new ReferenceQueue<>();
WeakReference<Activity> ref = new WeakReference<>(activity, queue);

// 如果 activity 被 GC 回收 → ref 出现在 queue 中
// 如果 activity 没被回收 → queue 中没有 ref → 可能泄漏了
```

### ObjectWatcher 核心逻辑

```kotlin
class ObjectWatcher {

    // 正在监测的对象：key → WeakReference
    private val watchedObjects = mutableMapOf<String, KeyedWeakReference>()

    // 引用队列：被 GC 回收的对象的 WeakReference 会出现在这里
    private val queue = ReferenceQueue<Any>()

    fun expectWeaklyReachable(watchedObject: Any, description: String) {
        val key = UUID.randomUUID().toString()

        // 1. 创建 WeakReference，关联 ReferenceQueue
        val ref = KeyedWeakReference(watchedObject, key, description, queue)
        watchedObjects[key] = ref

        // 2. 5 秒后检查
        postDelayed(5_000) {
            checkRetainedObject(key)
        }
    }

    private fun checkRetainedObject(key: String) {
        // 3. 先清理已回收的对象
        removeWeaklyReachableObjects()

        // 4. 检查目标对象是否还在 watchedObjects 中
        val ref = watchedObjects[key]
        if (ref != null) {
            // 还在 → 没被回收 → 可能泄漏了
            // 触发 GC 再确认一次
            gcTrigger.runGc()
            removeWeaklyReachableObjects()

            if (watchedObjects[key] != null) {
                // GC 后仍未回收 → 确认泄漏！
                onObjectRetained(key)
            }
        }
    }

    private fun removeWeaklyReachableObjects() {
        // 从 ReferenceQueue 中取出所有已回收的 WeakReference
        var ref: KeyedWeakReference?
        do {
            ref = queue.poll() as KeyedWeakReference?
            if (ref != null) {
                // 已回收 → 从监测列表移除
                watchedObjects.remove(ref.key)
            }
        } while (ref != null)
    }
}
```

### 判定流程图

```
Activity.onDestroy()
    │
    ▼
创建 WeakReference(activity, queue)
存入 watchedObjects[key]
    │
    ▼
等待 5 秒（给 GC 时间）
    │
    ▼
检查 queue 中是否有这个 ref？
    │
    ├── 有 → activity 已被 GC → 从 watchedObjects 移除 → 没有泄漏 ✅
    │
    └── 没有 → activity 可能还活着
                │
                ▼
            手动触发 GC（Runtime.gc()）
                │
                ▼
            再次检查 queue
                │
                ├── 有 → 被回收了 → 没有泄漏 ✅
                │
                └── 没有 → 确认泄漏！→ dump heap 📸
```

---

## 七、Heap 分析（Shark）

```
确认泄漏后
    │
    ▼
Debug.dumpHprofData(filePath)
    │
    ▼
生成 .hprof 文件（堆转储快照）
包含了此刻内存中所有对象和引用关系
    │
    ▼
Shark 库解析 .hprof（LeakCanary 自己的分析引擎）
    │
    ▼
从 GC Root 出发，找到到泄漏对象的最短引用链
```

### 分析结果示例

```
┌──────────────────────────────────────────────────────┐
│  GC Root: Thread → "main"                             │
│    │                                                  │
│    └── static MainActivity.sInstance                  │
│          │                                            │
│          └── MainActivity.mContext                    │
│                │                                      │
│                └── DetailActivity (已 destroy，泄漏！)  │
│                                                      │
│  原因：MainActivity 的 static 变量持有了 DetailActivity │
│  的引用，导致 DetailActivity 无法被 GC                  │
└──────────────────────────────────────────────────────┘
```

### GC Root 有哪些

```
能阻止对象被 GC 回收的根引用：

1. 静态变量（static field）
   static Activity sInstance = this;  ← 最常见的泄漏原因

2. 活跃线程（Thread）
   正在运行的线程及其栈上的局部变量

3. 系统类引用
   被 Bootstrap ClassLoader 加载的类

4. JNI 引用
   Native 代码持有的引用

5. Monitor 锁
   synchronized 锁持有的对象
```

### Shark vs MAT

```
旧版 LeakCanary 1.x：
  使用 HAHA 库（基于 MAT）分析 hprof
  在 App 进程内分析 → 容易 OOM
  分析速度慢

新版 LeakCanary 2.x：
  使用 Shark（自研）分析 hprof
  在独立进程分析 → 不影响 App
  速度快，内存占用小
  随机访问 hprof 文件，不需要全部加载到内存
```

---

## 八、常见内存泄漏场景

```
1. 静态引用持有 Activity
   companion object {
       var activity: Activity? = null  // ❌
   }

2. 内部类 / 匿名类持有外部类引用
   // Handler 内部类持有 Activity
   val handler = object : Handler() {  // ❌ 匿名内部类持有 Activity
       override fun handleMessage(msg: Message) { }
   }
   handler.postDelayed(runnable, 60_000)
   // Activity 销毁了，但 Message 还在队列里 → 泄漏

3. 未取消的注册 / 监听
   // 注册了但没取消
   sensorManager.registerListener(this, sensor, rate)
   // onDestroy 里没有 unregisterListener → 泄漏

4. 单例持有 Activity Context
   class Manager private constructor(val context: Context) {  // ❌
       companion object {
           lateinit var instance: Manager
       }
   }
   // 应该用 Application Context

5. 协程 / 线程未取消
   // Activity 销毁了但协程还在运行
   GlobalScope.launch {  // ❌ GlobalScope 不跟随生命周期
       delay(30_000)
       textView.text = "done"  // Activity 已经销毁了
   }
   // 应该用 lifecycleScope
```

---

## 九、面试高频问题

### Q1: LeakCanary 怎么判断对象泄漏了？

- 对象销毁时创建 `WeakReference` 并关联 `ReferenceQueue`
- 等 5 秒后检查 ReferenceQueue，如果 WeakReference 没出现在队列中
- 手动触发 GC，再检查一次，仍未回收则确认泄漏

### Q2: WeakReference + ReferenceQueue 的原理？

- 当 WeakReference 引用的对象被 GC 回收后
- JVM 自动把该 WeakReference 加入关联的 ReferenceQueue
- 通过 `queue.poll()` 可以知道哪些对象已被回收
- 如果 poll 不到 → 对象还活着 → 可能泄漏

### Q3: LeakCanary 怎么做到零配置的？

- 利用 ContentProvider 自动初始化
- ContentProvider 在 AndroidManifest 中声明，通过 manifest merge 自动合并
- `ContentProvider.onCreate()` 在 `Application.onCreate()` 之前执行
- 在这里注册各种 LifecycleCallbacks 开始监测

### Q4: 为什么要等 5 秒？

- 对象销毁后，GC 不是立即执行的
- 给系统 GC 留出足够时间
- 5 秒内对象被正常回收 → 不是泄漏
- 5 秒后仍未回收 → 手动触发 GC 确认

### Q5: dump hprof 会影响 App 性能吗？

- 会。dump 时 App 会短暂冻结（Stop The World）
- 所以 LeakCanary 只在 debug 包启用（`debugImplementation`）
- LeakCanary 2.x 的 Shark 在独立进程分析 hprof，不影响 App 主进程

### Q6: LeakCanary 能在线上使用吗？

- 默认不行（dump hprof 会卡顿）
- 线上方案：只做泄漏判定（WeakReference 检测），不 dump hprof
- 记录泄漏的类名和堆栈上报，不做引用链分析
- 美团的 ResourceCanary、快手的 KOOM 就是这个思路
