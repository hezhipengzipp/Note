# 原生与 Flutter 互相嵌入

> Add-to-App 方案

## 两种场景

```
场景 1：Flutter 页面中间嵌入原生控件 → 用 PlatformView
场景 2：原生页面中间嵌入 Flutter 控件 → 用 FlutterFragment
```

## 场景 1：Flutter 嵌入原生控件（PlatformView）

```
Flutter 页面
┌─────────────────────┐
│  Flutter Text        │
│  Flutter Button      │
│  ┌───────────────┐  │
│  │  原生地图控件    │  │  ← PlatformView
│  │  (MapFragment) │  │
│  └───────────────┘  │
│  Flutter Text        │
└─────────────────────┘
```

### Flutter 端

```dart
class MyPage extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Text('Flutter 上方'),
        SizedBox(
          height: 300,
          child: AndroidView(
            viewType: 'com.example/native_map',
            creationParams: {'lat': 39.9, 'lng': 116.3},
            creationParamsCodec: StandardMessageCodec(),
          ),
        ),
        Text('Flutter 下方'),
      ],
    );
  }
}
```

### Android 端

```java
// 注册 PlatformViewFactory
public class MapFactory extends PlatformViewFactory {
    @Override
    public PlatformView create(Context context, int id, Object args) {
        return new NativeMapView(context, id, args);
    }
}

// Application 或 Plugin 中注册
registrar.platformViewRegistry()
    .registerViewFactory("com.example/native_map", new MapFactory());
```

### 原理

```
Flutter 渲染树中留一个"空位"
    → 原生 View 画在这个空位上
    → 纹理通过 Surface/Texture 绘制到 Flutter 的合成层
```

## 场景 2：原生页面嵌入 Flutter 控件（FlutterFragment）

```
原生 Activity
┌─────────────────────┐
│  原生 Toolbar         │
│  原生 TextView        │
│  ┌───────────────┐  │
│  │  Flutter 页面    │  │  ← FlutterFragment
│  │  (任意Widget)   │  │
│  └───────────────┘  │
│  原生 Button          │
└─────────────────────┘
```

### Android 端

```java
// 布局
<LinearLayout>
    <TextView android:text="原生标题" />
    <FrameLayout
        android:id="@+id/flutter_container"
        android:layout_height="300dp" />
    <Button android:text="原生按钮" />
</LinearLayout>

// Activity 中
FlutterFragment fragment = FlutterFragment
    .withCachedEngine("my_engine")
    .build();

getSupportFragmentManager()
    .beginTransaction()
    .replace(R.id.flutter_container, fragment)
    .commit();
```

### iOS 端

```swift
let flutterVC = FlutterViewController(engine: engine, nibName: nil, bundle: nil)
addChild(flutterVC)
view.addSubview(flutterVC.view)
flutterVC.view.frame = CGRect(x: 0, y: 100, width: 300, height: 300)
flutterVC.didMove(toParent: self)
```

## FlutterEngine 预热

```java
// Application 中预创建，避免首次白屏
public class MyApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        FlutterEngine engine = new FlutterEngine(this);
        engine.getDartExecutor().executeDartEntrypoint(
            DartExecutor.DartEntrypoint.createDefault()
        );
        FlutterEngineCache.getInstance().put("my_engine", engine);
    }
}
```

## Engine 复用策略

| 方式 | 优点 | 缺点 |
|---|---|---|
| 全局单例 | 快，共享状态 | 页面状态互相影响 |
| 独立 Engine | 隔离 | 内存大，首次慢 |
| EngineGroup（Flutter 2.0+） | 共享 VM，隔离 Isolate | 兼顾两者 |

## 通信：MethodChannel

### Flutter 端

```dart
final channel = MethodChannel('com.example/native');

// 调用原生
final result = await channel.invokeMethod('getBatteryLevel');

// 接收原生调用
channel.setMethodCallHandler((call) async {
  if (call.method == 'showToast') {
    return 'done';
  }
});
```

### Android 端

```java
MethodChannel channel = new MethodChannel(flutterView, "com.example/native");

// 接收 Flutter 调用
channel.setMethodCallHandler((call, result) -> {
    if (call.method.equals("getBatteryLevel")) {
        result.success(85);
    }
});

// 调用 Flutter
channel.invokeMethod("showToast", "hello", null);
```

### 通信方式对比

| 方式 | 方向 | 场景 |
|---|---|---|
| MethodChannel | 双向 | 方法调用（请求-响应） |
| EventChannel | 原生→Flutter | 持续数据流（如传感器） |
| BasicMessageChannel | 双向 | 传递字符串/JSON |

## 对比总结

| | Flutter 嵌原生 | 原生嵌 Flutter |
|---|---|---|
| 核心 API | `AndroidView` / `UiKitView` | `FlutterFragment` / `FlutterView` |
| 注册方式 | `PlatformViewFactory` | 预热 `FlutterEngine` |
| 通信 | MethodChannel | MethodChannel |
| 性能开销 | 较大（纹理拷贝） | 较小（整块渲染） |
| 典型场景 | 地图、WebView、相机预览 | App 中某个页面用 Flutter 写 |

## 面试常问

1. **为什么要预热 Engine？** — Engine 创建要初始化 Dart VM，耗时 300-500ms，预热后打开 Flutter 页面几乎无感
2. **原生和 Flutter 如何共享数据？** — MethodChannel 传递，复杂数据用 JSON，或用 Pigeon 生成类型安全代码
3. **PlatformView 性能问题？** — 纹理拷贝有开销，频繁交互场景建议用 Hybrid Composition（Android 10+）
