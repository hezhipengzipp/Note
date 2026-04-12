# LiveData 原理详解

## 一、是什么

LiveData 是一个**可观察的数据持有类**，具有生命周期感知能力。只有在 Activity/Fragment 处于活跃状态（STARTED/RESUMED）时才会通知观察者，且在 DESTROYED 时自动移除观察者，不会内存泄漏。

## 二、基本使用

```kotlin
// ViewModel 中
class UserViewModel : ViewModel() {
    private val _name = MutableLiveData<String>()
    val name: LiveData<String> = _name      // 对外暴露不可变的

    fun updateName(newName: String) {
        _name.value = newName               // 主线程
        _name.postValue(newName)            // 子线程
    }
}

// Activity 中
viewModel.name.observe(this) { name ->
    // 只在 Activity 活跃时收到回调
    textView.text = name
}
```

## 三、核心源码分析

### observe() — 注册观察者

```java
public void observe(LifecycleOwner owner, Observer<? super T> observer) {
    // 1. 如果已经 DESTROYED，直接忽略
    if (owner.getLifecycle().getCurrentState() == DESTROYED) {
        return;
    }

    // 2. 用 LifecycleBoundObserver 包装（关联生命周期）
    LifecycleBoundObserver wrapper = new LifecycleBoundObserver(owner, observer);

    // 3. 存入观察者 Map（去重）
    ObserverWrapper existing = mObservers.putIfAbsent(observer, wrapper);

    // 4. 注册到 Lifecycle，感知生命周期变化
    owner.getLifecycle().addObserver(wrapper);
}
```

### LifecycleBoundObserver — 生命周期感知的包装

```java
class LifecycleBoundObserver extends ObserverWrapper implements LifecycleEventObserver {
    final LifecycleOwner mOwner;

    @Override
    public void onStateChanged(LifecycleOwner source, Lifecycle.Event event) {
        // 生命周期变化时回调
        if (mOwner.getLifecycle().getCurrentState() == DESTROYED) {
            // DESTROYED 时自动移除观察者 → 不会内存泄漏
            removeObserver(mObserver);
            return;
        }
        // 状态变化时尝试分发数据
        activeStateChanged(shouldBeActive());
    }

    @Override
    boolean shouldBeActive() {
        // 至少是 STARTED 才算活跃
        return mOwner.getLifecycle().getCurrentState().isAtLeast(STARTED);
    }
}
```

### setValue() — 设置数据并通知

```java
public class LiveData<T> {
    // 数据版本号，每次 setValue 递增
    private int mVersion = START_VERSION;  // -1

    // 存储的数据
    private volatile Object mData;

    @MainThread
    protected void setValue(T value) {
        mVersion++;             // 版本号 +1
        mData = value;          // 存储新数据
        dispatchingValue(null);  // 通知所有观察者
    }
}
```

### dispatchingValue() — 分发逻辑

```java
void dispatchingValue(ObserverWrapper initiator) {
    if (initiator != null) {
        // 单个观察者（生命周期变化触发）
        considerNotify(initiator);
    } else {
        // 所有观察者（setValue 触发）
        for (ObserverWrapper observer : mObservers) {
            considerNotify(observer);
        }
    }
}
```

### considerNotify() — 最终决定是否通知

```java
private void considerNotify(ObserverWrapper observer) {
    // 1. 观察者不活跃（Activity 在后台）→ 不通知
    if (!observer.mActive) {
        return;
    }

    // 2. 再次检查生命周期状态
    if (!observer.shouldBeActive()) {
        observer.activeStateChanged(false);
        return;
    }

    // 3. 观察者的版本号 >= LiveData 的版本号 → 已经是最新数据，不重复通知
    if (observer.mLastVersion >= mVersion) {
        return;
    }

    // 4. 更新观察者版本号，分发数据
    observer.mLastVersion = mVersion;
    observer.mObserver.onChanged((T) mData);
}
```

## 四、完整流程图

