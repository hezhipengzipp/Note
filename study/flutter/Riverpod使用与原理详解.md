# Flutter Riverpod 使用与原理详解

> 本文以 Riverpod 3.x、手写 provider 为主，不要求代码生成。不同版本的 API 和生命周期行为可能不同，项目中应以实际依赖版本为准。

## 一、Riverpod 解决什么问题

Riverpod 是 Dart / Flutter 的响应式状态管理与依赖管理库。它把数据来源、状态和业务逻辑组织成 provider，UI 或其他 provider 可以读取并监听它们。

```text
Repository / 网络 / 数据库
          │
          ▼
      Provider 定义
          │ 创建、缓存状态并记录依赖
          ▼
ProviderContainer（Flutter 中由 ProviderScope 提供）
          │
          ▼
ref.watch → Widget 或其他 provider 随状态变化更新
```

全局声明的 provider 是状态的**定义和访问入口**，状态实际保存在 container 中，不等于全局变量本身存放状态。不同 container 可以拥有不同状态，这对测试和作用域隔离很有用。

典型用途：共享登录状态、管理页面交互、封装网络请求、组合数据来源和替换测试依赖。它并不限定项目必须采用某一种分层架构。

## 二、接入与第一个示例

```bash
flutter pub add flutter_riverpod
```

在应用根节点放置 `ProviderScope`。下面是可放在 `lib/main.dart` 中的完整计数器示例：

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

final counterProvider = NotifierProvider<Counter, int>(Counter.new);

class Counter extends Notifier<int> {
  @override
  int build() => 0;

  void increment() => state = state + 1;
}

void main() {
  runApp(const ProviderScope(child: MyApp()));
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(home: CounterPage());
  }
}

class CounterPage extends ConsumerWidget {
  const CounterPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final count = ref.watch(counterProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Riverpod 计数器')),
      body: Center(child: Text('$count')),
      floatingActionButton: FloatingActionButton(
        onPressed: () => ref.read(counterProvider.notifier).increment(),
        child: const Icon(Icons.add),
      ),
    );
  }
}
```

`Notifier.build()` 提供初始状态；调用 `increment()` 更新 `state`，监听该状态的 widget 会收到变化并更新界面。`counterProvider.notifier` 访问状态的管理对象，`counterProvider` 则读取状态值。

## 三、核心组成

| 概念 | 作用 |
|---|---|
| `ProviderScope` | 在 Flutter widget 树中提供 container，支持覆盖依赖 |
| Provider | 描述如何创建和暴露状态，以及依赖关系 |
| `Ref` / `WidgetRef` | 读取、监听 provider，或管理 provider 生命周期 |
| `Notifier` / `AsyncNotifier` | 将可变化的状态和修改状态的方法集中管理 |
| `AsyncValue<T>` | 表达异步数据、加载和错误状态 |
| `ConsumerWidget` / `ConsumerStatefulWidget` | 给 widget 提供 `WidgetRef` |

Provider 通常按需初始化和缓存：第一次被使用时创建状态；后续读取复用 container 中的状态。依赖变化、失效或销毁时可能重新计算。缓存不等于永久保存，具体取决于生命周期配置。

### 常用 provider 类型

| 类型 | 适合场景 |
|---|---|
| `Provider<T>` | 服务、仓库、配置或由其他状态派生的只读值 |
| `NotifierProvider<N, T>` | 同步状态和由用户操作触发的状态更新 |
| `FutureProvider<T>` | 请求或读取配置等异步结果 |
| `StreamProvider<T>` | WebSocket、数据库监听等连续异步事件 |
| `AsyncNotifierProvider<N, T>` | 异步初始化，同时需要暴露增删改等操作 |

## 四、watch、read、listen

| API | 行为 | 常见位置 |
|---|---|---|
| `ref.watch(provider)` | 读取并订阅变化；建立响应式依赖 | widget 的 `build`、provider 的构建逻辑 |
| `ref.read(provider)` | 读取当前值，不订阅变化 | 按钮点击、命令式操作 |
| `ref.listen(provider, callback)` | 状态变化时运行回调 | 展示提示、导航等副作用 |

```dart
// 根据状态渲染，变化时更新。
final count = ref.watch(counterProvider);

// 点击时执行命令，不需要在回调里建立 UI 订阅。
ref.read(counterProvider.notifier).increment();

