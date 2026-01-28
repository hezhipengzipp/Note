# PullDownView 模块分析文档

## 一、模块概述

PullDownView 模块是 ATOTO M1 车机定制的下拉快捷设置面板，替代了标准 Android 的通知栏下拉面板。主要功能包括：

- 自定义下拉快捷设置界面
- 快捷开关（WiFi、蓝牙、热点、亮度等）
- 圆形屏幕适配（480×480）
- 弧形滚动指示器
- 手势控制（从顶部下滑打开，从底部上滑关闭）

---

## 二、文件结构

```
statusbar/pulldownview/
├── 核心控制器
│   ├── PullDownWindowController.java        # 控制器接口
│   └── PullDownWindowControllerImpl.java    # 控制器实现 ⭐
│
├── 基础组件
│   ├── AlphaImageView.java                  # 带按压效果的基类 ⭐
│   ├── M1FrameLayout.java                   # 支持按键监听的容器
│   ├── MyLinearLayout.java                  # 自定义 LinearLayout
│   ├── ArcScrollIndicator.java              # 弧形滚动指示器 ⭐
│   └── RecycleGridDivider.java              # 网格分隔线
│
├── 快捷开关 View
│   ├── WifiView.java                        # WiFi 开关
│   ├── BluetoothView.java                   # 蓝牙开关
│   ├── HotSpotView.java                     # 热点开关
│   ├── BrightNessView.java                  # 亮度调节
│   ├── BrightNessAutoView.java              # 自动亮度开关
│   ├── VideoRecordView.java                 # 行车记录仪开关
│   ├── SettingsView.java                    # 设置入口
│   ├── InternetView.java                    # 网络设置
│   ├── CpaaEnterView.java                   # CarPlay/AA 入口
│   └── DriveChatView.java                   # 驾驶通话
│
├── 工具类
│   ├── BrightNessManagerUtil.java           # 亮度管理工具 ⭐
│   ├── AudioOutputManagerUtil.java          # 音频输出管理
│   └── Constants.java                       # 常量定义
│
└── 布局文件
    └── res/layout/status_bar_expanded2.xml  # 面板布局
```

---

## 三、核心架构

### 3.1 类关系图

```
┌─────────────────────────────────────────────────────────────────┐
│                 PullDownWindowControllerImpl                     │
│                    (Dagger 单例注入)                             │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ - 管理窗口的显示/隐藏                                       │ │
│  │ - 处理手势广播                                              │ │
│  │ - 管理 RecyclerView 的快捷开关                              │ │
│  │ - 更新时间显示                                              │ │
│  │ - 管理状态栏图标                                            │ │
│  └────────────────────────────────────────────────────────────┘ │
└───────────────────────────────┬─────────────────────────────────┘
                                │
          ┌─────────────────────┼─────────────────────┐
          │                     │                     │
          ▼                     ▼                     ▼
┌─────────────────┐   ┌─────────────────┐   ┌─────────────────────┐
│  M1FrameLayout  │   │   RecyclerView  │   │  ArcScrollIndicator │
│  (容器视图)      │   │  (快捷开关网格)  │   │   (弧形进度条)      │
└─────────────────┘   └────────┬────────┘   └─────────────────────┘
                               │
            ┌──────────────────┼──────────────────┐
            │                  │                  │
            ▼                  ▼                  ▼
    ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
    │ AlphaImageView│   │ AlphaImageView│   │ AlphaImageView│
    │  (WifiView)  │   │(BluetoothView)│   │(BrightNessView)│
    └──────────────┘   └──────────────┘   └──────────────┘
```

### 3.2 窗口层级

```
WindowManager
    │
    └── TYPE_NOTIFICATION_SHADE (窗口类型)
            │
            └── M1FrameLayout (id: pullDownView)
                    │
                    ├── StatusIconContainer (状态图标)
                    ├── TextView (时钟)
                    ├── RecyclerView (快捷开关网格)
                    └── ArcScrollIndicator (滚动进度)
```

