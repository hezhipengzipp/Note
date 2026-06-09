# Flutter Provider 使用详解

---

## 一、Provider 是什么

`provider` 是对 `InheritedWidget` 的一层封装，核心目标有两件事：

- 把对象安全地注入到组件树里
- 让依赖这个对象的 Widget 在数据变化时自动刷新

一句话理解：

> `provider` 解决的是“状态放哪”和“谁该跟着刷新”这两个问题。

---

## 二、先记住三组 API

### 2.1 提供数据

- `Provider<T>`：提供一个普通对象
- `ChangeNotifierProvider<T>`：提供 `ChangeNotifier`
- `FutureProvider<T>`：提供异步结果
- `StreamProvider<T>`：提供流式数据
- `ProxyProvider`：一个对象依赖另一个对象时使用

### 2.2 读取数据

- `context.watch<T>()`：监听，`T` 变化会触发当前 Widget 重建
- `context.read<T>()`：只读取，不监听
- `context.select<T, R>()`：只监听 `T` 的一部分字段

### 2.3 局部刷新

- `Consumer<T>`：只让包裹的那一小块 UI 重建
- `Selector<T, R>`：只在选中的字段变化时重建

---

## 三、最小使用方式

### 3.1 提供一个普通对象

```dart
Provider<UserRepository>(
  create: (_) => UserRepository(),
  child: const MyApp(),
)
```

读取：

```dart
final repo = context.read<UserRepository>();
```

如果只是依赖注入，不需要通知界面刷新，用 `Provider` 就够了。

---

### 3.2 提供一个可通知刷新的状态对象

```dart
class CounterModel extends ChangeNotifier {
  int count = 0;

  void increment() {
    count++;
    notifyListeners();
  }
}

ChangeNotifierProvider(
  create: (_) => CounterModel(),
  child: const CounterPage(),
)
```

页面读取：

```dart
class CounterPage extends StatelessWidget {
  const CounterPage({super.key});

  @override
  Widget build(BuildContext context) {
    final count = context.watch<CounterModel>().count;

    return Scaffold(
      body: Center(child: Text('$count')),
      floatingActionButton: FloatingActionButton(
        onPressed: () => context.read<CounterModel>().increment(),
        child: const Icon(Icons.add),
      ),
    );
  }
}
```

记忆方式：

- 显示数据用 `watch`
- 触发动作用 `read`

---

## 四、`watch`、`read`、`select` 怎么选

### 4.1 `watch`

```dart
final user = context.watch<UserModel>();
```

适合：

- 整个 Widget 都依赖这份状态
- 状态变化后整个 `build` 都要重新执行

---

### 4.2 `read`

```dart
context.read<UserModel>().logout();
```

适合：

- 点击事件
- 提交表单
- 只想调用方法，不关心后续刷新

注意：

`read` 不能替代 `watch` 做界面订阅，因为它不会监听变化。

---

### 4.3 `select`

```dart
final nickname = context.select((UserModel m) => m.profile.nickname);
```

适合：

- 状态对象很大
- 当前 Widget 只关心其中一个字段
- 想减少无关字段变化带来的重建

---

## 五、`Consumer` 和 `Selector`

### 5.1 `Consumer`

如果整个页面都 `watch`，那么 `build` 会整体重跑。想把刷新范围缩小，就用 `Consumer`：

```dart
Scaffold(
  appBar: AppBar(title: const Text('Counter')),
  body: Consumer<CounterModel>(
    builder: (_, model, __) {
      return Text('${model.count}');
    },
  ),
)
```

还可以配合 `child` 避免静态子树重复构建：

```dart
Consumer<CounterModel>(
  child: const Icon(Icons.add),
  builder: (_, model, child) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Text('${model.count}'),
        child!,
      ],
    );
  },
)
```

---

### 5.2 `Selector`

```dart
Selector<UserModel, String>(
  selector: (_, model) => model.profile.nickname,
  builder: (_, nickname, __) {
    return Text(nickname);
  },
)
```

适合：

- 希望像 `select` 一样只监听某个字段
- 但又不想直接在 `build` 里写 `context.select`

---

## 六、多个 Provider 一起用

### 6.1 不推荐多层嵌套

```dart
Provider<A>(
  create: (_) => A(),
  child: Provider<B>(
    create: (_) => B(),
    child: Provider<C>(
      create: (_) => C(),
      child: const MyApp(),
    ),
  ),
)
```

### 6.2 推荐 `MultiProvider`

```dart
MultiProvider(
  providers: [
    Provider(create: (_) => UserRepository()),
    ChangeNotifierProvider(create: (_) => AuthModel()),
    ChangeNotifierProvider(create: (_) => ThemeModel()),
  ],
  child: const MyApp(),
)
```

`MultiProvider` 只是把嵌套写平，不改变 Provider 的实际行为。

---

## 七、对象之间有依赖时用 `ProxyProvider`

典型场景：

- `AuthService` 提供 token
- `ApiClient` 依赖 token

```dart
MultiProvider(
  providers: [
    Provider(create: (_) => AuthService()),
    ProxyProvider<AuthService, ApiClient>(
      update: (_, auth, previous) {
        return previous ?? ApiClient(auth.token);
      },
    ),
  ],
  child: const MyApp(),
)
```

