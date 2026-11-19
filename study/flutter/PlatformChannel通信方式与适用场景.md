# Flutter Platform Channel：通信方向与适用场景

Platform Channel 用于 Flutter 的 Dart 代码与 Android、iOS 等平台代码交换消息。三种常用 Channel 的主要区别是**消息语义**：调用方法、收发普通消息，或订阅事件流。它们都基于 `BinaryMessenger`，双方需要约定相同的 Channel 名称与编解码方式。

## 一、快速对照

| 方式 | 通信方向 | 适用场景 | Dart 类与常用 API |
| --- | --- | --- | --- |
| MethodChannel | 双向；常见模式是 Dart 发起调用，Native 返回结果；Native 也可调用 Dart 注册的方法 | 类似 RPC：获取电量、查询系统状态、执行一次性原生动作 | `MethodChannel`：`invokeMethod`、`setMethodCallHandler` |
| BasicMessageChannel | 双向；Dart 和 Native 都可主动发消息，接收方可回复 | 双方都需要主动发送的字符串、结构化数据或二进制消息 | `BasicMessageChannel<T>`：`send`、`setMessageHandler` |
| EventChannel | 业务数据主要由 Native 持续推送到 Dart；Dart 负责订阅与取消 | 传感器、系统广播、定位或连接状态等连续事件 | `EventChannel`：`receiveBroadcastStream` |

> **“双向”不等于两边共享内存。** 每次通信都是编码、传递和解码消息。`EventChannel` 的事件方向是 Native → Dart，但 Dart 订阅和取消时仍会向 Native 发送控制消息。

## 二、MethodChannel：调用一个方法，取得一个结果

典型流程：

```text
Dart invokeMethod("getBatteryLevel") → Native 处理 → success / error → Dart Future 完成
```

```dart
import 'package:flutter/services.dart';

const channel = MethodChannel('example/device');

Future<int?> getBatteryLevel() {
  return channel.invokeMethod<int>('getBatteryLevel');
}
```

原生端按方法名分发，并**对每次调用回复一次**。以 Android Kotlin 为例：

```kotlin
val channel = MethodChannel(binaryMessenger, "example/device")
channel.setMethodCallHandler { call, result ->
    when (call.method) {
        "getBatteryLevel" -> result.success(85)
        else -> result.notImplemented()
    }
}
```

Native 也可以通过自己的 `invokeMethod` 主动调用 Dart，Dart 用 `setMethodCallHandler` 接收。所以 `MethodChannel` 的能力是双向的，只是项目中最常见的是 Dart 发起、Native 响应。它适合离散的“命令/查询”，不适合把持续变化的状态反复当作 RPC 轮询。

## 三、BasicMessageChannel：双方主动收发消息

它没有内置的方法名，也没有 `EventChannel` 的订阅语义。双方都可以发送一条消息，接收方处理后可以回复一条消息。消息结构与含义由业务协议定义。

```dart
import 'package:flutter/services.dart';

const messages = BasicMessageChannel<String>(
  'example/messages',
  StringCodec(),
);

void startMessages() {
  messages.setMessageHandler((message) async {
    print('Native 发来：$message');
    return 'Dart 已收到';
  });
}

Future<String?> sendMessage(String text) {
  return messages.send(text);
}
```

Android 侧要使用同名 Channel 和兼容的 `StringCodec`。如果要传列表或 Map，可使用两端兼容的 `StandardMessageCodec`；原始字节可选 `BinaryCodec`。**发送频繁并不自动意味着选它**：若数据天然是 Native 持续产生、Dart 只需订阅，`EventChannel` 的语义更直接。

## 四、EventChannel：订阅 Native 事件流

典型流程：

```text
Dart listen → Native onListen → Native 多次 success(event) → Dart Stream 收到事件
Dart cancel → Native onCancel → 停止事件源、释放资源
```

```dart
import 'dart:async';
import 'package:flutter/services.dart';

const events = EventChannel('example/sensor');

StreamSubscription<dynamic>? subscription;

void startListening() {
  subscription = events.receiveBroadcastStream().listen(
    (event) => print('传感器事件：$event'),
    onError: (Object error) => print('事件错误：$error'),
  );
}

Future<void> stopListening() async {
  await subscription?.cancel();
  subscription = null;
}
```

Android 侧用 `EventChannel.StreamHandler` 的 `onListen` 启动数据源，通过 `EventSink.success` 推送数据；`onCancel` 停止监听并清理资源。`receiveBroadcastStream()` 返回广播流，首次订阅触发原生端 `onListen`，最后一个订阅取消时触发 `onCancel`。

## 五、如何选择

1. **需要一个返回值或一次性动作**：选 `MethodChannel`。
2. **双方都要主动发送独立消息**：选 `BasicMessageChannel`，并明确消息格式与回复规则。
3. **Native 连续产生事件，Dart 订阅接收**：选 `EventChannel`；记得取消订阅和释放原生监听器。

Channel 名称、消息类型及 codec 必须在两端一致。Android 端通常在插件 attach 时注册 Channel，在 detach 时移除 handler 并释放资源。进一步的插件封装示例见 [FlutterPlugin 使用详解](FlutterPlugin使用详解.md)，底层传递过程见 [Platform Channel 底层原理](PlatformChannel底层原理.md)。

## 参考资料

- [Flutter 官方：Writing custom platform-specific code](https://docs.flutter.dev/platform-integration/platform-channels)
- [Flutter API：MethodChannel](https://api.flutter.dev/flutter/services/MethodChannel-class.html)
- [Flutter API：BasicMessageChannel](https://api.flutter.dev/flutter/services/BasicMessageChannel-class.html)
- [Flutter API：EventChannel.receiveBroadcastStream](https://api.flutter.dev/flutter/services/EventChannel/receiveBroadcastStream.html)
