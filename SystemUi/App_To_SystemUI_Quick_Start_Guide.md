# App 开发者快速上手 SystemUI 指南

> 从 Android 应用开发转向 SystemUI 系统开发的实用指南

---

## 一、思维转变

### 1.1 开发思维对比

```
┌─────────────────────────────────────────────────────────────┐
│                    App 开发 vs SystemUI                      │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   App 开发思维：                                             │
│   "我要实现一个功能给用户用"                                   │
│                                                             │
│   SystemUI 开发思维：                                        │
│   "我要响应系统事件，展示系统状态"                              │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 详细对比表

| 方面 | App 开发 | SystemUI |
|------|----------|----------|
| 启动方式 | 用户点击图标 | 开机自动启动，常驻后台 |
| 生命周期 | Activity 生命周期 | Service 长期运行 |
| UI 载体 | Activity/Fragment | Window 直接添加到 WindowManager |
| 数据来源 | 网络/数据库 | 系统服务 (SystemService) |
| 权限 | 普通权限 | 系统签名权限 |
| 调试 | 直接安装运行 | push 到 /system，重启 SystemUI |
| 依赖注入 | Hilt（简化版） | Dagger（原生） |
| 编译工具 | Gradle | Soong/Make |

---

## 二、SystemUI 整体架构

### 2.1 架构图

```
┌─────────────────────────────────────────────────────────────┐
│                        SystemUI                              │
│                                                             │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐           │
│  │ 状态栏   │ │ 通知栏   │ │ 快捷设置 │ │ 锁屏    │           │
│  │StatusBar│ │Notific- │ │  QS     │ │Keyguard│           │
│  │         │ │ation    │ │         │ │        │           │
│  └────┬────┘ └────┬────┘ └────┬────┘ └────┬───┘           │
│       │           │           │           │                │
│       └───────────┴─────┬─────┴───────────┘                │
│                         │                                   │
│                         ▼                                   │
│              ┌──────────────────┐                          │
│              │  SystemUIService │  ← 核心服务               │
│              └────────┬─────────┘                          │
│                       │                                     │
│                       ▼                                     │
│              ┌──────────────────┐                          │
│              │ SystemUIApplicat │  ← 入口                   │
│              └──────────────────┘                          │
└─────────────────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────┐
│                    系统服务层                                │
│  WindowManager / NotificationManager / StatusBarManager     │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 源码目录结构

```
frameworks/base/packages/SystemUI/
├── src/com/android/systemui/
│   ├── SystemUIApplication.java    # 应用入口
│   ├── SystemUIService.java        # 核心服务
│   │
│   ├── statusbar/                  # 状态栏 ⭐
│   │   ├── phone/
│   │   │   └── StatusBar.java
│   │   │   └── CentralSurfaces.java  # Android 13+
│   │   └── notification/           # 通知相关
│   │
│   ├── qs/                         # 快捷设置 ⭐
│   │   ├── QSPanel.java
│   │   ├── QSTileImpl.java
│   │   └── tiles/                  # 各种 Tile
│   │       ├── WifiTile.java
│   │       ├── BluetoothTile.java
│   │       └── ...
│   │
│   ├── keyguard/                   # 锁屏
│   ├── navigationbar/              # 导航栏
│   ├── volume/                     # 音量面板
│   ├── power/                      # 电源菜单
│   ├── recents/                    # 最近任务
│   ├── screenshot/                 # 截图
│   └── dagger/                     # 依赖注入
│
├── res/                            # 资源文件
└── AndroidManifest.xml
```

---

## 三、核心模块速览