更常见的写法是把依赖变化同步到旧对象里，而不是每次都新建：

```dart
ProxyProvider<AuthService, ApiClient>(
  update: (_, auth, previous) {
    final client = previous ?? ApiClient();
    client.updateToken(auth.token);
    return client;
  },
)
```

如果目标对象本身也是 `ChangeNotifier`，就用 `ChangeNotifierProxyProvider`。

---

## 八、异步数据：`FutureProvider` 与 `StreamProvider`

### 8.1 `FutureProvider`

```dart
FutureProvider<User?>(
  initialValue: null,
  create: (_) => api.fetchUser(),
  child: const ProfilePage(),
)
```

读取：

```dart
final user = context.watch<User?>();
```

适合：

- 页面初始化拉一次数据
- 让异步结果直接进入组件树

---

### 8.2 `StreamProvider`

```dart
StreamProvider<int>(
  initialValue: 0,
  create: (_) => counterStream,
  child: const CounterPage(),
)
```

适合：

- WebSocket
- 登录状态流
- 数据库监听

---

## 九、生命周期与创建方式

### 9.1 默认用 `create`

```dart
Provider(
  create: (_) => MyModel(),
  child: const MyPage(),
)
```

适用于：

- Provider 自己负责创建对象
- 让 Provider 接管对象生命周期

---

### 9.2 已有实例再用 `.value`

```dart
final model = MyModel();

Provider.value(
  value: model,
  child: const MyPage(),
)
```

适用于：

- 你手里已经有现成对象
- 列表复用、外部缓存对象、已有单例对象

不要反过来写：

```dart
// 不推荐
Provider.value(
  value: MyModel(),
  child: const MyPage(),
)
```

原因是 `.value` 适合“复用已有实例”，不适合“在这里新建实例”。

---

## 十、两个高频坑

### 10.1 在 `initState` 里监听 Provider

下面这种写法会有问题：

```dart
@override
void initState() {
  super.initState();
  context.watch<UserModel>();
}
```

原因：

- `initState` 只会执行一次
- 但 `watch` 的语义是“建立订阅并在变化后重建”
- 这个生命周期和 `initState` 不匹配

正确做法有两种：

1. 只是取一次值，用 `read`
2. 需要订阅变化，就放到 `build` 里用 `watch/select`，或者用 `didChangeDependencies`

示例：

```dart
@override
void initState() {
  super.initState();
  Future.microtask(() {
    context.read<UserModel>().load();
  });
}
```

---

### 10.2 在 build 过程中同步修改状态

下面这种模式容易报错：

```dart
initState() {
  super.initState();
  context.read<MyNotifier>().fetchSomething();
}
```

如果 `fetchSomething()` 立刻同步 `notifyListeners()`，就可能出现“构建中修改状态”的问题。

更稳妥的方式：

- 直接在 model 构造函数里启动初始化逻辑
- 或者在本帧结束后再触发

```dart
ChangeNotifierProvider(
  create: (_) => MyNotifier()..fetchSomething(),
  child: const MyPage(),
)
```

或者：

```dart
@override
void initState() {
  super.initState();
  Future.microtask(() {
    context.read<MyNotifier>().fetchSomething();
  });
}
```

---

## 十一、推荐使用套路

### 11.1 页面级状态

```dart
class UserPageModel extends ChangeNotifier {
  bool loading = false;
  User? user;

  Future<void> load() async {
    loading = true;
    notifyListeners();

    user = await api.fetchUser();

    loading = false;
    notifyListeners();
  }
}
```

```dart
ChangeNotifierProvider(
  create: (_) => UserPageModel()..load(),
  child: const UserPage(),
)
```

```dart
class UserPage extends StatelessWidget {
  const UserPage({super.key});

  @override
  Widget build(BuildContext context) {
    final loading = context.select((UserPageModel m) => m.loading);
    final user = context.select((UserPageModel m) => m.user);

    if (loading) {
      return const Center(child: CircularProgressIndicator());
    }

    return Text(user?.name ?? 'empty');
  }
}
```

这套写法的优点：

- 初始化逻辑集中在 model
- UI 只读状态，不关心数据来源
- 用 `select` 降低无关重建

---

## 十二、面试式总结

- `Provider`：做依赖注入，适合普通对象
- `ChangeNotifierProvider`：管理可通知刷新的状态对象
- `watch`：监听整个对象
- `read`：只取值或调方法，不监听
- `select`：只监听对象的一部分
- `Consumer`：缩小重建范围
- `Selector`：字段级监听
- `MultiProvider`：平铺多个 Provider
- `ProxyProvider`：处理 Provider 之间的依赖

---

## 十三、使用口诀

```text
普通对象用 Provider
可变状态用 ChangeNotifierProvider
显示数据用 watch
触发动作用 read
局部订阅用 select / Selector
缩小刷新用 Consumer
多个注入用 MultiProvider
对象依赖对象用 ProxyProvider
```

---

## 参考

- 官方中文 README：
  https://github.com/rrousselGit/provider/blob/master/resources/translations/zh-CN/README.md
