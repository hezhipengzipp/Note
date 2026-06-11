# Flutter Future 和 Completer 使用指南

## 什么是 Future？

`Future` 代表一个异步操作的结果。当你调用一个异步函数时，它会立即返回一个 `Future` 对象，该对象最终会包含操作的结果或错误。

```dart
Future<String> fetchData() async {
  await Future.delayed(Duration(seconds: 2));
  return 'Data loaded';
}

void main() async {
  print('开始加载...');
  String result = await fetchData();
  print(result);
}
```

## 什么是 Completer？

`Completer` 是一个用于手动创建和控制 `Future` 的工具。它允许你在非 async/await 的场景下（比如回调函数）创建和完成 Future。

```dart
import 'dart:async';

Completer<String> completer = Completer<String>();

// 获取这个 Future
Future<String> future = completer.future;

// 在某个时刻完成它
completer.complete('完成了！');

// 或者以错误完成
completer.completeError('出错了！');
```

## 何时需要手动使用 Completer？

| 场景 | 是否需要用 Completer |
|------|---------------------|
| 普通的 async/await 函数 | ❌ 不需要（Dart 自动处理） |
| 将回调 API 转为 Future | ✅ 需要 |
| 等待一个外部事件（用户点击、定时器结束） | ✅ 需要 |
| 创建可取消的 Future | ✅ 需要 |
| 单元测试中控制异步完成时机 | ✅ 需要 |

## 使用场景详解

### 1. 普通的 async/await 函数（不需要 Completer）

对于常规的异步操作，直接使用 `async/await` 即可：

```dart
Future<int> calculateSum(int a, int b) async {
  await Future.delayed(Duration(milliseconds: 100));
  return a + b;
}

void main() async {
  int result = await calculateSum(5, 3);
  print('结果: $result'); // 输出: 结果: 8
}
```

### 2. 将回调 API 转为 Future（需要 Completer）

许多旧的 API 使用回调而不是 Future。使用 Completer 可以将它们转换为 Future：

```dart
import 'dart:async';

class OldCallbackAPI {
  void fetchData(Function(String) onSuccess, Function(String) onError) {
    Future.delayed(Duration(seconds: 1), () {
      if (DateTime.now().second % 2 == 0) {
        onSuccess('数据加载成功');
      } else {
        onError('加载失败');
      }
    });
  }
}

Future<String> fetchDataAsFuture() {
  final completer = Completer<String>();
  final api = OldCallbackAPI();
  
  api.fetchData(
    (result) => completer.complete(result),
    (error) => completer.completeError(error),
  );
  
  return completer.future;
}

void main() async {
  try {
    String result = await fetchDataAsFuture();
    print(result);
  } catch (e) {
    print('错误: $e');
  }
}
```

### 3. 等待外部事件（需要 Completer）

当需要等待用户交互或其他外部事件时：

```dart
import 'dart:async';
import 'package:flutter/material.dart';

class WaitForButtonPress extends StatefulWidget {
  @override
  _WaitForButtonPressState createState() => _WaitForButtonPressState();
}

class _WaitForButtonPressState extends State<WaitForButtonPress> {
  Completer<String>? _completer;

  Future<String> waitForUserInput() {
    _completer = Completer<String>();
    return _completer!.future;
  }

  void _onButtonPressed(String value) {
    if (_completer != null && !_completer!.isCompleted) {
      _completer!.complete(value);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        ElevatedButton(
          onPressed: () => _onButtonPressed('选项 A'),
          child: Text('选项 A'),
        ),
        ElevatedButton(
          onPressed: () => _onButtonPressed('选项 B'),
          child: Text('选项 B'),
        ),
      ],
    );
  }
}

// 使用示例
void processUserChoice() async {
  final state = _WaitForButtonPressState();
  print('等待用户选择...');
  String choice = await state.waitForUserInput();
  print('用户选择了: $choice');
}
```

### 4. 创建可取消的 Future（需要 Completer）

使用 Completer 可以创建可以被取消的异步操作：

