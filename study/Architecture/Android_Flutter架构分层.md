# Android/Flutter 项目架构分层

> Clean Architecture 分层设计

## 整体分层

```
┌─────────────────────────────────┐
│         Presentation            │  UI层：Activity/Fragment/ViewModel
│   (View + ViewModel)            │  职责：渲染UI、处理用户交互
├─────────────────────────────────┤
│           Domain                │  领域层：UseCase（用例）
│   (UseCase + Repository接口)    │  职责：业务逻辑、定义接口
├─────────────────────────────────┤
│            Data                 │  数据层：Repository实现
│  (Repository + DataSource)      │  职责：数据获取（网络/本地/缓存）
└─────────────────────────────────┘
```

**依赖规则**：外层依赖内层，内层不知道外层（依赖倒置）

## Android 实现（Kotlin）

```kotlin
// Domain层 - 定义接口
interface UserRepository {
    suspend fun getUser(id: String): User
}

// Domain层 - UseCase
class GetUserUseCase(private val repo: UserRepository) {
    suspend operator fun invoke(id: String) = repo.getUser(id)
}

// Data层 - 实现接口
class UserRepositoryImpl(
    private val api: UserApi,
    private val dao: UserDao
) : UserRepository {
    override suspend fun getUser(id: String): User {
        return dao.getUser(id) ?: api.fetchUser(id).also { dao.save(it) }
    }
}

// Presentation层 - ViewModel
class UserViewModel(private val getUser: GetUserUseCase) : ViewModel() {
    val user = liveData { emit(getUser("123")) }
}
```

## Flutter 实现（BLoC）

```dart
// Domain层
abstract class UserRepository {
  Future<User> getUser(String id);
}

class GetUserUseCase {
  final UserRepository repo;
  GetUserUseCase(this.repo);
  Future<User> call(String id) => repo.getUser(id);
}

// Data层
class UserRepositoryImpl implements UserRepository {
  final UserRemoteDataSource remote;
  final UserLocalDataSource local;

  Future<User> getUser(String id) async {
    try {
      final user = await remote.fetchUser(id);
      local.cacheUser(user);
      return user;
    } catch (_) {
      return local.getCachedUser(id);
    }
  }
}

// Presentation层 (BLoC)
class UserCubit extends Cubit<UserState> {
  final GetUserUseCase getUser;
  UserCubit(this.getUser) : super(UserInitial());

  Future<void> loadUser(String id) async {
    emit(UserLoading());
    try {
      emit(UserLoaded(await getUser(id)));
    } catch (e) {
      emit(UserError(e.toString()));
    }
  }
}
```

## 什么时候需要 Domain 层

一句话总结：当你的应用有**复杂的业务逻辑、多个数据源需要协调、需要在多个 ViewModel 间复用逻辑，或者需要对业务逻辑进行纯粹的单元测试时**，就应该考虑引入 Domain 层。

| 场景 | 需要 Domain 层？ |
|---|---|
| 简单 CRUD 应用（天气、记事本） | 不需要，ViewModel 直接调 Repository |
| 多数据源协调（先缓存再网络） | 需要，用例封装协调逻辑 |
| 多个页面用同一业务逻辑 | 需要，用例可复用 |
| 业务规则复杂（校验、计算、状态机） | 需要，用例隔离纯逻辑 |
| 需要对业务逻辑做单元测试 | 需要，用例可独立测试 |

## Domain 层 vs Data 层：边界在哪

| | Domain | Data |
|---|---|---|
| 职责 | 定"要什么" | 定"怎么拿" |
| 内容 | 抽象接口 + 用例 | 具体实现 + 数据源 |
| 依赖 | 无第三方库 | dio/hive/retrofit |
| 对应 | `interface ApiService` | `class ApiServiceImpl` |

**一句话区分**：
- Domain：`"我需要一个能获取用户的能力"` → 定接口
- Data：`"我是用 dio + hive 来实现的"` → 写实现

**验证标准**：把 Data 层整个换掉（比如从 dio 换成 http 包），Domain 层代码一行不用改。

## 包结构对比

| 层 | Android (Kotlin) | Flutter |
|---|---|---|
| UI | `presentation/` | `presentation/` |
| 领域 | `domain/` | `domain/` |
| 数据 | `data/` | `data/` |
| 依赖注入 | Hilt/Koin | get_it + injectable |

## 面试常问

1. **为什么要分层？** — 解耦、可测试（Mock 接口）、职责清晰
2. **Domain 层为什么没有 Android/Flutter 依赖？** — 纯业务逻辑可跨平台复用
3. **ViewModel vs BLoC？** — 都是状态管理，BLoC 用 Stream 驱动，ViewModel 用 LiveData/Flow
