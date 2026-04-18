# Perfetto 实战指南：抓取与分析

## 一、Perfetto 是什么

Perfetto 是 Google 开源的系统级性能 trace 工具，是 **Systrace 的继任者**，从 Android 9 开始内置。

```
       ┌─────────────────────────────────────┐
       │       App 进程（你的应用）          │
       │   Trace.beginSection("xxx") ──┐     │
       └───────────────────────────────┼─────┘
                                       │ 写 trace_marker
       ┌───────────────────────────────▼─────┐
       │       Linux Kernel ftrace           │
       │   sched / freq / irq / mm / ...     │
       └───────────────┬─────────────────────┘
                       │ 共享内存
       ┌───────────────▼─────────────────────┐
       │   Perfetto Service (traced)         │  ← 系统常驻进程
       │   ┌─────────────────────────────┐   │
       │   │  环形缓冲区（protobuf）      │   │
       │   └─────────────────────────────┘   │
       └───────────────┬─────────────────────┘
                       │ 写文件
                       ▼
            /data/misc/perfetto-traces/xxx.pftrace
                       │
                       │ adb pull
                       ▼
              ui.perfetto.dev 可视化分析
```

**核心组件**：
- **traced**：核心服务，常驻系统进程
- **traced_probes**：数据采集器（ftrace、atrace、procfs）
- **perfetto**：命令行工具，发起 trace

---

## 二、抓取文件（三种方式）

### 方式 A：手机端开发者选项（最简单，零命令）

**适用**：快速复现某个场景，无需联电脑

#### 步骤

```
1. 设置 → 关于手机 → 连续点 7 次"版本号" → 开启开发者模式
2. 设置 → 系统 → 开发者选项 → 滑到底部 → "系统跟踪"
3. 进入"系统跟踪" 页面：
   ┌─────────────────────────────┐
   │  记录跟踪记录    ○──         │  ← 开关
   │  类别                        │  ← 选要采集的内容
   │  在快捷设置中显示"快速设置"  │  ← 推荐打开
   │  长跟踪记录                  │  ← 长 trace
   └─────────────────────────────┘
4. 选"类别"：勾选 gfx、view、wm、am、sched、binder_driver 等
5. 下拉通知栏 → 点"系统跟踪"快捷开关 → 开始
6. 复现卡顿/启动等场景
7. 再次下拉 → 点开关 → 停止
8. 通知栏出现"系统跟踪已保存" → 点击 → 分享 → 通过邮件/网盘传到电脑
```

**输出位置**：`/data/local/traces/`（需 root 才能直接拉）

---

### 方式 B：adb 命令（开发常用）

**适用**：自动化、命令可复用

#### 1. 最简命令（兼容 Systrace 语法）

```bash
adb shell perfetto -o /data/misc/perfetto-traces/trace.pftrace \
    -t 10s \
    sched freq idle am wm gfx view binder_driver hal dalvik input res memory \
    -a com.example.app
```

参数说明：

| 参数 | 含义                                              |
| ---- | ------------------------------------------------- |
| `-o` | 输出路径（必须在 `/data/misc/perfetto-traces/`）  |
| `-t` | 采集时长（10s/1m 等）                             |
| `-a` | 指定应用包名（开启 app 级 atrace tag）            |
| 末尾 | atrace 类别（空格分隔）                           |

#### 2. 拉取到电脑

```bash
adb pull /data/misc/perfetto-traces/trace.pftrace ./
```

#### 3. 一行命令（推荐脚本）

```bash
#!/bin/bash
# capture.sh
PKG="com.example.app"
FILE="trace_$(date +%Y%m%d_%H%M%S).pftrace"
adb shell perfetto -o /data/misc/perfetto-traces/$FILE -t 15s \
    sched freq idle am wm gfx view binder_driver hal dalvik input res memory \
    -a $PKG
adb pull /data/misc/perfetto-traces/$FILE ./
echo "✅ 已保存: $FILE"
```

---

### 方式 C：配置文件（最强大）

**适用**：自定义采集源、长 trace、高级 ftrace 事件

#### 1. 写配置文件

