# ViewModel 原理详解

## 一、是什么

ViewModel 是 Jetpack 组件，用于**存储和管理 UI 相关数据**。核心能力：**Activity/Fragment 配置变更（如旋转屏幕）重建时，ViewModel 不会被销毁，数据得以保留。**

## 二、解决什么问题

```
没有 ViewModel：
  屏幕旋转 → Activity 销毁重建 → 数据丢了 → 重新请求网络

  onSaveInstanceState 能存，但只适合少量简单数据（Bundle 有大小限制）
  大列表、Bitmap、网络请求中的状态都存不了

有 ViewModel：
  屏幕旋转 → Activity 销毁重建 → ViewModel 还活着 → 数据还在
```

## 三、基本使用

```kotlin
class UserViewModel : ViewModel() {
    private val _users = MutableLiveData<List<User>>()
    val users: LiveData<List<User>> = _users

    fun loadUsers() {
        viewModelScope.launch {
            _users.value = repository.getUsers()
        }
    }

    // Activity 真正销毁时调用，用于清理资源
    override fun onCleared() {
        super.onCleared()
        // 取消网络请求、关闭数据库连接等
    }
}

// Activity 中
class UserActivity : AppCompatActivity() {
    private val viewModel: UserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.users.observe(this) { users ->
            // 更新 UI
        }
        viewModel.loadUsers()
    }
}
```

## 四、生命周期对比

```
Activity 生命周期            ViewModel 生命周期
    │                              │
 onCreate() ──────────────────  创建（如果不存在）
    │                              │
 onStart()                         │
    │                              │
 onResume()                        │
    │                              │
 ── 屏幕旋转 ──                     │ ← ViewModel 还活着！
    │                              │
 onPause()                         │
 onStop()                          │
 onDestroy() ← 配置变更销毁         │
    │                              │
 onCreate() ← 重新创建              │ ← 拿到同一个 ViewModel
    │                              │
 onStart()                         │
    │                              │
 onResume()                        │
    │                              │
 ── 用户按返回键 ──                  │
    │                              │
 onPause()                         │
 onStop()                          │
 onDestroy() ← 真正销毁             │
    │                           onCleared() ← 此时才清理
    ▼                              ▼
```

## 五、核心原理：ViewModel 存在哪？

**ViewModel 存储在 Activity 的 `ViewModelStore` 中，而 ViewModelStore 在配置变更时不会被销毁。**

```
┌─────────────────────────────────────────┐
│  ComponentActivity                       │
│                                         │
│  ┌─────────────────────────────────┐    │
│  │  ViewModelStore                  │    │
│  │  (本质是一个 HashMap)             │    │
│  │                                 │    │
│  │  key="UserViewModel" → UserViewModel  │
│  │  key="CartViewModel" → CartViewModel  │
│  │                                 │    │
│  └─────────────────────────────────┘    │
│                                         │
└─────────────────────────────────────────┘
```

```java
public class ViewModelStore {
    // 就是一个 HashMap
    private final HashMap<String, ViewModel> mMap = new HashMap<>();

    final void put(String key, ViewModel viewModel) {
        ViewModel oldViewModel = mMap.put(key, viewModel);
        if (oldViewModel != null) {
            oldViewModel.onCleared();
        }
    }

    final ViewModel get(String key) {
        return mMap.get(key);
    }

    // Activity 真正销毁时调用
    public final void clear() {
        for (ViewModel vm : mMap.values()) {
            vm.clear();  // 触发 onCleared()
        }
        mMap.clear();
    }
}
```

## 六、配置变更时 ViewModelStore 怎么保留？

**通过 `NonConfigurationInstances` 机制：**

```
屏幕旋转时：

1. Activity 即将销毁
   │
   ▼
ActivityThread.performDestroyActivity()
   │
   ▼
Activity.retainNonConfigurationInstances()
   │
   ├── 把 ViewModelStore 保存到 NonConfigurationInstances 对象中
   │   （这个对象由 ActivityThread 持有，不随 Activity 销毁）
   │
   ▼
Activity.onDestroy() → Activity 对象被销毁

2. Activity 重新创建
   │
   ▼
ActivityThread.performLaunchActivity()
   │
   ▼
Activity.attach(... lastNonConfigurationInstances ...)
   │
   ├── 把之前保存的 NonConfigurationInstances 传回给新 Activity
   │
   ▼
新 Activity 调用 getViewModelStore()
   │
   ├── 发现 NonConfigurationInstances 里有 ViewModelStore
   └── 直接复用，不创建新的 → ViewModel 还在！
```

```java
// ComponentActivity 源码简化
public ViewModelStore getViewModelStore() {
    if (mViewModelStore == null) {
        // 尝试从上一次配置变更中恢复
        NonConfigurationInstances nc = getLastNonConfigurationInstance();
        if (nc != null) {
            mViewModelStore = nc.viewModelStore;  // 恢复！
        }
        if (mViewModelStore == null) {
            mViewModelStore = new ViewModelStore();  // 第一次创建
        }
    }
    return mViewModelStore;
}
```

## 七、ViewModel 的创建流程

