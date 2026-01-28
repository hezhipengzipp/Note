# M1 NavigationBar 模块分析文档

## 一、模块概述

M1 NavigationBar 模块是 ATOTO M1 车机定制的导航栏组件，负责在导航栏区域显示各种状态图标和信息。主要功能包括：

- 天气和温度显示
- 方向指南针显示
- WiFi 状态显示
- 音频输出类型显示
- OBD（车载诊断）状态和故障码显示
- 外接电池状态显示
- SD 卡状态显示
- 音量静音状态显示

---

## 二、核心类关系图

```
┌─────────────────────────────────────────────────────────────────┐
│                    M1NavigationBarView                           │
│                   (自定义导航栏视图)                              │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ 实现接口:                                                │    │
│  │ - NavigationModeController.ModeChangedListener          │    │
│  │ - WeatherManager.WeatherListener                        │    │
│  │ - M1NavBarPresenter.NavIconListener                     │    │
│  │ - M1NavigationBarDataManager.OnObdUpdateListener        │    │
│  │ - WifiIconUpdater                                       │    │
│  │ - AudioOutTypeUpdater                                   │    │
│  └─────────────────────────────────────────────────────────┘    │
└────────────────────────────┬────────────────────────────────────┘
                             │
         ┌───────────────────┼───────────────────┐
         │                   │                   │
         ▼                   ▼                   ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────┐
│ M1NavBarPresenter│  │M1NavBarIconHolder│  │M1NavigationBarData- │
│ (传感器和天气)   │  │ (图标数据容器)   │  │     Manager          │
│                 │  │                 │  │ (硬件服务数据管理)    │
└─────────────────┘  └─────────────────┘  └─────────────────────┘
         │                                         │
         ▼                                         ▼
┌─────────────────┐                    ┌─────────────────────────┐
│  WeatherManager │                    │  AtotoHardwareService   │
│  (天气API管理)  │                    │  ├── CPAA Service       │
└─────────────────┘                    │  ├── OBD Service        │
                                       │  ├── BT Secondary       │
                                       │  └── MCU Service        │
                                       └─────────────────────────┘
```

---

## 三、文件结构

```
navigationbar/
├── M1NavigationBarView.java          # 主视图类 ⭐
├── M1NavigationBarDataManager.java   # 硬件数据管理器 ⭐
├── M1NavBarPresenter.java            # 传感器和天气数据处理
├── M1NavBarIconHolder.java           # 图标数据容器
├── M1NavUtils.java                   # 工具类
└── WeatherManager.java               # 天气管理器
```

---

## 四、M1NavigationBarView 详解

### 4.1 类定义

**文件**: `M1NavigationBarView.java`

```java
public class M1NavigationBarView extends LinearLayout implements
    NavigationModeController.ModeChangedListener,
    WeatherManager.WeatherListener,
    M1NavBarPresenter.NavIconListener,
    M1NavigationBarDataManager.OnObdUpdateListener,
    WifiIconUpdater,
    AudioOutTypeUpdater
```

### 4.2 核心属性

| 属性 | 类型 | 说明 |
|------|------|------|
| `MAX_ICONS` | int | 最大显示图标数量，固定为 5 |
| `slots` | List<String> | 图标插槽列表，定义图标顺序 |
| `navBarPresenter` | M1NavBarPresenter | 传感器和天气数据处理器 |
| `mWifiView` | StatusBarWifiView | WiFi 图标视图 |
| `mCurrentAudioOutType` | int | 当前音频输出类型 |
| `mObdDataEcuCodeItems` | ArrayList | OBD 故障码列表 |

### 4.3 支持的广播 Action

