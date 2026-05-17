# MVP 与 MVVM 对比

---

## 一、结构对比

```
MVP：
View ←── Presenter ──→ Model
  │         ↑
  └──接口回调─┘

MVVM：
View ──观察──→ ViewModel ──→ Model
  │              ↑
  └──数据绑定─────┘
```

## 二、代码对比

### MVP

```kotlin
// 1. 定义接口
interface UserView {
    fun showLoading()
    fun showUser(user: User)
    fun showError(msg: String)
}

// 2. Presenter 持有 View 接口
class UserPresenter(private val view: UserView) {
    fun loadUser(id: String) {
        view.showLoading()
        api.getUser(id, onSuccess = { user ->
            view.showUser(user)
        }, onError = { msg ->
            view.showError(msg)
        })
    }
}

// 3. Activity 实现接口
class UserActivity : AppCompatActivity(), UserView {
    private val presenter = UserPresenter(this)

    override fun showLoading() { progressBar.visible() }
    override fun showUser(user: User) { nameTv.text = user.name }
    override fun showError(msg: String) { toast(msg) }

    override fun onDestroy() {
        presenter.detachView()  // 必须手动解绑
        super.onDestroy()
    }
}
```

### MVVM

```kotlin
// 1. ViewModel 不持有 View
class UserViewModel : ViewModel() {
    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state = _state.asStateFlow()

    fun loadUser(id: String) {
        viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                val user = api.getUser(id)
                _state.value = UiState.Success(user)
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message)
            }
        }
    }
}

// 2. Activity 只观察数据
class UserActivity : AppCompatActivity() {
    private val viewModel: UserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        is UiState.Loading -> progressBar.visible()
                        is UiState.Success -> nameTv.text = state.user.name
                        is UiState.Error -> toast(state.msg)
                    }
                }
            }
        }
    }
}
```

## 三、核心区别

| | MVP | MVVM |
|---|---|---|
| 通信方式 | 接口回调（手动） | 数据绑定/观察者（自动） |
| Presenter/ViewModel 持有 View | 持有 View 接口引用 | 不持有 View 引用 |
| 数据流方向 | 双向（View↔Presenter） | 单向（ViewModel→View） |
| 内存泄漏风险 | 高（Presenter 持有 View） | 低（ViewModel 不持有 View） |
| 生命周期感知 | 需要手动管理 | 自动（ViewModel + LiveData/Flow） |
| 可测试性 | 需要 Mock View 接口 | 直接测试 ViewModel |
| 代码量 | 多（定义接口） | 少（数据绑定） |

## 四、MVVM 解决了 MVP 的什么问题

```
MVP 的痛点：
1. 接口爆炸 — 每个页面一个 View 接口，方法越来越多
2. 生命周期 — 手动在 onDestroy 解绑，忘了就泄漏
3. 状态恢复 — Activity 重建后 Presenter 重新创建，数据丢失

MVVM 解决：
1. 没有接口 — 数据驱动 UI
2. 不需要解绑 — ViewModel 不持有 View
3. 自动恢复 — ViewModel 在配置变更时存活
```

## 五、MVVM 的缺点

### 1. 数据绑定难以调试

```kotlin
// XML 里直接绑定，出了 bug 不知道在哪
<TextView android:text="@{viewModel.userName}" />
// 问题：userName 为空，是 ViewModel 没赋值？还是绑定表达式写错？
// 没有断点可以打
```

### 2. 状态管理复杂

```kotlin
// 页面状态多时，ViewModel 臃肿
class HomeViewModel : ViewModel() {
    val banner = MutableStateFlow<List<Banner>>(empty())
    val news = MutableStateFlow<List<News>>(empty())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val refreshing = MutableStateFlow(false)
    val page = MutableStateFlow(1)
    val hasMore = MutableStateFlow(true)
    // 状态爆炸...
}
```

### 3. 事件处理不直观

```kotlin
// UI 事件（Toast、跳转）用数据流表达很别扭
class LoginViewModel : ViewModel() {
    // ❌ StateFlow 有问题：页面重建会重新收到
    private val _toast = MutableStateFlow("")

    // ✅ 用 SharedFlow，但增加了复杂度
    private val _event = MutableSharedFlow<UiEvent>()
}
```

### 4. 学习曲线

```
MVP：接口回调，Android 开发者秒懂
MVVM：StateFlow / SharedFlow / LiveData / 作用域 / 结构化并发
      概念多，新手容易用错
```

### 5. 过度设计

```kotlin
// 简单页面也搞 MVVM → 杀鸡用牛刀
// 一个设置页面，就几个开关，还需要 ViewModel + StateFlow 吗？
class SettingsViewModel : ViewModel() {
    private val _darkMode = MutableStateFlow(false)
    val darkMode = _darkMode.asStateFlow()
    fun toggleDarkMode() { _darkMode.value = !_darkMode.value }
}
// 直接 SharedPreferences + setState 就行了
```

### 6. 输入场景绕

```kotlin
// 用户输入 → 通知 ViewModel → 更新 State → UI 订阅更新输入框
// 中间有延迟，输入框可能闪烁
// TextWatcher 触发 → 再次通知 ViewModel → 循环风险
```

## 六、怎么选

| 场景 | 推荐 |
|---|---|
| 简单页面（设置、关于） | MVC / 直接写 |
| 中等复杂度 | MVVM |
| 复杂业务 + 需要测试 | MVVM + Clean Architecture |
| 老项目维护 | 保持 MVP，不强改 |

**一句话**：MVP 是"推模式"（Presenter 推给 View），MVVM 是"拉模式"（View 从 ViewModel 拉数据）。MVVM 解耦好、可测试，但复杂页面状态管理难，简单页面用它反而是负担。
