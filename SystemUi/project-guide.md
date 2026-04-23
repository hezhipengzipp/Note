# SystemUI 项目阅读指南

> **"Everything you see in Android that's not an app"**
> Android 系统中除应用外的所有可见 UI

包括：状态栏、通知栏、锁屏、导航栏、快速设置、音量控制、截图等。

---

## 一、项目基本信息

| 属性 | 值 |
|------|-----|
| **项目名称** | SystemUI |
| **包名** | `com.android.systemui` |
| **编译目标** | Android 14 (API 34) |
| **开发语言** | Java + Kotlin |
| **架构模式** | Dagger 依赖注入 + MVP/MVC |
| **构建系统** | Soong (Android.bp) / Gradle (build.gradle) |

---

## 二、推荐阅读顺序

```
┌─────────────────────────────────────────────────────────────┐
│  第1步：入口文件                                              │
│  ↓                                                          │
│  第2步：核心服务启动流程                                       │
│  ↓                                                          │
│  第3步：你关心的具体模块                                       │
└─────────────────────────────────────────────────────────────┘
```

### 第1步：入口文件 ⭐⭐⭐⭐⭐

| 顺序 | 文件 | 作用 |
|-----|------|------|
| 1 | `AndroidManifest.xml` | 了解注册了哪些组件 |
| 2 | `src/com/android/systemui/SystemUIApplication.java` | 应用入口，初始化流程 |
| 3 | `src/com/android/systemui/SystemUIService.java` | 主服务，启动核心模块 |

### 第2步：核心架构 ⭐⭐⭐⭐

| 顺序 | 文件/目录 | 作用 |
|-----|----------|------|
| 4 | `src/com/android/systemui/dagger/` | 依赖注入配置（理解模块如何组装） |
| 5 | `src/com/android/systemui/CoreStartable.java` | 核心服务接口 |
| 6 | `src/com/android/systemui/statusbar/CommandQueue.java` | 事件分发中枢 |

### 第3步：具体模块（按需阅读）

| 模块 | 目录 | 功能 |
|------|------|------|
| 状态栏/通知栏 | `statusbar/` | 顶部状态栏、下拉通知 |
| 锁屏 | `keyguard/` | 锁屏界面、解锁逻辑 |
| 快速设置 | `qs/` | 下拉快速设置面板 |
| 导航栏 | `navigationbar/` | 底部导航栏 |
| 音量控制 | `volume/` | 音量调节UI |
| 截图 | `screenshot/` | 截图功能 |

---

## 三、目录结构速览

```
SystemUI/
├── src/com/android/
│   ├── systemui/              # 核心代码 ⭐
│   │   ├── dagger/            # 依赖注入 (先看)
│   │   ├── statusbar/         # 状态栏/通知栏 (核心)
│   │   ├── keyguard/          # 锁屏相关
│   │   ├── qs/                # 快速设置
│   │   ├── navigationbar/     # 导航栏
│   │   ├── volume/            # 音量控制
│   │   ├── notification/      # 通知管理
│   │   ├── shade/             # 通知栏遮罩
│   │   ├── biometrics/        # 生物识别
│   │   ├── screenshot/        # 截图
│   │   ├── screenrecord/      # 屏幕录制
│   │   ├── media/             # 媒体控制
│   │   ├── power/             # 电源相关
│   │   └── util/              # 工具类
│   └── keyguard/              # 锁屏模块 (独立包)
├── shared/                    # 共享代码库
├── plugin/                    # 插件系统
├── plugin_core/               # 插件核心接口
├── animation/                 # 动画资源和代码
├── log/                       # 日志系统
├── customization/             # 定制化模块
├── monet/                     # Material You 主题引擎
├── common/                    # 公共代码
├── res/                       # 主资源
├── res-keyguard/              # 锁屏资源
├── res-product/               # 产品定制资源
├── docs/                      # 项目文档
└── tests/                     # 测试代码
```

---

## 四、核心模块功能说明

### 4.1 顶级模块 (src/com/android/systemui/)