---

## 四、PullDownWindowControllerImpl 详解

### 4.1 类定义

**文件**: `PullDownWindowControllerImpl.java`

```java
@SysUISingleton
public class PullDownWindowControllerImpl implements PullDownWindowController, Dumpable {
    // Dagger 注入
    @Inject
    public PullDownWindowControllerImpl(
        Context mContext,
        StatusBarIconController statusBarIconController,
        FeatureFlags featureFlags,
        StatusBarIconController.DarkIconManager.Factory darkIconManagerFactory
    )
}
```

### 4.2 手势广播监听

| 广播 Action | 说明 | 处理 |
|-------------|------|------|
| `SWIPE_FROM_TOP` | 从顶部下滑 | 显示面板 |
| `SWIPE_FROM_BOTTOM` | 从底部上滑 | 隐藏面板 |
| `ACTION_TIME_TICK` | 每分钟时间更新 | 更新时钟 |
| `ACTION_TIME_CHANGED` | 时间改变 | 更新时钟 |
| `ACTION_TIMEZONE_CHANGED` | 时区改变 | 更新时间格式 |
| `ACTION_LOCALE_CHANGED` | 语言改变 | 更新时间格式 |

### 4.3 窗口显示/隐藏动画

```java
public void updateVisibility(boolean show, boolean animation) {
    if (animation) {
        if (show) {
            // 从上向下滑入
            ObjectAnimator.ofFloat(mView, "translationY", -height, 0)
                .setDuration(300).start();
        } else {
            // 从下向上滑出
            ObjectAnimator.ofFloat(mView, "translationY", 0, -height)
                .setDuration(300).start();
        }
    } else {
        mView.setVisibility(show ? VISIBLE : GONE);
    }
}
```

### 4.4 显示条件检查

```java
public boolean isEnableShowUi() {
    // 正在通话时不显示
    boolean isCalling = M1NavigationBarDataManager.getInstance(mContext)
        .isCpaaConnectedAndCalling();
    // 用户设置未完成时不显示
    boolean isUserSetupComplete = M1NavigationBarDataManager.getInstance(mContext)
        .isUserSetup();

    return !(isCalling || !isUserSetupComplete);
}
```

### 4.5 快捷开关动态加载

```java
// 从资源配置读取快捷开关类名
String[] clss = mContext.getResources().getStringArray(R.array.pulldown_setting_cls);

// RecyclerView Adapter
public void onBindViewHolder(ViewHolder holder, int position) {
    // 反射创建 View 实例
    Class<?> aClass = getClass().getClassLoader().loadClass(clss[position]);
    Constructor<?> constructor = aClass.getConstructor(Context.class);
    AlphaImageView view = (AlphaImageView) constructor.newInstance(mContext);
    view.setmController(PullDownWindowControllerImpl.this);
    ((ViewGroup) holder.itemView).addView(view);
}
```

### 4.6 弧形滚动指示器配置

```java
private void initArcScrollIndicator() {
    // 圆形屏幕 480×480，内切圆半径 240dp
    // 右侧垂直弧形：起始角度 -30°，扫过 60°
    mArcScrollIndicator.setArcAngles(-30f, 60f);
    mArcScrollIndicator.setArcWidth(8f);
    mArcScrollIndicator.setArcColor(0xFF4A90E2, 0x33FFFFFF);
    mArcScrollIndicator.setSliderMode(true);    // 滑块模式
    mArcScrollIndicator.setSliderLength(20f);   // 滑块长度 20 度
}
```

---

## 五、AlphaImageView 基类

### 5.1 功能

- 按下时有 10% 透明度变化的视觉反馈
- 提供点击和长按回调
- 提供跳转应用的工具方法

### 5.2 代码结构

