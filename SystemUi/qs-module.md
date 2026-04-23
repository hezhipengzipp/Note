# QS (Quick Settings) 模块深度分析文档

## 一、模块概述

QS（Quick Settings，快速设置）模块负责：
- 下拉通知栏顶部的快速设置面板
- 各种功能磁贴（WiFi、蓝牙、飞行模式等）
- 磁贴的自定义和排序
- 支持第三方应用添加自定义磁贴

---

## 二、目录结构

```
qs/
├── 核心类
│   ├── QSFragment.java              # QS UI 片段 ⭐
│   ├── QSPanel.java                 # QS 面板容器 ⭐
│   ├── QSPanelController.java       # 面板控制器
│   ├── QSContainerImpl.java         # QS 容器
│   ├── QSHost.java                  # QS 主机接口
│   └── QSTileHost.java              # Tile 宿主实现 ⭐
│
├── tileimpl/                        # Tile 实现基础
│   ├── QSTileImpl.java              # Tile 抽象基类 ⭐
│   ├── QSTileViewImpl.kt            # Tile 视图实现
│   ├── QSFactoryImpl.java           # Tile 工厂
│   ├── QSIconViewImpl.java          # Tile 图标视图
│   └── QSTileServiceWrapper.java    # Tile 服务包装
│
├── tiles/                           # 内置 Tile (32种) ⭐
│   ├── InternetTile.java            # 互联网
│   ├── BluetoothTile.java           # 蓝牙
│   ├── AirplaneModeTile.java        # 飞行模式
│   ├── LocationTile.java            # 定位
│   ├── DndTile.java                 # 勿扰模式
│   ├── FlashlightTile.java          # 手电筒
│   ├── HotspotTile.java             # 热点
│   ├── NfcTile.java                 # NFC
│   ├── RotationLockTile.java        # 旋转锁定
│   ├── ScreenRecordTile.java        # 屏幕录制
│   ├── CastTile.java                # 投屏
│   ├── UiModeNightTile.java         # 深色模式
│   └── ...更多 Tile
│
├── customize/                       # QS 自定义
│   ├── QSCustomizer.java            # 自定义 UI
│   ├── QSCustomizerController.java  # 自定义控制器
│   ├── TileAdapter.java             # Tile 适配器
│   └── TileQueryHelper.java         # Tile 查询助手
│
├── external/                        # 外部 Tile 支持
│   ├── CustomTile.java              # 自定义 Tile
│   ├── TileLifecycleManager.java    # 生命周期管理
│   └── TileServices.java            # Tile 服务管理
│
├── footer/                          # QS 底部区域
│   ├── QSFooterView.java
│   └── QSFooterViewController.java
│
├── dagger/                          # Dagger DI
│   ├── QSModule.java
│   ├── QSFragmentModule.java
│   └── QSHostModule.kt
│
└── logging/
    └── QSLogger.kt                  # 日志记录
```

---

## 三、分层架构

```
┌──────────────────────────────────────────────────────┐
│              UI Layer (视图层)                        │
│  ┌─────────────────────────────────────────────────┐ │
│  │ QSFragment / QSPanel / QSTileViewImpl           │ │
│  │ QuickQSPanel / PagedTileLayout                  │ │
│  └─────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
                    ↕ (回调/状态)
┌──────────────────────────────────────────────────────┐
│              Tile Layer (磁贴层)                      │
│  ┌─────────────────────────────────────────────────┐ │
│  │ QSTileImpl / QSTileHost / QSFactoryImpl         │ │
│  │ InternetTile / BluetoothTile / ...              │ │
│  └─────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
                    ↕ (Controller)
┌──────────────────────────────────────────────────────┐
│              Controller Layer (控制器层)              │
│  ┌─────────────────────────────────────────────────┐ │
│  │ NetworkController / BluetoothController         │ │
│  │ LocationController / ZenModeController          │ │
│  └─────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
                    ↕ (系统 API)
┌──────────────────────────────────────────────────────┐
│              System Services (系统服务)               │
│  ┌─────────────────────────────────────────────────┐ │
│  │ WifiManager / BluetoothAdapter                  │ │
│  │ LocationManager / NotificationManager           │ │
│  └─────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
```

