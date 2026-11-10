# Flutter FFI 入门与实践

## 1. FFI 是什么

FFI（Foreign Function Interface，外部函数接口）让 Dart 代码调用原生代码，并访问原生内存。Flutter 移动端和桌面端可以通过 Dart SDK 的 `dart:ffi` 调用符合 **C ABI** 的函数。

```text
Flutter UI / Dart 业务代码
        │
        ▼
Dart FFI 绑定：声明函数签名、转换参数
        │ 同进程中的原生函数调用
        ▼
C 接口 → C/C++ 或其他原生库
```

ABI（应用二进制接口）规定了函数如何传参、返回结果、导出符号以及数据如何布局。Dart 和原生代码必须对这些约定达成一致，否则可能出现错误结果或进程崩溃。

常见用途包括复用已有 C/C++ 算法库、音视频处理、加密计算、数据库引擎和机器学习推理。Rust 等语言也可以提供 C ABI 接口供 FFI 调用。

## 2. FFI 和 Platform Channel 如何选择

| 方面 | FFI | Platform Channel / Pigeon |
|---|---|---|
| 交互方式 | 通过绑定调用原生函数，访问原生内存 | Dart 与平台侧交换消息；Pigeon 可生成类型化接口 |
| 常见对象 | C ABI 库、C/C++ 算法 | Android、iOS 平台能力和插件接口 |
| 数据处理 | 需要考虑类型、指针、内存所有权和线程 | 需要考虑消息编解码、平台生命周期和线程 |
| 典型场景 | 复用原生算法、处理大块数据 | 权限、Activity、系统服务和平台 SDK |

FFI 不是 Flutter 插件的替代品：同一个插件也可以同时使用 Channel 和 FFI。需要 Android Java/Kotlin API 时，可以考虑 Channel/Pigeon 或 JNIgen；访问 Objective-C API 也有相应的绑定工具。选择取决于目标 API、平台支持和维护成本。

FFI 是直接函数调用，但不意味着一定更快：绑定调用、数据复制、内存分配和线程切换都有成本，具体应以实际数据和性能测试为准。

## 3. 一个最小调用示例

下面手写绑定以解释原理。实际工程中更推荐根据头文件用 `ffigen` 生成绑定，减少签名不一致的风险。

原生头文件 `math_utils.h`：

```c
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

int32_t add(int32_t a, int32_t b);

#ifdef __cplusplus
}
#endif
```

实现文件 `math_utils.c`：

```c
#include "math_utils.h"

int32_t add(int32_t a, int32_t b) {
  return a + b;
}
```

Dart 侧加载动态库并查找符号：

```dart
import 'dart:ffi';

typedef NativeAdd = Int32 Function(Int32, Int32);
typedef DartAdd = int Function(int, int);

// 示例假设目标平台已经构建并打包了该动态库。
final library = DynamicLibrary.open('libmath_utils.so');
final DartAdd add = library.lookupFunction<NativeAdd, DartAdd>('add');

void main() {
  print(add(1, 2)); // 3
}
```

`NativeAdd` 描述 C 层的二进制签名，`DartAdd` 描述 Dart 调用时的类型。C 的 `int32_t` 必须对应 `Int32`；这里的示例只考虑结果在 32 位整数范围内的输入。C++ 实现通常需要用 `extern "C"` 暴露 C 接口，并正确导出符号。

示例中的 `.so` 名称适用于 Android/Linux 的动态库命名习惯；macOS、Windows 和 Apple 平台的加载或链接方式可能不同，不能直接照搬。仅调用 `DynamicLibrary.open` 不会编译或打包库，目标架构、构建产物和加载名称必须一致。

## 4. 如何接入 Flutter 项目

从 Flutter 3.38 开始，官方推荐对 C 互操作优先使用 `package_ffi` 模板和 build hooks：

```bash
flutter create --template=package_ffi native_math
```

模板的主要组成：

```text
native_math/
├── lib/              # Dart API 和生成的绑定
├── src/              # C/C++ 源码和头文件
├── hook/build.dart   # 原生代码的构建 hook
├── pubspec.yaml
└── example/          # 示例应用，具体内容以生成模板为准
```

接入步骤：

1. 在 `src/` 中提供原生实现和清晰的 C 头文件。
2. 根据项目需要配置 `hook/build.dart`，让构建过程为目标平台和架构编译、打包原生代码。
3. 按模板中的 `ffigen` 配置，从头文件生成 Dart 绑定；头文件变化后重新生成。
4. 在 `lib/` 中将生成的底层绑定封装成业务友好的 Dart API。
5. 在目标设备或模拟器上构建和运行，验证符号、架构、内存释放和错误处理。

构建 hook 和生成命令应以当前 Flutter SDK 生成的模板为准。已有项目可能使用旧的 `plugin_ffi`、CMake 或其他平台构建配置；需要 Flutter Plugin API 或 Android Google Play services runtime 时，应参考官方插件方案。

