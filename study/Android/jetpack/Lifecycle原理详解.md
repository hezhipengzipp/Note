# Android Lifecycle 原理详解

## 一、是什么

Lifecycle 是 Jetpack 组件，让**任意类**都能感知 Activity/Fragment 的生命周期，而不需要在 `onCreate()`、`onDestroy()` 里手动调用。

## 二、解决了什么问题

### 没有 Lifecycle 之前

```kotlin
// 到处都是手动管理，容易遗漏
class MyActivity : AppCompatActivity() {
    private val locationManager = LocationManager()
    private val videoPlayer = VideoPlayer()
    private val sensorTracker = SensorTracker()

    override fun onStart() {
        super.onStart()
        locationManager.start()      // 手动启动
        videoPlayer.start()          // 手动启动
        sensorTracker.start()        // 手动启动
    }

    override fun onStop() {
        super.onStop()
        locationManager.stop()       // 手动停止，忘了就泄漏
        videoPlayer.stop()           // 手动停止
        sensorTracker.stop()         // 手动停止
    }
}
```

问题：Activity 越来越臃肿，组件多了容易遗漏，生命周期管理散落在各处。

### 有 Lifecycle 之后

```kotlin
// 组件自己管理自己的生命周期
class LocationManager : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        startTracking()
    }
    override fun onStop(owner: LifecycleOwner) {
        stopTracking()
    }
}

// Activity 只需要一行注册
class MyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(LocationManager())    // 注册就完事了
        lifecycle.addObserver(VideoPlayer())
        lifecycle.addObserver(SensorTracker())
    }
    // 不需要在 onStart/onStop 里写任何东西
}
```

## 三、三个核心角色

```
┌──────────────────┐     观察     ┌──────────────────┐
│ LifecycleObserver │ ──────────→ │  Lifecycle         │
│ （观察者）         │             │  （生命周期对象）    │
│ LocationManager   │             │  管理 State + Event │
└──────────────────┘             └──────────────────┘
                                          ↑
                                          │ 拥有
                                 ┌──────────────────┐
                                 │  LifecycleOwner   │
                                 │  （生命周期持有者）  │
                                 │  Activity/Fragment │
                                 └──────────────────┘
```

| 角色 | 职责 |
|------|------|
| **LifecycleOwner** | 生命周期的持有者，提供 `getLifecycle()` 方法。Activity、Fragment 已默认实现 |
| **Lifecycle** | 生命周期对象，存储当前状态，管理 Observer。实现类是 `LifecycleRegistry` |
| **LifecycleObserver** | 观察者，接收生命周期事件回调 |

## 四、Event 和 State 的关系

```
        Event:     ON_CREATE   ON_START   ON_RESUME
                      │           │          │
State:  INITIALIZED ──→ CREATED ──→ STARTED ──→ RESUMED
                      ←──         ←──         ←──
                   ON_DESTROY  ON_STOP    ON_PAUSE
                      │
                      ▼
                   DESTROYED
```

```
State 是静态的"我现在在哪"
Event 是动态的"发生了什么事"

Activity.onStart() 被调用时：
  Event = ON_START
  State = CREATED → STARTED（状态迁移）
```

## 五、核心实现：LifecycleRegistry

```java
public class LifecycleRegistry extends Lifecycle {

    // 当前生命周期状态
    private State mState;

    // 所有观察者，用 Map 存储（保序）
    private FastSafeIterableMap<LifecycleObserver, ObserverWithState> mObserverMap;

    // 生命周期持有者（Activity/Fragment）的弱引用
    private final WeakReference<LifecycleOwner> mLifecycleOwner;

    // 添加观察者
    public void addObserver(LifecycleObserver observer) {
        // 1. 初始状态为 INITIALIZED 或 DESTROYED
        State initialState = mState == DESTROYED ? DESTROYED : INITIALIZED;

        // 2. 包装成 ObserverWithState
        ObserverWithState statefulObserver = new ObserverWithState(observer, initialState);
        mObserverMap.putIfAbsent(observer, statefulObserver);

        // 3. 如果当前状态比 INITIALIZED 更靠后，要追赶到当前状态
        //    比如在 onResume 里 addObserver，观察者会依次收到
        //    ON_CREATE → ON_START → ON_RESUME
        while (statefulObserver.mState < mState) {
            statefulObserver.dispatchEvent(owner, upEvent(statefulObserver.mState));
        }
    }

    // 处理生命周期事件
    public void handleLifecycleEvent(Event event) {
        State newState = event.getTargetState();
        moveToState(newState);
    }

    private void moveToState(State next) {
        mState = next;
        // 遍历所有观察者，分发事件
        for (ObserverWithState observer : mObserverMap) {
            observer.dispatchEvent(lifecycleOwner, event);
        }
    }
}
```

