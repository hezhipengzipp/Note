# FlutterPlugin 使用详解

> 本文的 `FlutterPlugin` 指 Android Flutter embedding 中的接口 `io.flutter.embedding.engine.plugins.FlutterPlugin`，用于把 Android 原生能力封装成 Flutter 插件。

## 1. FlutterPlugin 解决什么问题

Flutter 插件通常包含两部分：

- Dart API：供 Flutter 页面调用。
- Android 实现：调用 Android 系统或第三方 SDK，再把结果返回给 Dart。

`FlutterPlugin` 是 Android 实现接入 `FlutterEngine` 的入口。插件在 Engine attach 时拿到 `FlutterPluginBinding`，可通过它访问 `applicationContext`、`BinaryMessenger`、资源查找器等；Engine 销毁或插件移除时收到 detach 回调，应清理 Channel 和其他资源。

```text
Flutter Dart API
      │ MethodChannel
      ▼
Android FlutterPlugin
      │ Android API / SDK
      ▼
Android 系统或依赖库
```

它不是 Activity，也不是一个全局单例。一个应用可以创建多个 `FlutterEngine`，每个 Engine 都会有自己的插件实例。

## 2. 创建插件工程

用 Flutter 插件模板创建工程：

```bash
flutter create \
  --org com.example \
  --template=plugin \
  --platforms=android \
  -a kotlin \
  device_info_plugin
```

常见目录如下：

```text
device_info_plugin/
├── lib/
│   └── device_info_plugin.dart       # Dart API
├── android/src/main/kotlin/...
│   └── DeviceInfoPlugin.kt           # Android 实现
├── example/                          # 插件示例应用
└── pubspec.yaml                      # 插件元数据和平台声明
```

插件的 `pubspec.yaml` 会声明 Android 包名和插件类名，Flutter 工具据此生成并使用插件注册代码：

```yaml
flutter:
  plugin:
    platforms:
      android:
        package: com.example.device_info_plugin
        pluginClass: DeviceInfoPlugin
```

## 3. 实现 FlutterPlugin

下面的例子提供一个 `getPlatformVersion` 方法，演示插件如何通过 `MethodChannel` 接收 Dart 调用。

```kotlin
package com.example.device_info_plugin

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

class DeviceInfoPlugin : FlutterPlugin, MethodCallHandler {
    private var channel: MethodChannel? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(
            binding.binaryMessenger,
            "com.example.device_info/platform"
        ).also { it.setMethodCallHandler(this) }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getPlatformVersion" -> result.success("Android ${android.os.Build.VERSION.RELEASE}")
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }
}
```

生命周期可以概括为：

```text
FlutterEngine 创建或注册插件
        │
        ▼
onAttachedToEngine(binding)
        │ 创建 Channel、保存需要的 Engine 级资源
        │
        ▼
处理 Dart 调用
        │
        ▼
onDetachedFromEngine(binding)
          移除 handler、停止任务、释放资源
```

`FlutterPluginBinding` 只在 attach 到 detach 这段生命周期内有效。detach 之后不要继续读取 binding 中的对象，也不要让 Channel 或异步任务持有已经失效的 Engine 资源。

## 4. Dart 侧定义 API

应用使用的 Dart API 可以把 Channel 封装起来，避免业务代码到处传方法名：

```dart
import 'package:flutter/services.dart';

class DeviceInfoPlugin {
  static const _channel = MethodChannel(
    'com.example.device_info/platform',
  );

  static Future<String?> getPlatformVersion() {
    return _channel.invokeMethod<String>('getPlatformVersion');
  }
}
```

业务侧调用：

```dart
final version = await DeviceInfoPlugin.getPlatformVersion();
```

Channel 名称和方法名是 Dart 与原生端共同遵守的协议，应保持一致。`MethodChannel` 返回的值必须是 `StandardMessageCodec` 支持的类型，例如 `null`、布尔值、数字、字符串、字节数组、列表和 Map；复杂模型可在 Dart API 层转换为这些基础类型。

## 5. 需要 Activity 时使用 ActivityAware

`FlutterPlugin` 提供的是应用级 `Context`，它不保证存在 Activity。访问权限请求、启动 Activity、接收 Activity 生命周期等场景，应额外实现 `ActivityAware`，并在 Activity 变化时更新引用。