| 模块名 | 功能描述 | 关键类 |
|--------|--------|--------|
| **statusbar** | 状态栏和通知栏管理 | `CentralSurfacesImpl`, `CommandQueue`, `NotificationListener` |
| **keyguard** | 锁屏管理 | `KeyguardViewController`, `KeyguardUpdateMonitor` |
| **qs** | Quick Settings（快速设置面板） | `QSPanel`, `QSTileHost` |
| **shade** | 通知栏遮罩/阴影 | `ShadeController`, `NotificationShadeWindowController` |
| **navigationbar** | 导航栏管理 | `NavigationBarView`, `NavigationBarController` |
| **volume** | 音量控制UI | `VolumeUI`, `VolumeDialogImpl` |
| **controls** | Home Controls（家居控制） | `ControlsActivity`, `ControlsController` |
| **wallet** | Quick Access Wallet（快捷钱包） | `WalletActivity`, `QuickAccessWalletController` |
| **screenshot** | 截图功能 | `TakeScreenshotService`, `ScreenshotController` |
| **screenrecord** | 屏幕录制 | `RecordingService` |
| **notification** | 通知管理 | `NotificationStackScrollLayout`, `NotificationEntryManager` |
| **media** | 媒体播放控制 | `MediaOutputController`, `MediaProjectionManager` |
| **biometrics** | 生物识别UI | `AuthController`, `UdfpsController` |
| **usb** | USB相关对话框 | `UsbConfirmActivity`, `UsbPermissionActivity` |
| **power** | 电源相关UI | `PowerUI`, `BatteryController` |
| **tuner** | 系统UI调试工具 | `TunerActivity` |
| **dagger** | 依赖注入配置 | `GlobalRootComponent`, `SysUIComponent` |
| **util** | 工具库 | `NotificationChannels`, `SettableWakeLock` |

### 4.2 Keyguard 模块 (src/com/android/keyguard/)

**主要子模块**:
- **Core Components**: `KeyguardViewController`, `KeyguardUpdateMonitor`, `KeyguardSecurityContainer`
- **Security Views**: `KeyguardPINView`, `KeyguardPatternView`, `KeyguardPasswordView`
- **Clock System**: `ClockManager`, `ClockLayout`, 各种时钟控制器
- **Dagger Integration**: `KeyguardBouncerComponent`, `KeyguardStatusViewComponent`
- **Provider**: `KeyguardSliceProvider` (数据提供)

---

## 五、关键入口点

### 5.1 Application 类

**文件**: `src/com/android/systemui/SystemUIApplication.java`

```java
public class SystemUIApplication extends Application
    implements SystemUIAppComponentFactory.ContextInitializer {

    // 核心职责：
    // 1. 初始化 Dagger 依赖注入图
    // 2. 启动核心服务（CoreStartable）
    // 3. 管理应用生命周期
}
```

**关键方法**:
- `onCreate()`: 初始化 Dagger 和 SystemUI 服务
- `startServicesIfNeeded()`: 启动所有注册的核心服务

### 5.2 主要 Service

| Service 类 | 位置 | 功能 |
|-----------|------|------|
| **SystemUIService** | `SystemUIService.java` | SystemUI 主服务，启动所有核心服务 |
| **SystemUISecondaryUserService** | `SystemUISecondaryUserService.java` | 用户切换时启动 |
| **KeyguardService** | `statusbar/phone/KeyguardService.java` | 锁屏服务 |
| **DreamOverlayService** | `dreams/DreamOverlayService.java` | 梦景覆盖层服务 |
| **DozeService** | `doze/DozeService.java` | 低电耗模式服务 |

### 5.3 主要 Activity

| Activity 类 | 功能 | 导出 |
|-----------|------|------|
| `TunerActivity` | 系统UI调试工具 | 是 |
| `WalletActivity` | 快捷钱包界面 | 否 |
| `ControlsActivity` | 家居控制界面 | 是 |
| `UsbConfirmActivity` | USB确认对话框 | 是 |
| `BrightnessDialog` | 亮度调整对话框 | 是 |
| `CreateNoteTaskShortcutActivity` | 笔记快捷方式创建 | 是 |
| `PeopleSpaceActivity` | 人脉空间配置 | 是 |

