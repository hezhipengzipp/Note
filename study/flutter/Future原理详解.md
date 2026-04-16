# Future 原理详解

> Future 没有任何魔法，本质就是**回调注册 + ID 映射 + PostTask 通知**。

---

## 一、Future 就是一个"欠条"

```
同步函数：
  String getData() {
    return "hello";    // 当场给你结果
  }

异步函数：
  Future<String> getData() async {
    await Future.delayed(Duration(seconds: 1));
    return "hello";    // 1 秒后才有结果
  }

  Future<String> 的意思：
  "我现在给不了你 String，先给你一张欠条（Future）
   等数据好了，欠条自动兑现"
```

类比：

```
去奶茶店点单：

同步（阻塞）：
  你站在柜台等，奶茶做好了直接给你
  → String tea = makeTea();

异步（Future）：
  店员给你一个取餐号（Future）
  你先去逛街（线程去干别的）
  奶茶好了叫号（Future 完成）
  你回来取（await 恢复执行）
  → Future<String> tea = makeTea();
  → String result = await tea;
```

---

## 二、Future 的三种状态

```
Future<String> future = http.get(url);

状态一：Uncompleted（未完成）
┌──────────────────────────┐
│  Future<String>           │
│  status: pending          │
│  value: ???               │
│  "还在路上，别急"          │
└──────────────────────────┘

状态二：Completed with value（成功）
┌──────────────────────────┐
│  Future<String>           │
│  status: completed        │
│  value: "response data"   │
│  "到了！给你结果"          │
└──────────────────────────┘

状态三：Completed with error（失败）
┌──────────────────────────┐
│  Future<String>           │
│  status: error            │
│  error: TimeoutException  │
│  "出事了！给你报错"        │
└──────────────────────────┘
```

---

## 三、Future 的本质——一个回调容器

```dart
// Future 内部简化版（伪代码）
class Future<T> {
  T? _value;
  Object? _error;
  _State _state = _State.pending;
  List<Function> _callbacks = [];    // ← 回调列表

  // await 的本质：注册回调
  void then(Function(T) callback) {
    if (_state == _State.pending) {
      _callbacks.add(callback);      // 还没完成 → 存起来等通知
    } else {
      scheduleMicrotask(() => callback(_value));  // 已完成 → 立即调度
    }
  }

  // 数据到了调这个
  void complete(T value) {
    _value = value;
    _state = _State.completed;
    
    // 把所有注册的回调放入 Microtask Queue
    for (var cb in _callbacks) {
      scheduleMicrotask(() => cb(value));
    }
  }
}
```

---

## 四、await 的真面目

```dart
// 你写的代码：
Future<void> printWithDelay(String message) async {
  await Future.delayed(oneSecond);
  print(message);
}

// 编译器翻译成：
Future<void> printWithDelay(String message) {
  return Future.delayed(oneSecond).then((_) {
    print(message);    // await 后面的代码变成了 .then 的回调
  });
}
```

```
await 把函数切成两段：

               await 在这里切一刀
                      ↓
┌─── 前半段（同步执行）───┐  ┌─── 后半段（回调）──────────┐
│                        │  │                            │
│  Future.delayed(1s)    │  │  print(message);           │
│  → 拿到一个 Future     │  │  被包装成 callback          │
│  → 把后半段注册为       │  │  塞进 Future._callbacks    │
│    这个 Future 的回调   │  │                            │
│  → return（让出线程）   │  │                            │
└────────────────────────┘  └────────────────────────────┘
```

---

## 五、有 await 和没 await 的区别

```dart
// ① 有 await：等欠条兑现，拿到实际值
Future<void> test() async {
  String result = await getData();  // 等 Future 完成，拿到 String
  print(result);                    // 一定在 getData 完成后执行
}

// ② 没 await：只拿到欠条本身
Future<void> test() async {
  Future<String> future = getData();  // 只拿到 Future（欠条）
  print(future);                      // 打印 Instance of 'Future<String>'
                                      // 不是数据！是欠条本身！
}
```

```
有 await   = 你等叫号，拿到奶茶再走
没 await   = 你拿着取餐号就走了，奶茶还没做好
```

---

