# IsolateNameServer 与 PluginUtilities 注册查找全流程

> 两个 API 一起解决后台 Isolate 的两个核心问题：
> - `PluginUtilities.getCallbackHandle` —— **Native 怎么知道 Dart 函数入口在哪？** 把 Dart 函数转成一个 int64 句柄交给 Native 保管。
> - `IsolateNameServer` —— **后台 Isolate 怎么找到主 Isolate 的端口？** Engine 层维护一张全局名字 → 端口表。

---

## 一、为什么需要 IsolateNameServer

```
Isolate 之间是"完全隔离"的：
   ┌──────────────┐         ┌──────────────┐
   │ Main Isolate │         │ Bg  Isolate  │
   │              │         │              │
   │   Dart 堆     │   ❌    │   Dart 堆     │
   │   GC、Stack  │  不能   │   GC、Stack  │
   │              │  共享   │              │
   └──────────────┘  对象   └──────────────┘
          │                       ▲
          │    只能通过端口通信     │
          │    (Dart_Port = int64) │
          └────────────────────────┘

问题：子 Isolate 怎么知道主 Isolate 的 SendPort？

  ❌ 不能通过全局变量传 —— 内存不共享
  ❌ 不能通过文件传   —— 太慢、跨进程才需要
  ❌ 启动子 Isolate 时传参数也不行 —— 比如后台任务是 Native 拉起的 Dart 入口，
                                  根本没有"启动者"传参数给它

  ✅ 通过全局命名注册表：主 Isolate 注册名字，子 Isolate 按名字查
```

**核心需求：在没有任何共享对象的两个 Isolate 之间建立通信通道。**

---

## 二、API 一览

```dart
import 'dart:ui';   // ⚠️ 注意是 dart:ui，不是 dart:isolate

// ① 注册：把 SendPort 用 name 注册到全局表
//    返回 true 表示注册成功；false 表示该 name 已被占用
bool ok = IsolateNameServer.registerPortWithName(sendPort, 'my_channel');

// ② 查找：通过 name 拿到 SendPort（拿不到返回 null）
SendPort? port = IsolateNameServer.lookupPortByName('my_channel');

// ③ 移除：注销注册
bool removed = IsolateNameServer.removePortNameMapping('my_channel');
```

---

## 三、完整调用链路

以 Android 后台任务通知 UI 刷新为例：

```
Main Isolate (UI)                  Engine 层 (C++)                    Background Isolate
─────────────────                  ────────────────                   ────────────────────

① 创建 ReceivePort
final rp = ReceivePort();
rp.listen((msg) {
  setState(...);   // 刷新 UI
});

② 拿 SendPort
SendPort sp = rp.sendPort;
   │
   │  SendPort 内部其实是一个 int64 的 Dart_Port
   │
   ▼
③ 注册到全局表
IsolateNameServer
  .registerPortWithName(sp, "ui_port")
   │
   │  Dart 调 native 函数
   │  (FFI / Native Extension)
   ▼
   ───────────────────────────→  ④ C++ 层处理
                                 DartIsolateGroup::
                                 RegisterIsolatePortWithName
                                       │
                                       │ 写入全局 map
                                       ▼
                                 ┌──────────────────────────┐
                                 │ port_name_map_           │
                                 │ ───────────────          │
                                 │ "ui_port"  → Dart_Port#7 │
                                 │ "log_port" → Dart_Port#3 │
                                 │ ......                   │
                                 └──────────────────────────┘
                                       │
                                       │  返回 true（注册成功）
                                       ▼
                                                            ⑤ Native 唤起 Background Isolate
                                                               （AlarmManager / WorkManager 等）
                                                                       │
                                                                       ▼
                                                            ⑥ 后台 Dart 入口执行
                                                            void backgroundCallback() {
                                                              ...
                                                              ⑦ 查询全局表
                                                              SendPort? sp =
                                                                IsolateNameServer
                                                                  .lookupPortByName("ui_port");
                                                                       │
                                ←──────────────────────────────────────┘
                                ⑧ C++ 层查 map
                                DartIsolateGroup::
                                LookupIsolatePortByName
                                找到 Dart_Port#7
                                包装成 SendPort 返回
                                       │
                                       ▼
                                                            sp 拿到了！
                                                                       │
                                                                       ▼
                                                            ⑨ sp.send("data ready");
                                                                       │
                                                                       │ Dart VM 走端口投递
                                                                       │ 把消息塞进 Dart_Port#7
                                                                       │ 对应的 MessageQueue
                                                                       ▼
   ⑩ ReceivePort 触发 listen        ←─────────  VM 调度 Main Isolate 处理消息
   setState(...);
```