```java
public class AlphaImageView extends ImageView
    implements View.OnClickListener, View.OnLongClickListener {

    private PullDownWindowController mController;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case ACTION_DOWN:
                setAlpha(0.9f);  // 按下变暗
                break;
            case ACTION_CANCEL:
            case ACTION_UP:
                setAlpha(1f);    // 恢复正常
                break;
        }
        return super.onTouchEvent(event);
    }

    // 子类重写这些方法实现功能
    @Override
    public void onClick(View v) { }

    @Override
    public boolean onLongClick(View v) { return false; }

    // 跳转应用的工具方法
    protected static Intent getOpenIntentByPackageName(Context context, String packageName);
}
```

---

## 六、快捷开关 View 详解

### 6.1 通用模式

所有快捷开关都继承 `AlphaImageView`，遵循相同模式：

```
┌─────────────────────────────────────────────────┐
│                 XxxView                          │
├─────────────────────────────────────────────────┤
│ 构造函数:                                        │
│   - 初始化图标状态                               │
│   - 注册状态监听                                 │
├─────────────────────────────────────────────────┤
│ onClick():                                       │
│   - 切换开关状态                                 │
├─────────────────────────────────────────────────┤
│ onLongClick():                                   │
│   - 跳转到详细设置页面                           │
│   - 关闭下拉面板                                 │
├─────────────────────────────────────────────────┤
│ 状态监听回调:                                    │
│   - 更新图标（开/关）                            │
└─────────────────────────────────────────────────┘
```

### 6.2 各快捷开关功能

| 类名 | 功能 | 点击 | 长按 |
|------|------|------|------|
| **WifiView** | WiFi 开关 | 开/关 WiFi | 跳转 WiFi 设置 |
| **BluetoothView** | 蓝牙开关 | 开/关蓝牙 | 跳转蓝牙设置 |
| **HotSpotView** | 热点开关 | 开/关热点 | 跳转热点设置 |
| **BrightNessView** | 亮度调节 | 循环切换亮度档位 | - |
| **BrightNessAutoView** | 自动亮度 | 开/关自动亮度 | - |
| **VideoRecordView** | 行车记录 | 开/关录制 | 跳转 DVR 应用 |
| **SettingsView** | 设置入口 | 打开设置应用 | - |
| **CpaaEnterView** | CarPlay/AA | 打开 CPAA 应用 | - |

### 6.3 WifiView 示例

```java
public class WifiView extends AlphaImageView {

    public WifiView(Context context) {
        super(context);
        // 初始化状态
        updateWifiState(wifiManager.isWifiEnabled());

        // 注册广播监听
        context.registerReceiver(mReceiver,
            new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION));
    }

    @Override
    public void onClick(View v) {
        // 检查 CPAA 连接状态（连接时禁止操作 WiFi）
        if (M1NavigationBarDataManager.getInstance(getContext()).isCpaaConnected()) {
            return;
        }
        // 切换 WiFi 状态
        wifiManager.setWifiEnabled(!wifiManager.isWifiEnabled());
    }

    @Override
    public boolean onLongClick(View v) {
        // 跳转 WiFi 设置页面
        Intent intent = new Intent();
        intent.setClassName("com.android.settings",
            "com.android.settings.customized.wifi.WifiSettings");
        getContext().startActivity(intent);
        // 关闭下拉面板
        getmController().updateVisibility(false, false);
        return true;
    }

    private void updateWifiState(boolean enabled) {
        setImageResource(enabled ? R.drawable.wifi_on : R.drawable.wifi_off);
    }
}
```

### 6.4 BrightNessView 亮度调节

```java
public class BrightNessView extends AlphaImageView {

    @Override
    public void onClick(View v) {
        // 循环调节亮度（85 → 170 → 255 → 85）
        BrightNessManagerUtil.getInstance(getContext()).adjustBrightness(true);
        updateBrightness(getBrightness());
    }

    private boolean updateBrightness(int brightness) {
        // 根据亮度值显示不同图标
        if (brightness <= 85) {
            setImageResource(R.drawable.bright_ness_l);       // 低亮度
        } else if (brightness <= 170) {
            setImageResource(R.drawable.bright_ness_m);       // 中亮度
        } else {
            setImageResource(R.drawable.bright_ness_h);       // 高亮度
        }
    }
}
```

