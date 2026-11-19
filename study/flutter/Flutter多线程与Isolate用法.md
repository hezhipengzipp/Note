# Flutter 多线程与 Isolate 用法

## 1. 先理解：`async` 不等于多线程

Flutter 的 Dart 代码默认运行在**主 Isolate**。每个 Isolate 有自己的内存和事件循环；主 Isolate 负责处理输入、构建界面等工作。`Future` / `async` / `await` 让等待 I/O 的代码不阻塞事件循环，但 `await` 不会把同步计算自动搬到其他线程。

```dart
// 网络等待：直接 await 即可。
final response = await http.get(uri);

// 大量同步计算：即使函数写在 async 中，仍会占用当前 Isolate。
Future<int> calculate() async {
  return expensiveCalculation();
}
```

当 JSON 解析、图片处理、压缩或复杂计算造成界面掉帧时，再考虑后台 Isolate。创建 Isolate 和传递数据都有开销，短小计算通常直接执行更合适。是否需要迁移，以实际设备上的性能分析结果为准。

## 2. 三种常用方式

| 需求 | API | 特点 |
| --- | --- | --- |
| 一次性 CPU 计算，移动端或桌面端 | `Isolate.run` | 执行完成后返回结果并结束 Isolate |
| 一次性计算，同时支持 Flutter Web | `compute` | Web 上仍在主线程执行，不能消除 Web 的计算卡顿 |
| 持续处理任务、双向通信 | `Isolate.spawn` + Port | 自行管理消息、异常与生命周期 |

### `Isolate.run`：一次性任务

```dart
import 'dart:convert';
import 'dart:isolate';

Future<int> countItems(String jsonText) {
  return Isolate.run(() {
    final items = jsonDecode(jsonText) as List<dynamic>;
    return items.length;
  });
}

final count = await countItems(largeJsonText);
```

`Isolate.run` 会等待计算结果；计算抛出的异常会通过返回的 `Future` 传递，可以用 `try/catch` 处理。只传计算需要的数据，避免闭包意外捕获 `BuildContext`、控制器或大对象。

### `compute`：兼容 Flutter Web 的写法

```dart
import 'dart:convert';
import 'package:flutter/foundation.dart';

int countItems(String jsonText) {
  return (jsonDecode(jsonText) as List<dynamic>).length;
}

final count = await compute(countItems, largeJsonText);
```

移动端和桌面端，`compute(callback, message)` 的效果相当于 `Isolate.run(() => callback(message))`。Flutter Web 不支持 Dart Isolate，`compute` 在 Web 主线程执行；它能保持代码跨平台，但不能让 Web 的 CPU 计算并行。回调宜使用顶层函数或静态方法，并让消息和结果保持可发送。

### `Isolate.spawn`：长期运行的 worker

下面的 worker 可持续接收整数并返回平方；调用方演示发送一次任务。主 Isolate 先拿到 worker 的 `SendPort`，随后通过端口发送任务。`null` 表示停止 worker。

```dart
import 'dart:async';
import 'dart:isolate';

void squareWorker(SendPort mainPort) {
  final input = ReceivePort();
  mainPort.send(input.sendPort);

  input.listen((message) {
    if (message == null) {
      input.close();
      mainPort.send('stopped');
      return;
    }
    final number = message as int;
    mainPort.send(number * number);
  });
}

Future<void> main() async {
  final output = ReceivePort();
  final ready = Completer<SendPort>();
  final result = Completer<int>();
  final stopped = Completer<void>();

  output.listen((message) {
    if (message is SendPort) {
      ready.complete(message);
    } else if (message is int) {
      result.complete(message);
    } else if (message == 'stopped') {
      stopped.complete();
    }
  });

  final worker = await Isolate.spawn(squareWorker, output.sendPort);
  try {
    final input = await ready.future;
    input.send(12);
    print(await result.future); // 144
    input.send(null);
    await stopped.future;
  } finally {
    output.close();
    worker.kill(priority: Isolate.immediate);
  }
}
```

这个例子只等待一个结果。生产环境如果并发发送多个任务，应给每个请求分配 ID、按 ID 匹配响应，并处理 worker 错误、超时和关闭。

## 3. 数据传递与限制

- Isolate **不共享可变内存**。通过 `SendPort.send` 传递的可变对象通常会复制；大数据往返可能抵消并行收益。不可变对象可共享，`Isolate.run` 返回结果时利用 `Isolate.exit` 转移结果。
- 消息必须是可发送对象。不要传 `BuildContext`、Widget、打开的文件句柄等；优先传字符串、数字、列表、Map 或必要的字节数据。
- 后台 Isolate 不能构建 UI，也不能直接使用依赖主 Isolate 的 `dart:ui` 功能或 `rootBundle`。可以先在主 Isolate 读取资源，再把数据交给 worker。
- 部分插件调用可通过 `BackgroundIsolateBinaryMessenger` 在后台 Isolate 发起请求，但不能接收平台主动推送的消息；具体插件还需确认是否支持这种用法。
- `Isolate.spawn` 和 `Isolate.run` 不适用于 Flutter Web。跨平台一次性任务可先用 `compute`，同时记住 Web 上仍需单独解决长时间计算导致的卡顿。

## 4. 选择顺序

1. 只是等待网络、文件等异步 I/O：直接 `await`。
2. 同步计算造成掉帧：移动端/桌面端用 `Isolate.run`；需要兼容 Web 的 API 可用 `compute`。
3. 高频重复计算且创建 Isolate 的开销明显：考虑复用 `Isolate.spawn` 创建的 worker。
4. 用 DevTools 的时间线和真实输入数据验证收益；别只凭数据大小或固定毫秒阈值判断。

## 参考资料

- [Flutter：Concurrency and isolates](https://docs.flutter.dev/perf/isolates)
- [Dart：Isolates](https://dart.dev/language/isolates)
- [Dart API：Isolate.run](https://api.dart.dev/dart-isolate/Isolate/run.html)
- [Flutter：Parse JSON in the background](https://docs.flutter.dev/cookbook/networking/background-parsing)