```
setValue("hello")
    │
    ├── mVersion++ (0 → 1)
    ├── mData = "hello"
    │
    ▼
dispatchingValue(null)  → 遍历所有观察者
    │
    ▼
considerNotify(observer)
    │
    ├── observer.mActive?  ── false → 跳过（Activity 在后台）
    │
    ├── observer.mLastVersion >= mVersion?  ── true → 跳过（已通知过）
    │
    └── 都通过 → observer.onChanged("hello")  ← 回调！
```

## 五、生命周期变化时的分发

```
Activity 从后台回到前台（onStart）
    │
    ▼
LifecycleBoundObserver.onStateChanged()
    │
    ▼
shouldBeActive() == true
    │
    ▼
activeStateChanged(true)
    │
    ▼
dispatchingValue(this)  → 只分发给这一个观察者
    │
    ▼
considerNotify(observer)
    │
    ├── observer.mLastVersion (0) < mVersion (1)
    │   → 有新数据没收到过
    │
    └── observer.onChanged("hello")  ← 回到前台立刻收到最新数据
```

## 六、setValue vs postValue

```java
// setValue：主线程调用，同步分发
@MainThread
protected void setValue(T value) {
    mVersion++;
    mData = value;
    dispatchingValue(null);  // 立即通知
}

// postValue：子线程调用，通过 Handler 切到主线程
protected void postValue(T value) {
    mPendingData = value;
    ArchTaskExecutor.getInstance().postToMainThread(mPostValueRunnable);
    // mPostValueRunnable 里调用 setValue()
}
```

```
postValue 的坑：短时间内多次 postValue，只有最后一次生效
因为 mPendingData 会被覆盖，只 post 了一次 Runnable

postValue("A")  → mPendingData = "A"，post Runnable
postValue("B")  → mPendingData = "B"，Runnable 还没执行
postValue("C")  → mPendingData = "C"，Runnable 还没执行
                   Runnable 执行 → setValue("C")  ← 只收到 "C"
```

---

## 七、粘性事件问题

### 什么是粘性事件

```
ViewModel 中：
liveData.setValue("旧数据")    // 在 FragmentA 中设置的

然后跳转到 FragmentB：
liveData.observe(this) { data ->
    // 一注册就收到了 "旧数据" ！！
    // FragmentB 根本没有主动请求，却收到了之前的数据
}
```

**新的观察者注册时，会立即收到之前设置的最后一个值。**

### 为什么会粘性

```
时间线：

1. liveData.setValue("旧数据")
   mVersion = 1, mData = "旧数据"

2. FragmentB observe
   新 Observer 的 mLastVersion = -1（START_VERSION）

3. Observer 状态追赶到 STARTED → activeStateChanged(true)
   → dispatchingValue(this)
   → considerNotify(observer)

4. 判断：observer.mLastVersion (-1) < mVersion (1)
   → 有"新数据" → 分发 "旧数据"

问题：新 Observer 的 mLastVersion 是 -1，永远小于 mVersion
      所以一定会收到最后一次 setValue 的数据
```

### 粘性事件的问题场景

```
场景：用 LiveData 发送一次性事件（Toast、导航、弹窗）

ViewModel:
  showToast.setValue("登录成功")     // 在 LoginFragment 中触发

用户旋转屏幕 → Activity 重建 → 重新 observe
  → 又收到 "登录成功" → Toast 又弹了一次！

或者：导航到新页面后，返回时又收到导航事件 → 死循环跳转
```

### 解决方案一：Event 包装类（Google 官方推荐）

```kotlin
/**
 * 只消费一次的事件包装
 */
open class Event<out T>(private val content: T) {
    private var hasBeenHandled = false

    // 只有第一次调用返回内容，之后返回 null
    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) {
            null
        } else {
            hasBeenHandled = true
            content
        }
    }

    // 无论是否处理过都能获取（用于查看）
    fun peekContent(): T = content
}

// ViewModel 中
class LoginViewModel : ViewModel() {
    private val _toastEvent = MutableLiveData<Event<String>>()
    val toastEvent: LiveData<Event<String>> = _toastEvent

    fun login() {
        _toastEvent.value = Event("登录成功")
    }
}

// Activity 中
viewModel.toastEvent.observe(this) { event ->
    event.getContentIfNotHandled()?.let { message ->
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        // 第二次收到（旋转屏幕）→ getContentIfNotHandled() 返回 null → 不弹
    }
}
```