---

## 四、核心类详解

### 4.1 QSFragment（QS 片段）⭐

**文件**: `QSFragment.java`（约 1300 行）

**职责**:
- QS 的顶级 UI 容器
- 管理展开/收起动画
- 处理亮度控制
- 集成媒体播放器

**关键方法**:
```java
public void onViewCreated(View view, Bundle savedInstanceState)
public void setListening(boolean listening)
public void setExpanded(boolean expanded)
public void animateExpansionFactor(float expansionFactor)
```

---

### 4.2 QSPanel（QS 面板）

**文件**: `QSPanel.java`（约 800 行）

**职责**:
- Tile 的视图容器（继承 LinearLayout）
- 管理磁贴网格布局
- 处理亮度滑块
- 支持横竖屏切换

**关键属性**:
```java
protected QSTileLayout mTileLayout;      // 磁贴布局
protected View mBrightnessView;          // 亮度控制
protected View mFooter;                  // 底部视图
```

---

### 4.3 QSTileHost（Tile 宿主）⭐

**文件**: `QSTileHost.java`（约 700 行）

**职责**:
- 所有 Tile 的管理者和工厂
- 维护 Tile 列表
- 从 Settings.Secure.QS_TILES 读取配置
- 处理 Tile 的增删改

**关键方法**:
```java
public QSTile createTile(String tileSpec)      // 创建 Tile
public void addTile(String spec)               // 添加 Tile
public void removeTile(String spec)            // 移除 Tile
public void changeTilesByUser(List<String> tiles) // 用户修改
public Collection<QSTile> getTiles()           // 获取所有 Tile
```

**Tile 配置格式**:
```
# Settings.Secure.QS_TILES 存储格式
internet,bt,flashlight,dnd,alarm,airplane,controls,wallet,...

# 自定义 Tile 格式
custom(com.example.app/.MyTileService)
```

---

### 4.4 QSTileImpl（Tile 基类）⭐

**文件**: `tileimpl/QSTileImpl.java`（约 800 行）

**职责**:
- 所有 Tile 的抽象基类
- 管理 Tile 生命周期
- 处理用户交互（点击、长按）
- 管理状态更新

**生命周期**:
```
CREATED → STARTED → RESUMED → DESTROYED
```

**关键抽象方法**:
```java
// 子类必须实现
public abstract TState newTileState();                    // 创建状态
protected abstract void handleClick(View view);           // 处理点击
protected abstract void handleUpdateState(TState state, Object arg); // 更新状态
```

**状态管理流程**:
```
Controller 状态变化
    ↓
Tile.refreshState()
    ↓
mHandler.sendMessage(H.REFRESH_STATE) [后台线程]
    ↓
handleUpdateState(mTmpState, arg)
    ↓
比较 mState 和 mTmpState
    ↓
如果不同 → fireCallbacks() [UI 线程]
    ↓
QSTileView.setState(newState)
```

---

### 4.5 QSTileViewImpl（Tile 视图）

**文件**: `tileimpl/QSTileViewImpl.kt`（约 600 行）

**职责**:
- Tile 的 UI 展示
- 处理状态变化的视觉效果
- 支持可访问性功能

**结构**:
```
QSTileViewImpl
├── QSIconView (图标)
├── TextView (标签)
├── TextView (副标签)
└── 背景/涟漪效果
```

---

## 五、内置 Tile 类型（32 种）

### 5.1 网络类 (5 个)

