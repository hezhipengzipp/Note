# Riverpod、GetX、Bloc 底层原理对比

> 本文重点解释状态如何保存、订阅、通知和驱动 UI。Riverpod 以 3.x API 为参考；GetX 和 Bloc 的源码细节会随版本变化，源码链接为官方仓库当前分支，排查时应对照项目的 `pubspec.lock` 和对应版本源码。

## 一、先看核心区别

| 维度 | Riverpod | GetX | Bloc / Cubit |
|---|---|---|---|
| 状态组织 | provider 定义状态和依赖，状态存于 container | Rx 对象或 controller 保存状态 | Bloc / Cubit 实例保存当前 state |
| 订阅关系 | `ref.watch` 显式声明依赖 | `Obx` 构建时追踪读取的 Rx；`GetBuilder` 订阅 controller | `BlocBuilder` 等订阅 Bloc / Cubit 的状态流 |
| 状态更新 | Notifier 修改 `state`，或依赖变化触发重新计算 | Rx 赋值通知观察者；`GetBuilder` 通过 `update()` 通知 | 调用 `emit` 发布新状态；Bloc 还可通过 event 驱动处理 |
| 依赖获取 | provider 与 `ProviderScope` / container | `Get.put`、`Get.find` 等实例注册与查找 | 常用 `BlocProvider` / `RepositoryProvider`；也可构造函数注入 |
| 异步 | FutureProvider、StreamProvider、AsyncNotifier 等 | Dart Future / Stream 搭配 Rx 或 controller | 在方法或事件处理器中执行异步任务，再 emit 状态 |
| UI 粒度 | 按监听的 provider 或 `select` 结果 | `Obx` 跟踪读取的 Rx；`GetBuilder` 可按 ID 更新 | `BlocBuilder`、`BlocSelector`、`buildWhen` 控制 |

它们都是运行在 Dart / Flutter 之上的状态管理方案，不会因为使用了状态管理库就自动获得后台线程。最后界面是否绘制，仍由 Flutter 的构建与渲染管线决定。

## 二、先理解共同的通知链

```text
业务操作或外部数据
        │
        ▼
更新状态 → 判断是否需要通知 → 通知订阅者
                                  │
                                  ▼
                         Flutter 标记组件需要更新
                                  │
                                  ▼
                        后续构建和渲染阶段显示
```

状态变化与实际屏幕绘制不是同一个时刻。短时间多次更新不保证用户看到每个中间画面；需要逐条处理的业务逻辑应在业务层或专门的监听中完成，而不是依赖每次 UI 构建。

## 三、Riverpod：container + provider 依赖图

### 3.1 状态在哪里

```dart
final counterProvider = NotifierProvider<Counter, int>(Counter.new);

class Counter extends Notifier<int> {
  @override
  int build() => 0;
  void increment() => state = state + 1;
}
```

`counterProvider` 是全局可引用的**定义**，不是直接保存计数的全局变量。实际状态和订阅关系由 `ProviderContainer` 管理；Flutter 中一般通过 `ProviderScope` 提供 container。不同 container 可以持有互不相同的状态；scope 的覆盖和继承关系则由配置决定。

### 3.2 如何建立依赖

```dart
final doubledProvider = Provider<int>((ref) {
  final count = ref.watch(counterProvider);
  return count * 2;
});

// ConsumerWidget.build 中：
final doubled = ref.watch(doubledProvider);
```

逻辑关系：

```text
counterProvider ──watch──▶ doubledProvider ──watch──▶ ConsumerWidget
```

1. 第一次需要 provider 时，container 按需创建或计算状态。
2. provider 内调用 `ref.watch`，声明它依赖另一个 provider；widget 的 `watch` 声明 UI 依赖。
3. 上游状态变化时，Riverpod 根据依赖关系使相关计算更新，并通知对应订阅者。
4. Consumer 根据通知安排重建；UI 在 `build` 中读取最新值。

这是概念流程，不代表每个版本内部都使用同一种数据结构或同步执行顺序。`ref.read` 只读取，不建立响应式依赖；`ref.listen` 则用于状态变化时执行回调。

### 3.3 更新、相等性与释放

- Riverpod 3.x 默认用 `==` 过滤 provider 更新；自定义 Notifier 可按 API 调整通知判断。可变对象原地修改后继续使用同一个对象，容易造成变化无法被正确观察，优先采用不可变状态。
- `select` 让监听者关注状态的一部分；选中结果变化时才通知该监听者。它不能修复上游原地修改导致的通知问题。
- 手写 provider 可配置 `autoDispose`，不再被监听时按生命周期规则释放；依赖变化导致重新计算也会销毁旧状态。`ref.onDispose` 用于清理资源。
- `FutureProvider` / `StreamProvider` 向消费方暴露 `AsyncValue`；刷新时旧数据与加载状态可能同时存在。3.x 对初始化失败默认有自动重试策略，可配置。

