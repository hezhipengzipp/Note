# StatusBar 模块深度分析文档

## 一、模块概述

StatusBar 是 SystemUI 最核心的模块，负责：
- 状态栏显示（时钟、图标、电池等）
- 通知栏下拉面板
- 通知管理和显示
- 锁屏状态栏
- 系统图标管理

---

## 二、目录结构

```
statusbar/
├── 核心层（主目录）
│   ├── CommandQueue.java                    # 核心消息队列 ⭐
│   ├── StatusBarStateControllerImpl.java    # 状态管理 ⭐
│   ├── NotificationListener.java            # 通知监听器
│   └── StatusBarState.java                  # 状态定义
│
├── phone/                                   # 手机设备实现 ⭐
│   ├── CentralSurfaces.java                 # 核心接口
│   ├── CentralSurfacesImpl.java             # 核心实现 ⭐
│   ├── PhoneStatusBarView.java              # 状态栏视图
│   ├── PhoneStatusBarViewController.kt      # 视图控制器
│   ├── StatusBarIconControllerImpl.java     # 图标管理
│   ├── NotificationIconAreaController.java  # 通知图标区
│   ├── BarTransitions.java                  # 颜色过渡
│   ├── HeadsUpTouchHelper.java              # 悬浮通知触摸
│   ├── StatusBarTouchableRegionManager.java # 触摸区域管理
│   ├── LightBarController.java              # 亮/暗图标
│   ├── ScrimController.java                 # 遮罩控制
│   └── dagger/                              # 依赖注入
│
├── notification/                            # 通知管理 ⭐
│   ├── collection/                          # 通知集合
│   │   ├── NotifCollection.java
│   │   └── NotificationEntry.java
│   ├── row/                                 # 单条通知
│   │   ├── ExpandableNotificationRow.java
│   │   ├── NotificationContentView.java
│   │   ├── NotificationGuts.java
│   │   └── NotificationMenuRow.java
│   ├── stack/                               # 通知栈
│   │   ├── NotificationStackScrollLayout.java ⭐
│   │   ├── NotificationSwipeHelper.java
│   │   ├── StackScrollAlgorithm.java
│   │   └── StackStateAnimator.java
│   ├── shelf/                               # 通知架
│   └── icon/                                # 通知图标
│
├── policy/                                  # 系统策略
│   ├── connectivity/                        # 连接状态图标
│   ├── KeyguardStateController.java
│   └── Clock.java                           # 时钟
│
├── window/                                  # 窗口管理
│   ├── StatusBarWindowController.java
│   └── NotificationShadeWindowViewController.java
│
└── gesture/                                 # 手势处理
    └── GestureRecorder.java
```

---

## 三、分层架构

```
┌──────────────────────────────────────────────────────┐
│              User Interface Layer                     │
│  ┌─────────────────────────────────────────────────┐ │
│  │ PhoneStatusBarView / NotificationPanelView      │ │
│  │ ExpandableNotificationRow / NSSL                │ │
│  └─────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
                    ↕ (View hierarchy)
┌──────────────────────────────────────────────────────┐
│              Controller Layer                        │
│  ┌─────────────────────────────────────────────────┐ │
│  │ CentralSurfacesImpl / PhoneStatusBarVC          │ │
│  │ NotificationPanelVC / NSSLC                     │ │
│  │ StatusBarIconControllerImpl                     │ │
│  └─────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
                    ↕ (Callbacks)
┌──────────────────────────────────────────────────────┐
│              Business Logic Layer                    │
│  ┌─────────────────────────────────────────────────┐ │
│  │ CommandQueue / StatusBarStateController         │ │
│  │ NotificationListener / NotifCollection          │ │
│  │ BarTransitions / ScrimController                │ │
│  └─────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
                    ↕ (Binder IPC)
┌──────────────────────────────────────────────────────┐
│              System Services Layer                   │
│  ┌─────────────────────────────────────────────────┐ │
│  │ StatusBarManagerService (SystemServer)          │ │
│  │ NotificationManagerService                      │ │
│  │ WindowManager / KeyguardManager                 │ │
│  └─────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
```

---

## 四、核心类详解

### 4.1 CommandQueue（消息队列）⭐

**文件**: `CommandQueue.java`

