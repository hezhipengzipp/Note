# Flutter Bloc与GetX 核心 API 速查

---

## 一、Provider 层

### 1.1 `BlocProvider`

**作用**：注入单个 `Bloc` 或 `Cubit`，让子树可以通过 `context.read/watch/select` 获取。

```dart
BlocProvider(
  create: (_) => CounterBloc(),
  child: CounterPage(),
)
```

常见用法：

- 页面级注入一个 `Bloc`
- 在页面销毁时自动关闭 `Bloc`

---

### 1.2 `MultiBlocProvider`

**作用**：一次注入多个 `Bloc`，避免多层嵌套。

```dart
MultiBlocProvider(
  providers: [
    BlocProvider(create: (_) => AuthBloc()),
    BlocProvider(create: (_) => ProfileBloc()),
  ],
  child: HomePage(),
)
```

适合：

- 首页同时依赖多个 `Bloc`
- 根节点统一管理多个业务模块

---

## 二、Consumer 层

### 2.1 `BlocListener`

**作用**：处理一次性副作用，不负责重建 UI。

典型场景：

- 页面跳转
- `SnackBar`
- 弹窗
- 上报埋点

```dart
BlocListener<LoginBloc, LoginState>(
  listener: (context, state) {
    if (state is LoginSuccess) {
      Navigator.of(context).pushReplacementNamed('/home');
    }
  },
  child: LoginView(),
)
```

一句话记忆：

**导航弹窗用 `Listener`**

---

### 2.2 `BlocBuilder`

**作用**：根据状态变化重建 UI，不处理副作用。

```dart
BlocBuilder<CounterBloc, CounterState>(
  builder: (context, state) {
    return Text('count = ${state.count}');
  },
)
```

适合：

- 文本刷新
- 列表刷新
- 加载态/空态/错误态切换

一句话记忆：

**UI 更新用 `Builder`**

---

### 2.3 `BlocConsumer`

**作用**：把 `BlocListener` 和 `BlocBuilder` 合在一起，同时处理副作用和 UI 更新。

```dart
BlocConsumer<LoginBloc, LoginState>(
  listener: (context, state) {
    if (state is LoginFailure) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(state.message)),
      );
    }
  },
  builder: (context, state) {
    if (state is LoginLoading) {
      return const CircularProgressIndicator();
    }
    return const LoginForm();
  },
)
```

适合：

- 登录页
- 提交表单页
- 既要更新界面又要弹提示的页面

一句话记忆：

**两者都要用 `Consumer`**

---

## 三、性能优化层

### 3.1 `BlocSelector`

**作用**：从完整状态中选出一个“子集”，只有这个子集变化时才重建当前组件。

```dart
BlocSelector<UserBloc, UserState, String>(
  selector: (state) => state.user.name,
  builder: (context, name) {
    return Text(name);
  },
)
```

适合：

- 页面状态很大，但某个组件只关心其中一个字段
- 降低无关状态导致的重建

一句话记忆：

**组件方式选状态子集，用 `BlocSelector`**

---

### 3.2 `context.select`

**作用**：和 `BlocSelector` 思路一样，但写法更声明式，适合在 `build` 里直接取局部状态。

```dart
@override
Widget build(BuildContext context) {
  final isLoading = context.select(
    (LoginBloc bloc) => bloc.state.isLoading,
  );

  return ElevatedButton(
    onPressed: isLoading ? null : () {},
    child: Text(isLoading ? '提交中...' : '提交'),
  );
}
```

适合：

- 一个 widget 只依赖某个很小的字段
- 不想额外包一层 `BlocSelector`

一句话记忆：

**声明式选状态子集，用 `context.select`**

---

## 四、使用口诀

```text
数据注入用 Provider
导航弹窗用 Listener
UI 更新用 Builder
两者都要用 Consumer
性能优化选 Selector
```

---

## 五、最小组合示例