```protobuf
# trace_config.pbtx
buffers: {
    size_kb: 63488           # 64MB 缓冲区
    fill_policy: DISCARD     # 满了丢新的（RING_BUFFER 是覆盖旧的）
}

data_sources: {
    config {
        name: "linux.ftrace"
        ftrace_config {
            ftrace_events: "sched/sched_switch"      # 线程切换
            ftrace_events: "sched/sched_wakeup"      # 唤醒
            ftrace_events: "power/cpu_frequency"     # 频率
            ftrace_events: "power/cpu_idle"          # 空闲
            atrace_categories: "gfx"
            atrace_categories: "view"
            atrace_categories: "am"
            atrace_categories: "wm"
            atrace_apps: "com.example.app"           # 指定 app
            atrace_apps: "*"                         # 或所有 app
        }
    }
}

data_sources: {
    config {
        name: "linux.process_stats"
        process_stats_config {
            scan_all_processes_on_start: true
        }
    }
}

data_sources: {
    config {
        name: "android.surfaceflinger.frame"   # 帧统计（丢帧分析必备）
    }
}

duration_ms: 10000
```

#### 2. 执行

```bash
adb shell perfetto --txt -c - -o /data/misc/perfetto-traces/trace.pftrace \
    < trace_config.pbtx

adb pull /data/misc/perfetto-traces/trace.pftrace ./
```

#### 3. 长 trace（写入文件而非内存）

适合录制几分钟到几小时的场景：

```protobuf
write_into_file: true
file_write_period_ms: 5000     # 每 5 秒刷盘一次
duration_ms: 300000            # 5 分钟
```

---

### 抓取技巧

| 场景       | 推荐时长 | 必选类别                                       |
| ---------- | -------- | ---------------------------------------------- |
| 启动分析   | 10s      | sched gfx view wm am binder_driver dalvik      |
| 卡顿/掉帧  | 5s       | sched gfx view binder_driver + frame           |
| CPU 调度   | 5s       | sched freq idle                                |
| 主线程耗时 | 10s      | sched view + atrace_apps                       |
| Binder 通信| 10s      | binder_driver am wm                            |

**Tip**：用 `adb shell atrace --list_categories` 查看设备支持的类别。

---

## 三、分析文件（ui.perfetto.dev）

### 1. 打开方式

```
浏览器访问 https://ui.perfetto.dev/
左上角 "Open trace file" → 选 .pftrace 文件
（数据完全本地解析，不上传服务器，可放心）
```

或离线版：

```bash
# 下载离线 UI（一次性）
git clone https://github.com/google/perfetto.git
cd perfetto/ui
./run-dev-server
# 访问 http://localhost:10000
```

### 2. 界面总览

```
┌──────────────────────────────────────────────────────────────┐
│  时间轴标尺                              [搜索框] [SQL 查询]  │ ← 顶部
├──────────────────────────────────────────────────────────────┤
│ ┌─CPU 0─┐┌─CPU 1─┐┌─CPU 2─┐┌─CPU 3─┐                          │ ← CPU 占用
│ │█  █  █││ █ █ █ ││██  ██ ││  █  █ │                          │
├──────────────────────────────────────────────────────────────┤
│ Process: com.example.app (pid 12345)                          │ ← 进程组
│  ├─ Thread: main                                              │
│  │  [bindApplication][Activity.onCreate][doFrame][doFrame]    │ ← 主线程
│  ├─ Thread: RenderThread                                      │
│  │           [DrawFrame][DrawFrame]                           │ ← 渲染线程
│  ├─ Thread: Binder:12345_1                                    │
│  └─ Frames（Expected/Actual）                                  │
│      绿绿绿绿|黄|绿|红绿绿                                    │ ← 帧状态
├──────────────────────────────────────────────────────────────┤
│ surfaceflinger / system_server / ...                          │ ← 系统进程
└──────────────────────────────────────────────────────────────┘
```

### 3. 必学快捷键

| 按键      | 作用                                  |
| --------- | ------------------------------------- |
| `W` / `S` | 放大 / 缩小时间轴                     |
| `A` / `D` | 左移 / 右移                           |
| `M`       | 选中区间，显示总耗时（最常用！）       |
| `F`       | 缩放到选中 slice                      |
| `/`       | 搜索 slice 名称                       |
| `?`       | 显示所有快捷键                        |
| 鼠标拖选  | 选中区间，底部显示 CPU/Wall 时间分布  |

### 4. 关键面板

#### a) Slices（方法/事件块）

点击任意彩色块，底部面板显示：

```
Name: Choreographer#doFrame
Category: view
Start: 1234.567 ms
Duration: 18.5 ms       ← ⚠️ 超过 16.67ms = 丢帧
Thread: main
Args: ...
```

#### b) Flow events（连接线）

Binder 调用、Handler post 等会有箭头连接调用方和执行方，**点 slice → 看 "Following flows"** 可追踪跨线程调用。

#### c) Frame Timeline

