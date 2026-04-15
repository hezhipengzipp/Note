# Dart 单线程模型 + Event Loop

---

## 一、用 Android 类比

```
Android 主线程                         Dart 主 Isolate
──────────────                        ────────────────
Looper.loop()                    ←→   Event Loop
MessageQueue                     ←→   Event Queue + Microtask Queue
Handler.post(Runnable)           ←→   Future / scheduleMicrotask
new Thread()                     ←→   Isolate（独立内存，消息通信）
```

**核心区别**：Android 可以随意开线程共享内存，Dart 只有一个线程跑业务代码，靠 Event Loop 实现异步，靠 Isolate 实现并行（但内存隔离）。

---

## 二、什么是"单线程"

```
Dart 的单线程：

     你的所有 Dart 代码都在这一个线程上跑
     ┌──────────────────────────────────────────┐
     │                                          │
     │  main()                                  │
     │  build()                                 │
     │  setState()                              │
     │  网络回调处理                               │
     │  动画计算                                  │
     │  JSON 解析                                │
     │  ... 全部串行执行，同一时刻只有一件事在做      │
     │                                          │
     └──────────────────────────────────────────┘

     没有锁、没有竞态、没有死锁
     因为根本没有第二个线程来争抢！
```

**但是**，单线程不等于只能同步阻塞。Dart 通过 Event Loop 实现**非阻塞异步**。

---

## 三、Event Loop 机制

```
┌─────────────────────────────────────────────────────────┐
│                    Dart Event Loop                       │
│                                                         │
│                    ┌──────────┐                          │
│               ┌───→│ 取出任务  │───→ 执行 ──┐             │
│               │    └──────────┘            │             │
│               │         ↑                  │             │
│               │         │                  │             │
│               └─────────┴──────────────────┘             │
│                     无限循环                               │
│                                                         │
│  ┌─ Microtask Queue（微任务队列）──────────┐  优先级 高    │
│  │  scheduleMicrotask(() => ...)          │  ──────────  │
│  │  Future.then() 的回调（部分情况）        │  先清空这个   │
│  │  async 函数中 await 之后的代码           │              │
│  └────────────────────────────────────────┘              │
│                                                         │
│  ┌─ Event Queue（事件队列）────────────────┐  优先级 低    │
│  │  Timer / Future.delayed               │  ──────────  │
│  │  I/O 完成回调（网络、文件）              │  Microtask   │
│  │  用户输入事件（点击、滑动）              │  清空后才处理  │
│  │  绘制回调（VSync）                     │              │
│  └────────────────────────────────────────┘              │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

执行顺序的铁律：

```
main() 同步代码执行完毕
        │
        ▼
┌─ Event Loop 开始 ─────────────────────────┐
│                                           │
│  ① Microtask Queue 有任务？                │
│     │                                     │
│     ├─ YES → 取出一个执行                   │
│     │        执行完回到 ①（继续检查）         │
│     │        直到 Microtask Queue 清空      │
│     │                                     │
│     └─ NO  → 进入 ②                       │
│                                           │
│  ② Event Queue 有任务？                    │
│     │                                     │
│     ├─ YES → 取出一个执行                   │
│     │        执行完回到 ①（再检查 Microtask）│
│     │                                     │
│     └─ NO  → 空闲等待，有新任务时被唤醒       │
│                                           │
└───────────────────────────────────────────┘

关键：每处理完一个 Event，都要回去先清空 Microtask！
```

---

## 四、代码验证执行顺序

```dart
void main() {
  print('1. main start');

  Future(() => print('5. Future (Event Queue)'));

  Future.microtask(() => print('3. microtask 1'));

  Future.delayed(Duration.zero, () => print('6. Future.delayed'));

  scheduleMicrotask(() => print('4. microtask 2'));

  print('2. main end');
}

// 输出：
// 1. main start
// 2. main end        ← 同步代码先执行完
// 3. microtask 1     ← Microtask Queue 先清空
// 4. microtask 2
// 5. Future          ← 然后处理 Event Queue
// 6. Future.delayed
```

执行过程图解：

```
时间线 →

同步代码执行                Microtask Queue          Event Queue
──────────               ────────────────         ────────────
print('1. main start')
                          入队 microtask 1
                                                  入队 Future
                          入队 microtask 2
                                                  入队 Future.delayed
print('2. main end')
──── 同步代码结束 ────

Event Loop 启动 ↓

                          取出 microtask 1
                          print('3.')
                          取出 microtask 2
                          print('4.')
                          队列空了 ✓
                                                  取出 Future
                                                  print('5.')
                          检查 Microtask → 空
                                                  取出 Future.delayed
                                                  print('6.')
```

---

## 五、async / await 的本质

**async/await 不是多线程，是语法糖**，底层还是 Future + Event Loop：

```dart
// 你写的代码：
Future<String> fetchData() async {
  print('a. 开始请求');
  String result = await http.get(url);  // 看起来像阻塞
  print('b. 拿到结果');
  return result;
}