---

## 四、底层原理拆解

### 1. 注册表存在哪里？—— Engine 层的 `DartIsolateGroup`

```
不是放在某个 Isolate 内（否则隔离了就看不到），
而是放在 Engine 层 C++ 端，所有 Isolate 共享同一份。

┌─────────────── Flutter Engine 进程 ───────────────┐
│                                                  │
│   ┌────────────────────────────────────────┐    │
│   │ DartIsolateGroup (C++)                 │    │
│   │ ┌────────────────────────────────────┐ │    │
│   │ │  port_name_map_                    │ │    │
│   │ │  unordered_map<string, Dart_Port> │ │    │
│   │ └────────────────────────────────────┘ │    │
│   │            ▲           ▲                │    │
│   │            │           │                │    │
│   └────────────┼───────────┼────────────────┘    │
│                │           │                     │
│   ┌────────────┴───┐   ┌───┴───────────────┐    │
│   │ Main Isolate    │   │ Background Isolate│    │
│   │ (Dart Heap)     │   │ (Dart Heap)       │    │
│   └─────────────────┘   └───────────────────┘    │
│                                                  │
└──────────────────────────────────────────────────┘
```

### 2. 存的不是 SendPort，是 Dart_Port (int64)

```
SendPort 是 Dart 层的对象，不能跨 Isolate 直接共享。
但 SendPort 的本质是一个 int64 的端口 ID —— Dart_Port。
Dart_Port 是 VM 内全局唯一的，可以安全地在 C++ 层裸存。

┌─────────────┐                        ┌─────────────┐
│ SendPort    │  序列化成 int64        │  Dart_Port  │
│ (Dart 对象) │ ─────────────────────→ │  = 0x07     │ ← 存这个
└─────────────┘                        └─────────────┘
                                            │
                                            │ 查询时再
                                            │ 用 Dart_Port 重建 SendPort
                                            ▼
                                       ┌─────────────┐
                                       │ SendPort    │ 返回给查询方
                                       │ (新 Dart 对象)│
                                       └─────────────┘
```

> 这就是为什么在子 Isolate 拿到的 `SendPort` 也能给主 Isolate 的 `ReceivePort` 投递消息 —— 不管哪个 Isolate 拿到，底层都是同一个 `Dart_Port` 编号。

### 3. registerPortWithName 源码路径

```
dart:ui (sky_engine)
   IsolateNameServer.registerPortWithName(port, name)
            │
            ▼
   _registerPortWithName(port.nativePort, name)   // 拿到 int64 端口 ID
            │
            ▼
   @Native('IsolateNameServerNatives::RegisterPortWithName')   // FFI 绑定
            │
            ▼  跨语言进入 C++
   IsolateNameServerNatives::RegisterPortWithName(port_id, name)
            │
            ▼
   DartIsolateGroup::Current()->RegisterIsolatePortWithName(port_id, name)
            │
            ▼
   std::lock_guard<std::mutex> lock(port_name_map_mutex_);
   port_name_map_[name] = port_id;   // 写入 map（加锁，线程安全）
            │
            ▼
   return true / false
```

### 4. 一次完整通信涉及的"层次"

```
        Dart 层            │       C++ 层         │      Dart 层
─────────────────────────  │  ───────────────────  │  ──────────────────
                          │                      │
  IsolateNameServer       │                      │
  .registerPortWithName ──┼──→ port_name_map_    │
                          │    [name] = port_id  │
                          │         ▲            │
                          │         │            │
                          │         │            │  IsolateNameServer
                          │         └────────────┼── .lookupPortByName
                          │      返回 port_id     │
                          │                      │
  SendPort.send(msg) ─────┼──────────────────────┼──→  ReceivePort.listen
                          │   VM 内部端口队列      │
                          │   把消息送到对应       │
                          │   Isolate 的 MQ       │
```