// 在 ConsumerWidget.build 中注册监听：
ref.listen<int>(counterProvider, (previous, next) {
  if (next == 10) {
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('已达到 10')),
    );
  }
});
```

不要在 `onPressed`、异步回调等命令式代码里使用 `watch`；也不要为了避免重建而在 UI 渲染时把 `watch` 换成 `read`，否则页面不会随状态更新。`WidgetRef.listen` 用于 widget 的 `build`，需要在 `initState` 等生命周期中监听时使用 `listenManual` 并遵循其生命周期契约。

## 五、依赖组合和派生状态

Provider 可以监听另一个 provider，以声明数据依赖：

```dart
final unitPriceProvider = Provider<int>((ref) => 20);
final quantityProvider = NotifierProvider<Quantity, int>(Quantity.new);

class Quantity extends Notifier<int> {
  @override
  int build() => 1;

  void setQuantity(int value) => state = value;
}

final totalPriceProvider = Provider<int>((ref) {
  final price = ref.watch(unitPriceProvider);
  final quantity = ref.watch(quantityProvider);
  return price * quantity;
});
```

当价格或数量发生变化时，Riverpod 会使依赖它们的计算更新。把计算集中在派生 provider 中，可以让 UI 只关注结果。尽量避免在 `build` 中发起写操作或修改其他 provider；副作用放在明确的方法中。

## 六、异步数据和 AsyncValue

### 6.1 FutureProvider：加载一次异步数据

```dart
final userNameProvider = FutureProvider<String>((ref) async {
  // 示例中用延迟模拟请求；实际项目可从 ref.watch(repositoryProvider) 获取仓库。
  await Future<void>.delayed(const Duration(seconds: 1));
  return '小明';
});

class UserNameView extends ConsumerWidget {
  const UserNameView({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(userNameProvider);
    return user.when(
      data: (name) => Text(name),
      loading: () => const CircularProgressIndicator(),
      error: (error, stackTrace) => Text('加载失败：$error'),
    );
  }
}
```

`FutureProvider` 向 UI 暴露 `AsyncValue<String>`。刷新时状态可能同时携带旧数据和加载信息，`when` 的刷新行为也有配置；需要保留旧数据、展示刷新指示器时，应结合 `isLoading`、`hasValue` 等属性设计 UI。

### 6.2 AsyncNotifier：异步初始化和修改

```dart
final tasksProvider = AsyncNotifierProvider<Tasks, List<String>>(Tasks.new);

class Tasks extends AsyncNotifier<List<String>> {
  @override
  Future<List<String>> build() async {
    await Future<void>.delayed(const Duration(milliseconds: 300));
    return ['学习 Riverpod'];
  }