| Action | 说明 | 数据 |
|--------|------|------|
| `ACTION_WEATHER` | 天气更新 | value: 天气类型 |
| `ACTION_TEMPERATURE` | 温度更新 | value: 温度值, tempUnit: 单位 |
| `ACTION_DIRECTION` | 方向更新 | value: 角度 |
| `ACTION_EXT_BATTERY` | 外接电池 | value: 电量 |
| `ACTION_OBD_SWITCH` | OBD 开关 | value: 状态 |
| `ACTION_OBD_ERR` | OBD 故障 | index: 故障索引, value: 状态 |
| `ACTION_VOLUME_MUSIC` | 音量变化 | 系统标准音量广播 |
| `ACTION_WIFI_STATE` | WiFi 状态 | (兼容，已改用直接调用) |

### 4.4 图标显示逻辑

```
┌─────────────────────────────────────────────────────────┐
│              M1NavigationBarView (LinearLayout)          │
│  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐               │
│  │Icon1│ │Icon2│ │Icon3│ │Icon4│ │Icon5│  (最多5个)    │
│  └─────┘ └─────┘ └─────┘ └─────┘ └─────┘               │
│   ↑                                                     │
│   按 slots 数组顺序排列                                  │
└─────────────────────────────────────────────────────────┘
```

**图标排序规则**:
1. 图标顺序由 `config_navBarIcons_new` 资源数组定义
2. 每个图标有 `showIndex` 属性，根据在 slots 中的位置确定
3. 添加新图标时，通过 `calculateInsertIndex()` 计算正确的插入位置
4. 超过 `MAX_ICONS` 的图标会被隐藏 (`GONE`)

### 4.5 图标更新流程

```
广播/回调 触发
    ↓
创建 M1NavBarIconHolder
    ├── TYPE_TEXT: 文本类型（如温度 "25°C"）
    ├── TYPE_IMAGE: 图片类型（如天气图标）
    └── TYPE_VIEW: 自定义View类型（如WiFi信号View）
    ↓
设置 showIndex（根据 slots 顺序）
    ↓
调用 updateNavBarIcon(holder)
    ↓
┌─────────────────────────────────────────────┐
│ holder.isHidden() ?                         │
│    ├── true  → 移除对应 View                 │
│    └── false → 添加或更新 View               │
│                  ├── 不存在 → generateView() │
│                  │            → addView()    │
│                  └── 已存在 → updateView()   │
└─────────────────────────────────────────────┘
```

### 4.6 关键方法

```java
// 更新导航栏图标
private void updateNavBarIcon(M1NavBarIconHolder holder) {
    if (holder.isHidden()) {
        // 隐藏：移除对应View
        removeViewByTag(holder.getName());
    } else {
        // 显示：添加或更新View
        View oldView = findViewByTag(holder.getName());
        if (oldView == null) {
            View newView = generateView(holder);
            int addIndex = calculateInsertIndex(holder.getShowIndex());
            addView(newView, addIndex);
        } else {
            updateExistingView(oldView, holder);
        }
    }
}

// 根据类型生成View
private View generateView(M1NavBarIconHolder holder) {
    switch (holder.getType()) {
        case TYPE_TEXT:  return generateTextView(holder);
        case TYPE_IMAGE: return generateImageView(holder);
        case TYPE_VIEW:  return generateCustomView(holder);
    }
}

// 实现 WifiIconUpdater 接口
@Override
public void updateWifiIcon(WifiIconState state) {
    // 使用 StatusBarWifiView 显示WiFi状态
    if (mWifiView == null) {
        mWifiView = StatusBarWifiView.fromContext(getContext(), "wifi_nav");
    }
    mWifiView.applyWifiState(state);
    // 创建 TYPE_VIEW 类型的 holder
    M1NavBarIconHolder holder = new M1NavBarIconHolder(name, mWifiView, false);
    updateNavBarIcon(holder);
}

// 实现 AudioOutTypeUpdater 接口
@Override
public void updateAudioOutType(int type) {
    int drawableId = M1Utils.audioOutType2Drawable(type);
    M1NavBarIconHolder holder = new M1NavBarIconHolder(name, drawableId, false);
    updateNavBarIcon(holder);
}
```

---

## 五、M1NavigationBarDataManager 详解