---

## 五、配套机制：PluginUtilities.getCallbackHandle

`IsolateNameServer` 解决了**"端口怎么找"**，但还有另一半问题没解决：

```
问题：Native（Java/Kotlin/OC）在某个时刻（闹钟、推送、定时器）想"拉起一个 Dart 函数"
      ——但 Native 完全没法存 Dart 函数对象，更没法直接调它。

      怎么告诉 Native "请到时候执行 backgroundCallback 这个函数"？
```

这就是 `PluginUtilities.getCallbackHandle` 干的事。

### 1. API 一览

```dart
import 'dart:ui';

// ① Dart 函数 → 句柄
//    入参必须是【顶层函数】或【static 方法】，闭包不行
CallbackHandle? handle =
    PluginUtilities.getCallbackHandle(backgroundCallback);

// ② 拿出 int64 给 Native 持久化
int rawHandle = handle!.toRawHandle();

// ③ 反向：从句柄拿回函数（一般在子 Isolate 里用，比较少手动调）
Function? fn = PluginUtilities.getCallbackFromHandle(handle);
```

### 2. CallbackHandle 是什么？

```
是 Dart Kernel 里函数的一个稳定 ID（int64）
不是函数指针、不是地址、不是 hashCode

┌──────────────────────────────────────────────────────────┐
│  Dart Kernel (编译产物)                                    │
│                                                          │
│  函数表：                                                  │
│  ┌──────┬─────────────────────────────────┐              │
│  │ ID   │ 函数描述                          │              │
│  ├──────┼─────────────────────────────────┤              │
│  │ #1   │ main                            │              │
│  │ #2   │ backgroundCallback              │ ← getCallbackHandle 返回 #2 │
│  │ #3   │ foo                             │              │
│  │ ...  │ ...                             │              │
│  └──────┴─────────────────────────────────┘              │
│                                                          │
│  这个 ID 在【同一份编译产物】里是稳定的：                      │
│  - APP 重启依然有效                                        │
│  - 手机重启依然有效                                        │
│  - 重新编译 / 发新版本后 ❌ 可能失效                          │
└──────────────────────────────────────────────────────────┘
```

### 3. 为什么必须是 top-level / static 函数？

```dart
@pragma('vm:entry-point')
void ok1() {}                                  // ✅ 顶层

class A {
  @pragma('vm:entry-point')
  static void ok2() {}                         // ✅ static
  void bad() {}                                // ❌ 实例方法（this 找不到）
}

PluginUtilities.getCallbackHandle(() {});      // ❌ 闭包（捕获了外部状态）
```

```
原因：
  - 后台拉起时是一个【全新的 Isolate】
  - 全新 Isolate 里没有 this、没有外部捕获的变量、没有任何已有状态
  - 所以函数必须是"自包含、靠 ID 就能定位"的 —— 只有顶层 / static 满足
```

### 4. 完整调用链路（Dart → C++ → Native → 重启后回来）

```
APP 第一次启动（注册阶段）              重启 / 闹钟触发（执行阶段）
─────────────────────────              ─────────────────────────
Dart 层 (Main Isolate)                  Native 层 (Android 主线程)

PluginUtilities                         AlarmManager 触发
  .getCallbackHandle(backgroundCallback)         │
       │                                         ▼
       ▼                                FlutterCallbackInformation
@Native('GetCallbackHandle')              .lookupCallbackInformation(handle)
       │                                         │
       ▼                                         ▼
DartCallbackCache::                      启动一个新的 FlutterEngine
  GetCallbackHandle(closure)              （Background FlutterEngine）
       │                                         │
       │ 把 closure 在 Kernel 里的偏移量          ▼
       │ 算出来作为 ID（int64）                  在新 Isolate 里执行
       ▼                                  Dart_Function 入口
   返回 0x12345 (示例)                            │
       │                                         ▼
       ▼                                  backgroundCallback()  ← 真的跑起来了！
   handle.toRawHandle() = 0x12345                │
       │                                         │
       ▼                                         ▼
   交给 Native 持久化保存                   函数里再用
   (SharedPreferences /                    IsolateNameServer.lookupPortByName
    AlarmManager extras)                   找到 Main Isolate 的 SendPort
                                                 │
                                                 ▼
                                          sp.send(...) 通知主进程
```