```dart
import 'dart:async';

class CancellableOperation {
  Completer<String>? _completer;
  Timer? _timer;

  Future<String> startOperation() {
    _completer = Completer<String>();
    
    _timer = Timer(Duration(seconds: 5), () {
      if (!_completer!.isCompleted) {
        _completer!.complete('操作完成');
      }
    });
    
    return _completer!.future;
  }

  void cancel() {
    _timer?.cancel();
    if (_completer != null && !_completer!.isCompleted) {
      _completer!.completeError('操作被取消');
    }
  }
}

void main() async {
  final operation = CancellableOperation();
  
  final future = operation.startOperation();
  
  // 2秒后取消操作
  Timer(Duration(seconds: 2), () {
    print('取消操作...');
    operation.cancel();
  });
  
  try {
    String result = await future;
    print(result);
  } catch (e) {
    print('错误: $e'); // 输出: 错误: 操作被取消
  }
}
```

### 5. 单元测试中控制异步完成时机（需要 Completer）

在测试中，Completer 可以让你精确控制异步操作何时完成：

```dart
import 'package:test/test.dart';
import 'dart:async';

class DataService {
  Future<String> fetchData() async {
    // 实际的网络请求
    return 'real data';
  }
}

class MockDataService extends DataService {
  Completer<String>? _completer;
  
  @override
  Future<String> fetchData() {
    _completer = Completer<String>();
    return _completer!.future;
  }
  
  void completeWithData(String data) {
    _completer?.complete(data);
  }
  
  void completeWithError(Object error) {
    _completer?.completeError(error);
  }
}

void main() {
  test('测试数据加载', () async {
    final mockService = MockDataService();
    
    // 开始异步操作
    final future = mockService.fetchData();
    
    // 此时 future 还未完成
    
    // 在测试的某个时刻手动完成
    mockService.completeWithData('test data');
    
    // 现在可以等待结果
    final result = await future;
    expect(result, 'test data');
  });
}
```

## Completer 的重要方法和属性

### 属性

- `future`: 获取与此 Completer 关联的 Future
- `isCompleted`: 检查 Future 是否已经完成

### 方法

- `complete([value])`: 成功完成 Future，可选地提供一个值
- `completeError(Object error, [StackTrace stackTrace])`: 以错误完成 Future

## 注意事项

1. **只能完成一次**: 一个 Completer 只能调用一次 `complete` 或 `completeError`。重复调用会抛出异常。

```dart
final completer = Completer<String>();
completer.complete('第一次');
// completer.complete('第二次'); // 这会抛出 StateError
```

2. **检查是否已完成**: 在完成之前检查 `isCompleted` 可以避免错误。

```dart
if (!completer.isCompleted) {
  completer.complete('安全完成');
}
```

3. **异常处理**: 使用 `completeError` 时记得传递错误信息。

```dart
try {
  // 某些操作
} catch (e, stackTrace) {
  completer.completeError(e, stackTrace);
}
```

## 完整示例：网络请求超时处理

```dart
import 'dart:async';
import 'dart:io';

Future<String> fetchWithTimeout(String url, Duration timeout) async {
  final completer = Completer<String>();
  
  // 启动实际的网络请求
  HttpClient()
      .getUrl(Uri.parse(url))
      .then((request) => request.close())
      .then((response) {
        return response.transform(utf8.decoder).join();
      })
      .then((data) {
        if (!completer.isCompleted) {
          completer.complete(data);
        }
      })
      .catchError((error) {
        if (!completer.isCompleted) {
          completer.completeError(error);
        }
      });
  
  // 设置超时
  Timer(timeout, () {
    if (!completer.isCompleted) {
      completer.completeError(TimeoutException('请求超时', timeout));
    }
  });
  
  return completer.future;
}

void main() async {
  try {
    String data = await fetchWithTimeout(
      'https://api.example.com/data',
      Duration(seconds: 5),
    );
    print('数据: $data');
  } catch (e) {
    if (e is TimeoutException) {
      print('请求超时了');
    } else {
      print('发生错误: $e');
    }
  }
}
```

## 总结

- **简单异步操作**: 使用 `async/await`
- **回调转 Future**: 使用 `Completer`
- **需要手动控制完成时机**: 使用 `Completer`
- **外部事件触发**: 使用 `Completer`

记住：大多数情况下你不需要 Completer，Dart 的 async/await 已经足够强大。只有在需要桥接非 Future 的异步代码或需要精确控制异步完成时机时才使用 Completer。
