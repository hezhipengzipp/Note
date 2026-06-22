# Flutter 白屏原因与排查

## 一、白屏分类——先定位是哪个阶段

```
  用户点击 App 图标
       │
       ▼
  ┌─────────────────────────┐        ← 阶段①：原生启动白屏
  │  Android LaunchTheme    │           系统级的 Splash Screen
  │  / iOS LaunchScreen     │           这个白屏是"正常的"，可配置
  └────────────┬────────────┘
               │
               ▼
  ┌─────────────────────────┐        ← 阶段②：Flutter Engine 初始化白屏
  │  FlutterEngine 初始化    │           Dart VM 启动 / JIT 编译(debug)
  │  libflutter.so 加载      │           AOT 下很快，JIT 下较慢
  └────────────┬────────────┘
               │
               ▼
  ┌─────────────────────────┐        ← 阶段③：首帧渲染前白屏
  │  runApp → 首帧绘制       │           main() 中的同步操作
  │  (App 代码层面)          │           初始化太重会卡白屏
  └────────────┬────────────┘
               │
               ▼
  ┌─────────────────────────┐        ← 阶段④：运行时白屏
  │  页面渲染期              │           某个页面 build 出问题
  │  路由跳转                │           或者渲染线程卡死
  └─────────────────────────┘
```

---

## 二、各阶段白屏原因详解

### 2.1 阶段① —— 原生启动白屏（最容易被误解）

```
  时间线:
  App图标被点击
     │
     ├─ Android: 创建 Activity，显示 windowBackground / LaunchTheme
     │           默认白色 → 用户看到白屏
     │           FlutterActivity 的默认 Theme 是白色的
     │
     ├─ iOS:    显示 LaunchScreen.storyboard
     │           默认白色背景 → 用户看到白屏
     │
     ▼
  FlutterEngine 准备就绪 → Flutter 首帧覆盖原生背景

  这不是Bug，是原生端的默认行为。
```

**解决：在 Android 端配置启动主题**

```xml
<!-- Android: res/values/styles.xml -->
<style name="LaunchTheme" parent="Theme.MaterialComponents.NoActionBar">
    <!-- 把默认白色换成 App 品牌色或图片 -->
    <item name="android:windowBackground">@drawable/launch_background</item>
</style>

<!-- res/drawable/launch_background.xml -->
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@color/primary" /> <!-- 品牌色 -->
    <item>
        <bitmap
            android:gravity="center"
            android:src="@drawable/splash_logo" />
    </item>
</layer-list>
```

```xml
<!-- AndroidManifest.xml -->
<activity
    android:name=".MainActivity"
    android:theme="@style/LaunchTheme">  <!-- 指定启动主题 -->
</activity>
```

```dart
// Flutter 端：首帧出来后，让原生启动屏消失的时刻更流畅
// 如果有异步初始化，可以用 Flutter Native Splash 库（flutter_native_splash）
void main() async {
  WidgetsBinding.ensureInitialized();
  // 保留原生启动屏，直到 App 准备好
  // FlutterNativeSplash.preserve(widgetsBinding: widgetsBinding);
  await initApp();  // 异步初始化
  // FlutterNativeSplash.remove();
  runApp(MyApp());
}
```

### 2.2 阶段② —— Flutter Engine 初始化慢

```
  这个阶段白屏取决于引擎初始化时间：

  Debug 模式:
    ┌───────────────────────────────┐
    │ Dart VM 冷启动        ~200ms │
    │ Kernel 编译           ~500ms │  → 累计可能 1-2s
    │ JIT 编译首屏方法      ~300ms │
    └───────────────────────────────┘
    Debug 下的白屏是无法消除的，这是 JIT 编译代价
    只在 Release/Profile 下评估启动白屏才有意义

  Release 模式:
    ┌───────────────────────────────┐
    │ 加载 libflutter.so     ~100ms│
    │ 跳转到 Dart 入口       ~50ms │  → 总共 ~200ms
    │ 这部分白屏极短，用户基本无感    │
    └───────────────────────────────┘
```