> 关键点：**`handle` 是个普通 int64，可以跨进程、跨重启、跨设备休眠保存**——这正是后台任务能在 APP 被杀后还能拉起 Dart 函数的根基。

### 5. 源码路径（dart:ui → Engine C++）

```
dart:ui
  PluginUtilities.getCallbackHandle(callback)
        │
        ▼
  _getCallbackHandle(callback)
        │
        ▼
  @Native('Utils::GetCallbackHandle')   // FFI 绑定
        │
        ▼  跨语言进入 C++
  flutter::DartCallbackCache::GetCallbackHandle(closure)
        │
        ▼
  - 解析 closure 对应的 Dart_Function
  - 拿到 Function 在 Kernel 里的偏移信息
  - 算出/查出一个稳定的 int64 ID
  - 同时把 ID → 函数信息 缓存进 cache_（map）
        │
        ▼
  return int64 ID

dart:ui
  PluginUtilities.getCallbackFromHandle(handle)
        │
        ▼
  Utils::LookupCallback(handle)
        │
        ▼
  flutter::DartCallbackCache::GetCallback(handle)
        │
        ▼
  查 cache_，重建闭包返回
```

### 6. 与 `IsolateNameServer` 的分工

```
┌──────────────────────────┬─────────────────────────────────────────┐
│   API                     │            它负责什么                     │
├──────────────────────────┼─────────────────────────────────────────┤
│ PluginUtilities           │  把 Dart 函数 "序列化" 成 int64 句柄。     │
│ .getCallbackHandle        │  让 Native 能持久化保存"等下要调哪个函数"。 │
│                           │  解决【Native 如何拉起 Dart 函数】问题。   │
├──────────────────────────┼─────────────────────────────────────────┤
│ IsolateNameServer         │  把 Dart 端口 "命名" 后挂到全局表里。      │
│ .registerPortWithName /   │  让被拉起的子 Isolate 能找回主 Isolate。   │
│ .lookupPortByName         │  解决【子 Isolate 如何回传消息】问题。     │
└──────────────────────────┴─────────────────────────────────────────┘

两个 API 解决的是同一个场景的两个不同方向：
   Native ── 拉起 Dart ─→ getCallbackHandle
   Dart 子 ── 回传给主 ─→ IsolateNameServer
```

---

## 六、完整代码示例

### 6.1 简单 Demo：两个 Isolate 通过名字通信

```dart
import 'dart:isolate';
import 'dart:ui';

const String kPortName = 'demo_port';

void main() async {
  // === Main Isolate ===
  final receivePort = ReceivePort();

  // 先清掉旧的注册（同名注册会失败）
  IsolateNameServer.removePortNameMapping(kPortName);

  // 注册端口
  final ok = IsolateNameServer.registerPortWithName(
    receivePort.sendPort,
    kPortName,
  );
  print('register: $ok');

  // 监听来自其他 Isolate 的消息
  receivePort.listen((msg) {
    print('Main 收到: $msg');
  });

  // 启动子 Isolate（这里只是演示，注意：完全独立的 Isolate）
  await Isolate.spawn(workerEntry, null);
}

// === Background Isolate ===
void workerEntry(_) {
  // 注意：没有任何参数传递 Port，全靠"名字"找
  final sp = IsolateNameServer.lookupPortByName(kPortName);
  if (sp == null) {
    print('没找到端口');
    return;
  }
  sp.send('hello from worker');
}
```

输出：

```
register: true
Main 收到: hello from worker
```

### 6.2 实战场景：后台定时任务通知 UI（配合 `getCallbackHandle`）

下面这个例子完整演示了两个 API 的配合：
1. `getCallbackHandle` 把 Dart 函数变成 int64，交给 Native 持久化
2. `IsolateNameServer` 让后台 Isolate 找回主 Isolate 的端口

