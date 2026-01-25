# Android Framework/SystemUI 系统开发快速入门指南

> 从 Android 应用开发转向 Framework/SystemUI 系统开发的学习路径

---

## 一、核心差异

| 方面 | App 开发 | SystemUI/Framework 开发 |
|------|----------|------------------------|
| 代码位置 | 独立项目 | AOSP 源码树 |
| 编译方式 | Gradle | Soong/Make |
| 权限 | 普通/签名权限 | 系统权限 (platform 签名) |
| 进程 | 独立进程 | system_server / systemui 进程 |
| 调试 | Android Studio | AS + adb + logcat |

---

## 二、环境准备

### 2.1 下载 AOSP 源码

```bash
# 安装 repo
mkdir ~/bin
curl https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo
chmod a+x ~/bin/repo

# 下载源码 (以 Android 14 为例)
mkdir aosp && cd aosp
repo init -u https://android.googlesource.com/platform/manifest -b android-14.0.0_r1
repo sync -c -j8
```

### 2.2 或者只下载 SystemUI 模块

```bash
# 单独克隆 frameworks/base
git clone https://android.googlesource.com/platform/frameworks/base

# SystemUI 位置
# frameworks/base/packages/SystemUI/
```

### 2.3 硬件要求

| 配置 | 最低要求 | 推荐配置 |
|------|----------|----------|
| CPU | 4 核 | 8 核以上 |
| 内存 | 16 GB | 32 GB 以上 |
| 硬盘 | 250 GB SSD | 500 GB SSD |
| 系统 | Ubuntu 18.04+ | Ubuntu 20.04/22.04 |

---

## 三、SystemUI 核心结构

```
frameworks/base/packages/SystemUI/
├── src/com/android/systemui/
│   ├── SystemUIApplication.java    # 入口
│   ├── SystemUIService.java        # 主服务
│   │
│   ├── statusbar/                  # 状态栏 ⭐
│   │   ├── phone/
│   │   │   └── StatusBar.java      # 状态栏核心类
│   │   └── notification/           # 通知
│   │
│   ├── qs/                         # 快捷设置 (Quick Settings) ⭐
│   │   ├── QSPanel.java
│   │   └── tiles/                  # 各种开关 Tile
│   │
│   ├── navigationbar/              # 导航栏
│   ├── keyguard/                   # 锁屏
│   ├── recents/                    # 最近任务
│   ├── volume/                     # 音量面板
│   ├── power/                      # 电源菜单
│   └── dagger/                     # 依赖注入
│
├── res/                            # 资源文件
└── AndroidManifest.xml
```

### 3.1 核心类说明

| 类名 | 路径 | 功能 |
|------|------|------|
| `SystemUIApplication` | `/systemui/` | SystemUI 启动入口 |
| `SystemUIService` | `/systemui/` | 主服务，管理各组件生命周期 |
| `StatusBar` | `/statusbar/phone/` | 状态栏核心逻辑 |
| `QSPanel` | `/qs/` | 快捷设置面板 |
| `QSTileImpl` | `/qs/tileimpl/` | 快捷开关基类 |
| `NotificationStackScrollLayout` | `/statusbar/notification/` | 通知列表容器 |
| `KeyguardViewMediator` | `/keyguard/` | 锁屏控制器 |
| `NavigationBarView` | `/navigationbar/` | 导航栏视图 |

---

## 四、快速上手路径

### 第一周：熟悉编译和调试

#### 4.1 编译 SystemUI

```bash
# 1. 初始化编译环境
source build/envsetup.sh

# 2. 选择编译目标
lunch aosp_x86_64-eng  # 模拟器
# 或
lunch aosp_arm64-eng   # ARM 设备

# 3. 编译 SystemUI
make SystemUI -j8
# 或使用 mm 单独编译
cd frameworks/base/packages/SystemUI
mm -j8
```

#### 4.2 推送到设备

```bash
# 获取 root 权限
adb root
adb remount

# 推送 SystemUI
adb push out/target/product/xxx/system/priv-app/SystemUI /system/priv-app/

# 重启 SystemUI
adb shell pkill -f systemui
```

#### 4.3 查看日志

```bash
# 查看 SystemUI 日志
adb logcat -s SystemUI:*

# 查看所有相关日志
adb logcat | grep -E "SystemUI|StatusBar|QSTile"

# 清除并重新查看
adb logcat -c && adb logcat -s SystemUI:*
```

---

### 第二周：修改一个简单功能

#### 示例：修改状态栏时间格式

```java
// 文件: frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/policy/Clock.java

@Override
protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    // 修改时间格式，添加秒显示
    mClockFormat = new SimpleDateFormat("HH:mm:ss");
}
```

#### 示例：修改状态栏背景颜色

```xml
<!-- 文件: frameworks/base/packages/SystemUI/res/values/colors.xml -->
<color name="status_bar_background">#FF1A1A1A</color>
```

