# Dart Stream 原理与 Flutter 使用详解

## 一、Stream 是什么

`Stream<T>` 表示一段时间内陆续到达的异步事件序列。事件可以是数据、错误，最后还可能有一个结束事件。例如 WebSocket 消息、文件读取的字节块、传感器数据和数据库变化都可以用 Stream 表示。

```text
数据源 ── 数据 1 ── 数据 2 ── 错误 ── 数据 3 ── 结束
                       │
                       ▼
              StreamSubscription
                       │
                       ▼
                  监听者处理
```

错误事件不一定会结束流；是否继续取决于数据源以及监听时的配置。流也可能长期运行而不主动结束。

### 和 Future、Iterable 的区别

| 类型 | 表示什么 | 消费方式 |
|---|---|---|
| `Future<T>` | 未来一次成功结果或一次错误 | `await`、`then` |
| `Stream<T>` | 随时间到来的多个数据或错误事件 | `listen`、`await for` |
| `Iterable<T>` | 同步可迭代的一组元素 | `for`、`map` 等 |

可以把 Stream 想成异步的 Iterable：Iterable 通常由消费者主动取下一个元素；Stream 在事件可用时通知订阅者。`Stream<T>` 可以通过 `first`、`last` 等操作汇总成一个 `Future<T>`，这些操作也会订阅流。

## 二、如何消费 Stream

### 2.1 使用 listen

```dart
import 'dart:async';

final StreamSubscription<int> subscription = stream.listen(
  (value) => print('数据：$value'),
  onError: (Object error, StackTrace stackTrace) {
    print('错误：$error');
  },
  onDone: () => print('流已结束'),
  cancelOnError: false,
);

// 不再需要时取消；Future 完成表示订阅的清理过程完成。
await subscription.cancel();
```

`listen()` 返回 `StreamSubscription`，用于管理这一次订阅。`onDone` 表示流正常关闭并完成事件分发，不应把主动 `cancel()` 当成必然会触发 `onDone`。未处理的错误会交给当前 Zone；实际项目应明确错误处理策略。

### 2.2 使用 await for

```dart
Future<void> consume(Stream<int> stream) async {
  try {
    await for (final value in stream) {
      print(value);
      if (value == 3) break;
    }
  } catch (error, stackTrace) {
    print('处理失败：$error');
  }
}
```

`await for` 适合顺序消费事件；循环会等待数据，并在流结束时退出。遇到错误时会抛出异常并结束循环；`break` 等提前退出也会取消该循环的订阅。如果希望一个错误后继续接收后续事件，可用 `listen` 的 `onError` 来处理。

## 三、如何创建 Stream

### 3.1 async* 和 yield

```dart
Stream<int> countToThree() async* {
  for (var i = 1; i <= 3; i++) {
    await Future<void>.delayed(const Duration(seconds: 1));
    yield i;
  }
}

Future<void> main() async {
  await for (final value in countToThree()) {
    print(value); // 依次输出 1、2、3
  }
}
```

`async*` 声明异步生成器，`yield` 发送一个数据事件，函数执行结束后流关闭。`yield* otherStream` 可以转发另一个流的事件。每次调用生成器函数会创建一个新的单订阅流。

也可以用 `Stream.fromIterable(...)`、`Stream.periodic(...)` 等工厂创建流。注意持续产生事件的流需要合适的停止或取消机制。

### 3.2 StreamController

`StreamController` 适合将外部回调或主动产生的事件接入 Stream。示例只展示所有权和事件发送：

```dart
import 'dart:async';

class CounterEvents {
  final _controller = StreamController<int>();

  Stream<int> get stream => _controller.stream;

  void update(int value) => _controller.add(value);

  void reportError(Object error, StackTrace stackTrace) {
    _controller.addError(error, stackTrace);
  }

  Future<void> dispose() => _controller.close();
}
```

创建者负责关闭 controller；关闭后不能继续 `add`。消费方负责取消自己建立的订阅。单订阅 controller 在开始监听前可以缓冲事件，关闭操作的完成也可能需要等待监听者处理完成；不要假设 `close()` 总是立即完成。对于包裹定时器、Socket 等资源的 controller，还要在合适的 `onListen` / `onCancel` 等回调中管理底层资源。