## 四、GetX：Rx 依赖收集与手动通知

GetX 有两条常见状态管理路径：**`Obx` + Rx 的响应式更新**，以及 **`GetBuilder` + `update()` 的显式更新**。两者不能简单当作同一个机制。

### 4.1 Obx 如何知道依赖了哪个 Rx

```dart
final count = 0.obs;

Obx(() => Text('${count.value}'));

// 用户操作时：
count.value++;
```

概念流程：

```text
Obx 执行 builder
   │ 读取 count.value
   ▼
建立 Obx 对 count 的观察关系
   │
count.value 更新并触发通知
   ▼
对应 Obx 安排重建
```

Rx 持有当前值。`Obx` 执行 builder 时，框架记录读取到的响应式对象；这些对象更新后通知对应观察者。观察范围与实际读取位置有关：在 `Obx` 外提前读成普通值，再在内部使用该普通值，无法靠那次读取建立预期的 Rx 依赖。

GetX 的文档在不同位置同时使用“不是标准 Stream / ChangeNotifier”和“stream”描述；实现和版本也会变化。更可靠的理解是：**依赖收集和通知是关键语义，不能笼统断言所有 GetX 更新都靠 Dart `StreamController`，也不能断言它完全没有 Stream API**。当前源码中的 Rx 也提供与 Stream 交互的能力。

Rx 更新通常会比较新旧值，但首个通知等细节和具体 Rx 实现有关。`RxList` 等提供会触发通知的集合操作；自定义对象内部字段的原地修改通常不会自动被 Rx 捕获，需要使用库提供的更新/刷新方式或替换对象。

### 4.2 GetBuilder 是另一条路径

```dart
class CounterController extends GetxController {
  int count = 0;

  void increment() {
    count++;
    update(); // 显式通知 GetBuilder
  }
}

GetBuilder<CounterController>(
  builder: (controller) => Text('${controller.count}'),
);
```

`GetBuilder` 监听 controller 的更新通知，普通字段变化本身不会自动通知它。`update()` 可按 ID 选择通知范围；它不要求字段声明为 `.obs`。示例假设 controller 已由适当的 GetX 依赖管理方式创建或注册。

### 4.3 生命周期

controller 的创建与销毁取决于注册方式、路由绑定、SmartManagement 配置及 `permanent`、`fenix` 等选项，不能假定 `Get.find` 的对象一定随当前页面销毁。controller 中手动创建的计时器、Stream 订阅、worker 等要根据所有权在 `onClose` 等位置清理。

## 五、Bloc / Cubit：当前状态 + 状态 Stream

### 5.1 Cubit 的更新链

```dart
class CounterCubit extends Cubit<int> {
  CounterCubit() : super(0);

  void increment() => emit(state + 1);
}
```

```text
调用 increment()
   │
   ▼
emit(nextState)
   │ 更新当前 state，并向状态 Stream 发布变化
   ▼
BlocBuilder 的订阅收到状态 → 安排 UI 更新
```

`BlocBase` 同时提供同步读取的 `state` 和异步状态 `stream`。`BlocBuilder` 根据状态构建 UI；`BlocListener` 适合处理导航、提示等一次性副作用；`BlocSelector` 可以只关注状态的一部分。官方实现会对相等状态进行过滤，并允许首次 emit 与初始状态相等的特殊情况；状态相等性取决于 `==` 的实现。

```dart
BlocBuilder<CounterCubit, int>(
  builder: (context, count) => Text('$count'),
);
```

### 5.2 Bloc 比 Cubit 多了事件处理层

```dart
sealed class CounterEvent {}
class IncrementPressed extends CounterEvent {}

class CounterBloc extends Bloc<CounterEvent, int> {
  CounterBloc() : super(0) {
    on<IncrementPressed>((event, emit) {
      emit(state + 1);
    });
  }
}

// context.read<CounterBloc>().add(IncrementPressed());
```

```text
add(event) → 事件流 → on<Event> 对应处理器 → emit(state) → 状态流 → UI
```

Cubit 直接调用方法；Bloc 先接收 event，再交由匹配的 handler 处理。Bloc 默认可并发处理事件；异步请求可能交错完成，不能假设提交顺序等于完成顺序。可按业务需要使用 `bloc_concurrency` 的 `sequential`、`droppable`、`restartable`、`concurrent` 等 transformer，注意其作用范围和异步任务本身的取消能力。

`BlocObserver`、`onChange` / `onTransition` 可辅助观察状态变化；事件和状态的显式建模有助于追踪流程，但不自动保证业务操作的顺序或幂等性。

### 5.3 生命周期

`BlocProvider(create: ...)` 通常拥有并在移除时关闭创建的实例；`BlocProvider.value` 提供已有实例，不负责自动关闭它，原拥有者仍需管理。手动创建的 Bloc / Cubit 在不再使用时应调用 `close()`，订阅、定时器等资源也要释放。