| Tile | 标识 | 功能 |
|------|------|------|
| InternetTile | `internet` | 互联网（WiFi/Mobile 统一） |
| BluetoothTile | `bt` | 蓝牙开关和设备 |
| HotspotTile | `hotspot` | 移动热点 |
| NfcTile | `nfc` | NFC 开关 |
| DataSaverTile | `saver` | 数据保护 |

### 5.2 系统功能类 (7 个)

| Tile | 标识 | 功能 |
|------|------|------|
| AirplaneModeTile | `airplane` | 飞行模式 |
| LocationTile | `location` | 位置服务 |
| DndTile | `dnd` | 勿扰模式 |
| RotationLockTile | `rotation` | 自动旋转 |
| UiModeNightTile | `dark` | 深色模式 |
| DreamTile | `dream` | 屏保 |
| ScreenRecordTile | `screenrecord` | 屏幕录制 |

### 5.3 设备控制类 (7 个)

| Tile | 标识 | 功能 |
|------|------|------|
| FlashlightTile | `flashlight` | 手电筒 |
| ReduceBrightColorsTile | `reduce_brightness` | 亮度降低 |
| ColorCorrectionTile | `color_correction` | 色彩校正 |
| ColorInversionTile | `inversion` | 色彩反转 |
| MicrophoneToggleTile | `mictoggle` | 麦克风开关 |
| CameraToggleTile | `cameratoggle` | 摄像头开关 |
| SensorPrivacyToggleTile | `sensortile` | 传感器隐私 |

### 5.4 应用集成类 (6 个)

| Tile | 标识 | 功能 |
|------|------|------|
| CastTile | `cast` | 投屏 |
| DeviceControlsTile | `controls` | 智能家居 |
| QuickAccessWalletTile | `wallet` | 钱包 |
| QRCodeScannerTile | `qr_code_scanner` | 二维码扫描 |
| AlarmTile | `alarm` | 闹钟 |
| WorkModeTile | `work` | 工作模式 |

---

## 六、Tile 实现机制

### 6.1 Tile 三层结构

```
QSTile (接口)
    ↑
QSTileImpl<TState> (抽象基类)
    ↑
ConcreteXxxTile (具体实现)
```

### 6.2 State 继承体系

```java
QSTile.State (基类)
├── BooleanState    // 开/关状态（WiFi、蓝牙等）
├── SignalState     // 信号强度状态
└── TileState       // 通用状态

// State 常见属性
class State {
    int state;                    // STATE_UNAVAILABLE/INACTIVE/ACTIVE
    boolean value;                // 当前值（BooleanState）
    CharSequence label;           // 标题
    CharSequence secondaryLabel;  // 副标题
    Icon icon;                    // 图标
    boolean disabledByPolicy;     // 是否被策略禁用
}
```

### 6.3 Tile 状态常量

```java
Tile.STATE_UNAVAILABLE = 0;  // 不可用（灰色）
Tile.STATE_INACTIVE = 1;     // 未激活（暗色）
Tile.STATE_ACTIVE = 2;       // 已激活（亮色）
```

---

## 七、启动流程

```
SystemUIApplication.onCreate()
    ↓
Dagger 创建 QSFragmentComponent
    ↓
QSFragment 创建和初始化
    ↓
QSFragment.onViewCreated()
    ├── 查找 QSPanel
    ├── QSPanelController 初始化
    └── 注册状态监听
    ↓
QSFragment.onStart()
    ├── QSTileHost 监听 Settings.Secure.QS_TILES
    └── 订阅 Tile 状态更新
    ↓
QSTileHost.onTuningChanged()
    ├── 解析 QS_TILES 配置
    ├── 销毁不需要的 Tile
    ├── 创建新 Tile
    │   └── QSFactoryImpl.createTile(tileSpec)
    └── 通知 Tile 列表变化
    ↓
Tile 初始化
    ├── tile.setListening(true)
    ├── tile.refreshState()
    └── QSTileView.setState(state)
```

---

## 八、QS 面板布局

### 8.1 布局结构