---

## 七、BrightNessManagerUtil 工具类

### 7.1 功能

- 亮度调节（手动/自动）
- MCU 灭屏/唤醒控制
- 广播监听处理

### 7.2 核心方法

| 方法 | 功能 |
|------|------|
| `getBrightness()` | 获取当前亮度 (0-255) |
| `setBrightness(int)` | 设置亮度 |
| `adjustBrightness(boolean)` | 循环调节亮度 |
| `setAutoBrightness(boolean)` | 设置自动亮度模式 |
| `getMode()` | 获取亮度模式 |
| `sleepScreenByMcu()` | MCU 灭屏 |
| `wakeUpScreenByMcu()` | MCU 唤醒 |

### 7.3 亮度调节逻辑

```java
public void adjustBrightness(boolean isAdjustUp) {
    setAutoBrightness(false);  // 手动控制时关闭自动调节

    int brightness = getBrightness();
    if (isAdjustUp) {
        brightness += 85;       // 增加亮度
    } else {
        brightness -= 85;       // 降低亮度
    }

    // 循环：255 → 85 → 170 → 255
    if (brightness > 255) {
        brightness = 85;
    } else if (brightness < 0) {
        brightness = 255;
    }

    setBrightness(brightness);
}
```

### 7.4 MCU 灭屏控制

```java
// 灭屏文件路径
private static final String MCU_SLEEP_OR_WAKEUP_FILE_PATH =
    "/sys/class/backlight/panel0-backlight/brightness";

public void sleepScreenByMcu() {
    // 保存当前亮度模式，灭屏时恢复
    lastBrightNessModeBeforeMcuSleep = getMode();
    if (lastBrightNessModeBeforeMcuSleep == SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
        setAutoBrightness(false);
    }

    // 写入 0 到背光文件实现灭屏
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
        writer.write("0");
    }
}

public void wakeUpScreenByMcu() {
    // 恢复之前的亮度模式
    setAutoBrightness(lastMode == SCREEN_BRIGHTNESS_MODE_AUTOMATIC);

    // 写入当前亮度值到背光文件
    int brightness = getBrightness();
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
        writer.write(String.valueOf(brightness));
    }
}
```

### 7.5 广播常量

```java
public class Constants {
    // 亮度控制广播
    public static final String ACTION_SETTING_BRIGHTNESS_UP =
        "com.atoto.setting.brightness.up";
    public static final String ACTION_SETTING_BRIGHTNESS_DOWN =
        "com.atoto.setting.brightness.down";
    public static final String ACTION_SETTING_BRIGHTNESS_AUTO_ENABLE =
        "com.atoto.setting.brightness.auto.enable";
    public static final String ACTION_SETTING_BRIGHTNESS_AUTO_DISABLE =
        "com.atoto.setting.brightness.auto.disable";

    // MCU 灭屏/唤醒
    public static final String ACTION_SETTING_BRIGHTNESS_SLEEP =
        "com.atoto.setting.brightness.sleep";
    public static final String ACTION_SETTING_BRIGHTNESS_WAKEUP =
        "com.atoto.setting.brightness.wakeup";
}
```

---

## 八、ArcScrollIndicator 弧形滚动指示器

### 8.1 功能

- 适配圆形屏幕的弧形进度条
- 支持滑块模式和递增模式
- 平滑动画效果

### 8.2 显示模式

```
滑块模式 (SliderMode = true):                递增模式 (SliderMode = false):
固定长度的滑块在轨道上滑动                    从起点开始递增的进度条

    ╭─────────────╮                              ╭─────────────╮
   ╱               ╲                            ╱               ╲
  │      ▬▬        │  ← 滑块                  │   ▬▬▬▬▬▬▬     │  ← 进度
  │     ○   ○      │                          │     ○   ○      │
  │                │                          │                │
   ╲               ╱                            ╲               ╱
    ╰─────────────╯                              ╰─────────────╯
```