```dart
// ===== 共享常量 =====
const String kUiPortName = 'ui_update_port';

// ===== Main Isolate (UI) =====
class HomePage extends StatefulWidget {
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final ReceivePort _rp = ReceivePort();
  int _count = 0;

  @override
  void initState() {
    super.initState();

    // ---- ① 注册端口（保证唯一）----
    IsolateNameServer.removePortNameMapping(kUiPortName);
    IsolateNameServer.registerPortWithName(_rp.sendPort, kUiPortName);

    // ---- ② 监听后台消息 ----
    _rp.listen((msg) {
      setState(() => _count++);
      debugPrint('UI 收到后台消息: $msg');
    });

    // ---- ③ 把 Dart 函数转成 int64 句柄 ----
    final CallbackHandle? handle =
        PluginUtilities.getCallbackHandle(backgroundCallback);
    if (handle == null) {
      // top-level / static 函数才会成功；不会返回 null 一般是写错了
      return;
    }
    final int rawHandle = handle.toRawHandle();

    // ---- ④ 注册后台任务，把 handle 交给 Native 保存 ----
    //
    // 部分老插件 API 直接收 Function：
    //   AndroidAlarmManager.periodic(..., backgroundCallback);
    // 它内部其实也是先 getCallbackHandle 再传 int64 给 Native。
    //
    // 这里手动展示一遍底层：
    AndroidAlarmManager.periodic(
      const Duration(minutes: 1),
      0,                              // 任务 ID
      backgroundCallback,             // 这步等价于内部跑了 getCallbackHandle
      // 或者直接走原生 channel：
      //   channel.invokeMethod('schedule', {'handle': rawHandle});
    );
  }

  @override
  void dispose() {
    _rp.close();
    IsolateNameServer.removePortNameMapping(kUiPortName);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) { /* ... */ }
}

// ===== Background Isolate =====
// ⚠️ 必须是 top-level 函数 + @pragma('vm:entry-point')
//    - top-level：保证可以靠 ID 单独定位
//    - vm:entry-point：保证 AOT 编译不会把它 tree-shaking 掉
@pragma('vm:entry-point')
void backgroundCallback() {
  // 跑在一个【全新】的 Background Isolate 里：
  //   - 没有 widget、没有 BuildContext
  //   - 全局变量都是默认值（不是主 Isolate 的副本）
  //   - 但是【Engine 层的 IsolateNameServer 表共享】
  final sp = IsolateNameServer.lookupPortByName(kUiPortName);
  sp?.send({'type': 'tick', 'time': DateTime.now().toIso8601String()});
}
```

Native 侧拿到 `rawHandle` 后大概是这样使用（伪代码）：

```kotlin
// Android 侧（插件作者视角，调用方不用关心）
fun scheduleBackground(handle: Long) {
    sharedPrefs.edit().putLong("callback_handle", handle).apply()
    alarmManager.setRepeating(... PendingIntent)
}

// 闹钟触发时：
override fun onReceive(context: Context, intent: Intent) {
    val handle = sharedPrefs.getLong("callback_handle", -1)

    // 用 handle 启动一个新的 Background FlutterEngine
    val engine = FlutterEngine(context)
    val callbackInfo =
        FlutterCallbackInformation.lookupCallbackInformation(handle)
    engine.dartExecutor.executeDartCallback(
        DartCallback(context.assets, FlutterMain.findAppBundlePath(), callbackInfo)
    )
    // 这一步会在新 Isolate 里调起 backgroundCallback()
}
```

---

## 七、常见应用场景

```
┌─────────────────────────────────┬──────────────────────────────────┐
│           场景                   │             典型库                 │
├─────────────────────────────────┼──────────────────────────────────┤
│ 后台定时任务通知 UI               │ android_alarm_manager_plus       │
│ 后台 Service 与 UI 通信          │ flutter_background_service       │
│ FCM/推送在后台被唤起，转发数据      │ firebase_messaging               │
│ WorkManager 任务回调 UI          │ workmanager                      │
│ 下载/上传 Isolate 通知主线程进度    │ flutter_downloader               │
│ 多 Isolate 共享一个"协调器"端口    │ 自定义架构                        │
└─────────────────────────────────┴──────────────────────────────────┘

共同特征：通信双方"启动顺序未知、启动者未知"，没法靠传参数传 SendPort。
```

---

## 八、关键注意事项 / 踩坑点

### 1. import 用 `dart:ui` 而不是 `dart:isolate`