```kotlin
import android.app.Activity
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding

class CameraPlugin : FlutterPlugin, ActivityAware {
    private var activity: Activity? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // 创建 channel、保存 applicationContext 等 Engine 级资源
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        // 如需接收 Activity 回调，可在这里注册 listener。
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        // 在新 Activity 上重新注册需要的 listener。
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        activity = null
        // 移除 Engine 级 handler，释放 Engine 级资源
    }
}
```

这是生命周期骨架，省略了 Channel 初始化和具体 listener 实现。

实现时还应按实际注册情况移除 listener。配置变更期间旧 Activity 会 detach，新 Activity 随后 reattach；不能把旧 Activity 保存在静态变量中，也不能假定插件始终绑定着 Activity。若能力只需要应用级上下文，优先使用 `binding.applicationContext`，不要为了取 Context 强行依赖 Activity。

## 6. 注册方式与 FlutterEngine

### Flutter 管理的 Engine

通过标准 Flutter 项目依赖插件时，Flutter 工具会根据插件元数据生成注册配置；应用启动 Engine 时由 embedding 注册插件。通常不需要在业务代码里手动 `new DeviceInfoPlugin()`。

添加插件后应重新构建或完整重启应用。热重载不会把新增的原生代码装进已经运行的 APK，可能导致 `MissingPluginException`。

### 手动创建或自定义 Engine

对于自行创建的 `FlutterEngine`，可通过 `engine.plugins.add(...)` 显式注册插件。注册前先确认该 Engine 没有通过自动注册再注册一次：

```kotlin
val engine = FlutterEngine(context)
engine.plugins.add(DeviceInfoPlugin())
```

`PluginRegistry.add` 会触发插件的 `onAttachedToEngine`。Engine 销毁时会触发 detach；如果手动移除插件，也要让插件有机会清理自身状态。

## 7. 多 Engine 与资源管理

插件实例的生命周期属于 Engine。设计时应遵守这些原则：

- 把 Channel、任务句柄和 Engine 相关状态放在插件实例字段中，不放进静态字段。
- 在 `onDetachedFromEngine` 中取消监听、停止任务并释放资源。
- 不假设插件全进程只有一个实例，也不假设它只会 attach 一次。
- 如果多个插件实例共用原生单例资源，需要自行协调访问，并在最后一个使用者退出后再释放资源。
- 异步回调返回前检查插件或 Engine 是否仍然有效，避免对已销毁的 Dart 通道回调。

## 8. 插件设计最佳实践

以 `js_flutter_monitor` 为例，它把 Dart API、平台接口、MethodChannel 和 Android/iOS 原生实现分开；原生侧在 `onAttachedToEngine` 注册 handler，在 `onDetachedFromEngine` 移除 handler。这种边界清晰、生命周期可管理的结构适合继续扩展。下面补充跨平台插件落地时需要统一的约定。

### 8.1 维持清晰的分层

```text
业务代码
  ↓ 稳定、易懂的 Dart API
平台接口（抽象能力、默认行为）
  ↓ 按运行平台选择实现
MethodChannel / FFI
  ↓
Android / iOS 等原生代码
```

- 业务层只依赖 Dart API，不直接依赖 Channel 名称或原生方法字符串。
- 多平台插件可用平台接口包定义契约，各平台实现继承该接口；单一插件也可以像本文示例一样在一个包内组织接口和默认 MethodChannel 实现。
- 原生系统调用和数据转换放在平台实现中。平台专属实现可以拆成独立文件或工具类，避免 Channel handler 越长越难维护。
- 区分平台接口和业务领域接口。例如 `plugin_platform_interface` 用于约束平台实现；指标上报所需的 `ReportMetrics` 则是业务抽象，不应混为同一个接口。

### 8.2 把跨平台语义写进 API 契约

平台间要统一的不只是方法名，还包括单位、数据口径和错误语义。`js_flutter_monitor` 暴露了内存整数值，但 Android 侧代码以 KB 返回 PSS，iOS 侧以 MB 返回 physical footprint；失败时又分别返回 `0` 和 `-1`。调用方无法据此获得一致含义。

建议在 Dart API 中显式表达单位并统一失败方式，例如：

```dart
abstract class DeviceMonitorPlatform {
  /// 所有平台实现都返回字节数，显示时再由 UI 转成 MB/GB。
  Future<int> getMemoryUsageBytes();
}
```

