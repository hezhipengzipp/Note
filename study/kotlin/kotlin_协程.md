## 协程

参考：https://juejin.cn/post/7137905800504148004

---

### 一、协程是什么

- 协程是一种**用户态的轻量级线程**，本质上是对线程的一层封装/框架
- 协程并不是取代线程，而是在线程之上提供了一套更易用的异步编程 API
- 一个线程上可以运行多个协程，协程的切换不需要操作系统参与，开销极小
- 核心优势：**用同步的写法实现异步的效果**，避免回调地狱

```kotlin
// 回调地狱
getUserInfo { user ->
    getOrderList(user.id) { orders ->
        getOrderDetail(orders[0].id) { detail ->
            // ...
        }
    }
}

// 协程：同步写法
suspend fun loadData() {
    val user = getUserInfo()          // 挂起，不阻塞线程
    val orders = getOrderList(user.id)
    val detail = getOrderDetail(orders[0].id)
}
```

---

### 二、suspend 关键字的本质

#### 2.1 suspend 做了什么

`suspend` 关键字本身**不会挂起协程**，它只是一个**标记**，告诉编译器：这个函数可能会挂起。

真正的挂起发生在函数体内调用了 `suspendCoroutine` / `suspendCancellableCoroutine` 等挂起函数时。

#### 2.2 CPS 变换（Continuation Passing Style）

编译器在编译阶段会对 `suspend` 函数做 **CPS 变换**：

```kotlin
// 编写的代码
suspend fun getUserInfo(): User

// 编译后（反编译为 Java）
Object getUserInfo(Continuation<User> cont)
```

关键变化：
1. **增加 Continuation 参数**：每个 suspend 函数都会被添加一个 `Continuation` 类型的参数
2. **返回值变为 Object**：因为函数可能返回真实结果，也可能返回 `COROUTINE_SUSPENDED` 标记（表示已挂起）

#### 2.3 Continuation 是什么

```kotlin
public interface Continuation<in T> {
    public val context: CoroutineContext   // 协程上下文
    public fun resumeWith(result: Result<T>) // 恢复执行，传递结果
}
```

**Continuation 就是一个回调**，它代表了"协程挂起点之后剩余的代码"。协程的挂起与恢复本质就是：
- **挂起**：函数返回 `COROUTINE_SUSPENDED`，当前执行流结束
- **恢复**：某个时机调用 `continuation.resumeWith(result)`，从挂起点继续执行

---

### 三、状态机（核心重点）

#### 3.1 为什么需要状态机

一个 suspend 函数中可能有多个挂起点。编译器需要一种机制来记住"执行到哪里了"，以便恢复时从正确的位置继续。这就是**状态机**。

#### 3.2 状态机原理

以下面的代码为例：

```kotlin
suspend fun loadData() {
    val user = getUserInfo()        // 挂起点 1
    val orders = getOrderList(user) // 挂起点 2
    updateUI(user, orders)
}
```

编译器会将这个函数转换成一个**状态机**，伪代码如下：

```kotlin
fun loadData(cont: Continuation<Unit>): Any? {
    // 首次调用时创建状态机，后续调用复用
    val sm = cont as? LoadDataContinuation ?: LoadDataContinuation(cont)

    when (sm.label) {
        0 -> {
            // 初始状态：执行到第一个挂起点
            sm.label = 1  // 标记下一个状态
            val result = getUserInfo(sm)  // 传入状态机自身作为 Continuation
            if (result == COROUTINE_SUSPENDED) return COROUTINE_SUSPENDED
            // 如果没有真正挂起（比如缓存命中），直接继续
        }
        1 -> {
            // 从挂起点 1 恢复：拿到 user
            val user = sm.result as User
            sm.user = user  // 保存中间结果
            sm.label = 2
            val result = getOrderList(user, sm)
            if (result == COROUTINE_SUSPENDED) return COROUTINE_SUSPENDED
        }
        2 -> {
            // 从挂起点 2 恢复：拿到 orders
            val orders = sm.result as List<Order>
            val user = sm.user  // 取出之前保存的中间结果
            updateUI(user, orders)
        }
    }
    return Unit
}
```

#### 3.3 状态机的关键要素

| 要素 | 说明 |
|------|------|
| `label` | 标记当前执行到哪个状态（哪个挂起点） |
| `result` | 上一个挂起点恢复时传回的结果 |
| 中间变量 | 跨挂起点使用的局部变量会被保存到状态机对象的字段中 |
| `COROUTINE_SUSPENDED` | 特殊标记值，表示函数真正挂起了 |

#### 3.4 状态机执行流程图

```
loadData() 首次调用
    │
    ▼
label=0 → 调用 getUserInfo(sm)
    │
    ├─ 返回 COROUTINE_SUSPENDED → 函数返回，线程释放
    │       │
    │       ▼  (异步完成后)
    │   sm.resumeWith(user) → 重新调用 loadData(sm)
    │       │
    │       ▼
    │   label=1 → 拿到 user，调用 getOrderList(user, sm)
    │       │
    │       ├─ 返回 COROUTINE_SUSPENDED → 函数返回，线程释放
    │       │       │
    │       │       ▼  (异步完成后)
    │       │   sm.resumeWith(orders) → 重新调用 loadData(sm)
    │       │       │
    │       │       ▼
    │       │   label=2 → 拿到 orders，调用 updateUI
    │       │       │
    │       │       ▼
    │       │     完成
```