## 四、单订阅流和广播流

| 特性 | 单订阅（single-subscription） | 广播（broadcast） |
|---|---|---|
| 监听次数 | 整个生命周期只能监听一次，即使取消后也不能再次监听 | 可以有多个监听者，也可以稍后重新监听 |
| 适合场景 | 文件字节块等需要完整顺序消费的数据 | 点击事件、通知等多个监听者分别消费的事件 |
| 没有监听者时 | 是否缓冲取决于具体数据源和实现 | 通常不会为未来监听者重放已经发出的事件 |

```dart
final controller = StreamController<int>.broadcast();
final a = controller.stream.listen((value) => print('A: $value'));
final b = controller.stream.listen((value) => print('B: $value'));
controller.add(1); // 当前订阅者 A、B 都可收到
await a.cancel();
await b.cancel();
await controller.close();
```

`broadcast` 不等于保存当前状态：新监听者通常只能收到订阅之后的事件，不会自动收到上一条。需要新订阅者立刻获得当前状态时，要单独保存并提供当前值，或使用具有明确重放语义的状态管理方案。

已有单订阅流可通过 `asBroadcastStream()` 包装，但底层仍只有一个上游订阅；无下游监听时如何暂停或取消上游，需要根据其 `onListen`、`onCancel` 策略设计。广播不会自动给事件增加重放能力。

## 五、转换、过滤和组合

常用操作符会返回新 Stream，一般保持源流的单订阅或广播属性；具体以 API 契约为准。

```dart
final labels = numbers
    .where((number) => number.isEven)
    .map((number) => '偶数：$number')
    .distinct()
    .take(3);

await for (final label in labels) {
  print(label);
}
```

- `map`：转换每个数据事件；`where`：过滤数据。
- `distinct`：过滤**相邻**且相等的事件，不是全局去重。
- `take(n)`：最多消费前 `n` 个数据事件，然后结束该派生流并取消其上游订阅。
- `asyncMap`：用异步函数依次转换数据；`timeout`：处理一定时间内没有事件的情况。
- `handleError`：按条件处理错误；`transform`：应用自定义 `StreamTransformer`。

派生流仍会订阅上游：对单订阅流分别调用 `first` 和 `listen`，通常会因为第二次订阅而失败。若确实要多处消费，应先选择适当的共享方式，并考虑是否允许丢失事件。

## 六、事件循环、暂停和取消

Stream 本身不是线程，也不会自动开启 isolate。事件通常通过当前 isolate 的事件循环交付；事件来源可能是 Dart 代码、定时器、系统 I/O 或其他平台接口。具体交付时机取决于流的实现；例如同步 controller 与默认异步 controller 不应假定行为相同。

监听回调中的同步耗时操作仍会占用当前 isolate。在 Flutter UI isolate 处理大量计算时可能导致卡顿；CPU 密集计算可考虑使用 `Isolate.run()` 或 `compute()`，并考虑数据传递成本。

```dart
subscription.pause();  // 暂停向该订阅交付事件
subscription.resume(); // 恢复
await subscription.cancel(); // 停止该订阅并等待清理
```

暂停订阅不保证底层生产者一定停止工作：如果数据源继续产生事件，订阅可能缓冲它们，长时间暂停会带来内存压力。单订阅流的上游通常能收到暂停通知；广播流的各个订阅独立暂停，其他监听者不受影响。若中间事件不重要，取消广播订阅并在需要时重新监听可能更合适。取消订阅也不一定关闭共享数据源，取决于数据源的所有权和实现。

## 七、Flutter 中使用 StreamBuilder

`StreamBuilder` 将最新的流交互状态包装为 `AsyncSnapshot`，用于构建界面。