## 5. 内存和数据类型

Dart 垃圾回收器不会自动理解所有原生内存的所有权。接口应明确：谁分配、谁持有、能用多久、由谁释放。

例如，向 C 函数传临时 UTF-8 字符串：

```dart
import 'dart:ffi';
import 'package:ffi/ffi.dart';

void useText(void Function(Pointer<Utf8>) nativeUseText) {
  final text = '你好'.toNativeUtf8();
  try {
    nativeUseText(text); // 假设原生函数只在本次调用期间读取
  } finally {
    malloc.free(text);
  }
}
```

`package:ffi` 是额外依赖，提供分配器和字符串转换工具。上例只适用于原生函数**不保存指针**的情况；如果原生侧需要异步使用该指针，必须建立明确的所有权和释放协议。原生库返回的内存通常应使用该库提供的释放函数，不能随意用另一套分配器释放。

其他注意事项：

- 指针可能为 `nullptr`；解引用前按接口契约检查。
- 避免释放后使用、重复释放和访问越界；这些问题可能直接导致进程崩溃。
- 结构体字段顺序、对齐、整数宽度和指针类型要与头文件一致；`long`、`size_t` 等类型的宽度可能随平台变化。
- 原生返回的字符串或缓冲区要先确认长度、编码和有效期；复制成 Dart 数据后按契约释放。
- `NativeFinalizer` 可以辅助管理资源，但释放时机不确定；有明确生命周期的资源仍应提供显式 `close` / `dispose`。

## 6. FFI、异步和线程

**FFI 调用默认是同步的，在发起调用的线程上执行。** 如果在 Flutter UI isolate 调用耗时原生函数，整个调用期间 UI isolate 都无法继续处理事件，可能掉帧或卡住。

```dart
// 假设 calculate 是已经完成绑定的耗时原生函数。
final result = calculate(input); // 在 UI isolate 直接调用可能卡顿
```

给外层函数加 `async` 或 `await` 不会自动把同步原生计算搬到后台。对于可以在后台执行且线程安全的原生函数，可考虑在 worker isolate 中调用：

```dart
import 'dart:isolate';

// 在 worker 内加载库并建立绑定，避免直接传递原生指针或绑定对象。
int runCalculation(int input) {
  // 加载动态库、查找符号并执行原生函数。
  return input * 2; // 这里用普通计算占位
}

Future<int> calculateInBackground(int input) {
  return Isolate.run(() => runCalculation(input));
}
```

示例中的计算仅演示线程组织，并非完整 FFI 绑定。传入 isolate 的数据需要满足消息传递限制；原生指针的所有权、生命周期和跨线程使用必须单独设计。原生库可能有线程亲和性或全局状态，先确认其线程安全要求；如果它自身启动线程或提供异步回调，也要遵循其回调线程和生命周期约定。

回调尤其需要谨慎：`Pointer.fromFunction` 和 `NativeCallable` 有不同的调用线程及生命周期限制；选择对应 API，确保原生侧不会在 Dart 回调失效后继续调用。不要假定任意原生线程都可以直接执行普通 Dart 回调。

## 7. 常见问题

| 现象 | 可能原因 | 排查方向 |
|---|---|---|
| 找不到动态库 | 未打包、库名错误、架构不匹配 | 检查构建产物、目标 ABI 和加载路径 |
| 找不到符号 | 名称不一致、未导出、C++ 名称改编 | 检查头文件、导出配置和 `extern "C"` |
| 调用结果异常或崩溃 | 函数签名、结构体布局或指针不匹配 | 对照 C 头文件，优先使用 `ffigen` 生成绑定 |
| 长时间运行内存增长 | 原生分配未释放、回调或句柄未关闭 | 检查所有权、配对释放函数和资源生命周期 |
| 页面卡顿 | UI isolate 执行了耗时同步 FFI 调用 | 测量原生调用耗时，评估放入 worker isolate |
| Web 构建失败 | Web 不能直接使用 Dart Native 的 `dart:ffi` 模型 | 为 Web 提供独立实现，例如适合场景的 JS interop 或 WebAssembly 接入 |

## 参考资料

- [Flutter：Bind to native code using FFI](https://docs.flutter.dev/interop/bind-native-code)
- [Flutter：Native interop](https://docs.flutter.dev/interop)
- [Flutter：Interact with native memory using dart:ffi](https://docs.flutter.dev/interop/dart-ffi)
- [Dart：C interop using dart:ffi](https://dart.dev/interop/c-interop)
- [Flutter：Developing packages & plugins](https://docs.flutter.dev/packages-and-plugins/developing-packages)
- [Flutter：Legacy FFI plugin](https://docs.flutter.dev/platform-integration/legacy-ffi-plugin)