### 5.1 类定义

**文件**: `M1NavigationBarDataManager.java`

```java
public class M1NavigationBarDataManager {
    // 单例模式
    private static volatile M1NavigationBarDataManager instance;

    public static M1NavigationBarDataManager getInstance(Context context) {
        if (null == instance) {
            synchronized (M1NavigationBarDataManager.class) {
                if (null == instance) {
                    instance = new M1NavigationBarDataManager(context);
                }
            }
        }
        return instance;
    }
}
```

### 5.2 管理的服务

| 服务 | 接口 | 功能 |
|------|------|------|
| **HardwareService** | IHardwareService | 主服务，管理子服务 |
| **CPAA Service** | ICpaaService | CarPlay/Android Auto 连接 |
| **OBD Service** | IObdService | 车载诊断服务 |
| **BT Secondary** | IBtSecondaryService | 外置蓝牙服务 |
| **MCU Service** | IMcuService | MCU 微控制器服务 |

### 5.3 服务连接流程

```
M1NavigationBarDataManager 构造
    ↓
mHandler.sendEmptyMessage(MSG_WAIT_BOOT_COMPLETION)
    ↓ (等待系统启动完成)
init()
    ↓
bindHardwareService(true)
    ↓
context.bindService(intent, mConnection, BIND_AUTO_CREATE)
    ↓
┌─────────────────────────────────────────────────────────┐
│ ServiceConnection.onServiceConnected()                  │
│    ↓                                                    │
│ mHardwareService = IHardwareService.Stub.asInterface()  │
│    ↓                                                    │
│ 获取子服务:                                              │
│ ├── CPAA: mHardwareService.getService(ACTION_CPAA)     │
│ ├── OBD:  mHardwareService.getService(ACTION_OBD)      │
│ ├── BT2:  mHardwareService.getService(ACTION_BT2)      │
│ └── MCU:  mHardwareService.getService(ACTION_MCU)      │
│    ↓                                                    │
│ 注册回调:                                               │
│ ├── mCpaaServiceCallback                               │
│ ├── mObdServiceCallback                                │
│ ├── mBtSecondaryServiceCallback                        │
│ └── mMcuServiceCallback                                │
└─────────────────────────────────────────────────────────┘
```

### 5.4 回调监听器接口

```java
// OBD 更新监听
public interface OnObdUpdateListener {
    void onObdUpdate(String type, Bundle bundle);
}

// CPAA 更新监听
public interface OnCpaaUpdateListener {
    void onCpaaUpdate(String type, Object object);
}

// 外置蓝牙更新监听
public interface OnBtSecondaryUpdateListener {
    void onBtSecondaryBatteryUpdate(int batteryLevel, boolean isShow);
    void onBtSecondaryConnectedUpdate(boolean isConnected);
}
```

### 5.5 回调类型常量

```java
public static class Contants {
    // OBD 回调类型
    public static final String CALLBACK_TYPE_ODB_CONNECT = "connectionInfoUpdate";
    public static final String CALLBACK_TYPE_ODB_DATA_UPDATE = "ecuDataUpdate";
    public static final String CALLBACK_TYPE_ODB_CODE_ADD = "ecuCodeAdd";
    public static final String CALLBACK_TYPE_ODB_CODE_UPDATE = "ecuCodeUpdate";
    public static final String CALLBACK_TYPE_ODB_CODE_REMOVE = "ecuCodeRemove";
    public static final String CALLBACK_TYPE_ODB_CODE_CLEAR = "ecuCodeClear";

    // CPAA 回调类型
    public static final String CALLBACK_TYPE_CPAA_CONNECT = "UpdateServiceState";
    public static final String CALLBACK_TYPE_CPAA_UPDATE_SERVICEINFO = "UpdateServiceInfo";
    public static final String CALLBACK_TYPE_CPAA_UPDATE_COMMSTATE = "UpdateCommState";
    public static final String CALLBACK_TYPE_CPAA_UPDATE_PHONESTATE = "UpdatePhoneState";
    public static final String CALLBACK_TYPE_CPAA_UPDATE_ROUTEGUIDANCE = "UpdateRouteGuidance";
    public static final String CALLBACK_TYPE_CPAA_UPDATE_MEDIAINFO = "onUpdateMediaInfo";
    public static final String CALLBACK_TYPE_CPAA_UPDATE_MEDIAPLAYBACK = "UpdateMediaPlayback";
}
```