---

### 第三周：添加一个 Quick Settings Tile

#### 3.1 创建自定义 Tile

```java
// 文件: frameworks/base/packages/SystemUI/src/com/android/systemui/qs/tiles/MyCustomTile.java

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
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = "My Tile";
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
        return "My Custom Tile";
    }
}
```

#### 3.2 注册 Tile

在 Dagger 模块中注册：

```java
// 文件: frameworks/base/packages/SystemUI/src/com/android/systemui/qs/tileimpl/QSFactoryImpl.java

// 在 createTileInternal 方法中添加
case "mycustom":
    return mMyCustomTileProvider.get();
```

#### 3.3 添加到默认 Tiles

```xml
<!-- 文件: frameworks/base/packages/SystemUI/res/values/config.xml -->
<string name="quick_settings_tiles_default" translatable="false">
    wifi,bt,dnd,flashlight,rotation,battery,cell,airplane,mycustom
</string>
```

---

## 五、关键知识点

### 5.1 Dagger 依赖注入

SystemUI 大量使用 Dagger 进行依赖注入：

```java
// 定义组件
@SysUISingleton
@Component(modules = {SystemUIModule.class, DependencyProvider.class})
public interface SystemUIComponent {
    void inject(SystemUIApplication app);
    
    @Component.Builder
    interface Builder {
        @BindsInstance
        Builder context(Context context);
        SystemUIComponent build();
    }
}

// 使用注入
public class MyClass {
    @Inject
    StatusBarManager mStatusBarManager;
    
    @Inject
    NotificationManager mNotificationManager;
    
    @Inject
    public MyClass() {
        // 依赖会自动注入
    }
}
```

### 5.2 系统服务交互

```java
// 获取系统服务
StatusBarManager sbm = context.getSystemService(StatusBarManager.class);
NotificationManager nm = context.getSystemService(NotificationManager.class);
WindowManager wm = context.getSystemService(WindowManager.class);
PowerManager pm = context.getSystemService(PowerManager.class);

// AIDL 跨进程通信
IStatusBarService statusBarService = IStatusBarService.Stub.asInterface(
    ServiceManager.getService(Context.STATUS_BAR_SERVICE));

// 调用系统服务方法
statusBarService.expandNotificationsPanel();
statusBarService.collapsePanels();
```

### 5.3 Handler 和消息机制

```java
// SystemUI 中常见的 Handler 使用
private final Handler mHandler = new Handler(Looper.getMainLooper());

private void postUpdateState() {
    mHandler.post(() -> {
        updateState();
    });
}

private void postDelayedAction() {
    mHandler.postDelayed(() -> {
        doSomething();
    }, 1000);
}
```

### 5.4 广播监听

```java
// 监听系统广播
private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_SCREEN_ON.equals(action)) {
            onScreenOn();
        } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            onScreenOff();
        }
    }
};

// 注册
IntentFilter filter = new IntentFilter();
filter.addAction(Intent.ACTION_SCREEN_ON);
filter.addAction(Intent.ACTION_SCREEN_OFF);
context.registerReceiver(mReceiver, filter);
```

---

## 六、常用调试命令

### 6.1 SystemUI 相关

```bash
# 重启 SystemUI
adb shell pkill -f systemui
# 或
adb shell am crash com.android.systemui
# 或
adb shell killall com.android.systemui

# 查看 SystemUI 进程
adb shell ps -A | grep systemui

# dump SystemUI 状态
adb shell dumpsys activity service SystemUIService

# dump 通知
adb shell dumpsys notification

# dump 状态栏
adb shell dumpsys statusbar
```

### 6.2 窗口相关

```bash
# 查看当前窗口
adb shell dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'

# 查看窗口层级
adb shell dumpsys window

# 查看 Surface
adb shell dumpsys SurfaceFlinger
```

### 6.3 性能调试

```bash
# 抓取 systrace
python systrace.py -o trace.html -t 5 gfx view wm am

# 查看内存使用
adb shell dumpsys meminfo com.android.systemui

# 查看 CPU 使用
adb shell top -m 10 | grep systemui
```

### 6.4 日志过滤

```bash
# 按标签过滤
adb logcat -s SystemUI:V StatusBar:V QSTile:V

# 按级别过滤
adb logcat *:E  # 只看 Error

# 保存日志到文件
adb logcat -v time > systemui_log.txt
```

---

## 七、必读源码清单