**职责**:
- 实现 `IStatusBar.Stub`，接收来自 SystemServer 的 Binder 回调
- 将回调从 Binder 线程转换到主线程
- 支持消息合并，避免消息堆积
- 定义 `Callbacks` 接口供 CentralSurfaces 实现

**关键消息类型**:
```java
MSG_ICON                    // 图标设置/移除
MSG_DISABLE                 // 禁用标志
MSG_EXPAND_NOTIFICATIONS    // 展开通知
MSG_COLLAPSE_PANELS         // 折叠面板
MSG_BIOMETRIC_SHOW          // 生物识别显示
MSG_SHOW_TRANSIENT          // 瞬时条显示
```

**通信流程**:
```
StatusBarManagerService (SystemServer)
    ↓ Binder IPC
CommandQueue.setIcon() [IStatusBar.Stub]
    ↓ Handler.sendMessage()
主线程处理
    ↓
mCallbacks.setIcon() [通知所有监听者]
```

---

### 4.2 CentralSurfacesImpl（核心控制器）⭐

**文件**: `phone/CentralSurfacesImpl.java`

**职责**:
- 管理整个状态栏和通知面板的生命周期
- 协调各个子模块（icons, gestures, shade, keyguard）
- 处理状态转换（SHADE/KEYGUARD/SHADE_LOCKED）
- 实现 CommandQueue.Callbacks

**核心方法**:
```java
public void start();                              // 初始化
public void makeExpandedVisible(boolean expand);   // 展开面板
public void animateCollapsePanels(int flags);      // 折叠面板
public void updateMediaMetaData(...);              // 更新媒体
public void onSystemBarAttributesChanged(...);     // 系统栏属性变化
```

**状态定义**:
```java
StatusBarState.SHADE         // 普通模式（未锁定）
StatusBarState.KEYGUARD      // 锁屏模式
StatusBarState.SHADE_LOCKED  // 锁屏下拉模式
```

---

### 4.3 StatusBarStateControllerImpl（状态管理）

**文件**: `StatusBarStateControllerImpl.java`

**职责**:
- 跟踪和报告 StatusBar 状态
- 管理状态转换与监听器通知
- 处理 Doze（息屏显示）状态

**状态监听接口**:
```java
interface StateListener {
    void onStateChanged(int newState);      // 状态变化
    void onDozingChanged(boolean isDozing); // 息屏状态
    void onExpandedChanged(boolean isExpanded); // 展开状态
}
```

---

### 4.4 PhoneStatusBarView & PhoneStatusBarViewController

**文件**: `phone/PhoneStatusBarView.java` + `PhoneStatusBarViewController.kt`

**PhoneStatusBarView 结构**:
```
┌─────────────────────────────────────────────────────┐
│                PhoneStatusBarView                    │
├─────────────────────────────────────────────────────┤
│ ┌────────────────┐  ┌──────────┐  ┌──────────────┐ │
│ │  Start Side    │  │  Cutout  │  │   End Side   │ │
│ │ ─────────────  │  │  Space   │  │ ──────────── │ │
│ │ Clock          │  │ (刘海)    │  │ System Icons │ │
│ │ Operator       │  │          │  │ Battery      │ │
│ │ Notif Icons    │  │          │  │ User Chip    │ │
│ └────────────────┘  └──────────┘  └──────────────┘ │
└─────────────────────────────────────────────────────┘
```

---

### 4.5 NotificationStackScrollLayout（通知栈）⭐

**文件**: `notification/stack/NotificationStackScrollLayout.java`

**职责**:
- 管理可滚动的通知列表视图
- 处理通知的展开/折叠动画
- 实现下拉面板的滚动逻辑
- 管理通知的优先级分组
- 处理手势和触摸事件

**特点**:
- 动态布局，根据通知数量调整高度
- 支持 overscroll（过度滚动）
- 橡皮筋效果
- 与 ShadeExpansionStateManager 协调

---

### 4.6 ExpandableNotificationRow（单条通知）

**文件**: `notification/row/ExpandableNotificationRow.java`

**职责**:
- 代表单个通知在列表中的视图
- 管理通知的展开/折叠状态
- 处理通知菜单（删除、优先级等）
- 支持 RemoteInput（直接回复）
- 处理滑动删除

**组成**:
```
ExpandableNotificationRow
├── NotificationContentView (通知内容)
├── NotificationGuts (通知菜单)
└── NotificationMenuRow (滑动菜单)
```