如果用 `MethodChannel`，原生侧仍返回 `StandardMessageCodec` 支持的基础类型（这里是 `int`），由 Dart API 再包装成领域对象；不要直接把普通 Dart 类实例当作 Channel 参数或返回值。

实践中还应：

- 在 API 文档标明是进程级还是系统级数据，以及采样时点、单位和百分比范围。
- 将不同平台的底层指标转换到同一语义；如果底层口径无法等价，应公开平台差异或采用明确的统一近似口径。
- 不用 `0` 同时表示“真实值为零”和“读取失败”。可通过平台异常、Dart 自定义异常或带状态的结果类型表达失败。
- 选用稳定且受支持的原生 API；解析内部调试字符串时，要处理字段缺失、权限或系统版本差异，并提供可观测的错误信息。

### 8.3 做好生命周期和异步处理

- 只在 `onAttachedToEngine` 创建依赖 `BinaryMessenger` 的对象，在 `onDetachedFromEngine` 解除 handler 并释放监听、线程、定时器和其他资源。
- 异步原生任务在 Engine detach 时取消，或在完成时确认 Channel/插件实例仍有效；避免向已销毁的 Dart isolate 回调。
- 将 Engine 级状态存在插件实例上。只有确实共享进程级资源时才使用单例，并明确多 Engine 的并发和释放策略。
- 不需要 Activity 的能力只用 application context；确实需要 Activity 时实现 `ActivityAware`，正确处理配置变更和 listener 移除。
- 避免在平台主线程执行耗时采集或阻塞 IO。高频采样应评估采样周期、线程切换和 Channel 传输成本，必要时在原生侧采样并批量回传。

### 8.4 测试真实契约

- Dart 单测覆盖平台接口替换、成功值、异常和空值行为；平台接口使用 `plugin_platform_interface` 时，测试 mock 使用 `MockPlatformInterfaceMixin`。
- Android/iOS 测试应针对实际暴露的方法、参数、返回类型和错误分支，不保留模板默认测试。
- 在 example 或集成测试里验证真实平台返回值、单位转换和 Engine attach/detach 后资源清理。
- README 的方法名、单位和示例应与当前实现一致。案例中 README 的内存示例误调用了 CPU API；Android 测试还断言一个当前不存在的 `getPlatformVersion` 方法，这类模板遗留会误导用户并让测试失去保护作用。

## 9. 常见问题

| 现象 | 常见原因 | 排查方向 |
|---|---|---|
| `MissingPluginException` | 插件未被当前 Engine 注册；只热重载了 Dart；平台实现未声明或未编译 | 完整重启并重建；检查 `pubspec.yaml` 的平台声明；确认当前 Engine 的注册路径 |
| `channel` 收不到调用 | Dart 与原生 Channel 名称不一致；handler 未设置；注册到了另一个 Engine | 对照两端字符串；检查 attach 是否执行；确认调用所在 Engine |
| Activity 为 null | 插件只实现了 `FlutterPlugin`；当前 Engine 没有绑定 Activity；Activity 正处于配置变更切换期 | 实现 `ActivityAware`，并处理 detach 与 reattach |
| Engine 销毁后仍有回调 | 没有在 detach 时清理监听、任务或 Channel handler | 在 `onDetachedFromEngine` 取消异步工作并移除 handler |
| 多 Flutter 页面互相影响 | 插件把 Engine 状态放在静态变量或全局单例中 | 将状态限定在插件实例；必要时实现明确的跨实例协调 |

## 10. 何时用插件，何时用 FFI

需要使用 Android framework、Android SDK 或依赖 Flutter Plugin API 时，通常用 `--template=plugin`，通过 Platform Channel 暴露平台能力。若主要目的是从 Dart 调用自带的 C/C++ 库，且不需要 Flutter Plugin API，官方当前推荐优先考虑 FFI package。两者面向的问题不同，选择取决于原生能力和平台接入需求。

## 参考资料

- [Flutter 官方：Developing packages & plugins](https://docs.flutter.dev/packages-and-plugins/developing-packages)
- [Flutter 官方：Platform channels](https://docs.flutter.dev/platform-integration/platform-channels)
- [Flutter API：Android `FlutterPlugin`](https://api.flutter.dev/javadoc/io/flutter/embedding/engine/plugins/FlutterPlugin.html)
- [Flutter 官方：Add Flutter to an existing app](https://docs.flutter.dev/add-to-app)
