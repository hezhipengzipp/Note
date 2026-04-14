# ViewRootImpl 与 WindowManager 详解

## 核心类关系图

```
Activity
  └── PhoneWindow（Window 的唯一实现）
        └── DecorView（View 树根节点，继承 FrameLayout）
              └── ContentView（R.id.content，用户 setContentView 的地方）

WindowManagerImpl（每个 Activity/Context 一个实例）
  └── 委托 → WindowManagerGlobal（进程单例）
                └── 持有 → ViewRootImpl（每个窗口一个）
                              └── IWindowSession（Binder）→ WMS（系统进程）
                                                              └── Surface → SurfaceFlinger
```

---

## 第一阶段：Activity 启动，Window 创建

```
ActivityThread.handleLaunchActivity()
      ↓
performLaunchActivity()
      ↓
activity.attach()
  └── new PhoneWindow(context)          // 创建窗口
  └── window.setWindowManager(...)      // 绑定 WindowManagerImpl
      └── new WindowManagerImpl(context)
      ↓
activity.onCreate()
  └── setContentView(R.layout.xxx)
        ↓
        PhoneWindow.setContentView()
          └── installDecor()            // 创建 DecorView
          └── inflate(layoutResID)      // 用户布局充气到 mContentParent
```

> 此时 View 树已建好，但没有 Surface，不可见。

---

## 第二阶段：onResume 后，Window 注册到 WMS

```
ActivityThread.handleResumeActivity()
      ↓
activity.onResume()
      ↓
wm.addView(decorView, params)           // wm = WindowManagerImpl
      ↓
WindowManagerGlobal.addView()
  ├── new ViewRootImpl(context, display) // 核心：创建 ViewRootImpl
  ├── mViews.add(decorView)
  ├── mRoots.add(root)
  └── root.setView(decorView, params)   // 关键调用
```

---

## 第三阶段：ViewRootImpl.setView() —— 连接 WMS

```kotlin
// ViewRootImpl.setView() 核心逻辑（简化）
fun setView(view: View, attrs: WindowManager.LayoutParams) {
    mView = view  // 持有 DecorView

    // 1. 触发首次 View 测量布局绘制
    requestLayout()

    // 2. 通过 Binder 向 WMS 注册窗口，申请 Surface
    mWindowSession.addToDisplay(
        mWindow,        // IWindow（本地 Binder 存根，WMS 回调用）
        attrs,
        mAttachInfo,
        ...
    )
    // WMS 返回后 Surface 就分配好了
}
```

```
ViewRootImpl ──Binder IPC──► WindowManagerService (system_server)
                                ├── 创建 WindowState（窗口描述）
                                ├── 分配 SurfaceControl
                                └── 回调通知 Surface 就绪
```

---

## 第四阶段：View 三大流程（measure / layout / draw）

```
requestLayout()
      ↓
Choreographer 等待下一个 Vsync 信号（16ms）
      ↓
performTraversals()
  ├── performMeasure()   → view.measure()   → onMeasure()
  ├── performLayout()    → view.layout()    → onLayout()
  └── performDraw()      → view.draw()      → onDraw()
            ↓
        Surface.lockCanvas()    // 获取 Canvas
            ↓
        绘制到 Surface 缓冲区
            ↓
        Surface.unlockCanvasAndPost()  // 提交给 SurfaceFlinger
```

---

## 第五阶段：SurfaceFlinger 合成显示

```
App 进程 Surface Buffer
系统 UI Surface Buffer    →  SurfaceFlinger  →  屏幕
状态栏 Surface Buffer
```

---

## 完整时序图

```
App 进程                          system_server
───────────────────────────────────────────────────────
Activity.attach()
  └─ new PhoneWindow
  └─ new WindowManagerImpl

Activity.onCreate()
  └─ setContentView() → 构建 View 树（内存中）

Activity.onResume()
  └─ WindowManagerGlobal.addView()
       └─ new ViewRootImpl
            └─ requestLayout() ──────────────────────►
            └─ mWindowSession.addToDisplay() ─Binder─► WMS.addWindow()
                                             ◄──────── 分配 Surface
            └─ Choreographer 等 Vsync
                 └─ performTraversals()
                      └─ measure/layout/draw
                           └─ Surface 提交 ──────────► SurfaceFlinger
                                                        └─ 合成 → 屏幕
```

---

## 三个核心类的职责总结

| 类 | 所在进程 | 核心职责 |
|---|---|---|
| `WindowManagerImpl` | App | WindowManager 接口实现，持有 Context，委托给 Global |
| `WindowManagerGlobal` | App（单例） | 管理进程内所有 ViewRootImpl / View / LayoutParams |
| `ViewRootImpl` | App | **枢纽**：衔接 View 树与 WMS，驱动三大流程，分发输入事件 |
| `WMS` | system_server | 管理所有 App 的窗口层级、焦点、动画 |
| `SurfaceFlinger` | 独立进程 | 合成所有 Surface，送显 |

**核心结论**：`ViewRootImpl` 是整个显示体系的枢纽——它向上管理 View 树的测量绘制，向下通过 Binder 与 WMS 通信申请 Surface，向外接收输入事件并分发给 View 树。