```dart
import 'dart:ui';          // ✅ IsolateNameServer 在这里
import 'dart:isolate';     // ✅ SendPort / ReceivePort 在这里
```

### 2. 同名注册会失败，不是覆盖

```dart
IsolateNameServer.registerPortWithName(p1, 'x');  // true
IsolateNameServer.registerPortWithName(p2, 'x');  // ❌ false，不会覆盖
```

热重载 / 页面重建场景，旧的 `ReceivePort` 还在表里 → 必须先移除：

```dart
IsolateNameServer.removePortNameMapping('x');
IsolateNameServer.registerPortWithName(newPort, 'x');
```

### 3. 后台 Dart 入口必须加 `@pragma('vm:entry-point')`

```dart
@pragma('vm:entry-point')   // ❗ AOT 模式下不加会被 Tree-Shaking 掉
void backgroundCallback() { ... }
```

### 4. ReceivePort 别忘了 close，端口别忘了移除

```dart
@override
void dispose() {
  _rp.close();
  IsolateNameServer.removePortNameMapping(kUiPortName);
  super.dispose();
}
```

否则进程内"僵尸端口"会越来越多，且新的 `register` 会一直返回 `false`。

### 5. 注册表的作用域是 **Engine 的 IsolateGroup**，不是全 App

```
- 同一个 FlutterEngine 内所有 Isolate 共享 ✅
- 不同 FlutterEngine（如多引擎模式）之间 ❌ 互相看不到
- 完全跨进程 ❌ 看不到（不是 IPC）
```

### 6. SendPort 只能发"可序列化对象"

```dart
sp.send('字符串');                 // ✅
sp.send({'a': 1, 'b': [1,2,3]});  // ✅
sp.send(myWidget);                // ❌ Widget 不可序列化
sp.send(myStream);                // ❌ 大多数对象都不行
```

---

## 九、和其他通信方式的对比

```
┌──────────────────────┬───────────────────┬─────────────────────────────┐
│       方式            │     适用边界       │           特点               │
├──────────────────────┼───────────────────┼─────────────────────────────┤
│ Isolate.spawn 传参   │ 同进程、有启动者    │ 简单直接，但启动者必须能拿到 SP │
│ IsolateNameServer    │ 同 Engine、无启动者│ 用全局名字查，本文重点         │
│ PlatformChannel      │ Dart ↔ Native     │ JNI 调用，不是 Isolate 间     │
│ MethodChannel +      │ Native ↔ 后台 Dart│ 走 Native 中转，慢但通用       │
│ BackgroundIsolate    │                   │                             │
│ Socket / 共享文件     │ 跨进程            │ 真 IPC，速度慢但能跨进程       │
└──────────────────────┴───────────────────┴─────────────────────────────┘
```

---

## 十、一张图总结