---

## 六、启动流程

```
SystemUIApplication.onCreate()
    ↓
SystemUIService.onCreate()
    ↓
启动所有 CoreStartable 实现类
    ↓
┌─────────────────────────────────────┐
│  CentralSurfacesImpl (状态栏)        │
│  NavigationBarController (导航栏)    │
│  VolumeUI (音量)                     │
│  PowerUI (电源)                      │
│  KeyguardServiceImpl (锁屏)          │
│  ...                                │
└─────────────────────────────────────┘
```

---

## 七、核心架构模式

### 7.1 Dagger 依赖注入

**关键组件**:
- **GlobalRootComponent**: 全局根依赖图
- **SysUIComponent**: 系统UI依赖子图
- **WMComponent**: 窗口管理器组件

**关键模块文件** (`src/com/android/systemui/dagger/`):
```
- GlobalRootComponent.java (根接口)
- GlobalModule.java (全局配置)
- FrameworkServicesModule.java (框架服务)
- DefaultServiceBinder.java (Service绑定)
- DefaultActivityBinder.java (Activity绑定)
- PluginModule.java (插件系统)
```

**依赖图结构**:
```
GlobalRootComponent (根组件)
    ↓
SysUIComponent (SystemUI组件)
    ↓
各个功能模块
```

### 7.2 CoreStartable 模式

SystemUI 使用 `CoreStartable` 接口来管理各个功能模块:

```java
public interface CoreStartable {
    void start();           // 启动时调用
    void onBootCompleted(); // 开机完成后调用
}
```

**实现类示例**:
- `CentralSurfacesImpl` (状态栏/通知栏)
- `NavigationBarController` (导航栏)
- `VolumeUI` (音量控制)
- `PowerUI` (电源UI)
- `KeyguardServiceImpl` (锁屏)

### 7.3 CommandQueue 模式

**作用**: 接收来自 SystemServer 的事件，分发给各个监听器

```java
public class CommandQueue extends IStatusBar.Stub {
    // 接收 SystemServer 的回调
    // 分发给注册的 CommandQueue.Callbacks 监听器
}
```

---

## 八、AndroidManifest.xml 核心组件

### 8.1 Application 配置

```xml
<application
    android:name=".SystemUIApplication"
    android:persistent="true"
    android:allowClearUserData="false"
    android:appComponentFactory=".SystemUIAppComponentFactory"
    ... >
```

**关键属性**:
- `persistent="true"`: 系统重启后自动启动
- `sharedUserId="android.uid.system"`: 系统级权限

### 8.2 注册的主要组件

#### Service (7个核心服务)
- SystemUIService (主服务)
- SystemUISecondaryUserService (用户切换)
- TakeScreenshotService (截图)
- KeyguardService (锁屏)
- DreamOverlayService (梦景)
- DozeService (低电耗)
- RecordingService (屏幕录制)

#### Receiver (6个广播接收器)
- ScreenshotServiceErrorReceiver
- ActionProxyReceiver
- DeleteScreenshotReceiver
- SmartActionsReceiver
- SysuiRestartReceiver
- KeyboardShortcutsReceiver

#### Provider (3个内容提供者)
- KeyguardSliceProvider (锁屏数据)
- ClockOptionsProvider (时钟选项)
- PeopleProvider (人脉磁贴预览)

---

## 九、关键文件速查表

| 文件路径 | 用途 | 优先级 |
|---------|------|--------|
| `SystemUIApplication.java` | 应用入口和初始化 | ⭐⭐⭐⭐⭐ |
| `SystemUIService.java` | 主服务启动 | ⭐⭐⭐⭐⭐ |
| `statusbar/phone/CentralSurfacesImpl.java` | 状态栏/通知栏核心 | ⭐⭐⭐⭐⭐ |
| `CommandQueue.java` | 事件分发中枢 | ⭐⭐⭐⭐ |
| `dagger/GlobalRootComponent.java` | DI根组件 | ⭐⭐⭐⭐ |
| `keyguard/KeyguardViewController.java` | 锁屏核心控制 | ⭐⭐⭐⭐ |
| `qs/QSPanel.java` | 快速设置面板 | ⭐⭐⭐ |
| `navigationbar/NavigationBarController.java` | 导航栏控制 | ⭐⭐⭐ |
| `AndroidManifest.xml` | 组件声明和权限 | ⭐⭐⭐⭐ |

