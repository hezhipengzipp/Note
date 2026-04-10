# Flutter 面试知识点（P1 重要加分项）

> 会 Flutter 的 Android 开发在市场上非常抢手，重点准备渲染原理和混合开发。

---

## 一、Flutter 架构

```
┌─────────────────────────────────────────┐
│              Framework（Dart）            │
│  ┌─────────────────────────────────────┐│
│  │  Material / Cupertino（UI 组件库）    ││
│  ├─────────────────────────────────────┤│
│  │  Widgets（组合式 UI 描述）            ││
│  ├─────────────────────────────────────┤│
│  │  Rendering（布局、绘制）              ││
│  ├─────────────────────────────────────┤│
│  │  dart:ui（底层绘制 API）             ││
│  └─────────────────────────────────────┘│
├─────────────────────────────────────────┤
│              Engine（C++）               │
│  Skia（2D渲染引擎） + Dart VM + 平台通道  │
├─────────────────────────────────────────┤
│           Embedder（平台嵌入层）          │
│  Android（Java/Kotlin）/ iOS（ObjC/Swift）│
└─────────────────────────────────────────┘
```

**关键点**：
- Flutter **不使用原生控件**，通过 Skia 自绘，保证跨平台一致性
- Dart 代码运行在独立的 Dart VM 中，和原生通过 Platform Channel 通信

---

## 二、三棵树（核心概念）

```
Widget Tree           Element Tree          RenderObject Tree
(配置/蓝图)            (管理生命周期)          (实际布局和绘制)

Container ─────────> ComponentElement ──────> RenderBox
  │                     │                      │
  Text ────────────> StatelessElement ───────> RenderParagraph
  │                     │
  ListView ────────> StatefulElement
                        │
                     State 对象
```

| 树 | 特点 | 生命周期 |
|------|------|---------|
| Widget | 不可变，轻量级配置描述 | 每次 build 都会重建 |
| Element | 可变，Widget 和 RenderObject 的桥梁 | 尽量复用（key 匹配） |
| RenderObject | 负责 layout 和 paint | 只在必要时更新 |

**面试要点**：
- Widget 重建不等于 UI 重绘，Element 会 diff 比较，只更新变化的部分
- `Key` 的作用：帮助 Element 正确匹配和复用 Widget（列表场景尤其重要）

---

## 三、渲染流程

```
setState() / 初始化
    │
    ▼
Build 阶段
    Widget.build() → 生成新的 Widget Tree
    Element.updateChild() → diff 比较，复用/更新 Element
    │
    ▼
Layout 阶段（类似 Android measure + layout）
    RenderObject.performLayout()
    约束向下传递（Constraints go down）
    尺寸向上传递（Sizes go up）
    │
    ▼
Paint 阶段
    RenderObject.paint(context, offset)
    绘制到 Layer Tree
    │
    ▼
Compositing 阶段
    Layer Tree → Scene
    │
    ▼
Rasterize 阶段（GPU 线程）
    Skia 将 Scene 光栅化为像素
    │
    ▼
显示到屏幕
```

**Flutter vs Android View 绑定**：

| Flutter | Android |
|---------|---------|
| Constraints go down | MeasureSpec 向下传递 |
| Sizes go up | measuredWidth 向上返回 |
| Parent sets position | layout(l, t, r, b) |

---

## 四、State 管理

### StatelessWidget vs StatefulWidget

| | StatelessWidget | StatefulWidget |
|------|----------------|----------------|
| 状态 | 无 | 有 State 对象 |
| 重建 | 每次 build 创建新实例 | Widget 重建，State 保留 |
| 场景 | 纯展示 | 可交互、动态数据 |

### StatefulWidget 生命周期

```
createState() → initState() → didChangeDependencies() → build()
    │
    ├── setState() → build()（重建）
    ├── didUpdateWidget()（父 Widget 重建时）
    │
    └── deactivate() → dispose()
```

### 状态管理方案

| 方案 | 特点 | 适用场景 |
|------|------|---------|
| setState | 最简单，局部状态 | 单个 Widget 内 |
| InheritedWidget | 数据向下传递 | Provider 底层原理 |
| Provider | 官方推荐，InheritedWidget 封装 | 中小项目 |
| Riverpod | Provider 升级版，编译安全 | 中大项目 |
| Bloc | 事件驱动，状态隔离清晰 | 大项目 |
| GetX | 简洁但过于 magic | 快速开发 |

---

## 五、Platform Channel（混合开发重点）

### 三种 Channel

```
Flutter (Dart)  ←──────────────→  Native (Android/iOS)
                   │
    ┌──────────────┼──────────────────┐
    │              │                  │
MethodChannel  EventChannel   BasicMessageChannel
 (方法调用)     (事件流)        (基础消息)
```