## 六、以一次点击为例对照

| 阶段 | Riverpod | GetX Obx | Cubit / Bloc |
|---|---|---|---|
| UI 发起操作 | `ref.read(counterProvider.notifier).increment()` | `controller.count.value++` | `cubit.increment()` 或 `bloc.add(event)` |
| 状态变更 | Notifier 设置 `state` | Rx 设置 `value` | Cubit / handler 调用 `emit` |
| 通知对象 | 订阅对应 provider 的监听者和依赖者 | 构建时读取过该 Rx 的观察者 | 状态 Stream 订阅者 |
| UI 变化 | Consumer 重新读取被监听的值 | Obx 重新执行 builder | BlocBuilder 以状态构建 |

这三条链路都能实现计数器，但表达业务流程的方式不同。GetBuilder 的路径是 controller 修改字段后显式 `update()`，不应套用 Obx 的依赖收集流程。

## 七、异步与并发：不要把响应式当成多线程

- Riverpod 的 `FutureProvider`、GetX 的 Rx 和 Bloc 的状态 Stream 都不自动把 CPU 密集型任务移到后台。
- `async` / `await` 让等待异步操作期间可以处理其他任务；同步计算仍占用所在 isolate。大量 JSON 解析、图片处理等可评估 `Isolate.run` 或 `compute`。
- 网络请求可能在页面退出或新请求发起后才完成。应明确取消、过期结果丢弃、错误处理与资源释放策略；状态管理库不会自动替业务定义这些规则。
- 搜索框连续输入时，要区分**防抖**、**仅采用最新结果**与**真正取消网络请求**；它们不是同一件事。

## 八、重建性能与选择

不要仅根据“哪个库重建更少”判断性能。测量前先检查：状态是否拆分合理、订阅范围是否过大、build 内是否有昂贵计算、列表项是否稳定，以及相等性是否符合业务语义。

- Riverpod：拆分 provider，必要时使用 `select`；注意派生状态和对象相等性。
- GetX：缩小 `Obx` 观察范围；`GetBuilder` 需要时使用 ID；检查未释放的 controller 与 worker。
- Bloc：合理拆分状态和 widget，使用 `BlocSelector` 或 `buildWhen`；不可变状态和正确的 `==` 有助于减少无意义通知。

`buildWhen` 是构建优化手段，不应作为权限或业务正确性的唯一保障。Flutter DevTools 的实际性能分析比只看库名更可靠。

选型时，依赖组合和声明式数据流突出的项目可以考虑 Riverpod；希望显式建模事件、状态转换和事件并发策略的场景可以考虑 Bloc；已经使用 GetX 路由、依赖管理和响应式 API 的项目可以结合团队约定继续使用 GetX。团队经验、可测试性和已有代码往往比单一基准更重要。

## 九、面试速答

**三者底层原理分别是什么？**

> Riverpod 由 container 管理 provider 状态和依赖，通过 `watch` 建立响应式关系；GetX 的 Obx 在构建时追踪 Rx 读取并在值变化时通知观察者，GetBuilder 则由 controller 显式通知；Bloc / Cubit 保存当前状态并通过状态 Stream 通知订阅者，Bloc 另外提供事件处理层。

**状态变了就一定立刻刷新屏幕吗？**

> 不一定。框架先通知相关监听者并安排 widget 更新，实际构建和绘制由 Flutter 渲染管线决定；相等性过滤、订阅范围和构建时机都会影响结果。

**Bloc 使用 Stream 就一定比其他方案慢吗？**

> 不能仅凭是否使用 Stream 得出结论。对象分配、状态设计、订阅数量、重建范围和业务计算都影响性能，应使用真实场景测量。

## 参考资料

- [Riverpod：Refs](https://riverpod.dev/docs/concepts2/refs)
- [Riverpod：What's new in 3.0](https://riverpod.dev/docs/whats_new)
- [GetX 官方项目与文档](https://github.com/jonataslaw/getx)
- [GetX：Rx 实现](https://github.com/jonataslaw/getx/blob/master/lib/get_rx/src/rx_types/rx_core/rx_impl.dart)
- [GetX：监听与依赖收集实现](https://github.com/jonataslaw/getx/blob/master/lib/get_state_manager/src/simple/list_notifier.dart)
- [Bloc：核心概念](https://bloclibrary.dev/bloc-concepts/)
- [Bloc：BlocBase 源码](https://github.com/felangel/bloc/blob/master/packages/bloc/lib/src/bloc_base.dart)
- [Bloc：事件处理源码](https://github.com/felangel/bloc/blob/master/packages/bloc/lib/src/bloc.dart)
- [bloc_concurrency](https://pub.dev/packages/bloc_concurrency)