```xml
<QSContainerImpl>
    <!-- 展开的 QS 面板 -->
    <NonInterceptingScrollView id="expanded_qs_scroll_view">
        <QSPanel id="quick_settings_panel">
            <!-- 亮度滑块 -->
            <!-- Tile 布局 (PagedTileLayout) -->
            <!-- 底部按钮 -->
        </QSPanel>
    </NonInterceptingScrollView>

    <!-- 快速 QS 面板（顶部 6 个 Tile） -->
    <QuickStatusBarHeader id="header">
        <QuickQSPanel>  <!-- 最多 6 个 Tile -->
        </QuickQSPanel>
    </QuickStatusBarHeader>

    <!-- 底部操作按钮 -->
    <FooterActions id="qs_footer_actions" />

    <!-- QS 自定义面板 -->
    <QSCustomizer id="qs_customize" />
</QSContainerImpl>
```

### 8.2 布局管理

| 类 | 功能 |
|---|------|
| TileLayout | 基础网格布局 |
| PagedTileLayout | 分页布局（支持滑动翻页） |
| QuickQSPanel | 快速 QS（最多 6 个 Tile） |

---

## 九、交互流程

### 9.1 用户点击 Tile

```
用户点击 Tile
    ↓
QSTileViewImpl.onClick()
    ↓
Tile.click(view)
    ↓
FalsingManager.isFalseTap() [检查误触]
    ↓
mHandler.obtainMessage(H.CLICK).sendToTarget() [后台线程]
    ↓
handleClick(view) [子类实现]
    ├── 执行功能（如开关 WiFi）
    └── refreshState()
    ↓
handleUpdateState() [更新状态]
    ↓
fireCallbacks() [UI 线程]
    ↓
QSTileView.setState(newState) [更新 UI]
```

### 9.2 用户长按 Tile

```
用户长按 Tile
    ↓
Tile.longClick(view)
    ↓
通常打开对应的设置界面
    ↓
ActivityStarter.startActivity(intent)
```

### 9.3 编辑 Tile

```
用户点击"编辑"按钮
    ↓
QSCustomizerController 显示自定义面板
    ↓
TileAdapter 显示所有 Tile
    ├── 支持拖拽排序
    ├── 长按移除
    └── 点击添加
    ↓
修改保存到 Settings.Secure.QS_TILES
    ↓
QSTileHost.onTuningChanged()
```

---

## 十、与系统服务的交互

### 10.1 通过 Controller 交互

```
Tile (UI)
    ↓
Controller (业务逻辑)
    ↓
System Service (系统服务)
```

### 10.2 Controller 映射

| Tile | Controller | 系统服务 |
|------|-----------|---------|
| InternetTile | NetworkController | ConnectivityManager, WifiManager |
| BluetoothTile | BluetoothController | BluetoothAdapter |
| LocationTile | LocationController | LocationManager |
| DndTile | ZenModeController | NotificationManager |
| CastTile | CastController | MediaRouter |
| HotspotTile | HotspotController | WifiManager |

### 10.3 Controller 通用模式

```java
public interface SomeController {
    boolean isEnabled();
    void enable();
    void disable();
    void addCallback(Callback callback);
    void removeCallback(Callback callback);

    interface Callback {
        void onStateChanged(boolean newState);
    }
}
```

---

## 十一、如何添加自定义 Tile

### 11.1 方式一：内置 Tile（系统功能）

**步骤 1: 创建 Tile 类**

```java
package com.android.systemui.qs.tiles;

public class MyCustomTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "mycustom";

    @Inject
    public MyCustomTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            MyCustomController myController
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler,
              falsingManager, metricsLogger, statusBarStateController,
              activityStarter, qsLogger);
        mController = myController;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick(@Nullable View view) {
        mController.toggle();
        refreshState();
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        boolean enabled = mController.isEnabled();
        state.value = enabled;
        state.label = "My Custom";
        state.icon = ResourceIcon.get(R.drawable.ic_my_custom);
        state.state = enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }

    @Override
    public void setListening(Object client, boolean listening) {
        super.setListening(client, listening);
        if (listening) {
            mController.addCallback(mCallback);
        } else {
            mController.removeCallback(mCallback);
        }
    }
}
```