```dart
BlocProvider(
  create: (_) => LoginBloc(),
  child: BlocConsumer<LoginBloc, LoginState>(
    listener: (context, state) {
      if (state is LoginSuccess) {
        Navigator.of(context).pushReplacementNamed('/home');
      }
    },
    builder: (context, state) {
      final isLoading = context.select(
        (LoginBloc bloc) => bloc.state.isLoading,
      );

      return ElevatedButton(
        onPressed: isLoading
            ? null
            : () => context.read<LoginBloc>().add(LoginSubmitted()),
        child: Text(isLoading ? '登录中...' : '登录'),
      );
    },
  ),
)
```

这个例子里：

- `BlocProvider` 负责注入 `LoginBloc`
- `BlocConsumer` 负责副作用和 UI
- `context.select` 负责只监听 `isLoading`
- `context.read` 负责派发事件，不订阅状态

---

## 六、面试式一句话区分

- `BlocProvider`：把 `Bloc` 放进组件树
- `BlocListener`：监听状态做副作用
- `BlocBuilder`：监听状态重建 UI
- `BlocConsumer`：`Listener + Builder`
- `BlocSelector`：只订阅状态的一部分
- `context.select`：用声明式方式订阅状态的一部分

---

## 七、GetX 依赖注入层

### 7.1 `Get.put`

**作用**：立即注册并创建对象，后续通过 `Get.find()` 获取。

```dart
final controller = Get.put(HomeController());
```

适合：

- 页面进入时立刻需要 controller
- 全局对象常驻内存时配合 `permanent: true`

```dart
Get.put(AuthController(), permanent: true);
```

---

### 7.2 `Get.lazyPut`

**作用**：先注册工厂，第一次 `Get.find()` 时再创建实例。

```dart
Get.lazyPut<HomeController>(() => HomeController());
```

如果希望销毁后还能再次自动创建：

```dart
Get.lazyPut<HomeController>(
  () => HomeController(),
  fenix: true,
);
```

适合：

- 懒加载页面级 controller
- 避免首页一次性创建太多对象

---

### 7.3 `Get.find`

**作用**：从 GetX 容器中取出已经注册的对象。

```dart
final HomeController controller = Get.find<HomeController>();
```

一句话记忆：

**`put/lazyPut` 负责注册，`find` 负责取**

---

### 7.4 `Bindings`

**作用**：把某个页面依赖的 controller 和路由绑在一起。

```dart
class HomeBinding extends Bindings {
  @override
  void dependencies() {
    Get.lazyPut<HomeController>(() => HomeController());
  }
}
```

```dart
GetPage(
  name: '/home',
  page: () => HomePage(),
  binding: HomeBinding(),
)
```

适合：

- 页面级依赖管理
- 避免在 A 页面注册 B 页面 controller

---

## 八、GetX 状态更新层

### 8.1 `Obx`

**作用**：监听 `Rx` 变量，变量变化时自动重建。

```dart
class CounterController extends GetxController {
  final count = 0.obs;
}

Obx(() {
  return Text('count = ${controller.count.value}');
})
```

适合：

- 小范围响应式刷新
- 一个 widget 只依赖几个 `Rx` 字段

---

### 8.2 `GetX<T>`

**作用**：拿到指定 controller，并监听其内部 `Rx` 状态变化。

```dart
GetX<CounterController>(
  builder: (controller) {
    return Text('count = ${controller.count.value}');
  },
)
```

适合：

- 想在 builder 里直接拿 controller
- 页面已经通过 `Get.put` 或 `Binding` 注册了 controller

---

### 8.3 `GetBuilder`

**作用**：手动更新型状态管理，只有调用 `update()` 时才重建。

```dart
class CounterController extends GetxController {
  int count = 0;

  void increment() {
    count++;
    update();
  }
}

GetBuilder<CounterController>(
  builder: (controller) {
    return Text('count = ${controller.count}');
  },
)
```

适合：

- 不需要 `Rx`
- 你想精确控制重建时机

一句话区分：

- `Obx`：自动响应式
- `GetBuilder`：手动 `update()`

---

## 九、GetX 路由与副作用层

### 9.1 `GetMaterialApp`

**作用**：启用 GetX 的路由、依赖注入、弹窗等能力。