  void add(String title) {
    final current = state.value;
    if (current == null) return; // 尚未加载成功时不操作
    state = AsyncData([...current, title]);
  }
}
```

调用 `ref.read(tasksProvider.notifier).add('阅读文档')`。实际网络写入还应定义并发、失败、重试和状态回滚策略，不能只把本地列表更新当作服务端保存成功。

### 6.3 StreamProvider：持续变化的数据

```dart
final clockProvider = StreamProvider<DateTime>((ref) {
  return Stream.periodic(
    const Duration(seconds: 1),
    (_) => DateTime.now(),
  );
});
```

读取结果同样是 `AsyncValue<DateTime>`，可按数据、加载、错误渲染。Stream 不会因为交给 Riverpod 就自动在后台 isolate 执行；繁重同步处理仍可能阻塞 UI isolate。Stream 基础可参考 [Stream 原理与使用详解](Stream原理与使用详解.md)。

## 七、参数、刷新与生命周期

### 7.1 family：按参数区分状态

```dart
final greetingProvider = Provider.family<String, String>((ref, name) {
  return '你好，$name';
});

// ref.watch(greetingProvider('小明'));
```

不同参数对应各自的 provider 状态。参数应具备稳定、一致的 `==` 和 `hashCode`，不要每次构建都传入相等性不稳定的新对象。参数种类很多时尤其要考虑自动释放，避免积累缓存。

### 7.2 自动释放与资源清理

```dart
final tickerProvider = StreamProvider.autoDispose<int>((ref) {
  // 上游订阅随 provider 生命周期管理。
  return Stream.periodic(const Duration(seconds: 1), (i) => i);
});
```

手写 provider 默认不启用自动释放；加 `autoDispose` 后，provider 不再被监听时会按 Riverpod 生命周期规则进入销毁流程。代码生成的 provider 默认启用自动释放，可通过生成配置调整。无论是否自动释放，provider 状态在重新计算时也会被销毁和重建。

自行创建的资源要绑定清理逻辑：

```dart
final clockControllerProvider = Provider.autoDispose<StreamController<int>>((ref) {
  final controller = StreamController<int>();
  ref.onDispose(() {
    controller.close();
  });
  return controller;
});
```

该片段还需 `import 'dart:async';`。如果底层请求支持取消，也应在 `onDispose` 中取消；仅让 provider 失效并不保证 HTTP 请求自动停止。`onDispose` 应用于资源清理，避免在其中触发其他 provider 的状态修改。异步间隙后若需要继续使用 `ref`，还应检查 `ref.mounted`，避免访问已销毁的 provider。

### 7.3 invalidate 和 refresh

```dart
ref.invalidate(userNameProvider); // 使状态失效；有监听者时会重新计算
final fresh = ref.refresh(userNameProvider); // 失效并立即读取新状态
```

异步 provider 的 `refresh` 读取结果是对应的 `AsyncValue`；需要等待新请求结果时可使用 `ref.refresh(userNameProvider.future)`。刷新不等同于取消底层请求，取消需要数据源提供支持并接入清理。

## 八、测试与依赖替换

`ProviderContainer` 可在纯 Dart 测试中独立保存状态，不依赖 widget 树：

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

void main() {
  test('计数器递增', () {
    final container = ProviderContainer();
    addTearDown(container.dispose);

    expect(container.read(counterProvider), 0);
    container.read(counterProvider.notifier).increment();
    expect(container.read(counterProvider), 1);
  });
}
```

测试文件需要导入定义 `counterProvider` 的项目文件。测试自动释放 provider 时，应保持订阅存活以免测试过程中状态被释放。

可以通过 `ProviderScope(overrides: [...])` 或 `ProviderContainer(overrides: [...])` 替换仓库、配置或其他依赖，让测试使用可控数据。实际覆盖方式取决于 provider 类型；优先覆盖对外依赖并验证可观察行为。

## 九、和 Provider、Bloc、GetX 的关系

| 方案 | 主要思路 | 选型关注点 |
|---|---|---|
| Riverpod | provider 声明依赖、缓存状态并建立响应式关系 | 生命周期、provider 组合、测试覆盖 |
| Provider | 通过 widget 树提供对象并监听变化 | 与现有 `ChangeNotifier` 及 widget 树的集成 |
| Bloc / Cubit | 以状态和事件或方法组织业务逻辑 | 状态流转、团队约定和测试方式 |
| GetX | 提供响应式状态和其他应用能力 | 依赖范围、项目约定和可维护性 |

这些方案存在功能重叠，并非只能选一个。已有项目优先考虑团队习惯、现有架构与迁移成本。

## 十、常见问题与版本差异

- **页面不更新**：检查渲染是否用了 `watch`；使用 `read` 不会建立监听。还要检查状态是否真正变化，以及对象相等性。
- **修改列表后没有通知**：避免原地修改再复用同一对象；优先创建新的不可变状态，例如 `state = [...state, item]`。
- **请求重复执行**：检查是否发生 `invalidate`、依赖变化、自动释放后重新监听；Riverpod 3.x 初始化失败还会按默认策略自动重试，可配置重试行为。
- **状态意外重置**：检查 `autoDispose`、依赖改变导致的重算，以及是否创建了新的 container / scope。
- **多个页面读取到不同状态**：检查它们是否处于不同 container 或有覆盖配置的作用域中。
- **旧教程中的 `StateProvider` / `StateNotifierProvider`**：Riverpod 3.x 将它们和 `ChangeNotifierProvider` 移到 `package:flutter_riverpod/legacy.dart`；新代码优先考虑 `Notifier` / `AsyncNotifier`。
- **2.x 与 3.x 混用示例报错**：例如 3.x 合并了部分 Ref 和 auto-dispose notifier 类型，family 的手写 Notifier 参数写法也有变化；应查阅对应版本的迁移文档。

## 参考资料

- [Riverpod：Getting started](https://riverpod.dev/docs/introduction/getting_started)
- [Riverpod：Refs](https://riverpod.dev/docs/concepts2/refs)
- [Riverpod：What's new in Riverpod 3.0](https://riverpod.dev/docs/whats_new)
- [Riverpod：Migrating from 2.0 to 3.0](https://riverpod.dev/docs/3.0_migration)
- [Riverpod：Testing](https://riverpod.dev/docs/how_to/testing)
