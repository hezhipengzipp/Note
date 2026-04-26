# Flutter Bloc 核心 API 速查

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