```dart
GetMaterialApp(
  initialRoute: '/login',
  getPages: [
    GetPage(name: '/login', page: () => LoginPage()),
    GetPage(name: '/home', page: () => HomePage()),
  ],
)
```

---

### 9.2 `Get.to / Get.off / Get.offAll`

**作用**：无 `context` 路由跳转。

```dart
Get.to(() => DetailPage());
Get.off(() => HomePage());
Get.offAll(() => MainPage());
```

常见场景：

- `Get.to`：入栈新页面
- `Get.off`：关闭当前页并进入下一页
- `Get.offAll`：清空路由栈，常用于登录成功后进首页

---

### 9.3 `Get.snackbar / Get.dialog / Get.bottomSheet`

**作用**：不依赖 `context` 直接弹提示和弹层。

```dart
Get.snackbar('提示', '登录成功');

Get.dialog(
  const AlertDialog(title: Text('确认退出')),
);

Get.bottomSheet(
  Container(height: 200, color: Colors.white),
);
```

适合：

- Controller 里直接触发提示
- 减少 `BuildContext` 传递

---

## 十、GetX 生命周期与监听

### 10.1 `onInit / onReady / onClose`

**作用**：`GetxController` 常用生命周期。

```dart
class LoginController extends GetxController {
  @override
  void onInit() {
    super.onInit();
  }

  @override
  void onReady() {
    super.onReady();
  }

  @override
  void onClose() {
    super.onClose();
  }
}
```

适合放置的逻辑：

- `onInit`：初始化数据、注册 worker、发起请求
- `onReady`：首帧后跳转、弹窗
- `onClose`：释放 `TextEditingController`、流、worker

---

### 10.2 `ever / debounce / interval`

**作用**：监听 `Rx` 变量变化并按规则执行回调。

```dart
class SearchController extends GetxController {
  final keyword = ''.obs;
  late Worker worker;

  @override
  void onInit() {
    super.onInit();
    worker = debounce(
      keyword,
      (value) => search(value),
      time: const Duration(milliseconds: 500),
    );
  }

  void search(String value) {}

  @override
  void onClose() {
    worker.dispose();
    super.onClose();
  }
}
```

一句话区分：

- `ever`：每次变化都执行
- `debounce`：停下来后执行最后一次
- `interval`：一段时间内只执行第一次

---

## 十一、GetX 使用口诀

```text
对象注册用 put
懒加载用 lazyPut
页面依赖用 Binding
自动响应用 Obx
手动刷新用 GetBuilder
页面跳转用 Get.to
副作用提示用 snackbar/dialog
输入搜索用 debounce
防重复点击用 interval
```

---

## 十二、GetX 最小组合示例

```dart
class LoginController extends GetxController {
  final isLoading = false.obs;

  Future<void> login() async {
    isLoading.value = true;
    await Future.delayed(const Duration(seconds: 1));
    isLoading.value = false;
    Get.offAll(() => HomePage());
  }
}

class LoginBinding extends Bindings {
  @override
  void dependencies() {
    Get.lazyPut<LoginController>(() => LoginController());
  }
}

class LoginPage extends GetView<LoginController> {
  const LoginPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      return ElevatedButton(
        onPressed: controller.isLoading.value ? null : controller.login,
        child: Text(controller.isLoading.value ? '登录中...' : '登录'),
      );
    });
  }
}
```

这个例子里：

- `Binding` 负责注册 `LoginController`
- `GetView` 负责直接拿到 controller
- `Obx` 负责监听 `isLoading`
- `Get.offAll` 负责登录成功后清空栈进入首页

---

## 十三、Bloc 与 GetX 快速对照

- 依赖注入：`BlocProvider` 对应 `Get.put / Binding`
- UI 更新：`BlocBuilder` 对应 `Obx / GetBuilder`
- 副作用：`BlocListener` 对应 `ever` 或直接 `Get.snackbar / Get.to`
- 合体写法：`BlocConsumer` 对应 `Obx + 副作用逻辑`
- 性能优化：`BlocSelector/context.select` 对应更细粒度拆分 `Obx`

一句话总结：

- `Bloc` 更强调事件和状态流
- `GetX` 更强调直接、快速、少样板