## 六、完整闭环：从发起请求到 await 恢复

```
你写的代码：
  final data = await http.get(url);
  print(data);

实际发生的 4 步：


① 注册回调
─────────────────────────────────────
  Future future = http.get(url);
  // Dart 层：
  //   创建 Completer（内含一个 Future）
  //   发起 Socket 请求
  //   分配唯一 ID（fd / handle）
  
  future.then((data) { print(data); });
  //   把 await 后面的代码注册为回调
  //   存入 Future._callbacks 列表
  
  return; // 函数让出线程


② 映射表记录 ID → 回调
─────────────────────────────────────
  C++ Engine 内部：

  ┌─ 映射表 ──────────────────────────┐
  │  fd:10 → Dart 侧的 Completer 指针  │
  │  fd:11 → Dart 侧的 Completer 指针  │
  └────────────────────────────────────┘

  fd:10 注册到 epoll 监听


③ 数据到了，PostTask 回 UI Runner
─────────────────────────────────────
  IO 线程：
    epoll 唤醒："fd:10 有数据了"
    查映射表 → 找到 fd:10 对应的 Completer
    
    ui_task_runner->PostTask(() {
      completer.complete(responseData);
    });


④ Event Loop 取出，触发回调
─────────────────────────────────────
  UI Runner：
    Event Loop 取出 Task
    执行 completer.complete(responseData)
        │
        ▼
    future._state = completed
    future._value = responseData
        │
        ▼
    遍历 _callbacks，每个放入 Microtask Queue
    scheduleMicrotask(() => callback(responseData))
        │
        ▼
    Event Loop 清空 Microtask
    执行 callback → print(data)
    
    → await 后面的代码跑起来了
```

一张图串起来：

```
Dart 层               C++ 层                 IO 线程
────────             ────────               ────────

http.get()
  │
  ├ 创建 Future
  ├ 注册 callback（await 后面的代码）
  ├ 分配 handle/fd
  │
  └─ FFI 调用 ──→  映射表记录 fd → Completer
                   注册 fd 到 epoll
                        │
     await 挂起          │
     线程去干别的         epoll_wait(fd)
                        │               数据到了！
                        │←──────────────┘
                   查映射表：fd → Completer
                   PostTask 到 UI Runner
                        │
  Event Queue ←─────────┘
     │
  取出 Task
  completer.complete(data)
     │
  Future._callbacks → Microtask Queue
     │
  执行 callback
  print(data)  ← await 恢复
```

---

## 七、一句话总结

```
Future   = 存回调的容器
await    = 把后续代码塞进容器
complete = 触发容器里的回调
ID/fd    = 保证数据能找到正确的容器

注册回调 → 分配ID → 数据到了按ID找回 → 触发回调

和 Android 的 OkHttp enqueue + Handler.post 是同一个套路
只是 Dart 用 await 语法糖把 callback 藏起来了
```

---

## 八、和 Kotlin 对比

```
Kotlin                          Dart
──────                         ──────
suspend fun                    async 函数
Deferred<T>                    Future<T>
deferred.await()               await future
delay(1000)                    Future.delayed(Duration(...))
launch { }                     Future(() { })（不等结果）
async { }                      创建 Future
```

```
Future<void>   = 欠条兑现后没有值，只表示"这件事做完了"
Future<String> = 欠条兑现后给你一个 String
```

---

## 九、面试回答模板

**Q: Future 的底层原理是什么？**

> Future 本质是一个回调容器。await 时编译器把后续代码注册为 Future 的 .then 回调，函数让出线程。底层发起 IO 时会分配一个唯一 ID（fd/handle），C++ Engine 把这个 ID 和 Dart 侧的 Completer 记录在映射表中，并注册到 epoll 监听。数据到达时 IO 线程通过 ID 找到对应的 Completer，PostTask 回 UI Runner。Event Loop 取出后执行 completer.complete()，把之前注册的回调放入 Microtask Queue，Event Loop 清空 Microtask 时 await 后面的代码恢复执行。整个过程就是回调注册 + ID 映射 + PostTask 通知，和 Android 的 OkHttp enqueue + Handler.post 回主线程是同一个套路。