#### MethodChannel（最常用）

```dart
// Dart 端
final channel = MethodChannel('com.example/battery');
final level = await channel.invokeMethod('getBatteryLevel');

// Android 端
MethodChannel(flutterEngine.dartExecutor, "com.example/battery")
    .setMethodCallHandler { call, result ->
        if (call.method == "getBatteryLevel") {
            result.success(getBatteryLevel())
        } else {
            result.notImplemented()
        }
    }
```

#### EventChannel（持续事件流）
- 适合传感器数据、位置更新等持续性数据
- Dart 端 `receiveBroadcastStream()` 返回 Stream

### 混合开发方案

#### Flutter 嵌入原生
- `FlutterActivity` / `FlutterFragment`：将 Flutter 页面嵌入原生
- `FlutterEngineCache`：预热引擎，避免首次加载白屏

#### 原生嵌入 Flutter
- `PlatformView`：在 Flutter 中嵌入原生 View（如地图、WebView）
- **Hybrid Composition**：性能好但有线程开销
- **Virtual Display**：兼容性好但可能有触摸/键盘问题

### 面试重点：Flutter 与原生通信的数据序列化
- Platform Channel 使用 `StandardMessageCodec` 序列化
- 支持的类型：null、bool、int、double、String、byte[]、List、Map
- 复杂对象需要手动转换为 Map

---

## 六、Dart 语言要点

### 单线程模型 + Event Loop

```
┌────────────────────────────────────────┐
│            Dart Event Loop              │
│                                        │
│  ┌──────────────┐  ┌────────────────┐  │
│  │ Microtask     │  │  Event Queue   │  │
│  │ Queue         │  │  (I/O, Timer,  │  │
│  │ (优先级更高)   │  │  手势等)        │  │
│  └──────┬───────┘  └───────┬────────┘  │
│         │                  │           │
│         └───── 先清空 ──────┘           │
│              Microtask                  │
│              再处理 Event               │
└────────────────────────────────────────┘
```

- Dart 是**单线程**的，通过 Event Loop 实现异步
- `Future` → Event Queue；`scheduleMicrotask` → Microtask Queue
- `Isolate`：真正的多线程（独立内存空间，通过消息通信）

### async / await
- 类似 Kotlin 协程，语法糖，底层还是 Future 回调
- `await` 不会阻塞线程，只是暂停当前函数执行

### Dart vs Kotlin 对比

| 特性 | Dart | Kotlin |
|------|------|--------|
| 空安全 | Sound null safety | 编译器检查 |
| 异步 | Future / async-await | Coroutine / suspend |
| 并发 | Isolate（独立内存） | 线程（共享内存） |
| Mixin | `with` 关键字 | 接口默认实现 |
| 扩展 | `extension on Type` | `fun Type.xxx()` |

---

## 七、性能优化

### 常见问题
1. **不必要的 rebuild**：使用 `const` 构造函数、`RepaintBoundary`、`Selector`（Provider）
2. **列表性能**：`ListView.builder` 懒加载，避免 `ListView` 直接传 children
3. **图片优化**：`cacheWidth/cacheHeight` 限制解码尺寸
4. **Shader 编译卡顿（Jank）**：首次渲染时 Skia 编译着色器导致，可用 `--cache-sksl` 预热

### 性能分析工具
- **Flutter DevTools**：Performance、Memory、Widget Inspector
- **Timeline**：查看帧耗时，定位 Build/Layout/Paint 瓶颈
- `flutter run --profile` 模式下分析

---

## 八、面试高频问题

**Q: Flutter 为什么不用原生控件？**
> 自绘引擎保证跨平台一致性，不依赖 OEM 控件实现，避免了不同平台的表现差异。同时减少了 Flutter 和原生之间的桥接开销。

**Q: Flutter 和 React Native 的区别？**
> RN 通过 Bridge 调用原生控件渲染，Flutter 用 Skia 自绘。Flutter 性能更好（无 Bridge 瓶颈），UI 一致性更好，但原生集成不如 RN 灵活。

**Q: Flutter 的热重载原理？**
> 将修改的 Dart 代码增量编译为 kernel 文件，通过 DartVM 的热重载接口注入，重新 build Widget Tree。状态（State）保留，所以能看到即时效果。

**Q: 如何处理 Flutter 和原生的页面混合栈？**
> 可使用 FlutterBoost（闲鱼开源）或自行管理 FlutterEngine。核心问题是 Flutter 只有一个 Engine，多页面需要共享或创建多个 Engine（资源开销大）。