```
val viewModel: UserViewModel by viewModels()
    │
    ▼ 展开等价于
ViewModelProvider(this).get(UserViewModel::class.java)
```

```
ViewModelProvider.get(UserViewModel::class.java)
    │
    ▼
1. 从 ViewModelStore 中找 key="UserViewModel"
    │
    ├── 找到了 → 直接返回（配置变更后复用）
    │
    └── 没找到 → 通过 Factory 创建
                    │
                    ▼
              Factory.create(UserViewModel::class.java)
                    │
                    ├── 默认 Factory：反射调用无参构造函数
                    │   UserViewModel::class.java.newInstance()
                    │
                    └── 带参数的 Factory：自定义创建逻辑
                    │
                    ▼
              存入 ViewModelStore
              viewModelStore.put("UserViewModel", viewModel)
                    │
                    ▼
              返回 ViewModel
```

## 八、ViewModel 什么时候被清理

```java
// ComponentActivity 构造函数中
getLifecycle().addObserver(new LifecycleEventObserver() {
    @Override
    public void onStateChanged(LifecycleOwner source, Lifecycle.Event event) {
        if (event == Lifecycle.Event.ON_DESTROY) {
            // 关键判断：是配置变更还是真正销毁
            if (!isChangingConfigurations()) {
                // 真正销毁 → 清理所有 ViewModel
                getViewModelStore().clear();
            }
            // 配置变更 → 不清理，ViewModelStore 通过 NCI 保留
        }
    }
});
```

```
ON_DESTROY 触发时：
    │
    ├── isChangingConfigurations() == true（旋转屏幕）
    │   → 不清理 ViewModelStore
    │   → ViewModelStore 存入 NonConfigurationInstances
    │   → ViewModel 继续存活
    │
    └── isChangingConfigurations() == false（按返回键/finish）
        → getViewModelStore().clear()
        → 每个 ViewModel 的 onCleared() 被调用
        → ViewModel 被回收
```

## 九、Fragment 的 ViewModel

```kotlin
// Fragment 自己的 ViewModel（Fragment 重建时保留）
val viewModel: ItemViewModel by viewModels()

// 共享 Activity 级别的 ViewModel（多个 Fragment 共享数据）
val sharedViewModel: SharedViewModel by activityViewModels()
```

```
Activity
├── ViewModelStore
│   └── SharedViewModel  ← activityViewModels() 获取
│
├── FragmentA
│   └── ViewModelStore
│       └── ItemViewModelA  ← viewModels() 获取
│
└── FragmentB
    └── ViewModelStore
        └── ItemViewModelB  ← viewModels() 获取
```

Fragment 间通信：两个 Fragment 通过 `activityViewModels()` 拿到同一个 ViewModel 实例，用 LiveData 通信，不需要直接引用对方。

---

## 十、面试高频问题

### Q1: ViewModel 为什么配置变更后还能存活？

- Activity 销毁前，ViewModelStore 被保存到 `NonConfigurationInstances` 中
- `NonConfigurationInstances` 由 `ActivityThread` 持有，不随 Activity 销毁
- 新 Activity 创建时通过 `getLastNonConfigurationInstance()` 恢复 ViewModelStore

### Q2: ViewModel 会内存泄漏吗？

- ViewModel 的生命周期比 Activity 长，**绝对不能持有 Activity/View/Context 的引用**
- 如果需要 Context，使用 `AndroidViewModel`（持有 Application Context）
- 持有 Activity 引用 → 旋转屏幕后旧 Activity 无法被 GC → 泄漏

### Q3: ViewModel 和 onSaveInstanceState 的区别？

| | ViewModel | onSaveInstanceState |
|--|-----------|-------------------|
| 存储位置 | 内存（ViewModelStore） | Bundle（序列化到磁盘） |
| 数据大小 | 不限 | 有限（Bundle < 1MB） |
| 数据类型 | 任意对象 | 只能是 Parcelable/Serializable |
| 进程被杀 | **丢失** | **保留** |
| 配置变更 | 保留 | 保留 |
| 适用场景 | 大数据、复杂对象、网络请求状态 | 少量关键 UI 状态（滚动位置、输入内容） |

### Q4: 为什么不能在 ViewModel 构造函数里传 Activity？

```kotlin
// ❌ 错误：持有 Activity 引用
class MyViewModel(private val activity: Activity) : ViewModel()

// ✅ 正确：使用 AndroidViewModel
class MyViewModel(application: Application) : AndroidViewModel(application)

// ✅ 正确：通过 SavedStateHandle 获取 Intent 参数
class MyViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    val userId = savedStateHandle.get<String>("user_id")
}
```

### Q5: ViewModelProvider 的 Factory 有什么用？

- 默认 Factory 只能创建无参构造的 ViewModel
- 需要传参数时必须自定义 Factory：

```kotlin
class UserViewModelFactory(private val repo: UserRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return UserViewModel(repo) as T
    }
}

// 使用
val viewModel = ViewModelProvider(this, UserViewModelFactory(repo))
    .get(UserViewModel::class.java)
```