---

### 4.7 StatusBarIconControllerImpl（图标管理）

**文件**: `phone/StatusBarIconControllerImpl.java`

**职责**:
- 接收 CommandQueue 的图标更新回调
- 维护所有图标的状态
- 分发图标更新给各个 IconManager
- 支持 Demo Mode

**图标流程**:
```
CommandQueue
    → StatusBarIconControllerImpl
    → IconManager
    → PhoneStatusBarView
```

---

### 4.8 NotificationListener（通知监听）

**文件**: `NotificationListener.java`

**职责**:
- 继承 NotificationListenerService
- 监听系统通知变化
- 分发通知事件给 NotifCollection

**回调方法**:
```java
onNotificationPosted()      // 新通知
onNotificationRemoved()     // 通知移除
onNotificationRankingUpdate() // 排序更新
onListenerConnected()       // 监听器连接
```

---

## 五、启动流程

```
SystemUIApplication.onCreate()
    ↓
Dagger DI 容器初始化
    ↓
CentralSurfacesImpl 被注入
    ↓
CentralSurfacesImpl.start()
    ├── registerStatusBar() → 向 SystemServer 注册
    │
    ├── setupStatusBarWindows()
    │   ├── 创建 NotificationShadeWindowView
    │   ├── 添加到 WindowManager
    │   └── 初始化 NotificationShadeWindowViewController
    │
    ├── setUpNotificationScrim()
    │   └── 初始化 ScrimController
    │
    ├── setUpShadeController()
    │   └── 初始化 ShadeController
    │
    ├── setupCommandQueue()
    │   └── CommandQueue.addCallback()
    │
    ├── startNotificationHandling()
    │   └── NotificationListener.start()
    │
    ├── setupStatusBarIconManagers()
    │   └── StatusBarIconControllerImpl.addIconGroup()
    │
    └── setupBiometric()
```

---

## 六、状态栏组成部分

### 6.1 布局结构 (status_bar.xml)

```xml
PhoneStatusBarView
├── ImageView (lights_out indicator)
└── LinearLayout (status_bar_contents)
    ├── FrameLayout (start_side_container)
    │   └── FrameLayout (start_side_content)
    │       ├── HeadsUpStatusBarLayout
    │       └── LinearLayout (start_side_except_heads_up)
    │           ├── ViewStub (operator_name)
    │           ├── Clock (时钟)
    │           ├── OngoingCallChip (通话芯片)
    │           └── NotificationIconArea (通知图标)
    │
    ├── Space (cutout_space_view) [刘海屏适配]
    │
    └── FrameLayout (end_side_container)
        └── LinearLayout (end_side_content)
            ├── StatusBarUserChipContainer (用户芯片)
            └── SystemIcons (系统图标)
                ├── SignalStrength (信号)
                ├── WiFiSignal (WiFi)
                ├── BluetoothIcon (蓝牙)
                ├── VpnIcon (VPN)
                ├── RotationLockIcon (旋转锁)
                └── BatteryIcon (电池)
```

### 6.2 主要组件

| 组件 | 实现类 | 功能 |
|------|--------|------|
| 时钟 | `policy/Clock.java` | 显示时间，支持12/24小时 |
| 通知图标区 | `NotificationIconAreaController` | 显示当前通知图标 |
| 系统图标 | `StatusBarIconControllerImpl` | 信号、WiFi、电池等 |
| 用户芯片 | `StatusBarUserChipContainer` | 用户头像，快速切换 |
| 通话芯片 | `OngoingCallChip` | 显示正在进行的通话 |

---

## 七、通知栏下拉面板

### 7.1 面板结构 (status_bar_expanded.xml)

```xml
NotificationPanelView (通知面板容器)
├── NotificationsQuickSettingsContainer
│   ├── KeyguardStatusView (锁屏状态)
│   ├── QsFrame (快速设置)
│   ├── NotificationStackScrollLayout (通知列表) ⭐
│   │   ├── SectionHeaderView (分组头)
│   │   ├── ExpandableNotificationRow[] (通知行)
│   │   └── FooterView (底部)
│   ├── KeyguardStatusBar
│   └── PhotoPreviewOverlay
├── KeyguardBottomArea (锁屏底部)
└── LockIconView (锁定图标)
```

### 7.2 面板状态转换