```
┌─ 后台 Isolate 通信全景（两个 API 协同） ────────────────────────────────────┐
│                                                                          │
│  Main Isolate (UI)              Engine 层 C++              Background Isolate │
│                                                                          │
│  ┌───────────────────┐       ┌──────────────────────┐                    │
│  │ ReceivePort       │       │ DartCallbackCache    │                    │
│  │ SendPort sp = ... │       │ ────────────────     │                    │
│  └────────┬──────────┘       │ #1 backgroundCallback│                    │
│           │ ①register        │ #2 otherCallback     │                    │
│           │                  └──────────────────────┘                    │
│           ▼                          ▲                                    │
│  ┌────────────────────────────────────┴──────────────┐                   │
│  │ port_name_map_                                    │                   │
│  │ "ui_port" → Dart_Port#7                            │                   │
│  └────────────────────────────────────────────────────┘                   │
│           ▲                          ▲                                    │
│           │                          │                                    │
│           │ ② getCallbackHandle      │  ③ Native 持久化 int64 handle      │
│           │    返回 int64            │     存到 SharedPreferences          │
│           │                          │     注册到 AlarmManager 等         │
│           │                          │                                    │
│           └──────────────►  Native (Java/Kotlin) ◄──────────────          │
│                                          │                                │
│                                          │ ④ 触发时机到了                  │
│                                          │   lookupCallbackInformation    │
│                                          │   启动 Background FlutterEngine│
│                                          ▼                                │
│                                ┌──────────────────────────┐               │
│                                │ Background Isolate       │               │
│                                │ 执行 backgroundCallback()│               │
│                                │                          │               │
│                                │ ⑤ lookupPortByName       │               │
│                                │   ("ui_port")            │               │
│                                │   → 拿到 SendPort         │               │
│                                │                          │               │
│                                │ ⑥ sp.send(msg) ──────────┼───┐           │
│                                └──────────────────────────┘   │           │
│                                                               │           │
│  ⑦ ReceivePort.listen(msg)  ◄─────────────────────────────────┘           │
│     setState 刷新 UI                                                       │
│                                                                          │
│  ╭─ 两个 API 的角色 ────────────────────────────────────────────────╮    │
│  │ getCallbackHandle  → 解决 "Native 怎么找 Dart 函数入口"             │    │
│  │ IsolateNameServer  → 解决 "子 Isolate 怎么找 Main Isolate 端口"     │    │
│  ╰────────────────────────────────────────────────────────────────╯    │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 十一、面试回答模板

**Q: `IsolateNameServer.registerPortWithName` 和 `lookupPortByName` 的原理是什么？**

> IsolateNameServer 是 Flutter Engine 在 C++ 层维护的一张全局表，key 是字符串名字，value 是 `Dart_Port`（一个 VM 内全局唯一的 int64 端口 ID）。
>
> `registerPortWithName` 会通过 dart:ui 的 native 方法调到 C++ 的 `DartIsolateGroup::RegisterIsolatePortWithName`，把 `SendPort.nativePort` 写入 `port_name_map_`，整个过程加锁保证线程安全。同名注册会失败而不是覆盖。
>
> `lookupPortByName` 反向查表拿到 `Dart_Port`，再在 Dart 层包装成一个新的 `SendPort` 返回。
>
> 它的核心价值是解决了**两个互不知道对方的 Isolate 怎么建立通信通道**的问题——主 Isolate 提前用一个公共名字注册自己的接收端口，后台 Isolate 被 Native（如 AlarmManager、WorkManager、FCM）拉起后，通过同样的名字就能查到这个端口并发送消息。
>
> 注意：这不是 IPC，作用域是同一个 FlutterEngine 内的所有 Isolate；存的是 `Dart_Port` 编号而不是 `SendPort` 对象，因为 Isolate 之间不能共享对象；后台入口必须加 `@pragma('vm:entry-point')` 防止 AOT 下被 tree-shaking 掉。

**Q: `PluginUtilities.getCallbackHandle` 又是干什么的？和 IsolateNameServer 什么关系？**

> 它解决的是另一个方向的问题：**Native 怎么知道一个 Dart 函数？**
>
> `getCallbackHandle` 会把一个 top-level 或 static 的 Dart 函数转成一个 int64 句柄。底层会调到 C++ 的 `DartCallbackCache::GetCallbackHandle`，基于函数在 Dart Kernel 里的偏移信息算出一个稳定 ID，同时把 ID → 函数信息缓存起来。
>
> 这个 int64 是普通整数，可以跨进程、跨重启、跨设备休眠保存——所以 Native 可以把它存到 SharedPreferences 或 AlarmManager 里。等闹钟、推送、WorkManager 触发时，Native 用这个 handle 调 `FlutterCallbackInformation.lookupCallbackInformation`，再启动一个新的 Background FlutterEngine 把对应的 Dart 函数跑起来。
>
> 它和 IsolateNameServer 是后台任务场景的"黄金搭档"，分别解决两个方向的问题：
> - `getCallbackHandle`：**Native → Dart**，让 Native 能拉起 Dart 函数
> - `IsolateNameServer`：**Background Isolate → Main Isolate**，让被拉起的 Isolate 找回主 Isolate 的端口回传消息
>
> 因为后台 Isolate 是个全新的执行环境（没有 this、没有外部捕获、没有主 Isolate 的全局变量），所以传给 `getCallbackHandle` 的函数必须是顶层 / static 且加 `@pragma('vm:entry-point')`；这两条规矩本质上都是为了"靠一个 int64 就能在另一个 Isolate 里把函数完整找回来"。