### 8.3 核心属性

| 属性 | 类型 | 说明 |
|------|------|------|
| `mStartAngle` | float | 起始角度（度） |
| `mSweepAngle` | float | 扫过角度（度） |
| `mArcWidth` | float | 弧线宽度（dp） |
| `mProgress` | float | 当前进度 (0-1) |
| `mSliderMode` | boolean | 是否滑块模式 |
| `mSliderLength` | float | 滑块长度（度） |
| `mArcColor` | int | 进度条颜色 |
| `mTrackColor` | int | 轨道颜色 |

### 8.4 绘制逻辑

```java
@Override
protected void onDraw(Canvas canvas) {
    // 1. 绘制轨道（完整弧形背景）
    canvas.drawArc(mArcRect, mStartAngle, mSweepAngle, false, mTrackPaint);

    // 2. 根据模式绘制进度
    if (mSliderMode) {
        drawSlider(canvas);       // 滑块模式
    } else {
        drawProgressBar(canvas);  // 递增模式
    }
}

private void drawSlider(Canvas canvas) {
    // 计算滑块可滑动范围
    float availableRange = mSweepAngle - mSliderLength;

    // 计算滑块起始角度（随进度移动）
    float sliderStartAngle = mStartAngle + (availableRange * mProgress);

    // 绘制固定长度滑块
    canvas.drawArc(mArcRect, sliderStartAngle, mSliderLength, false, mArcPaint);
}
```

---

## 九、M1FrameLayout 容器

### 9.1 功能

- 支持按键事件监听
- 处理返回键关闭面板

### 9.2 代码

```java
public class M1FrameLayout extends FrameLayout {
    private OnKeyEventListner onKeyEventListner;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (onKeyEventListner != null) {
            return onKeyEventListner.onKeyEvent(event);
        }
        return super.dispatchKeyEvent(event);
    }

    public interface OnKeyEventListner {
        boolean onKeyEvent(KeyEvent event);
    }
}
```

### 9.3 返回键处理

```java
// 在 PullDownWindowControllerImpl 中
private OnKeyEventListner onKeyEventListner = event -> {
    if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
        if (event.getAction() == KeyEvent.ACTION_UP) {
            updateVisibility(false, true);  // 关闭面板
        }
        return true;
    }
    return false;
};
```

---

## 十、数据流总结

### 10.1 显示流程

```
用户从顶部下滑
    ↓
系统发送 SWIPE_FROM_TOP 广播
    ↓
PullDownWindowControllerImpl.mRecevier 接收
    ↓
检查 isEnableShowUi()
    ├── 正在通话 → 不显示
    └── 用户未设置完成 → 不显示
    ↓
updateVisibility(true, true)
    ├── 设置 View 可见
    ├── 执行下滑动画
    ├── 注册按键监听
    ├── 更新时钟显示
    └── 重置滚动位置
```

### 10.2 快捷开关操作流程

```
用户点击快捷开关
    ↓
AlphaImageView.onTouchEvent()
    ├── ACTION_DOWN → setAlpha(0.9f)
    └── ACTION_UP → setAlpha(1f)
    ↓
XxxView.onClick()
    ├── 执行开关操作
    └── 更新图标状态
    ↓
状态变化广播
    ↓
BroadcastReceiver 接收
    ↓
updateXxxState() 更新图标
```

### 10.3 关闭流程

```
用户操作
    ├── 从底部上滑 → SWIPE_FROM_BOTTOM 广播
    ├── 按返回键 → M1FrameLayout.dispatchKeyEvent()
    └── 长按跳转设置 → getmController().updateVisibility(false)
    ↓
updateVisibility(false, true)
    ├── 执行上滑动画
    └── 设置 View 不可见
```

---

## 十一、配置项

### 11.1 快捷开关配置