#### 3.5 一句话总结

> **协程的 suspend 函数在编译后变成了一个状态机。每个挂起点是一个状态（label），函数被多次调用，每次从上次挂起的状态继续执行。Continuation 对象既是回调也是状态机本身，保存了 label 和中间变量。**

---

### 四、协程的创建与启动

#### 4.1 launch 与 async

```kotlin
// launch：启动协程，不关心返回值，返回 Job
val job = scope.launch {
    doSomething()
}

// async：启动协程，可以获取返回值，返回 Deferred
val deferred = scope.async {
    getResult()
}
val result = deferred.await()  // 挂起等待结果
```

#### 4.2 启动流程

```
launch(context) { block }
    │
    ▼
创建 AbstractCoroutine（如 StandaloneCoroutine）
    │
    ▼
createCoroutineUnintercepted(block, coroutine)
  → 创建 block 对应的状态机实例（SuspendLambda 子类）
    │
    ▼
intercepted()
  → 用 CoroutineDispatcher 包装 Continuation（DispatchedContinuation）
    │
    ▼
resumeCancellableWith(Unit)
  → 通过 Dispatcher 调度到目标线程执行
    │
    ▼
状态机的 invokeSuspend() 开始执行
```

---

### 五、协程调度器（CoroutineDispatcher）

调度器决定协程在**哪个线程**上执行。

| 调度器 | 线程 | 适用场景 |
|--------|------|----------|
| `Dispatchers.Main` | 主线程 | UI 操作 |
| `Dispatchers.IO` | 共享线程池（最多 64 线程） | 网络请求、文件读写 |
| `Dispatchers.Default` | CPU 核心数线程池 | CPU 密集型计算 |
| `Dispatchers.Unconfined` | 不切换线程 | 测试、特殊场景 |

#### 线程切换原理

```kotlin
suspend fun example() {
    // 当前在主线程
    withContext(Dispatchers.IO) {
        // 切换到 IO 线程执行
        val data = readFile()
    }
    // 自动切回主线程
}
```

底层原理：`DispatchedContinuation` 在 `resumeWith` 时调用 `dispatcher.dispatch(context, block)`，将 Runnable 提交到目标线程池执行。切换回来同理。

---

### 六、协程的取消与异常

#### 6.1 结构化并发

- 协程通过 **CoroutineScope** 管理生命周期
- 父协程取消时，所有子协程也会被取消
- 子协程异常会传播到父协程

```kotlin
val scope = CoroutineScope(Dispatchers.Main + Job())

scope.launch {              // 父协程
    launch { task1() }      // 子协程 1
    launch { task2() }      // 子协程 2
}

scope.cancel()  // 取消所有协程
```

#### 6.2 取消原理

- 取消本质是将 Job 的状态设为 `Cancelling`
- 在挂起点（`suspendCancellableCoroutine`）检查取消状态，抛出 `CancellationException`
- **CPU 密集型任务不会自动响应取消**，需要主动检查：

```kotlin
suspend fun cpuTask() {
    while (isActive) {  // 主动检查取消状态
        // 计算...
    }
}
```

#### 6.3 SupervisorJob

普通 Job：一个子协程失败 → 取消所有兄弟协程
SupervisorJob：一个子协程失败 → 不影响其他子协程

```kotlin
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
```

---

### 七、面试高频问题速答

**Q: 协程和线程的区别？**
> 线程是操作系统调度的最小单位，切换需要内核态参与；协程是用户态的，切换只是函数调用，开销极小。一个线程可以跑多个协程。协程本质上还是依赖线程执行，是线程框架的上层封装。

**Q: suspend 关键字的作用？**
> 标记函数可能挂起。编译器对其做 CPS 变换：添加 Continuation 参数，返回值变为 Object。suspend 本身不挂起，真正挂起靠 `suspendCancellableCoroutine` 等。

**Q: 协程挂起时线程去哪了？**
> 线程没有阻塞，函数返回 `COROUTINE_SUSPENDED` 后线程就释放了，可以去执行其他任务。等异步操作完成后，通过 `Continuation.resumeWith()` 恢复执行，可能在原线程也可能在其他线程（取决于调度器）。

**Q: 协程的状态机是怎么回事？**
> 编译器将 suspend 函数中的多个挂起点转换为 switch-case 状态机。每个挂起点对应一个 label 状态。函数每次被调用时根据 label 跳到对应的分支执行。中间变量保存在 Continuation 对象中。

**Q: withContext 是怎么切换线程的？**
> withContext 创建新的协程上下文，通过 DispatchedContinuation 将任务 dispatch 到目标线程池。执行完毕后，恢复时再 dispatch 回原来的调度器对应的线程。

**Q: launch 和 async 的区别？**
> launch 返回 Job，不携带结果，适合"发射后不管"的场景；async 返回 Deferred，可通过 await() 获取结果。await() 本身是 suspend 函数，会挂起等待。

**Q: 协程如何保证取消安全？**
> 结构化并发保证父协程取消时子协程也取消。取消通过 CancellationException 在挂起点抛出实现。CPU 密集任务需手动检查 isActive。使用 `NonCancellable` 可以在取消时执行清理操作。