```
┌─────────────┐
│  collapsed  │  完全折叠，只显示 status_bar
└──────┬──────┘
       ↓
┌─────────────┐
│   expand    │  展开，显示通知列表
└──────┬──────┘
       ↓
┌─────────────┐
│  qs_expand  │  展开快速设置
└──────┬──────┘
       ↓
┌─────────────┐
│ shade_lock  │  从锁屏打开 shade
└─────────────┘
```

### 7.3 下拉交互流程

```
用户从顶部向下滑动
    ↓
PhoneStatusBarView.onTouchEvent()
    ↓
StatusBarTouchableRegionManager.handleTouchEvent()
    ↓
ShadeExpansionListener.onExpansionChanged()
    ↓
NotificationPanelViewController.onTouchEvent()
    ↓
NotificationStackScrollLayout.onTouchEvent()
    ↓
根据手势类型:
├── 滑动展开 → animateExpandNotificationsPanel()
├── 快速滑动 → fling 动画
├── 点击通知 → startNotificationClickAnimation()
└── 悬浮通知 → HeadsUpTouchHelper
```

---

## 八、通知管理流程

### 8.1 通知添加流程

```
应用发送 Notification
    ↓
NotificationManagerService
    ↓
NotificationListener.onNotificationPosted()
    ↓
NotifCollection 处理
    ├── 去重
    ├── 过滤 (隐私、权限)
    └── 分组 (子通知)
    ↓
RenderStageManager
    ├── Entrying (入库)
    ├── Binding (绑定)
    ├── Grouping (分组)
    ├── Sorting (排序)
    └── Filtering (过滤)
    ↓
NotificationStackScrollLayoutController
    └── updateNotifications()
        ├── 添加/移除通知行
        ├── 更新通知顺序
        └── 触发动画
```

### 8.2 通知点击流程

```
用户点击通知
    ↓
ExpandableNotificationRow.onClick()
    ↓
NotificationClickNotifier.onNotificationClick()
    ├── 关闭 face unlock
    └── 调用 NotificationActivityStarter
    ↓
NotificationActivityStarter
    ├── 关闭 HUD
    ├── 折叠面板
    ├── 启动 Activity
    └── ActivityLaunchAnimator 动画
```

### 8.3 通知删除流程

```
用户滑动通知
    ↓
NotificationSwipeHelper.onTouch()
    ├── 追踪滑动距离
    └── 超过阈值触发删除
    ↓
NotificationMenuRow.onDismiss()
    ↓
NotificationListenerWithPlugins.removeNotification()
    ├── 触发动画
    ├── 从 NotifCollection 移除
    └── NotificationManager.cancelNotification()
```

---

## 九、系统服务交互

### 9.1 主要交互服务

```
CentralSurfacesImpl
    │
    ├── StatusBarManagerService
    │   ├── setIcon() - 设置图标
    │   ├── disable() - 禁用功能
    │   └── registerStatusBar() - 注册
    │
    ├── NotificationManagerService
    │   ├── onNotificationPosted() - 新通知
    │   ├── onNotificationRemoved() - 移除通知
    │   └── cancelNotification() - 取消通知
    │
    ├── WindowManager
    │   ├── addView() - 添加窗口
    │   └── updateViewLayout() - 更新布局
    │
    ├── KeyguardManager
    │   ├── isLocked() - 是否锁定
    │   └── dismiss() - 解锁
    │
    ├── AudioManager
    │   └── 铃声模式
    │
    └── PowerManager
        ├── wakeUp() - 唤醒
        └── goToSleep() - 休眠
```

### 9.2 Binder IPC 通信

```
SystemServer 侧:
  IStatusBarService (StatusBarManagerService)

SystemUI 侧:
  CommandQueue (实现 IStatusBar.Stub)

通信流程:
1. 注册阶段:
   CentralSurfacesImpl.registerStatusBar()
   → mStatusBarService.registerStatusBar(mCommandQueue)

2. 命令下发:
   StatusBarManagerService.setIcon(slot, icon)
   → CommandQueue.setIcon() [Binder 回调]
   → Handler.sendMessage(MSG_ICON)
   → mCallbacks.setIcon()
```

---

## 十、关键接口

### 10.1 CommandQueue.Callbacks