| 优先级 | 文件 | 说明 |
|--------|------|------|
| ⭐⭐⭐⭐⭐ | `SystemUIApplication.java` | 启动入口，理解初始化流程 |
| ⭐⭐⭐⭐⭐ | `StatusBar.java` | 状态栏核心，最重要的类 |
| ⭐⭐⭐⭐ | `QSTileImpl.java` | 快捷开关基类 |
| ⭐⭐⭐⭐ | `QSPanel.java` | 快捷设置面板 |
| ⭐⭐⭐⭐ | `NotificationStackScrollLayout.java` | 通知列表 |
| ⭐⭐⭐ | `KeyguardViewMediator.java` | 锁屏控制 |
| ⭐⭐⭐ | `NavigationBarView.java` | 导航栏 |
| ⭐⭐⭐ | `VolumeDialogImpl.java` | 音量面板 |
| ⭐⭐ | `Dependency.java` | 依赖管理 |
| ⭐⭐ | `SystemUIFactory.java` | 工厂类 |

---

## 八、实战项目建议

| 难度 | 项目 | 涉及模块 | 预计时间 |
|------|------|----------|----------|
| ⭐ | 修改状态栏图标颜色 | StatusBar | 1-2 天 |
| ⭐ | 修改状态栏时间格式 | Clock | 1 天 |
| ⭐⭐ | 自定义 QS Tile | qs/tiles | 2-3 天 |
| ⭐⭐ | 修改锁屏时钟样式 | keyguard | 2-3 天 |
| ⭐⭐ | 修改音量面板样式 | volume | 2-3 天 |
| ⭐⭐⭐ | 添加状态栏新图标 | StatusBarIconController | 3-5 天 |
| ⭐⭐⭐ | 自定义导航栏按钮 | navigationbar | 3-5 天 |
| ⭐⭐⭐⭐ | 自定义通知样式 | notification | 1 周 |
| ⭐⭐⭐⭐ | 添加新的系统面板 | panel | 1 周 |
| ⭐⭐⭐⭐⭐ | 新增系统手势 | navigationbar + WMS | 2 周 |

---

## 九、学习资源

### 9.1 官方资源

- [AOSP 官方文档](https://source.android.com/docs)
- [Android Code Search](https://cs.android.com/) - 在线浏览 AOSP 源码
- [Android Issue Tracker](https://issuetracker.google.com/)

### 9.2 推荐博客

- [Gityuan 博客](http://gityuan.com/) - Android Framework 深度分析
- [刘望舒的博客](https://liuwangshu.cn/) - 系统源码分析
- [Weishu's Notes](https://weishu.me/) - 插件化和系统原理

### 9.3 书籍推荐

| 书名 | 作者 | 说明 |
|------|------|------|
| 《深入理解 Android》系列 | 邓凡平 | Framework 经典 |
| 《Android 系统源代码情景分析》 | 罗升阳 | 系统原理分析 |
| 《Android 进阶解密》 | 刘望舒 | Framework 进阶 |

### 9.4 视频教程

- 慕课网 - Android Framework 开发
- B站搜索 "Android Framework 源码分析"
- YouTube - Android Developers channel

---

## 十、常见问题 FAQ

### Q1: 编译报错 Jack server 问题

```bash
# 解决方案
export JACK_SERVER_VM_ARGUMENTS="-Dfile.encoding=UTF-8 -XX:+TieredCompilation -Xmx4g"
./prebuilts/sdk/tools/jack-admin kill-server
./prebuilts/sdk/tools/jack-admin start-server
```

### Q2: 如何在 Android Studio 中阅读源码

```bash
# 生成 IDE 项目文件
make idegen && development/tools/idegen/idegen.sh

# 用 Android Studio 打开 android.ipr
```

### Q3: 修改后 push 不生效

```bash
# 确保执行了 remount
adb root
adb remount

# 检查 selinux
adb shell getenforce  # 如果是 Enforcing，可能需要:
adb shell setenforce 0  # 临时关闭 (仅调试用)
```

### Q4: 如何快速定位代码

```bash
# 使用 grep 搜索
grep -rn "StatusBar" frameworks/base/packages/SystemUI/

# 使用 Android Code Search
# https://cs.android.com/
```

---

## 十一、开发工具推荐

| 工具 | 用途 |
|------|------|
| Android Studio | 代码阅读、调试 |
| VS Code | 快速编辑 |
| Source Insight | 大型项目代码阅读 |
| Beyond Compare | 代码对比 |
| Vysor / scrcpy | 设备投屏 |
| Android Code Search | 在线源码搜索 |

---

## 附录：常用 ADB 命令速查

```bash
# 设备连接
adb devices
adb connect <ip>:<port>

# 文件操作
adb push <local> <remote>
adb pull <remote> <local>

# Shell 操作
adb shell
adb shell <command>

# 应用管理
adb install <apk>
adb uninstall <package>

# 日志
adb logcat
adb logcat -c  # 清除
adb logcat -v time  # 带时间戳

# 调试
adb root
adb remount
adb reboot

# 截图/录屏
adb shell screencap /sdcard/screen.png
adb shell screenrecord /sdcard/video.mp4
```

---

> 📝 **提示**：系统开发需要耐心，建议从简单的 UI 修改开始，逐步深入核心逻辑。
> 
> 📅 **更新日期**：2026-01-21