### 5.6 OBD 服务回调处理

```java
private final IObdServiceCallback mObdServiceCallback = new IObdServiceCallback.Stub() {
    @Override
    public void onConnectionInfoUpdate(Bundle bundle) {
        // OBD 连接状态更新
        // connectionState: -1=失败, >=3=连接成功或正在连接
        mOnObdUpdateListener.onObdUpdate(CALLBACK_TYPE_ODB_CONNECT, bundle);
    }

    @Override
    public void onEcuDataUpdate(Bundle bundle) {
        // ECU 数据更新（如转速、车速等）
        mOnObdUpdateListener.onObdUpdate(CALLBACK_TYPE_ODB_DATA_UPDATE, bundle);
    }

    @Override
    public void onEcuCodeAdd(Bundle bundle) {
        // 新增故障码
        mOnObdUpdateListener.onObdUpdate(CALLBACK_TYPE_ODB_CODE_ADD, bundle);
    }

    @Override
    public void onEcuCodeUpdate(Bundle bundle) {
        // 故障码更新
        mOnObdUpdateListener.onObdUpdate(CALLBACK_TYPE_ODB_CODE_UPDATE, bundle);
    }

    @Override
    public void onEcuCodeRemove(Bundle bundle) {
        // 故障码移除
        mOnObdUpdateListener.onObdUpdate(CALLBACK_TYPE_ODB_CODE_REMOVE, bundle);
    }

    @Override
    public void onEcuCodeClear() {
        // 清除所有故障码
        mOnObdUpdateListener.onObdUpdate(CALLBACK_TYPE_ODB_CODE_CLEAR, null);
    }
};
```

### 5.7 CPAA 连接状态与 WiFi 互斥

```java
private final ICpaaServiceCallback mCpaaServiceCallback = new ICpaaServiceCallback.Stub() {
    @Override
    public void onUpdateServiceState(int i) {
        cpaaConnectState = i;
        // 当 CPAA 连接时，自动关闭 WiFi
        if (isCpaaConnected()) {
            disableWifiSwitch();
        }
    }
};

public boolean isCpaaConnected() {
    return (cpaaConnectState == 1 || cpaaConnectState == 2);
}

private void disableWifiSwitch() {
    WifiManager wifiManager = context.getSystemService(WifiManager.class);
    wifiManager.setWifiEnabled(false);
}
```

### 5.8 MCU 按键处理

```java
private final EventMcuHandler mEventMcuHandler = new EventMcuHandler() {
    @Override
    public void handleEventKeyCode(EventKeyCode event) {
        if (event.keyCode == EventKeyCode.KEY_CODE_POWER
            && event.action == EventKeyCode.ACTION_CLICK) {
            // 电源短按：切换屏幕亮灭
            if (isBrightNessSleep) {
                BrightNessManagerUtil.getInstance(context).wakeUpScreenByMcu();
            } else {
                BrightNessManagerUtil.getInstance(context).sleepScreenByMcu();
            }
        } else {
            // 其他按键：显示恢复出厂设置对话框
            showFactoryResetDialog();
        }
    }
};
```

---

## 六、M1NavBarIconHolder 详解

### 6.1 图标类型

| 类型常量 | 值 | 说明 | 示例 |
|---------|-----|------|------|
| `TYPE_TEXT` | 0 | 文本显示 | 温度 "25°C" |
| `TYPE_IMAGE` | 1 | 图片显示 | 天气图标 |
| `TYPE_VIEW` | 2 | 自定义View | WiFi信号View |