```
Expected Frame: ▓▓▓▓▓▓▓ (16.67ms 预算)
Actual Frame:   ▓▓▓▓▓▓▓▓▓▓▓▓ (24ms 实际)
                            ↑ 红色 = Janky frame
```

点击红/黄帧 → 底部显示 **Jank Type**（如 AppDeadlineMissed、SurfaceFlingerStuffing）和归因。

---

## 四、SQL 查询（高级分析）

Perfetto 把 trace 转成 SQLite 数据库，可以用 SQL 直接查询。

### 打开 SQL 查询

```
左侧菜单 → "Query (SQL)" → 输入 SQL → Ctrl+Enter
```

### 常用查询

#### 1. 找出主线程最耗时的 10 个方法

```sql
SELECT
    s.name,
    s.dur / 1e6 AS dur_ms,
    s.ts / 1e6 AS start_ms
FROM slice s
JOIN thread_track t ON s.track_id = t.id
JOIN thread th ON t.utid = th.utid
WHERE th.name = 'main'
  AND s.dur > 5000000     -- > 5ms
ORDER BY s.dur DESC
LIMIT 10;
```

#### 2. 统计所有丢帧（>16.67ms 的 doFrame）

```sql
SELECT
    s.ts / 1e6 AS start_ms,
    s.dur / 1e6 AS dur_ms
FROM slice s
WHERE s.name = 'Choreographer#doFrame'
  AND s.dur > 16670000
ORDER BY s.ts;
```

#### 3. 主线程 CPU vs 调度状态时间分布

```sql
SELECT
    state,
    SUM(dur) / 1e6 AS total_ms
FROM thread_state
WHERE utid = (
    SELECT utid FROM thread WHERE name = 'main'
    AND upid = (SELECT upid FROM process WHERE name = 'com.example.app')
)
GROUP BY state
ORDER BY total_ms DESC;
```

输出：

```
state         total_ms
Running       1234.5    ← 真正占用 CPU
Runnable       321.0    ← 等 CPU（说明被抢占）
Sleeping       567.8    ← 等锁/IO
S              ...      ← 不可中断睡眠（IO等）
```

#### 4. 启动耗时（bindApplication → 首帧）

```sql
WITH start AS (
    SELECT MIN(ts) AS t FROM slice WHERE name = 'bindApplication'
), first_frame AS (
    SELECT MIN(ts) AS t FROM slice WHERE name = 'Choreographer#doFrame'
)
SELECT (first_frame.t - start.t) / 1e6 AS startup_ms
FROM start, first_frame;
```

---

## 五、Metrics（开箱即用的分析）

ui.perfetto.dev 内置了一批 metrics，左侧菜单 → "Metrics"：

| Metric                       | 用途                          |
| ---------------------------- | ----------------------------- |
| `android_startup`            | App 启动耗时分析              |
| `android_jank_cuj`           | Critical User Journey 卡顿    |
| `android_cpu`                | CPU 使用率                    |
| `android_mem`                | 内存使用                      |
| `android_batt`               | 电量                          |
| `android_surfaceflinger`     | 合成器分析                    |

点 "Run" → 直接看汇总报告。

---

## 六、实战案例：定位启动慢

### 步骤

1. **抓取**

```bash
# 杀进程
adb shell am force-stop com.example.app

# 开始 trace（在另一个终端启动 app）
adb shell perfetto -o /data/misc/perfetto-traces/startup.pftrace \
    -t 10s \
    sched gfx view wm am binder_driver dalvik input \
    -a com.example.app

# 立即手动启动 app 或：
adb shell am start -n com.example.app/.MainActivity

# 等命令结束，拉文件
adb pull /data/misc/perfetto-traces/startup.pftrace
```

2. **分析路径**

```
打开 startup.pftrace → 找 com.example.app 进程
  ↓
找 main 线程时间轴
  ↓
按时间顺序看主线程 slice：
  ┌─ bindApplication        ← Application.onCreate 内
  │   └─ 看子 slice，找耗时长的 SDK 初始化
  ├─ activityStart
  │   ├─ performLaunchActivity
  │   ├─ Activity.onCreate
  │   │   ├─ setContentView   ← 布局加载
  │   │   └─ 自定义初始化
  │   ├─ Activity.onStart
  │   └─ Activity.onResume
  └─ Choreographer#doFrame   ← 首帧
       ├─ Layout
       ├─ Draw
       └─ ...
```

3. **常见瓶颈识别**