```dart
import 'package:flutter/material.dart';

class CounterView extends StatefulWidget {
  const CounterView({super.key});

  @override
  State<CounterView> createState() => _CounterViewState();
}

class _CounterViewState extends State<CounterView> {
  late final Stream<int> _counterStream;

  @override
  void initState() {
    super.initState();
    _counterStream = Stream<int>.periodic(
      const Duration(seconds: 1),
      (count) => count + 1,
    ).take(10);
  }

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<int>(
      stream: _counterStream,
      initialData: 0,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return Text('发生错误：${snapshot.error}');
        }
        if (snapshot.connectionState == ConnectionState.waiting &&
            !snapshot.hasData) {
          return const CircularProgressIndicator();
        }
        return Text('计数：${snapshot.data ?? 0}');
      },
    );
  }
}
```

此例设置了 `initialData: 0`，因此首次构建已有数据，等待中的 loading 分支通常不会显示；如果希望等待首个事件时显示 loading，可以去掉 `initialData`。

使用要点：

- 在 `initState`、`didUpdateWidget` 或 `didChangeDependencies` 等合适的位置获取流，不要在 `build()` 中反复创建，否则父组件重建可能重新订阅或重启任务。
- `StreamBuilder` 自己管理其订阅，销毁或切换 stream 时会取消旧订阅；但不会替你关闭外部持有的 controller、Socket 等资源。
- builder 的执行由 Flutter 渲染管线调度，可能不会逐条看到所有中间事件。不要在 builder 中执行必须每条都处理的业务副作用；这类逻辑应放在独立订阅或业务层。
- 流切换时 snapshot 可能保留先前数据；需要根据产品语义处理 `none`、`waiting`、`active`、`done` 等状态。

手动在 `State` 中调用 `listen` 时，应在 `dispose` 中取消订阅；如果流由 widget 参数提供，还要在 `didUpdateWidget` 中处理旧订阅取消和新订阅建立。通常 `dispose()` 不能等待异步取消结果，底层资源的清理策略应由拥有者设计。

## 八、常见问题

| 问题 | 原因或处理方式 |
|---|---|
| `Bad state: Stream has already been listened to` | 单订阅流被监听多次；检查 `listen`、`first`、`await for` 和多个 builder 是否重复订阅。 |
| 新监听者没有收到之前的数据 | 普通广播流不重放历史；另行保存当前状态或使用明确支持重放的方案。 |
| 页面重建后请求重新开始 | 检查是否在 `build` 内创建流，并将其移到适合的生命周期。 |
| 页面销毁后仍收到回调 | 检查手动创建的 `StreamSubscription` 是否取消，以及数据源是否释放。 |
| 暂停后内存持续增长 | 上游仍在生产，事件在订阅中缓冲；让生产者响应暂停，或在可丢事件时取消订阅。 |
| `async` 回调仍导致页面卡顿 | 回调中的同步计算仍在 UI isolate 执行；考虑隔离耗时计算。 |
| UI 没有展示每一个中间值 | `StreamBuilder` 根据渲染时机重建，不保证每次事件都独立构建一帧。 |

## 九、面试速答

**Stream 的核心作用是什么？**

> Stream 表示一系列异步数据、错误和结束事件。通过 `listen` 建立订阅后，`StreamSubscription` 负责向消费者交付事件，并支持暂停、恢复和取消。它是一种事件抽象，不等于线程。

**单订阅和广播有什么区别？**

> 单订阅流一生只能监听一次，适合需要按顺序完整消费的数据；广播流可以有多个监听者，通常只向当前监听者交付事件，不自动重放历史。

**StreamBuilder 会处理每条事件吗？**

> 它根据流状态安排界面更新，但 builder 的调用受渲染时机影响，可能只看到部分中间快照。需要逐条执行的业务逻辑应放在业务层订阅中。

## 参考资料

- [Dart：Asynchronous programming: Streams](https://dart.dev/libraries/async/using-streams)
- [Dart：Creating streams](https://dart.dev/libraries/async/creating-streams)
- [Dart API：Stream](https://api.dart.dev/dart-async/Stream-class.html)
- [Dart API：StreamSubscription.pause](https://api.dart.dev/dart-async/StreamSubscription/pause.html)
- [Flutter API：StreamBuilder](https://api.flutter.dev/flutter/widgets/StreamBuilder-class.html)