```java
interface Callbacks {
    // 图标管理
    void setIcon(String slot, StatusBarIcon icon);
    void removeIcon(String slot);

    // 禁用标志
    void disable(int displayId, int state1, int state2, boolean animate);

    // 面板操作
    void animateExpandNotificationsPanel();
    void animateCollapsePanels(int flags, boolean force);
    void togglePanel();

    // 窗口状态
    void setWindowState(int displayId, int window, int state);

    // 生物识别
    void showAuthenticationDialog(PromptInfo promptInfo, ...);
    void onBiometricAuthenticated(int modality);
    void onBiometricError(int modality, int error, int vendorCode);

    // 系统 UI
    void showGlobalActionsMenu();
    void showShutdownUi(boolean isReboot, String reason);

    // 其他
    void onSystemBarAttributesChanged(int displayId, int appearance, ...);
}
```

### 10.2 StatusBarStateController.StateListener

```java
interface StateListener {
    void onStateChanged(int newState);           // SHADE/KEYGUARD/SHADE_LOCKED
    void onDozingChanged(boolean isDozing);      // 息屏显示
    void onExpandedChanged(boolean isExpanded);  // 面板展开
}
```

### 10.3 ShadeExpansionListener

```java
interface ShadeExpansionListener {
    void onPanelExpansionChanged(
        float fraction,      // 0-1 展开比例
        boolean expanded,
        float velocity);

    void onPanelStateChanged(
        @PanelState int state,  // CLOSED/OPENING/OPEN/CLOSING
        boolean expanded,
        float velocity);
}
```

---

## 十一、事件处理

### 11.1 触摸事件处理

```
PhoneStatusBarView.onTouchEvent()
    ↓
Gefingerpoken mTouchEventHandler
    ├── 通知面板滑动
    ├── 通知点击
    └── 双击、长按
    ↓
StatusBarTouchableRegionManager
    └── 定义可触摸区域
    ↓
NotificationStackScrollLayout
    ├── 通知列表滚动
    ├── 通知滑动删除
    └── NotificationSwipeHelper
    ↓
HeadsUpTouchHelper
    ├── 点击 → 打开通知
    ├── 向下滑 → 展开面板
    └── 向上滑 → 关闭悬浮
```

### 11.2 手势类型

| 手势 | 处理类 | 效果 |
|------|--------|------|
| 下拉 | ShadeExpansionStateManager | 展开通知面板 |
| 上滑 | ShadeExpansionStateManager | 折叠面板 |
| 左右滑 | NotificationSwipeHelper | 删除/存档通知 |
| 双击 | StatusBarTouchableRegionManager | 唤醒屏幕 |
| 长按 | NotificationGuts | 打开通知菜单 |

### 11.3 面板展开动画

```
animateExpandNotificationsPanel()
    ↓
ShadeExpansionStateManager.expand()
    ├── 设置 target fraction = 1.0
    ├── 启动 ValueAnimator
    └── 触发 onPanelExpansionChanged()
    ↓
onPanelExpansionChanged(fraction, expanded, velocity)
    ├── NotificationPanelViewController
    │   └── 更新 panel 位置和透明度
    ├── PhoneStatusBarTransitions
    │   └── 更新 status bar 颜色
    ├── ScrimController
    │   └── 更新 background scrim
    └── NotificationStackScrollLayout
        └── 调整通知列表 layout
```

---

## 十二、布局文件列表

| 文件 | 用途 |
|------|------|
| `status_bar.xml` | 状态栏主布局 |
| `status_bar_expanded.xml` | 展开面板主布局 |
| `notification_stack_scroll_layout.xml` | 通知列表 |
| `notification_*row*.xml` | 单条通知视图 |
| `system_icons.xml` | 系统图标容器 |
| `heads_up_status_bar_layout.xml` | 悬浮通知 |
| `keyguard_status_bar.xml` | 锁屏状态栏 |

---

## 十三、关键维度 (dimens.xml)

```xml
<!-- 状态栏高度 -->
status_bar_height: 24dp (正常) / 32dp (刘海)

<!-- 图标尺寸 -->
status_bar_icon_size: 17dp
notification_icon_size: 20dp

<!-- 内边距 -->
status_bar_padding_start: 4dp
status_bar_padding_end: 4dp

<!-- 通知行 -->
notification_row_height: 127dp
notification_row_max_height: 294dp
```

---

## 十四、阅读顺序建议