| 现象                              | 原因                          | 优化                      |
| --------------------------------- | ----------------------------- | ------------------------- |
| `bindApplication` 长（>500ms）    | SDK 同步初始化太多            | 异步/延迟初始化           |
| `activityStart` 中有大段 Sleeping | 主线程被锁/IO 阻塞            | 找 flow，看谁持锁         |
| `setContentView` 长               | 布局复杂、自定义 View inflate | ViewStub、AsyncInflater   |
| `doFrame` 长（>16ms）             | 测量/绘制慢                   | 减层级、optimize draw     |
| Runnable 状态多                   | 被其他进程抢占 CPU            | 提优先级、减小工作        |

---

## 七、实战案例：定位卡顿

### 步骤

1. 在 app 里复现卡顿（例如滑列表）
2. 抓取 5 秒：

```bash
adb shell perfetto -o /data/misc/perfetto-traces/jank.pftrace \
    -t 5s sched gfx view binder_driver -a com.example.app
adb pull /data/misc/perfetto-traces/jank.pftrace
```

3. 分析：
   - 看 **Frames** 行，找黄/红帧
   - 点红帧 → 底部 "Jank Type" 显示归因
   - 沿时间轴向上看主线程在做什么
   - 用 SQL 查询前 10 慢方法

---

## 八、面试题

### Q1：Perfetto 抓 trace 的文件必须放在 `/data/misc/perfetto-traces/` 吗？

是的。`traced` 服务以 system 用户运行，受 SELinux 限制只能写这个目录。如果指定其他路径会报权限错误。

### Q2：Perfetto 和 Systrace 抓取的数据来源相同吗？

**底层相同（都是 ftrace + atrace）**，但 Perfetto 还能采集额外数据源：
- `process_stats`：定期采集 /proc 信息
- `heapprofd`：Native 内存采样
- `perf_event`：硬件性能计数器
- `surfaceflinger.frame`：帧时间线

### Q3：`size_kb` 设置太小会怎样？

如果 `fill_policy: DISCARD`，缓冲区满后丢弃新数据，trace 末尾会缺失。
如果 `fill_policy: RING_BUFFER`，覆盖旧数据，trace 开头会缺失。

**推荐**：复杂场景用 64MB+，长 trace 用 `write_into_file: true` 直接落盘。

### Q4：分析时怎么快速定位丢帧的根因？

1. UI 切到 Frame Timeline 视图，找红色帧
2. 点击红帧，看 Jank Type：
   - `AppDeadlineMissed`：App 主线程超时
   - `SurfaceFlingerCpuDeadlineMissed`：SF 慢
   - `BufferStuffing`：积压
3. App 主线程超时 → 沿时间轴向左找该帧的 `Choreographer#doFrame`
4. 看里面哪个子 slice 最长（measure/layout/draw/自定义）
5. 跨线程问题 → 看 Flow events 找等待的锁

### Q5：Perfetto 的 SQL 查询底层是什么？

Perfetto 把 protobuf trace 解析后存入 **SQLite 内存数据库**，提供标准 SQL 接口。常用表：

- `slice`：所有时间块（atrace、Trace.beginSection）
- `thread` / `process`：线程/进程元数据
- `thread_state`：线程调度状态
- `counter`：计数器（CPU 频率、内存等）
- `actual_frame_timeline_slice` / `expected_frame_timeline_slice`：帧时间线

### Q6：抓 Perfetto 会影响应用性能吗？

会有轻微影响，但很小（通常 <2% CPU）。原因：
- 数据通过共享内存传递，无系统调用开销
- ftrace 在内核里高效记录
- 长 trace 可能占用磁盘/内存

线下分析无忧，**线上灰度** 也可用，但建议短时（<30s）。

### Q7：如何在自定义代码里加 Perfetto 可见的 slice？

```kotlin
// AndroidX（推荐，兼容 API < 29）
import androidx.tracing.Trace
Trace.beginSection("loadUserList")
try { loadData() } finally { Trace.endSection() }

// 或 Kotlin 扩展
androidx.tracing.trace("loadUserList") { loadData() }
```

抓 trace 时必须加 `-a 包名`（atrace_apps），否则不会出现在 trace 里。

### Q8：Perfetto trace 文件能转成 Chrome Tracing 格式吗？

可以，用 `traceconv` 工具：

```bash
# 下载 traceconv
curl -LO https://get.perfetto.dev/traceconv
chmod +x traceconv

# 转换
./traceconv json input.pftrace output.json
# 或转 systrace HTML
./traceconv systrace input.pftrace output.html
```

但 ui.perfetto.dev 已经比 Chrome Tracing 强大，一般不需要转。
