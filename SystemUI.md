# 🗺️ Android Framework 开发入门指南 (SystemUI & 系统应用篇)

**适用对象**：有 Android/Flutter 应用开发经验，希望转型做系统定制、SystemUI 开发的工程师。
**核心思维转变**：从“调用 API”转变为“实现和管理 API”。

---

## 阶段一：环境搭建与编译体系 (基石)
*不做 Framework 开发，只看代码是学不会的。必须拥有可编译的 AOSP 环境。*

### 1. 硬件与系统准备
* **操作系统**：推荐 **Ubuntu 20.04/22.04 LTS** (物理机最佳，或 WSL2)。
* **硬件要求**：内存 32GB+，硬盘预留 300GB+ (源码+编译产物非常大)。

### 2. 构建工具链
* **源码下载**：熟练使用 `repo` 工具同步 AOSP 源码。
* **编译命令**：
    * `source build/envsetup.sh` (初始化环境)
    * `lunch <target>` (选择编译目标，如 `aosp_arm64-eng`)
    * `m <module_name>` (编译特定模块)
* **构建系统 (Soong)**：
    * 了解 **`Android.bp`** 语法 (替代旧的 `Android.mk`)。
    * **任务**：尝试写一个简单的 C++ 或 Java 模块，通过 `Android.bp` 编译进系统。

---

## 阶段二：SystemUI 专属架构 (主攻方向)
*SystemUI 是一个运行在系统特权下的复杂 APK，大量使用了现代架构。*

### 1. 源码位置
* 核心路径：`frameworks/base/packages/SystemUI/`

### 2. 核心模块拆解
* **StatusBar (状态栏)**：负责通知图标、时间、电池信号显示。
* **QuickSettings (QS 面板)**：下拉通知栏后的快捷开关 (WiFi, Bluetooth 等)。
* **Keyguard (锁屏)**：涉及安全解锁逻辑、生物识别交互。
* **NavigationBar (导航栏)**：三大金刚键、全面屏手势逻辑。

### 3. 难点架构
* **Dependency Injection (依赖注入)**：SystemUI 极其依赖 **Dagger2**。必须理解 Module, Component, @Inject，否则看不懂代码跳转逻辑。
* **Plugin 机制**：SystemUI 允许通过插件动态替换部分 UI 实现 (如时钟样式)，无需重启系统。

---

## 阶段三：核心系统服务 (AMS/WMS)
*SystemUI 只是“皮”，数据和逻辑来自底层的系统服务。*

### 1. Binder 进程间通信 (神经系统)
* **原理**：理解 Client-Server 模型，Binder 驱动的作用。
* **AIDL**：熟练掌握 `.aidl` 文件的编写，理解 Stub (服务端) 和 Proxy (客户端) 的生成逻辑。

### 2. WMS (WindowManagerService)
* **作用**：管理所有窗口的显示、层级 (Z-Order)、动画。
* **关联**：SystemUI 的悬浮窗、音量条、Toast 都是通过 WMS 添加的 `Window`。
* **关键词**：Window, Token, Layer, SurfaceFlinger。

### 3. AMS (ActivityManagerService) / ATMS
* **作用**：管理组件生命周期、任务栈 (Task Stack)。
* **关联**：最近任务列表 (Recents) 的数据直接来源于此。

---

## 阶段四：系统应用开发技巧
*开发 System App 与普通 App 的关键区别。*

### 1. 权限与签名
* **AndroidManifest.xml**：
    * `android:sharedUserId="android.uid.system"` (共享系统进程 ID)。
    * 权限级别：`signature` (签名级) 和 `privileged` (特权级)。
* **部署位置**：`/system/app/` (普通系统应用) vs `/system/priv-app/` (特权应用)。

### 2. 调用隐藏 API (@hide)
* **问题**：Framework 中大量 API 被标记为 `@hide`，SDK 无法直接调用。
* **解决方案**：
    * **反射 (Reflection)**：通过 Java 反射机制调用。
    * **编译期欺骗**：使用修改过的 `framework.jar` (移除 @hide 标记) 替换官方 SDK 进行编译。
    * **源码环境编译**：直接在 AOSP 源码树下编写应用，可以直接访问所有 API。

---

## 阶段五：调试神器 (必修课)
*Framework 开发通常无法使用断点调试，日志和 Dump 是核心。*

### 1. Dumpsys (核武器)
通过 ADB 查看系统服务当前状态的快照。
* `adb shell dumpsys activity` (查看 Activity 栈、Task 信息)
* `adb shell dumpsys window` (查看窗口层级、焦点)
* `adb shell dumpsys statusbar` (专门查看 SystemUI 内部状态)

### 2. 可视化工具
* **WinScope**：追踪窗口层级变化和动画帧，做 UI 动画必用。
* **Perfetto / Systrace**：分析系统卡顿、掉帧、性能瓶颈。

---

## 🚀 实战任务建议 (Hello Framework)

**目标**：在 SystemUI 状态栏的时间旁边，添加一个“自定义图标”。

**步骤**：
1.  在线搜索代码 (推荐 [cs.android.com](https://cs.android.com/)) 找到状态栏布局文件 (通常是 `status_bar.xml` 相关)。
2.  修改 XML，添加一个 `<ImageView ... />`。
3.  找到对应的 Controller (Java/Kotlin 代码) 控制图标显示/隐藏。
4.  执行编译：`m SystemUI`。
5.  推送产物：`adb push .../SystemUI.apk /system_ext/priv-app/SystemUI/`。
6.  重启生效：`adb shell stop && adb shell start` (软重启)。