// 编译器翻译成：
Future<String> fetchData() {
  print('a. 开始请求');
  return http.get(url).then((result) {  // 实际是回调
    print('b. 拿到结果');
    return result;
  });
}
```

await 的执行流程：

```
fetchData() 被调用
    │
    print('a. 开始请求')     ← 同步执行
    │
    await http.get(url)
    │
    ├─ 发起 I/O 请求（底层交给系统线程池）
    │
    ├─ 当前函数暂停（让出线程！不是阻塞！）
    │   Event Loop 继续处理其他任务
    │   UI 继续刷新、动画继续跑
    │
    │   ... I/O 完成 ...
    │
    ├─ I/O 回调被放入 Event Queue / Microtask Queue
    │
    └─ Event Loop 取出回调，恢复函数执行
       print('b. 拿到结果')
```

---

## 六、Isolate——真正的并行

CPU 密集型任务（JSON 解析大数据、图片处理）会阻塞 Event Loop 导致掉帧，这时需要 Isolate：

```
主 Isolate                              Worker Isolate
┌───────────────────────┐              ┌───────────────────────┐
│  自己的堆内存            │              │  自己的堆内存            │
│  自己的 Event Loop      │              │  自己的 Event Loop      │
│  自己的 GC              │              │  自己的 GC              │
│                       │              │                       │
│  UI 代码               │   message    │  耗时计算               │
│  build / layout       │ ←──────────→ │  JSON 解析 10万条数据    │
│  动画                  │   passing    │  图片压缩               │
│                       │  （消息通信）   │  加密/解密              │
└───────────────────────┘              └───────────────────────┘
     不共享内存！不能互相访问对方的变量！
     只能通过 SendPort / ReceivePort 传消息
```

```dart
// 简单用法：compute 函数
// Flutter 封装好的，底层就是 Isolate
final result = await compute(parseJson, rawData);

// parseJson 在另一个 Isolate 中执行
// 主 Isolate 的 UI 不受影响
List<Item> parseJson(String raw) {
  final data = jsonDecode(raw);  // 耗时操作
  return data.map((e) => Item.fromJson(e)).toList();
}
```

### Isolate vs Android Thread

| | Isolate | Android Thread |
|---|---|---|
| 内存模型 | **独立堆**，互相不可见 | **共享堆**，可直接访问 |
| 通信方式 | 消息传递（SendPort） | 直接读写共享变量 |
| 是否需要锁 | 不需要（内存隔离） | 需要（synchronized） |
| 数据传递 | 拷贝或转移（TransferableTypedData） | 引用传递（零拷贝） |
| 创建开销 | 较大（独立堆 + Event Loop） | 较小 |
| 适用场景 | CPU 密集型计算 | 任意并发场景 |

---

## 七、经典面试题：输出顺序

```dart
void main() async {
  print('1');

  Future(() => print('2')).then((_) {
    print('3');
    scheduleMicrotask(() => print('4'));
  }).then((_) => print('5'));

  Future(() => print('6'));

  scheduleMicrotask(() => print('7'));

  print('8');
}
```

逐步分析：

```
步骤 1：执行同步代码
  print('1')                          → 输出 1
  注册 Future(() => print('2'))       → 进 Event Queue
  注册 Future(() => print('6'))       → 进 Event Queue
  scheduleMicrotask(print('7'))       → 进 Microtask Queue
  print('8')                          → 输出 8

此时队列状态：
  Microtask Queue: [print('7')]
  Event Queue:     [Future(print('2')), Future(print('6'))]

步骤 2：清空 Microtask Queue
  print('7')                          → 输出 7

步骤 3：取 Event Queue 第一个
  print('2')                          → 输出 2
  执行 .then: print('3')              → 输出 3
  then 里的 scheduleMicrotask         → print('4') 进 Microtask Queue
  执行第二个 .then: print('5')         → 输出 5

步骤 4：Event 处理完，回去检查 Microtask
  print('4')                          → 输出 4

步骤 5：取 Event Queue 下一个
  print('6')                          → 输出 6

最终输出：1 8 7 2 3 5 4 6
```

---

## 八、面试回答模板

**Q: Dart 的单线程模型是怎么实现异步的？**

> Dart 业务代码运行在单线程上，通过 Event Loop 实现非阻塞异步。Event Loop 维护两个队列：Microtask Queue 优先级高，每次处理完一个 Event 后都要先清空 Microtask；Event Queue 处理 I/O 回调、Timer、用户输入等。async/await 是语法糖，await 时函数暂停让出线程，I/O 交给底层 C++ 线程池，完成后回调进入 Event Queue，Event Loop 取出后恢复执行。由于单线程没有共享内存竞争，所以不需要锁。CPU 密集型任务用 Isolate 实现真正并行，但 Isolate 之间内存隔离，只能通过消息传递通信。

**Q: Microtask 和 Event 的区别？什么时候用 Microtask？**

> Microtask 优先级高于 Event，Event Loop 每处理完一个 Event 就会先把 Microtask Queue 清空。Future.then 的回调、async 函数 await 恢复后的代码都走 Microtask；Timer、I/O 回调、用户输入走 Event Queue。一般不需要手动用 scheduleMicrotask，它的典型场景是需要在当前 Event 结束后、下一个 Event 开始前立即执行的逻辑。注意不要在 Microtask 里做耗时操作，否则会阻塞 Event Queue 导致 UI 无响应。