**步骤 2: 创建 Dagger Module**

```java
@Module
public interface MyCustomModule {

    @Binds
    @IntoMap
    @StringKey("mycustom")
    QSTileImpl<?> bindMyCustomTile(MyCustomTile tile);
}
```

**步骤 3: 注册到 QSModule**

```java
@Module(includes = {
    // ...
    MyCustomModule.class,
})
public interface QSModule { }
```

**步骤 4: 添加到默认列表**

```xml
<!-- res/values/config.xml -->
<string name="quick_settings_tiles_default">
    internet,bt,flashlight,mycustom,...
</string>
```

### 11.2 方式二：外部 Tile（第三方应用）

**在应用中实现 TileService**:

```java
public class MyTileService extends TileService {

    @Override
    public void onStartListening() {
        // 开始监听
    }

    @Override
    public void onClick() {
        Tile tile = getQsTile();
        if (tile.getState() == Tile.STATE_ACTIVE) {
            tile.setState(Tile.STATE_INACTIVE);
        } else {
            tile.setState(Tile.STATE_ACTIVE);
        }
        tile.updateTile();
    }
}
```

**AndroidManifest.xml 声明**:

```xml
<service
    android:name=".MyTileService"
    android:icon="@drawable/ic_tile"
    android:label="@string/tile_label"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
</service>
```

---

## 十二、关键接口

### 12.1 QSTile 接口

```java
public interface QSTile {
    // 状态
    void refreshState();
    State getState();

    // 生命周期
    void setListening(Object client, boolean listening);
    void destroy();

    // 用户交互
    void click(@Nullable View view);
    void secondaryClick(@Nullable View view);
    void longClick(@Nullable View view);

    // 回调
    void addCallback(Callback callback);
    void removeCallback(Callback callback);

    interface Callback {
        void onStateChanged(State state);
    }
}
```

### 12.2 QSHost 接口

```java
public interface QSHost {
    QSTile createTile(String tileSpec);
    Collection<QSTile> getTiles();
    void addTile(String spec);
    void removeTile(String spec);
    void changeTilesByUser(List<String> newTiles);

    interface Callback {
        void onTilesChanged();
    }
}
```

---

## 十三、布局文件

| 文件 | 用途 |
|------|------|
| `qs_panel.xml` | QS 面板基础布局 |
| `qs_paged_tile_layout.xml` | 分页 Tile 布局 |
| `quick_status_bar_expanded_header.xml` | 快速 QS 头部 |
| `qs_footer_impl.xml` | 底部按钮 |
| `qs_customize.xml` | 自定义面板 |

---

## 十四、配置资源

### 14.1 默认 Tile 列表

```xml
<!-- res/values/config.xml -->
<string name="quick_settings_tiles_default">
    internet,bt,flashlight,dnd,alarm,airplane,controls,wallet,
    rotation,battery,cast,screenrecord,mictoggle,cameratoggle
</string>
```

### 14.2 维度定义

```xml
<dimen name="qs_tile_height">104dp</dimen>
<dimen name="qs_tile_width">80dp</dimen>
<dimen name="qs_tile_margin_horizontal">0dp</dimen>
<dimen name="qs_tile_margin_vertical">0dp</dimen>
```

---

## 十五、线程模型

```
Main Thread (UI 线程)
├── QSFragment / QSPanel UI 操作
├── QSTileView.setState() 更新显示
└── Callback.onStateChanged()

Background Thread (后台线程)
├── QSTileImpl.H Handler
├── handleClick() 处理点击
├── handleUpdateState() 更新状态
└── 与 Controller/系统服务交互
```

