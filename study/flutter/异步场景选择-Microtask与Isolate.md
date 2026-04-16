# Flutter 异步场景选择——什么时候用 Microtask、Isolate

---

## 一、决策图

```
你要做什么异步操作？
    │
    ├─ IO 操作（网络、文件、数据库）
    │   → 直接 await / Future
    │   → 底层已经在 IO 线程跑了，你不用管
    │
    ├─ 想在"当前 Event 之后、下一个 Event 之前"立刻执行
    │   → scheduleMicrotask
    │   → 极少用，99% 的人用不到
    │
    └─ CPU 密集计算（耗时 > 16ms 的纯计算）
        → Isolate / compute
        → 不用就掉帧
```

---

## 二、大多数情况：直接 await

```dart
// 网络请求 → await
final response = await http.get(url);

// 文件读写 → await
final content = await File('path').readAsString();

// 数据库查询 → await
final users = await db.query('users');

// SharedPreferences → await
final prefs = await SharedPreferences.getInstance();
```

```
这些操作底层已经在 IO 线程/线程池跑了
你的 Dart 代码只是在等回调
UI 线程不会被阻塞，不会掉帧
不需要 Microtask，不需要 Isolate
```

---

## 三、Microtask：在当前 Event 和下一个 Event 之间插队

```
Event Loop 的执行顺序：

  处理 Event A
      │
      ▼
  清空 Microtask Queue ← Microtask 在这里执行
      │
      ▼
  处理 Event B
      │
      ▼
  清空 Microtask Queue
      │
      ▼
  处理 Event C
```

### 什么时候用

```dart
// 场景一：确保在当前帧内、其他 Event 之前执行
// 实际开发中极少手动用

scheduleMicrotask(() {
  // 在当前 Event 处理完后立即执行
  // 比下一个 Timer、IO 回调、用户输入都早
});


// 场景二：Future.microtask（语义更清晰）
Future.microtask(() {
  // 同上，只是包了一层 Future
});


// 场景三：框架内部用（你基本不会手写）
// Flutter 的 setState 内部就是用类似机制
// 把 rebuild 安排为 Microtask
// 保证同一帧内多次 setState 只 rebuild 一次
```

### 实际开发中几乎用不到的原因

```
你能用 Microtask 的地方，用 Future 也行：

  // 这两个效果几乎一样：
  scheduleMicrotask(() => doSomething());
  Future(() => doSomething());

  // 区别只是 Microtask 比 Future 早一点点执行
  // 这个"一点点"在业务代码中几乎不重要

  // 框架内部用 Microtask 是为了精确控制执行时序
  // 应用层开发者不需要这种精度
```

**结论：你不主动用 Microtask 是完全正确的。**

---

## 四、Isolate：CPU 密集计算超过 16ms 时必须用

### 判断标准

```
一帧 = 16.6ms（60fps）

你的计算耗时：
  < 1ms     → 直接同步做，不用任何异步
  1~16ms    → 可以直接做，可能偶尔掉帧，看情况
  > 16ms    → 必须用 Isolate，否则卡 UI
  > 100ms   → 不用 Isolate 用户一定能感觉到卡
```

### 典型场景

```dart
// ❌ 这些操作在主 Isolate 做会卡 UI：

// 1. 大 JSON 解析
final data = jsonDecode(hugeJsonString);  // 10MB JSON → 卡 200ms+

// 2. 图片处理
final resized = resizeImage(rawBytes);    // 大图缩放 → 卡 100ms+

// 3. 加密/解密
final encrypted = aes.encrypt(bigData);   // 大数据加密 → 卡

// 4. 复杂排序/搜索
list.sort(complexComparator);             // 10万条数据排序 → 卡

// 5. 文件压缩/解压
final zipped = gzip.encode(bigData);      // 压缩大文件 → 卡
```

### 用法

```dart
// ① 最简单：compute（一次性任务）
final result = await compute(jsonDecode, hugeJsonString);
// compute 自动创建 Isolate → 执行 → 返回结果 → 销毁 Isolate
// 主 Isolate UI 不受影响


// ② 复杂参数：compute + 自定义函数
// 注意：传给 compute 的函数必须是顶层函数或静态方法
List<User> parseUsers(String json) {
  final list = jsonDecode(json) as List;
  return list.map((e) => User.fromJson(e)).toList();
}

final users = await compute(parseUsers, jsonString);


// ③ 长期运行：手动创建 Isolate
// 适合需要反复通信的场景（如持续的图片处理）
final receivePort = ReceivePort();
await Isolate.spawn(workerFunction, receivePort.sendPort);

receivePort.listen((message) {
  // 收到 Worker Isolate 的消息
});

void workerFunction(SendPort sendPort) {
  // 在独立 Isolate 中运行
  // 通过 sendPort 发消息回主 Isolate
  sendPort.send(result);
}
```

---

## 五、对比总结

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  IO 操作（网络/文件/数据库）                                  │
│  → 直接 await                                               │
│  → 底层 IO 线程处理，主线程不阻塞                              │
│  → 你写的 90% 的异步代码都是这种                               │
│                                                             │
│  ─────────────────────────────────────────────────           │
│                                                             │
│  Microtask                                                  │
│  → 框架内部用（setState、调度器）                              │
│  → 你几乎不需要手动用                                        │
│  → 如果你在纠结要不要用 Microtask                             │
│    答案就是不用                                               │
│                                                             │
│  ─────────────────────────────────────────────────           │
│                                                             │
│  Isolate / compute                                          │
│  → CPU 密集计算（JSON 解析、图片处理、加密、排序）              │
│  → 判断标准：这段计算跑一次超过 16ms 吗？                      │
│  → 超过就用，不超过就不用                                     │
│  → 简单任务用 compute，长期任务手动 Isolate                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

| | await / Future | Microtask | Isolate |
|---|---|---|---|
| 解决什么 | IO 等待 | 精确时序控制 | CPU 密集计算 |
| 会阻塞 UI？ | 不会 | 会（如果任务重） | **不会** |
| 使用频率 | **90%** | <1% | ~10% |
| 典型场景 | 网络、文件、DB | 框架内部调度 | JSON 解析、图片处理 |
| 是否开新线程 | IO 层自动开 | 不开（主线程跑） | **开**（独立内存） |

---

## 六、面试回答模板

**Q: Flutter 异步什么时候用 Microtask，什么时候用 Isolate？**

> 大多数异步操作直接 await 就行，网络请求、文件读写底层已经在 IO 线程处理，不会阻塞 UI。Microtask 用于在当前 Event 处理完后、下一个 Event 之前立即执行，主要是框架内部使用，比如 setState 的调度机制，应用层开发几乎不需要手动用。Isolate 用于 CPU 密集型计算——大 JSON 解析、图片处理、加密等耗时超过 16ms 的纯计算任务，不用 Isolate 就会阻塞 UI 线程导致掉帧。简单的一次性计算用 compute，需要持续通信的用手动 Isolate。判断标准就一个：这段计算会不会超过一帧的时间（16ms），超过就用 Isolate。