```xml
<!-- res/values/arrays.xml -->
<string-array name="pulldown_setting_cls">
    <item>com.android.systemui.statusbar.pulldownview.WifiView</item>
    <item>com.android.systemui.statusbar.pulldownview.BluetoothView</item>
    <item>com.android.systemui.statusbar.pulldownview.HotSpotView</item>
    <item>com.android.systemui.statusbar.pulldownview.BrightNessView</item>
    <item>com.android.systemui.statusbar.pulldownview.BrightNessAutoView</item>
    <item>com.android.systemui.statusbar.pulldownview.VideoRecordView</item>
    <item>com.android.systemui.statusbar.pulldownview.SettingsView</item>
    <!-- 按需添加更多快捷开关 -->
</string-array>
```

### 11.2 布局配置

```xml
<!-- res/layout/status_bar_expanded2.xml -->
<M1FrameLayout id="pullDownView">
    <StatusIconContainer id="statusIcons"/>
    <TextView id="tvClock"/>
    <RecyclerView id="recycleView"
        layoutManager="GridLayoutManager"
        spanCount="3"/>
    <ArcScrollIndicator id="arcScrollIndicator"/>
</M1FrameLayout>
```

---

## 十二、调试方法

### 12.1 日志标签

```java
TAG = "PullDownWindowControlle"
TAG = "BrigttNessUtils"
TAG = "WifiView"
TAG = "BlueboothView"
TAG = "HotSpotView"
TAG = "VideoRecordView"
TAG = "ArcScrollIndicator"
```

### 12.2 ADB 命令

```bash
# 模拟从顶部下滑
adb shell am broadcast -a android.intent.action.SWIPE_FROM_TOP

# 模拟从底部上滑
adb shell am broadcast -a android.intent.action.SWIPE_FROM_BOTTOM

# 调节亮度
adb shell am broadcast -a com.atoto.setting.brightness.up
adb shell am broadcast -a com.atoto.setting.brightness.down

# MCU 灭屏/唤醒
adb shell am broadcast -a com.atoto.setting.brightness.sleep
adb shell am broadcast -a com.atoto.setting.brightness.wakeup

# 查看亮度值
adb shell cat /sys/class/backlight/panel0-backlight/brightness
adb shell settings get system screen_brightness
```

---

## 十三、常见修改场景

| 需求 | 修改位置 |
|------|---------|
| 添加新的快捷开关 | 1. 创建继承 `AlphaImageView` 的类<br>2. 添加到 `pulldown_setting_cls` 数组 |
| 修改快捷开关顺序 | 调整 `pulldown_setting_cls` 数组顺序 |
| 修改弧形进度条样式 | `ArcScrollIndicator` 或 `initArcScrollIndicator()` |
| 修改下拉动画时长 | `updateVisibility()` 中的 `setDuration()` |
| 修改亮度档位 | `BrightNessManagerUtil.adjustBrightness()` |
| 禁止通话时显示 | `isEnableShowUi()` 方法 |
| 修改面板布局 | `status_bar_expanded2.xml` |

---

## 十四、关键文件速查表

| 文件 | 功能 | 优先级 |
|------|------|--------|
| `PullDownWindowControllerImpl.java` | 核心控制器 | ⭐⭐⭐⭐⭐ |
| `AlphaImageView.java` | 快捷开关基类 | ⭐⭐⭐⭐⭐ |
| `BrightNessManagerUtil.java` | 亮度管理工具 | ⭐⭐⭐⭐ |
| `ArcScrollIndicator.java` | 弧形进度条 | ⭐⭐⭐⭐ |
| `WifiView.java` | WiFi 开关示例 | ⭐⭐⭐ |
| `M1FrameLayout.java` | 按键监听容器 | ⭐⭐⭐ |
| `Constants.java` | 常量定义 | ⭐⭐ |
| `status_bar_expanded2.xml` | 面板布局 | ⭐⭐⭐ |

---

## 十五、相关文档

- `project-guide.md` - 项目整体指南
- `statusbar-module.md` - StatusBar 模块分析
- `m1-navigationbar-module.md` - M1 NavigationBar 模块分析

---

*文档更新日期: 2026-01-28*