**线程安全**:
```java
// 所有状态更新在后台线程
public void refreshState() {
    mHandler.obtainMessage(H.REFRESH_STATE).sendToTarget();
}

// UI 更新回到主线程
private void fireCallbacks() {
    mUiHandler.post(() -> {
        for (Callback cb : mCallbacks) {
            cb.onStateChanged(mState);
        }
    });
}
```

---

## 十六、阅读顺序建议

```
1. QSTileHost.java            # Tile 管理，理解整体架构
       ↓
2. QSTileImpl.java            # Tile 基类 (重点 ⭐)
       ↓
3. tiles/InternetTile.java    # 具体 Tile 实现示例
       ↓
4. QSFragment.java            # QS UI 容器
       ↓
5. QSPanel.java               # Tile 布局容器
       ↓
6. QSTileViewImpl.kt          # Tile 视图实现
       ↓
7. customize/QSCustomizer.java # 自定义功能
       ↓
8. external/CustomTile.java   # 外部 Tile 支持
```

---

## 十七、常见修改场景

| 需求 | 修改位置 |
|------|---------|
| 添加新内置 Tile | 创建 XxxTile 类 + Dagger Module |
| 修改 Tile 样式 | `QSTileViewImpl.kt` + 布局文件 |
| 修改默认 Tile 列表 | `config.xml` - `quick_settings_tiles_default` |
| 修改 Tile 网格布局 | `TileLayout` / `PagedTileLayout` |
| 修改快速 QS 数量 | `QuickQSPanel` - `TUNER_MAX_TILES_FALLBACK` |
| 添加 Tile 动画 | `QSTileViewImpl.kt` - `setState()` |
| 修改长按行为 | 对应 Tile 的 `handleLongClick()` |

---

## 十八、调试方法

### Dump 信息

```bash
# QS 状态
adb shell dumpsys systemui QS

# Tile 列表
adb shell settings get secure sysui_qs_tiles
```

### 日志标签

```java
TAG = "QSTileHost"
TAG = "QSTileImpl"
TAG = "QSPanel"
TAG = "QSFragment"
```

### 修改 Tile 列表

```bash
# 设置自定义 Tile 列表
adb shell settings put secure sysui_qs_tiles "wifi,bt,flashlight"

# 重置为默认
adb shell settings delete secure sysui_qs_tiles
```

---

## 十九、关键文件速查表

| 文件 | 路径 | 功能 | 优先级 |
|------|------|------|--------|
| QSTileHost | `qs/QSTileHost.java` | Tile 管理 | ⭐⭐⭐⭐⭐ |
| QSTileImpl | `qs/tileimpl/QSTileImpl.java` | Tile 基类 | ⭐⭐⭐⭐⭐ |
| QSFragment | `qs/QSFragment.java` | QS 片段 | ⭐⭐⭐⭐ |
| QSPanel | `qs/QSPanel.java` | QS 面板 | ⭐⭐⭐⭐ |
| QSTileViewImpl | `qs/tileimpl/QSTileViewImpl.kt` | Tile 视图 | ⭐⭐⭐⭐ |
| InternetTile | `qs/tiles/InternetTile.java` | 示例 Tile | ⭐⭐⭐ |
| QSFactoryImpl | `qs/tileimpl/QSFactoryImpl.java` | Tile 工厂 | ⭐⭐⭐ |
| QSCustomizer | `qs/customize/QSCustomizer.java` | 自定义 | ⭐⭐⭐ |
| CustomTile | `qs/external/CustomTile.java` | 外部 Tile | ⭐⭐⭐ |

---

## 二十、相关文档

- `project-guide.md` - 项目整体指南
- `statusbar-module.md` - StatusBar 模块
- `volume-module.md` - Volume 模块
- `qs-tiles.md` - 官方 Tile 开发文档
- `dagger.md` - Dagger 依赖注入

---

*文档更新日期: 2026-01-27*