### 2.3 阶段③ —— 首帧渲染前白屏（最值得优化）

这是开发者能控制的最关键阶段：

```
  main() 执行
     │
     ▼
  runApp(MyApp()) 被调用之前的所有同步操作
     │
     ├── X 大量 SDK 同步初始化（崩溃收集、埋点、网络库）
     ├── X SharedPreferences 同步读取（getInstance()）
     ├── X 数据库初始化
     ├── X 全局 Provider/BLoC 的 create
     ├── X 首页数据网络预加载（同步等待）
     │
     ▼
  runApp() → 首帧绘制

  上面任何一个同步操作耗时 = 白屏时间增加
```

**排查方法：在 main() 中打点**

```dart
void main() {
  final t0 = DateTime.now();

  WidgetsFlutterBinding.ensureInitialized(); // t1
  final t1 = DateTime.now();

  // 各种 SDK 初始化
  await initSdks();                           // t2
  final t2 = DateTime.now();

  runApp(MyApp());

  // 在 MaterialApp 的 builder 中打点首帧
  // t3 = 首帧时间
  print('''
    FlutterBinding: ${t1.difference(t0).inMilliseconds}ms
    SDK初始化:      ${t2.difference(t1).inMilliseconds}ms
    首帧前总计:     ${t3.difference(t0).inMilliseconds}ms
  ''');
}
```

**常见耗时大户：**

```
  1. SharedPreferences 首次 await —— 读文件，可能 50-200ms
     → 优化：用 MMKV 替代，或异步预加载，首帧不依赖

  2. Firebase / 友盟 / Bugly 初始化 —— 可能 100-500ms
     → 优化：延迟初始化，在首帧后执行
       WidgetsBinding.instance.addPostFrameCallback((_) {
         initAnalytics();
       });

  3. 网络预请求 —— 网络耗时不可控
     → 优化：首帧先展示缓存/骨架屏，网络请求在首帧后异步进行

  4. 大量 Provider 的 create —— 每个 create 几ms，多了就几十ms
     → 优化：非首屏的 Provider 用 lazy 创建
```

### 2.4 阶段④ —— 运行时白屏（Bug）

#### 原因A：页面 build 方法返回了空视图

```dart
// ❌ 白屏
class HomePage extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    // 忘了 return，或者条件分支没覆盖全
    if (state.isLoading) {
      return CircularProgressIndicator();
    }
    // 忘记 else 或者不在 loading 时什么都没返回
    // → 隐式 return null → 白屏
  }
}

// ✅
class HomePage extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    if (state.isLoading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    return const Scaffold(body: Center(child: Text('内容')));
  }
}
```

#### 原因B：ErrorWidget 在 Release 下是白屏

```dart
// Debug 下：
//   有错误 → 红色错误界面（ErrorWidget）
//   Red screen with error message

// Release 下：
//   有错误 → ErrorWidget 被替换为空白 Container
//   → 看起来就是"白屏"，没有任何提示！

// 排查：在 Release 包也捕获错误
void main() {
  FlutterError.onError = (details) {
    FlutterError.presentError(details);
    // 写入文件或上报到服务器
    logErrorToFile(details);
  };

  runApp(MyApp());
}
```

#### 原因C：MaterialApp 主题配了白色，没有设置 scaffoldBackgroundColor

```dart
// ❌ Scaffold 没有设置背景色时，从 Theme 继承
MaterialApp(
  theme: ThemeData(
    // scaffoldBackgroundColor 默认就是白色
    // 如果 Scaffold 里只有白色文字 → 看起来像白屏
  ),
  home: Scaffold(
    body: Text('Hello'),  // 白色背景 + 默认黑色文字 → 不是白屏
  ),
);

// ❌ 但如果 body 返回了空白的 Container，或有透明背景的组件
Scaffold(
  body: Container(
    // 从 Theme 继承 scaffoldBackgroundColor = 白色
    // Container 是空的 → 就是一片白
  ),
);
```

#### 原因D：路由 pop 后页面被移除但 UI 没刷新