| 模块 | 路径 | 功能 | 入门难度 |
|------|------|------|----------|
| **状态栏** | `statusbar/` | 顶部状态栏、图标、时间 | ⭐⭐⭐ |
| **快捷设置** | `qs/` | 下拉快捷开关 (WiFi/蓝牙等) | ⭐⭐ |
| **通知** | `statusbar/notification/` | 通知显示和管理 | ⭐⭐⭐⭐ |
| **锁屏** | `keyguard/` | 锁屏界面 | ⭐⭐⭐⭐ |
| **导航栏** | `navigationbar/` | 底部导航按钮 | ⭐⭐⭐ |
| **音量面板** | `volume/` | 音量调节 UI | ⭐⭐ |
| **电源菜单** | `globalactions/` | 长按电源键菜单 | ⭐⭐ |
| **最近任务** | `recents/` | 最近应用列表 | ⭐⭐⭐ |
| **截图** | `screenshot/` | 截图功能 | ⭐⭐ |

---

## 四、快速上手路径（4 周计划）

### 第 1 周：搭建环境 + 跑通流程

#### Day 1-2: 下载代码

```bash
# 初始化仓库
repo init -u https://android.googlesource.com/platform/manifest -b android-14.0.0_r1

# 同步 frameworks/base（包含 SystemUI）
repo sync -c -j8 frameworks/base
```

#### Day 3-4: 编译 SystemUI

```bash
# 初始化编译环境
source build/envsetup.sh

# 选择编译目标
lunch aosp_x86_64-eng

# 编译 SystemUI
make SystemUI -j8

# 或者单独编译
cd frameworks/base/packages/SystemUI
mm -j8
```

#### Day 5: 修改验证

```bash
# 修改状态栏时间颜色
# 编辑 res/values/colors.xml

# 推送到设备
adb root
adb remount
adb push out/.../SystemUI.apk /system/priv-app/SystemUI/

# 重启 SystemUI
adb shell killall com.android.systemui

# 查看效果
```

**本周目标**：能编译、能 push、能看到修改效果

---

### 第 2 周：理解启动流程

#### 阅读顺序

```
1. SystemUIApplication.java    # 入口，看 onCreate
       ↓
2. SystemUIService.java        # 启动各个组件
       ↓
3. Dependency.java             # 依赖管理（旧方式）
       ↓
4. dagger/                     # Dagger 注入（新方式）
```

#### 动手实践：加日志追踪

```java
// SystemUIApplication.java
@Override
public void onCreate() {
    Log.d("SystemUI_Debug", "=== SystemUIApplication onCreate ===");
    super.onCreate();
    // ...
}

// StatusBar.java
public void start() {
    Log.d("SystemUI_Debug", "=== StatusBar start ===");
    // ...
}
```

#### 启动流程图

```
系统启动
    ↓
Zygote 启动 SystemUI 进程
    ↓
SystemUIApplication.onCreate()
    ↓
SystemUIService.onCreate()
    ↓
启动各个组件：
├── StatusBar (状态栏)
├── NavigationBar (导航栏)
├── VolumeUI (音量)
├── PowerUI (电源)
├── RingtonePlayer (铃声)
└── ...
```

---

### 第 3 周：深入 QS Tile 模块（推荐入门模块）

#### 为什么从 QS Tile 开始？

- ✅ 代码量小，结构清晰
- ✅ 独立性强，改动风险低
- ✅ 能快速看到效果
- ✅ 涵盖 Dagger 注入、状态管理等核心概念

#### 实践：添加自定义 Tile

##### 1. 创建 Tile 类

```java
// MyCustomTile.java
package com.android.systemui.qs.tiles;

import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.plugins.qs.QSTile.BooleanState;

import javax.inject.Inject;

public class MyCustomTile extends QSTileImpl<BooleanState> {

    private boolean mEnabled = false;

    @Inject
    public MyCustomTile(QSHost host) {
        super(host);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        mEnabled = !mEnabled;
        refreshState();
        
        // 点击效果
        if (mEnabled) {
            Toast.makeText(mContext, "开启", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(mContext, "关闭", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = "我的开关";
        state.icon = ResourceIcon.get(R.drawable.ic_my_tile);
        state.state = mEnabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.contentDescription = state.label;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_CUSTOM;
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_SETTINGS);
    }

    @Override
    public CharSequence getTileLabel() {
        return "我的开关";
    }
}
```

##### 2. 注册到 Dagger