---

## 十、阅读路线图

### 如果你想了解状态栏：
```
SystemUIApplication → SystemUIService → CentralSurfacesImpl → StatusBarView
```

### 如果你想了解锁屏：
```
SystemUIApplication → KeyguardService → KeyguardViewController → KeyguardSecurityContainer
```

### 如果你想了解快速设置：
```
CentralSurfacesImpl → QSPanel → QSTileHost → 具体 Tile 实现
```

### 如果你想了解通知：
```
CommandQueue → NotificationListener → NotificationEntryManager → NotificationStackScrollLayout
```

---

## 十一、SystemUI vs 普通 App

| 特性 | 普通 App | SystemUI |
|------|----------|----------|
| **API 访问** | 公开 SDK | 隐藏 API (`@hide`) |
| **签名** | 开发者签名 | 平台签名 (`platform`) |
| **权限** | 受限 | 系统级特权 |
| **安装位置** | `/data/app/` | `/system_ext/priv-app/` |
| **编译环境** | Android Studio | AOSP 源码树 |
| **依赖获取** | Maven/JCenter | AOSP 内部模块 |
| **构建文件** | `build.gradle` | `Android.bp` |

---

## 十二、重要权限

### 系统权限
- `STATUS_BAR_SERVICE`: 状态栏服务权限
- `INTERNAL_SYSTEM_WINDOW`: 系统窗口权限
- `CONTROL_KEYGUARD`: 锁屏控制权限
- `MANAGE_USERS`: 用户管理权限
- `DEVICE_POWER`: 设备电源控制
- `CAMERA`: 相机访问
- `RECORD_AUDIO`: 音频录制

### 特殊权限
- `CUSTOMIZE_SYSTEM_UI`: 自定义系统UI (签名权限)
- `BIND_QUICK_SETTINGS_TILE`: 快速设置磁贴绑定
- `BIND_CONTROLS`: Home Controls 绑定
- `MANAGE_NOTIFICATIONS`: 通知管理

---

## 十三、开发流程建议

### 新增功能步骤:
1. **定义 CoreStartable**: 继承并实现 `CoreStartable` 接口
2. **创建 Dagger Module**: 在 `dagger/` 目录创建模块配置
3. **注册依赖**: 在 `GlobalModule` 或相关模块中注册
4. **定义 UI 组件**: 创建相应的 View/Activity/Fragment
5. **绑定清单**: 在 `AndroidManifest.xml` 中声明组件
6. **资源配置**: 在 `res/` 中添加布局、样式、字符串资源

### 目录划分原则:
- **statusbar/** - 顶部状态栏和通知栏相关
- **keyguard/** - 锁屏和安全认证相关
- **qs/** - Quick Settings 快速设置
- **shade/** - 通知栏阴影和展开效果
- **util/** - 通用工具类和辅助函数
- **dagger/** - 依赖注入配置

---

## 十四、相关文档

本目录下其他文档：

| 文档 | 内容 |
|------|------|
| `dagger.md` | Dagger 依赖注入详解 |
| `corestartable.md` | CoreStartable 模式说明 |
| `plugins.md` | 插件系统文档 |
| `qs-tiles.md` | 快速设置磁贴开发 |
| `status-bar-data-pipeline.md` | 状态栏数据管道 |
| `media-controls.md` | 媒体控制文档 |
| `executors.md` | 执行器和线程模型 |
| `dialogs.md` | 对话框系统 |

---

## 十五、项目统计

- **总Java/Kotlin文件数**: 500+ (src/ 主目录)
- **Keyguard 模块**: 63个文件
- **主要包数**: 80+ 个 (systemui/*)
- **注册的 Service**: 7+
- **注册的 Activity**: 15+
- **注册的 Receiver**: 6+
- **注册的 Provider**: 3+

---

*文档更新日期: 2026-01-27*