```
1. CommandQueue.java              # 消息队列，理解通信机制
       ↓
2. CentralSurfacesImpl.java       # 核心控制器 (重点 ⭐)
       ↓
3. StatusBarStateControllerImpl.java  # 状态管理
       ↓
4. PhoneStatusBarView.java        # 状态栏视图
       ↓
5. status_bar.xml                 # 状态栏布局
       ↓
6. NotificationStackScrollLayout.java  # 通知列表 (重点 ⭐)
       ↓
7. ExpandableNotificationRow.java # 单条通知
       ↓
8. NotificationListener.java      # 通知监听
       ↓
9. StatusBarIconControllerImpl.java # 图标管理
```

---

## 十五、常见修改场景

| 需求 | 修改位置 |
|------|---------|
| 修改状态栏高度 | `dimens.xml` - `status_bar_height` |
| 修改状态栏布局 | `status_bar.xml` + `PhoneStatusBarView` |
| 添加系统图标 | `StatusBarIconControllerImpl` + `system_icons.xml` |
| 修改时钟样式 | `policy/Clock.java` |
| 修改通知行样式 | `ExpandableNotificationRow` + 布局文件 |
| 修改下拉动画 | `ShadeExpansionStateManager` |
| 添加状态栏按钮 | `status_bar.xml` + 对应 Controller |
| 修改通知滑动行为 | `NotificationSwipeHelper` |

---

## 十六、调试方法

### Dump 信息

```bash
# 状态栏状态
adb shell dumpsys systemui StatusBar

# 通知信息
adb shell dumpsys notification

# 窗口信息
adb shell dumpsys window windows | grep StatusBar
```

### 日志标签

```java
TAG = "CentralSurfaces"
TAG = "CommandQueue"
TAG = "StatusBarStateController"
TAG = "NotificationStackScrollLayout"
TAG = "ExpandableNotificationRow"
```

---

## 十七、设计模式应用

| 模式 | 应用 |
|------|------|
| **MVC** | Model(StatusBarState) / View(PhoneStatusBarView) / Controller(CentralSurfacesImpl) |
| **观察者** | CommandQueue.Callbacks, StateListener, ShadeExpansionListener |
| **依赖注入** | Dagger @SysUISingleton, CentralSurfacesModule |
| **策略** | NotificationSwipeActionHelper, BarTransitions |
| **工厂** | IconManager 工厂, NotificationPresenter 工厂 |

---

## 十八、关键文件速查表

| 文件 | 路径 | 功能 | 优先级 |
|------|------|------|--------|
| CommandQueue | `statusbar/CommandQueue.java` | 消息队列 | ⭐⭐⭐⭐⭐ |
| CentralSurfacesImpl | `statusbar/phone/CentralSurfacesImpl.java` | 核心控制器 | ⭐⭐⭐⭐⭐ |
| StatusBarStateControllerImpl | `statusbar/StatusBarStateControllerImpl.java` | 状态管理 | ⭐⭐⭐⭐ |
| PhoneStatusBarView | `statusbar/phone/PhoneStatusBarView.java` | 状态栏视图 | ⭐⭐⭐⭐ |
| NotificationStackScrollLayout | `notification/stack/NotificationStackScrollLayout.java` | 通知列表 | ⭐⭐⭐⭐⭐ |
| ExpandableNotificationRow | `notification/row/ExpandableNotificationRow.java` | 单条通知 | ⭐⭐⭐⭐ |
| StatusBarIconControllerImpl | `statusbar/phone/StatusBarIconControllerImpl.java` | 图标管理 | ⭐⭐⭐ |
| NotificationListener | `statusbar/NotificationListener.java` | 通知监听 | ⭐⭐⭐⭐ |
| ScrimController | `statusbar/phone/ScrimController.java` | 遮罩控制 | ⭐⭐⭐ |
| BarTransitions | `statusbar/phone/BarTransitions.java` | 颜色过渡 | ⭐⭐⭐ |
| status_bar.xml | `res/layout/status_bar.xml` | 状态栏布局 | ⭐⭐⭐⭐ |

---

## 十九、相关文档

- `project-guide.md` - 项目整体指南
- `volume-module.md` - Volume 模块分析
- `dagger.md` - Dagger 依赖注入
- `corestartable.md` - CoreStartable 模式
- `status-bar-data-pipeline.md` - 状态栏数据管道

---

*文档更新日期: 2026-01-27*