```java
// QSFactoryImpl.java - createTileInternal 方法
case "mycustom":
    return mMyCustomTileProvider.get();
```

##### 3. 添加到默认 Tile 列表

```xml
<!-- res/values/config.xml -->
<string name="quick_settings_tiles_default" translatable="false">
    wifi,bt,dnd,flashlight,rotation,battery,cell,airplane,mycustom
</string>
```

#### Tile 生命周期

```
Tile 创建
    ↓
handleSetListening(true)  ← 开始监听
    ↓
handleUpdateState()       ← 更新状态
    ↓
用户点击 → handleClick()
    ↓
refreshState() → handleUpdateState()
    ↓
handleSetListening(false) ← 停止监听
```

---

### 第 4 周：深入状态栏模块

#### 阅读顺序

```
1. StatusBar.java / CentralSurfaces.java  # 状态栏核心
       ↓
2. StatusBarWindowController.java         # 窗口控制
       ↓
3. CollapsedStatusBarFragment.java        # 收起状态
       ↓
4. NotificationPanelView.java             # 下拉面板
       ↓
5. StatusBarIconController.java           # 图标管理
```

#### 状态栏结构

```
┌─────────────────────────────────────────────────────────────┐
│ 状态栏 (StatusBar)                                          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────┐    ┌─────────────┐    ┌─────────────────────┐ │
│  │ 通知图标 │    │    时钟     │    │  系统图标 (信号等)   │ │
│  │  (左侧) │    │   (中间)    │    │      (右侧)         │ │
│  └─────────┘    └─────────────┘    └─────────────────────┘ │
│                                                             │
└─────────────────────────────────────────────────────────────┘
        ↓ 下拉
┌─────────────────────────────────────────────────────────────┐
│ 通知面板 (NotificationPanelView)                            │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 快捷设置 (QSPanel)                                   │   │
│  │  [WiFi] [蓝牙] [手电筒] [勿扰] ...                   │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 通知列表 (NotificationStackScrollLayout)             │   │
│  │  ┌─────────────────────────────────────────────┐    │   │
│  │  │ 通知 1                                       │    │   │
│  │  └─────────────────────────────────────────────┘    │   │
│  │  ┌─────────────────────────────────────────────┐    │   │
│  │  │ 通知 2                                       │    │   │
│  │  └─────────────────────────────────────────────┘    │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## 五、代码阅读技巧

### 5.1 从 UI 反推代码

```bash
# 开启布局边界
adb shell setprop debug.layout true
adb shell service call activity 1599295570

# 查看当前 View 层级
adb shell dumpsys activity top | grep -A 100 "View Hierarchy"

# 使用 Layout Inspector (Android Studio)
# 可以直接看到 View 类名和 ID
```

### 5.2 从日志找代码

```bash
# 查看 SystemUI 相关日志
adb logcat -s SystemUI:* StatusBar:* QSTile:*

# 搜索日志对应的代码位置
grep -rn "日志内容" frameworks/base/packages/SystemUI/

# 搜索特定方法
grep -rn "handleClick" frameworks/base/packages/SystemUI/src/
```

### 5.3 从事件追踪流程

以点击 WiFi Tile 为例：

```
用户点击 WiFi Tile
    ↓
View.onClick()
    ↓
QSTileImpl.click()
    ↓
WifiTile.handleClick()
    ↓
WifiManager.setWifiEnabled()
    ↓
系统广播 WIFI_STATE_CHANGED
    ↓
WifiTile.handleUpdateState()
    ↓