## 六、生命周期事件怎么触发的

**关键：不是 Activity 直接通知的，而是通过一个无 UI 的 Fragment（ReportFragment）感知生命周期。**

```
Activity.onCreate()
    │
    ▼
ComponentActivity.onCreate()
    │
    ▼
ReportFragment.injectIfNeededIn(this)
    │
    ▼
添加一个无 UI 的 ReportFragment 到 Activity 中
```

```
ReportFragment（无 UI，用户不可见）
    │
    ├── onActivityCreated()  → dispatch(ON_CREATE)
    ├── onStart()            → dispatch(ON_START)
    ├── onResume()           → dispatch(ON_RESUME)
    ├── onPause()            → dispatch(ON_PAUSE)
    ├── onStop()             → dispatch(ON_STOP)
    └── onDestroy()          → dispatch(ON_DESTROY)
                                    │
                                    ▼
                             LifecycleRegistry.handleLifecycleEvent()
                                    │
                                    ▼
                             遍历所有 Observer，分发回调
```

**在 API 29+ 上**，不再依赖 ReportFragment，而是直接通过 `Activity.registerActivityLifecycleCallbacks()` 注册回调：

```java
// ComponentActivity.onCreate() 中（API 29+）
if (Build.VERSION.SDK_INT >= 29) {
    registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
        @Override
        public void onActivityPostCreated(...) {
            mLifecycleRegistry.handleLifecycleEvent(Event.ON_CREATE);
        }
        @Override
        public void onActivityPostStarted(...) {
            mLifecycleRegistry.handleLifecycleEvent(Event.ON_START);
        }
        // ...
    });
}
```

## 七、观察者状态追赶机制

在 `onResume()` 之后才 `addObserver()`，观察者不会漏掉之前的事件：

```
当前 Activity 状态：RESUMED

addObserver(myObserver)
    │
    ▼
myObserver 初始状态：INITIALIZED
    │
    ├── INITIALIZED < RESUMED，需要追赶
    │
    ├── dispatch ON_CREATE → myObserver 状态变为 CREATED
    ├── dispatch ON_START  → myObserver 状态变为 STARTED
    └── dispatch ON_RESUME → myObserver 状态变为 RESUMED
    │
    ▼
追赶完成，和 Activity 状态一致
```

这就是为什么 LiveData 在任何时候 observe 都能收到最新数据的基础。

## 八、完整事件流

```
用户按下返回键
    │
    ▼
Activity.onPause()
    │
    ▼
ReportFragment.onPause()  (或 ActivityLifecycleCallbacks)
    │
    ▼
LifecycleRegistry.handleLifecycleEvent(ON_PAUSE)
    │
    ▼
moveToState(STARTED)
    │
    ▼
遍历 mObserverMap
    │
    ├── observer1.onPause(owner)
    ├── observer2.onPause(owner)
    └── observer3.onPause(owner)
```

---

## 九、面试高频问题

### Q1: Lifecycle 是怎么感知 Activity 生命周期的？

- API < 29：通过向 Activity 注入一个无 UI 的 `ReportFragment`，Fragment 生命周期回调中分发事件
- API >= 29：通过 `registerActivityLifecycleCallbacks` 直接注册回调
- 两种方式最终都调用 `LifecycleRegistry.handleLifecycleEvent()`

### Q2: 为什么用无 UI 的 Fragment 而不是直接在 Activity 里分发？

- 为了不侵入 Activity 代码
- 早期 Activity 没有实现 LifecycleOwner，需要兼容方案
- Fragment 天然跟随 Activity 生命周期，不需要额外逻辑
- 和 Glide 感知生命周期的方式相同

### Q3: 在 onResume 之后 addObserver，会收到 onCreate 回调吗？

- 会。LifecycleRegistry 有状态追赶机制
- 新注册的 Observer 会从 INITIALIZED 开始，依次 dispatch 事件，直到追上当前状态

### Q4: Lifecycle 和 LiveData 的关系？

```
LiveData.observe(lifecycleOwner, observer)
    │
    ├── 内部 addObserver 到 LifecycleOwner 的 Lifecycle
    │
    ├── 当 State >= STARTED 时才分发数据（onStart/onResume 时活跃）
    │
    └── 当 ON_DESTROY 事件到来时，自动 removeObserver
        → 不会内存泄漏
```

LiveData 的自动取消订阅、仅活跃状态分发，都依赖 Lifecycle。

### Q5: LifecycleOwner 和 LifecycleObserver 的设计模式？

- 观察者模式
- LifecycleOwner（被观察者）持有 LifecycleRegistry，管理所有 Observer
- 状态变化时遍历通知所有 Observer