### 6.2 构造函数

```java
// 文本类型
public M1NavBarIconHolder(String name, String txtValue, boolean hidden)

// 图片类型
public M1NavBarIconHolder(String name, int imgId, boolean hidden)

// 自定义View类型
public M1NavBarIconHolder(String name, View customView, boolean hidden)
```

### 6.3 属性说明

| 属性 | 类型 | 说明 |
|------|------|------|
| `name` | String | 图标标识名称，用于查找和更新 |
| `type` | int | 图标类型 |
| `txtValue` | String | 文本内容（TYPE_TEXT） |
| `imgId` | int | 图片资源ID（TYPE_IMAGE） |
| `customView` | View | 自定义视图（TYPE_VIEW） |
| `hidden` | boolean | 是否隐藏 |
| `showIndex` | int | 显示顺序索引 |

---

## 七、M1NavBarPresenter 详解

### 7.1 功能

- 传感器数据处理（方向指南针）
- 天气数据获取和分发
- 位置服务管理

### 7.2 传感器处理

```java
// 使用旋转向量传感器获取方向
Sensor rotationVectorSensor = systemService.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

private SensorEventListener sensorEventListener = new SensorEventListener() {
    @Override
    public void onSensorChanged(SensorEvent event) {
        // 计算航向角（方向）
        float[] rotationMatrix = new float[9];
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVectorValus);

        float[] orientation = new float[3];
        SensorManager.getOrientation(rotationMatrix, orientation);

        float azimuthInRadians = orientation[0];
        float azimuthInDegrees = (float) Math.toDegrees(azimuthInRadians);
        azimuthInDegrees = (azimuthInDegrees + 360 - 90) % 360;

        // 通知监听器
        listener.onOrientataionChanged((int) Math.round(azimuthInDegrees));
    }
};
```

### 7.3 天气更新

```java
// WeatherManager 通过 GPS/网络定位获取位置
// 然后调用天气 API 获取天气数据

@Override
public void onWeatherUpdate(int tempUnit, Double temperature, int weather, int weatherId) {
    if (listener != null) {
        listener.onWeatherUpdate(tempUnit, temperature, weather, weatherId);
    }
}
```

---

## 八、数据流总结

### 8.1 硬件数据流

```
AtotoHardwareService (系统服务)
    │
    ├── OBD Service ────────────► M1NavigationBarDataManager
    │   └── 连接状态、故障码            │
    │                                  ▼
    ├── CPAA Service ───────────► OnObdUpdateListener
    │   └── CarPlay/AA 状态            │
    │                                  ▼
    ├── BT Secondary ───────────► M1NavigationBarView.onObdUpdate()
    │   └── 外置蓝牙电量                │
    │                                  ▼
    └── MCU Service ────────────► 发送广播 → updateNavBarIcon()
        └── 按键事件                    │
                                       ▼
                                  更新导航栏图标
```

### 8.2 传感器数据流

```
SensorManager
    │
    └── TYPE_ROTATION_VECTOR
            │
            ▼
    M1NavBarPresenter.onSensorChanged()
            │
            ▼
    计算航向角（0-360度）
            │
            ▼
    listener.onOrientataionChanged()
            │
            ▼
    M1NavigationBarView.onOrientataionChanged()
            │
            ▼
    发送 ACTION_DIRECTION 广播
            │
            ▼
    updateNavBarIcon() 更新方向图标
```

### 8.3 WiFi/音频数据流

```
StatusBarSignalPolicy
    │
    └── WiFi 状态变化
            │
            ▼
    WifiIconUpdater.updateWifiIcon()
            │
            ▼
    M1NavigationBarView.updateWifiIcon()
            │
            ▼
    创建/更新 StatusBarWifiView
            │
            ▼
    updateNavBarIcon() 更新 WiFi 图标

PhoneStatusBarPolicy
    │
    └── 音频输出类型变化
            │
            ▼
    AudioOutTypeUpdater.updateAudioOutType()
            │
            ▼
    M1NavigationBarView.updateAudioOutType()
            │
            ▼
    updateNavBarIcon() 更新音频输出图标
```