View 更新显示
```

### 5.4 使用 Android Code Search

在线浏览 AOSP 源码：[https://cs.android.com/](https://cs.android.com/)

- 支持全局搜索
- 支持跳转定义
- 支持查看引用

---

## 六、关键类速查

### 6.1 入口类

| 类名 | 作用 |
|------|------|
| `SystemUIApplication` | 应用入口，初始化各组件 |
| `SystemUIService` | 核心服务，管理生命周期 |
| `Dependency` | 依赖管理（旧方式） |
| `SysUIComponent` | Dagger Component |

### 6.2 状态栏相关

| 类名 | 作用 |
|------|------|
| `StatusBar` / `CentralSurfaces` | 状态栏核心逻辑 |
| `StatusBarWindowController` | 状态栏窗口控制 |
| `StatusBarIconController` | 图标管理 |
| `StatusBarIconView` | 单个图标 View |
| `CollapsedStatusBarFragment` | 收起状态的状态栏 |
| `Clock` | 状态栏时钟 |

### 6.3 快捷设置相关

| 类名 | 作用 |
|------|------|
| `QSPanel` | 快捷设置面板 |
| `QSTileHost` | Tile 管理器 |
| `QSTileImpl` | Tile 基类 |
| `QSTileView` | Tile View |
| `WifiTile` | WiFi 开关 |
| `BluetoothTile` | 蓝牙开关 |
| `FlashlightTile` | 手电筒开关 |

### 6.4 通知相关

| 类名 | 作用 |
|------|------|
| `NotificationListener` | 通知监听服务 |
| `NotificationStackScrollLayout` | 通知列表容器 |
| `ExpandableNotificationRow` | 单条通知 |
| `NotificationContentView` | 通知内容 View |
| `NotificationShadeWindowController` | 通知面板窗口控制 |

### 6.5 锁屏相关

| 类名 | 作用 |
|------|------|
| `KeyguardViewMediator` | 锁屏核心控制 |
| `KeyguardHostView` | 锁屏宿主 View |
| `KeyguardSecurityContainer` | 解锁方式容器 |
| `KeyguardBouncer` | 锁屏弹出层 |

---

## 七、与 App 开发的概念对应

| App 开发概念 | SystemUI 对应 | 说明 |
|--------------|---------------|------|
| `Activity` | `Window` + `WindowManager` | 直接添加窗口 |
| `Fragment` | `View` 组件 | 手动管理 |
| `ViewModel` | `Controller` / `Interactor` | 业务逻辑层 |
| `Repository` | `SystemService` 调用 | 数据来源 |
| `LiveData` | 回调 / 广播监听 | 数据观察 |
| `Hilt` | `Dagger` | 依赖注入 |
| `Navigation` | 手动管理 View | 页面切换 |
| `RecyclerView` | `NotificationStackScrollLayout` | 列表容器 |
| `SharedPreferences` | `Settings.System/Secure` | 配置存储 |

---

## 八、常用调试命令

### 8.1 SystemUI 操作

```bash
# 重启 SystemUI
adb shell killall com.android.systemui

# 或者
adb shell am crash com.android.systemui

# 查看 SystemUI 进程
adb shell ps -A | grep systemui

# 查看 SystemUI 日志
adb logcat -s SystemUI:* StatusBar:* QSTile:*

# 清除日志后查看
adb logcat -c && adb logcat -s SystemUI:*
```

### 8.2 状态栏操作

```bash
# 展开通知面板
adb shell cmd statusbar expand-notifications

# 展开快捷设置
adb shell cmd statusbar expand-settings

# 收起面板
adb shell cmd statusbar collapse

# dump 状态栏信息
adb shell dumpsys statusbar
```

### 8.3 通知操作

```bash
# 查看所有通知
adb shell dumpsys notification

# 发送测试通知
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

### 8.4 窗口调试

```bash
# 查看窗口层级
adb shell dumpsys window windows | grep -E "Window #|mOwnerUid"

# 查看当前焦点窗口
adb shell dumpsys window windows | grep mCurrentFocus

# 查看 SurfaceFlinger
adb shell dumpsys SurfaceFlinger
```

### 8.5 View 调试

```bash
# 开启布局边界
adb shell setprop debug.layout true
adb shell service call activity 1599295570

# 关闭布局边界
adb shell setprop debug.layout false
adb shell service call activity 1599295570

# dump 当前界面 View 树
adb shell dumpsys activity top
```

### 8.6 服务调试