### 解决方案二：SingleLiveEvent（简单场景够用）

```kotlin
class SingleLiveEvent<T> : MutableLiveData<T>() {
    private val mPending = AtomicBoolean(false)

    override fun observe(owner: LifecycleOwner, observer: Observer<in T>) {
        super.observe(owner) { value ->
            // 只有 setValue 之后才分发，且只分发一次
            if (mPending.compareAndSet(true, false)) {
                observer.onChanged(value)
            }
        }
    }

    @MainThread
    override fun setValue(value: T?) {
        mPending.set(true)    // 标记有新数据
        super.setValue(value)
    }
}

// 使用
class LoginViewModel : ViewModel() {
    val toastEvent = SingleLiveEvent<String>()

    fun login() {
        toastEvent.value = "登录成功"
    }
}
```

局限：只支持一个观察者。多个观察者时只有一个能收到。

### 解决方案三：反射修改 mLastVersion（不推荐但面试常问）

```
原理：把新 Observer 的 mLastVersion 设置成和 mVersion 一样
这样 considerNotify 判断时 mLastVersion >= mVersion → 不分发旧数据

缺点：依赖反射、依赖内部实现、版本更新可能失效
```

### 解决方案四：Kotlin Flow 替代（现代推荐）

```kotlin
class LoginViewModel : ViewModel() {
    // SharedFlow 默认 replay=0，不会粘性
    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent = _toastEvent.asSharedFlow()

    fun login() {
        viewModelScope.launch {
            _toastEvent.emit("登录成功")
        }
    }
}

// Activity 中
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.toastEvent.collect { message ->
            Toast.makeText(this@Activity, message, Toast.LENGTH_SHORT).show()
        }
    }
}
```

### 方案对比

| 方案 | 优点 | 缺点 | 推荐度 |
|------|------|------|--------|
| **Event 包装类** | 简单、无反射、多观察者支持 | 使用时要解包 | 推荐 |
| **SingleLiveEvent** | 简单 | 只支持一个观察者 | 简单场景可用 |
| **反射改 version** | 透明、无需改使用方式 | 依赖内部实现，不稳定 | 不推荐 |
| **SharedFlow** | 原生支持、无粘性、功能强大 | 需要协程基础 | **最推荐** |

---

## 八、面试高频问题

### Q1: LiveData 为什么不会内存泄漏？

- `LifecycleBoundObserver` 监听了 Lifecycle
- 当 `ON_DESTROY` 事件到来时，自动调用 `removeObserver()`
- 观察者被移除，引用链断开，可以被 GC

### Q2: LiveData 的 setValue 和 postValue 区别？

| | setValue | postValue |
|--|---------|-----------|
| 线程 | 主线程 | 任意线程 |
| 时机 | 同步立即分发 | 通过 Handler post 到主线程 |
| 多次调用 | 每次都通知 | 只有最后一次生效 |

### Q3: LiveData 数据丢失问题？

- `postValue` 短时间多次调用，中间的值会丢失（被覆盖）
- 如果每次数据都重要，用 `setValue`（确保在主线程）或用 Flow

### Q4: observeForever 和 observe 的区别？

| | observe | observeForever |
|--|---------|---------------|
| 需要 LifecycleOwner | 是 | 否 |
| 自动移除 | DESTROYED 时自动移除 | 不会，必须手动 removeObserver |
| 活跃状态判断 | 只在 STARTED 以上分发 | 始终分发 |
| 内存泄漏风险 | 无 | 有，忘记 remove 就泄漏 |

### Q5: 粘性事件的根本原因？

- 新 Observer 的 `mLastVersion = -1`，LiveData 的 `mVersion >= 0`
- `considerNotify` 判断 `mLastVersion < mVersion` 为 true → 分发旧数据
- 本质上这是 LiveData 的设计意图（数据持有类，保证观察者拿到最新数据），但在一次性事件场景下变成了问题