```dart
// 场景：A 页面 push B，从 B pop 回来时 A 白屏
// 原因：A 页面的状态在 B 页面期间被意外清空了
```

#### 原因E：渲染线程卡死

```
  Platform 线程  ← 卡住（比如主线程做 IO）
  UI 线程       ← 等着 Platform 线程返回结果
  GPU 线程      ← 等着 UI 线程输出新帧
  ─────────────────────────────────
  全部卡住 → 屏幕定格在白屏或上一个画面
```

---

## 三、排查流程图

```
  白屏出现
     │
     ├─ 所有页面都白屏？
     │   ├─ 是 → 是不是刚启动？
     │   │   ├─ 是 → 阶段①②③
     │   │   │    └─ 检查: launchTheme + Engine初始化 + main()耗时
     │   │   └─ 否 → 是不是从后台回来？
     │   │        └─ 检查: FlutterEngine 是否被回收 / Platform Channel 超时
     │   │
     │   └─ 否 → 只有某个页面白屏？
     │        └─ 阶段④
     │             ├─ Debug下: 看有没有红色错误页
     │             │   有 → ErrorWidget → build 里报错了
     │             │   无 → 检查 build 返回了什么
     │             │
     │             └─ Release 下:
     │                └─ 加 FlutterError.onError 日志
     │                   + 用抓包看看是不是网络请求失败导致空数据
     │
     └─ 是不是只在 Debug 模式下白屏，Release 正常？
          → Debug JIT 编译慢，正常现象
```

---

## 四、快速检查清单

```
  □ Android: android/app/src/main/res/values/styles.xml
    LaunchTheme 的 windowBackground 是不是白色？

  □ iOS: LaunchScreen.storyboard 的背景色是不是白色？

  □ main() 里有没有大段同步初始化代码？
    → 移到首帧后或异步执行

  □ 首页 build 有没有 null 返回路径？
    → 检查所有 if/else 分支、switch 分支

  □ Release 包配了 FlutterError.onError 吗？
    → 线上白屏 = 收不到错误日志 = 无法排查

  □ Scaffold 有没有明确设置 backgroundColor？
    → 不依赖 Theme 默认值，显式设置

  □ 是不是使用了透明路由导致看到上一个页面？
    → PageRouteBuilder(opaque: false) 要小心
  
  □ 有没有在 build 里做复杂计算阻塞 UI 线程？
    → 用 compute() 或 Isolate 处理
```

---

## 五、面试题

**Q1: Flutter App 启动白屏的原因有哪些？**

> 分层看：
> ① 原生层白屏（正常）：Android FlutterActivity 默认白色 LaunchTheme，iOS LaunchScreen 默认白色，需配置为品牌色/Logo
> ② Engine 初始化白屏（正常但有优化空间）：libflutter.so 加载 + Dart VM 启动，Debug 下 JIT 编译可达 1-2s，Release AOT 仅约 200ms
> ③ 首帧渲染前白屏（可优化）：main() 中同步初始化 SDK、读 SharedPreferences、网络预请求等阻塞了 runApp → 首帧绘制
> ④ 运行时白屏（Bug）：build 返回空、ErrorWidget 在 Release 下变成空白、主题背景白色、路由异常等

**Q2: Debug 模式白屏很久，Release 正常，为什么？**

> Debug 下使用 JIT 编译，Dart VM 启动后需要编译 kernel dill → 逐方法 JIT 编译，首次启动慢。Release 使用 AOT 编译，安装包里已经是 ARM 机器码，没有编译过程。这种白屏无法消除，只在 Release/Profile 下评估启动性能。

**Q3: Release 下错误页变成白屏，怎么排查？**

> Flutter 在 Release 模式下会将 ErrorWidget 替换为空白 Container（避免红色错误页暴露给用户）。必须主动捕获：
> ① `FlutterError.onError` 记录错误日志
> ② `PlatformDispatcher.instance.onError` 捕获未处理异常
> ③ 接入崩溃上报 SDK（如 Sentry、Bugly 的 Flutter 插件）
> ④ 可以在 debug 模式下用 `kDebugMode` 开关保留错误页