```bash
# dump SystemUIService
adb shell dumpsys activity service SystemUIService

# 查看所有系统服务
adb shell service list
```

---

## 九、实战项目建议

| 难度 | 项目 | 学习点 | 预计时间 |
|------|------|--------|----------|
| ⭐ | 修改状态栏时间格式 | 资源文件、编译流程 | 1 天 |
| ⭐ | 修改状态栏背景色 | 主题、颜色资源 | 1 天 |
| ⭐⭐ | 添加自定义 QS Tile | Dagger、Tile 生命周期 | 2-3 天 |
| ⭐⭐ | 修改快捷设置布局 | QSPanel 结构 | 2-3 天 |
| ⭐⭐ | 修改音量面板样式 | VolumeDialog | 2-3 天 |
| ⭐⭐⭐ | 添加状态栏新图标 | IconController | 3-5 天 |
| ⭐⭐⭐ | 自定义导航栏按钮 | NavigationBar | 3-5 天 |
| ⭐⭐⭐⭐ | 自定义通知样式 | Notification 流程 | 1 周 |
| ⭐⭐⭐⭐ | 修改锁屏界面 | Keyguard 模块 | 1 周 |
| ⭐⭐⭐⭐⭐ | 新增系统手势 | NavigationBar + WMS | 2 周 |

---

## 十、学习资源

### 10.1 官方资源

- [AOSP 官方文档](https://source.android.com/docs)
- [Android Code Search](https://cs.android.com/) - 在线源码搜索
- [Android Issue Tracker](https://issuetracker.google.com/)

### 10.2 推荐博客

- [Gityuan](http://gityuan.com/) - Android Framework 深度分析
- [刘望舒](https://liuwangshu.cn/) - 系统源码分析
- [Weishu](https://weishu.me/) - 插件化和系统原理

### 10.3 推荐书籍

| 书名 | 作者 | 说明 |
|------|------|------|
| 《深入理解 Android》系列 | 邓凡平 | Framework 经典 |
| 《Android 系统源代码情景分析》 | 罗升阳 | 系统原理 |
| 《Android 进阶解密》 | 刘望舒 | Framework 进阶 |

### 10.4 调试工具

| 工具 | 用途 |
|------|------|
| Android Studio | 代码阅读、Layout Inspector |
| VS Code + Remote SSH | 远程编辑 |
| ASFP | 系统开发专用 IDE |
| Scrcpy | 设备投屏 |
| Logcat | 日志查看 |

---

## 十一、常见问题 FAQ

### Q1: 修改后 push 不生效？

```bash
# 确保执行了 remount
adb root
adb remount

# 检查文件是否正确推送
adb shell ls -la /system/priv-app/SystemUI/

# 强制重启 SystemUI
adb shell killall com.android.systemui
```

### Q2: 如何快速定位代码？

```bash
# 使用 grep 搜索
grep -rn "关键字" frameworks/base/packages/SystemUI/

# 使用 Android Code Search
# https://cs.android.com/

# 使用 IDE 的全局搜索
```

### Q3: Dagger 注入报错？

- 检查是否添加了 `@Inject` 注解
- 检查是否在 Module 中提供了依赖
- 检查 Module 是否加入到 Component

### Q4: 如何调试 SystemUI？

```bash
# 方法1: 加日志
Log.d("TAG", "message");

# 方法2: attach debugger
# Android Studio → Run → Attach Debugger to Android Process → com.android.systemui

# 方法3: dump 状态
adb shell dumpsys statusbar
```

---

## 十二、一句话总结

> **SystemUI = 一个特殊的系统 App，它没有 Activity，通过 WindowManager 直接管理各种悬浮窗口（状态栏、通知、锁屏等），响应系统服务的状态变化来更新 UI。**

> **学习路径：环境搭建 → 启动流程 → QS Tile → 状态栏 → 通知 → 锁屏**

---

> 📝 **提示**：建议从 QS Tile 入手，代码量小、独立性强、效果明显，是最佳的入门模块。
>
> 📅 **更新日期**：2026-01-26