---

## 九、配置项

### 9.1 资源配置

```xml
<!-- 导航栏图标顺序配置 -->
<string-array name="config_navBarIcons_new">
    <item>@string/nav_wifi</item>
    <item>@string/nav_audio_out</item>
    <item>@string/nav_weather</item>
    <item>@string/nav_temperature</item>
    <item>@string/nav_direction</item>
    <item>@string/nav_ext_battery</item>
    <item>@string/nav_obd_switch</item>
    <item>@string/nav_sdcard_unmountable</item>
    <item>@string/nav_volume_music</item>
</string-array>

<!-- 天气和温度显示模式 -->
<integer name="config_weatherAndTemperatureDisplayMode">1</integer>
<!-- 0: 不显示天气和温度 -->
<!-- 1: 显示天气和温度 -->
```

### 9.2 系统属性

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `persist.sys.location.test` | true | 使用旋转向量传感器 |
| `persist.set.bt2_name_custom.timer` | 25000 | BT2 名称设置延迟（ms） |
| `persist.sys.default_ext_bt_name` | "M1_Smart_link" | 默认 BT2 名称 |
| `persist.systemui.weatherAndTemperatureDisplayMode.display_mode` | 0 | 调试用天气显示模式 |

---

## 十、调试方法

### 10.1 日志标签

```java
TAG = "M1NavigationBarView"
TAG = "M1NavigationBarDataManager"
TAG = "M1NavBarPresenter"
```

### 10.2 ADB 命令

```bash
# 查看导航栏相关日志
adb logcat -s M1NavigationBarView M1NavigationBarDataManager M1NavBarPresenter

# 模拟发送天气广播
adb shell am broadcast -a com.android.systemui.customization.ACTION_WEATHER --ei value 1

# 模拟发送温度广播
adb shell am broadcast -a com.android.systemui.customization.ACTION_TEMPERATURE --ei value 25 --ei tempUnit 0

# 查看 OBD 服务状态
adb shell dumpsys activity service com.atoto.hardware.service.AtotoHardwareService
```

---

## 十一、常见修改场景

| 需求 | 修改位置 |
|------|---------|
| 添加新的状态图标 | 1. `config_navBarIcons_new` 添加 slot<br>2. 定义新的 ACTION<br>3. 在 `mReceiver` 中处理 |
| 修改图标顺序 | 修改 `config_navBarIcons_new` 数组顺序 |
| 修改最大图标数量 | 修改 `MAX_ICONS` 常量 |
| 添加新的硬件服务监听 | 在 `M1NavigationBarDataManager` 中添加新的回调接口 |
| 修改图标样式/大小 | 修改 `generateImageView()` 或 `generateTextView()` |
| 禁用天气显示 | 设置 `config_weatherAndTemperatureDisplayMode` 为 0 |

---

## 十二、关键文件速查表

| 文件 | 功能 | 优先级 |
|------|------|--------|
| `M1NavigationBarView.java` | 导航栏视图，图标显示逻辑 | ⭐⭐⭐⭐⭐ |
| `M1NavigationBarDataManager.java` | 硬件服务数据管理 | ⭐⭐⭐⭐⭐ |
| `M1NavBarIconHolder.java` | 图标数据容器 | ⭐⭐⭐⭐ |
| `M1NavBarPresenter.java` | 传感器和天气处理 | ⭐⭐⭐ |
| `M1NavUtils.java` | 工具类 | ⭐⭐⭐ |
| `WeatherManager.java` | 天气API管理 | ⭐⭐ |

---

## 十三、相关文档

- `project-guide.md` - 项目整体指南
- `statusbar-module.md` - StatusBar 模块分析
- `notification-module.md` - Notification 模块分析

---

*文档更新日期: 2026-01-28*
