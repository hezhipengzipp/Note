# Perfetto Trace 使用指南（DriveAgent 项目）

## 一、代码插桩

使用 `android.os.Trace` API 在关键位置插入自定义标记：

```kotlin
import android.os.Trace

// 同步代码段
Trace.beginSection("DriveAgent:标记名称")
// ... 要测量的代码 ...
Trace.endSection()

// 异步操作
Trace.beginAsyncSection("DriveAgent:BLE_Connect", requestId)
// ... 异步操作 ...
Trace.endAsyncSection("DriveAgent:BLE_Connect", requestId)
```

### 已插桩位置

| 位置 | 文件 | 标记名称 |
|---|---|---|
| Application 启动 | `DriveAgentApplication.kt` | `DriveAgent:Application.onCreate` |
| Firebase 初始化 | `DriveAgentApplication.kt` | `DriveAgent:initFirebase` |
| Koin 初始化 | `DriveAgentApplication.kt` | `DriveAgent:initKoin` |
| Logger 初始化 | `DriveAgentApplication.kt` | `DriveAgent:initLogger` |
| MainActivity 启动 | `MainActivity.kt` | `DriveAgent:MainActivity.onCreate` |

## 二、录制 Trace

### 方式一：使用 Perfetto 录制

#### 1. 准备配置文件

在 Mac 上创建 `/tmp/perfetto_config.txt`，内容如下：

```
buffers {
  size_kb: 65536
  fill_policy: RING_BUFFER
}
data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "power/suspend_resume"
      ftrace_events: "ftrace/print"
      atrace_categories: "am"
      atrace_categories: "wm"
      atrace_categories: "view"
      atrace_categories: "gfx"
      atrace_apps: "com.atol.drive.agent"
    }
  }
}
data_sources {
  config {
    name: "linux.process_stats"
    target_buffer: 0
    process_stats_config {
      scan_all_processes_on_start: true
    }
  }
}
duration_ms: 15000
```

> **关键**：必须包含 `ftrace_events: "ftrace/print"`，这是 `Trace.beginSection()` 写入的底层事件。缺少此项会导致搜不到自定义标记。

#### 2. 推送配置到设备

```bash
adb push /tmp/perfetto_config.txt /data/misc/perfetto-configs/perfetto_config.txt
```

> 注意：必须推送到 `/data/misc/perfetto-configs/` 目录，推送到 `/data/local/tmp/` 会因权限问题失败。

#### 3. 录制

```bash
# 杀掉 App
adb shell am force-stop com.atol.drive.agent

# 手动设置 app tracing 属性
adb shell setprop debug.atrace.app_cmdlines com.atol.drive.agent

# 开始录制（会阻塞 15 秒）
adb shell perfetto --txt --config /data/misc/perfetto-configs/perfetto_config.txt -o /data/misc/perfetto-traces/trace.pb
```

录制开始后，**立刻在另一个终端**冷启动 App：

```bash
adb shell am start com.atol.drive.agent/.mobile.feature.main.MainActivity
```

#### 4. 导出

```bash
adb pull /data/misc/perfetto-traces/trace.pb ./trace.pb
```

然后在 [ui.perfetto.dev](https://ui.perfetto.dev) 打开 `trace.pb`。

---

### 方式二：使用 atrace 录制

更简单直接，兼容性更好：

```bash
# 1. 杀掉 App
adb shell am force-stop com.atol.drive.agent

# 2. 开始录制（10秒，输出到 Mac 本地）
adb shell atrace -a com.atol.drive.agent -t 10 am wm view gfx -z > /tmp/trace_systrace.ctrace

# 3. 录制开始后，立刻在【另一个终端】启动 App
adb shell am start com.atol.drive.agent/.mobile.feature.main.MainActivity

# 4. 等待录制结束，复制到桌面方便使用
cp /tmp/trace_systrace.ctrace ~/Desktop/
```

然后在 [ui.perfetto.dev](https://ui.perfetto.dev) 打开 `.ctrace` 文件，分析能力与 Perfetto 录制完全一样。

---

### 参数说明

| 参数 | 含义 |
|---|---|
| `-a com.atol.drive.agent` | 启用该 App 的自定义 Trace 事件采集 |
| `-t 10` | 录制时长 10 秒 |
| `-z` | 压缩输出 |
| `am wm view gfx` | 系统级 atrace 类别：ActivityManager、WindowManager、View、Graphics |

### 常用 atrace 类别

| 类别 | 含义 |
|---|---|
| `am` | Activity Manager（Activity 生命周期） |
| `wm` | Window Manager（窗口管理） |
| `view` | View 系统（布局、绘制） |
| `gfx` | Graphics（渲染） |
| `sched` | CPU 调度 |
| `freq` | CPU 频率 |
| `input` | 输入事件 |
| `dalvik` | Dalvik/ART 虚拟机（GC 等） |

查看设备支持的所有类别：

```bash
adb shell atrace --list_categories
```

## 三、分析 Trace 文件

1. 打开 [ui.perfetto.dev](https://ui.perfetto.dev)
2. 将 `.pb` 或 `.ctrace` 文件拖入页面
3. 分析要点：
   - **搜索 `DriveAgent`**：找到自定义插桩标记，查看各阶段耗时
   - **展开 App 进程**：查看主线程活动和阻塞情况
   - **CPU 行**：顶部 CPU 0/1/2... 行展示线程调度
   - **Binder 调用**：跨进程通信耗时

## 四、注意事项

### 设备兼容性

- 本项目设备的 `atrace` **不支持 `--app` 参数，必须用 `-a`**
- 使用 Perfetto 录制时，配置文件中**必须显式包含 `ftrace_events: "ftrace/print"`**，否则无法采集 App 自定义 Trace 事件
- 使用 Perfetto 录制时，**必须在录制前手动设置系统属性**：`adb shell setprop debug.atrace.app_cmdlines com.atol.drive.agent`
- 配置文件必须推送到 **`/data/misc/perfetto-configs/`** 目录（其他目录可能权限不足）
- Perfetto 命令必须加 **`--txt`** 参数（告知配置文件是文本格式，非二进制 protobuf）
- Perfetto 命令**必须写在一行内**，换行 `\` 在某些终端下可能失效

### 录制时的关键点

1. **必须先杀 App 再录制**：插桩代码在 `onCreate` 中，热启动不会再次触发
2. **先开始录制，再启动 App**：确保录制窗口覆盖 App 冷启动过程
3. **用两个终端**：一个跑录制命令，另一个启动 App
4. **确保安装的是 debug 包**：release 包中 `Trace.beginSection()` 是空操作
5. **修改插桩代码后必须重新编译安装**：否则设备上运行的还是旧代码

### 包名

- applicationId：`com.atol.drive.agent`（注意是 `atol` 不是 `atoto`）

## 五、验证插桩是否生效

如果在 Perfetto UI 中搜不到自定义标记，先用以下命令验证：

```bash
adb shell am force-stop com.atol.drive.agent
adb shell atrace -a com.atol.drive.agent -t 10 am wm view gfx > /tmp/atrace_raw.txt 2>&1
# 另一个终端启动 App
adb shell am start com.atol.drive.agent/.mobile.feature.main.MainActivity
# 录制结束后检查
grep -iE "driveagent|drive.agent" /tmp/atrace_raw.txt
```

如果 grep 有输出，说明插桩正常。若 Perfetto 方式仍搜不到，回退使用 atrace 方式录制。
