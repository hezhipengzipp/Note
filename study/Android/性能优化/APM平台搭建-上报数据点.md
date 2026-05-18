# APM 平台搭建 —— 上报数据点设计

## 一、APM 整体架构

```
   ┌─────────────────────────────────────────────────────────────┐
   │                     App (数据采集层)                         │
   │                                                             │
   │  Crash  │  ANR  │ 启动耗时 │ 页面耗时 │ 网络 │ 内存 │ CPU  │ 卡顿 │
   └────────────────────────┬────────────────────────────────────┘
                            │  批量+压缩上报
                            ▼
   ┌─────────────────────────────────────────────────────────────┐
   │                     接入层 (Gateway)                         │
   │                    限流 / 鉴权 / 格式化                       │
   └────────────────────────┬────────────────────────────────────┘
                            │
                            ▼
   ┌─────────────────────────────────────────────────────────────┐
   │                     消息队列 (Kafka)                         │
   └────┬──────────┬──────────┬──────────┬──────────┬───────────┘
        │          │          │          │          │
        ▼          ▼          ▼          ▼          ▼
   ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐
   │ 实时计算 │ │ 离线计算 │ │ 告警    │ │ 聚合    │ │ 存储    │
   │ Flink  │ │ Spark  │ │ 规则引擎 │ │ 分钟级  │ │ ES/HDFS │
   └────┬───┘ └───┬────┘ └────┬────┘ └────┬────┘ └────┬────┘
        │         │          │          │          │
        └─────────┴──────────┴──────────┴──────────┘
                            │
                            ▼
   ┌─────────────────────────────────────────────────────────────┐
   │                     可视化 (Dashboard)                       │
   │        大盘趋势 / 多维下钻 / 告警通知 / 单点追踪              │
   └─────────────────────────────────────────────────────────────┘
```

---

## 二、上报数据点全景

```
                        APM 上报数据点

  ┌──────────┬──────────┬──────────┬──────────┬──────────┐
  │  稳定性   │  性能    │  网络    │  行为    │  环境    │
  │ Stability│ Perf     │ Network  │ Behavior │ Context  │
  └──────────┴──────────┴──────────┴──────────┴──────────┘
```

---

## 三、稳定性 —— Crash & ANR

### 3.1 Java Crash 上报字段

```java
public class CrashReport {
    // ===== 标识 =====
    String crashId;           // UUID，唯一标识一次 Crash
    String crashType;         // "java_crash" / "native_crash"

    // ===== 异常信息 =====
    String exceptionClass;    // "java.lang.NullPointerException"
    String exceptionMessage;  // getMessage()
    String stackTrace;        // 完整堆栈，getStackTrace()
    String causedBy;          // getCause() 链，序列化

    // ===== 崩溃位置 =====
    String threadName;        // 崩溃线程名（main/xxx-thread-1）
    boolean isMainThread;     // 是否主线程

    // ===== 场景 =====
    String processName;       // 进程名（主进程/:push等）
    String activityName;      // 崩溃时栈顶 Activity
    String fragmentName;      // 崩溃时栈顶 Fragment

    // ===== 环境（所有上报共用，后文不再重复）=====
    AppContext appContext;    // 见第十节

    // ===== 自定义 =====
    Map<String, String> bizTags;  // 业务标签，如 {"scene":"login"}
    Map<String, String> customData; // 自定义键值对
}
```

### 3.2 Native Crash 额外字段

```java
public class NativeCrashExtra {
    String signal;            // 信号名 e.g. "SIGSEGV"
    int signalCode;           // 信号码
    String faultAddress;       // 出错地址 (pc)
    String backtrace;         // unwind 后的 native 堆栈
    String buildFingerprint;  // 系统指纹，判断 ROM 兼容
    List<MemoryMap> maps;     // /proc/pid/maps
    List<String> fdList;      // /proc/pid/fd，排查 fd 泄漏
}
```

### 3.3 ANR 上报字段

```java
public class ANRReport {
    // ===== 标识 =====
    String anrId;             // UUID
    String anrType;           // "input" / "broadcast" / "service" / "content_provider"

    // ===== ANR 原因 =====
    String reason;            // 系统给出的 ANR reason，如 "Input dispatching timed out"
    String mainThreadTrace;   // 主线程堆栈
    int mainThreadState;      // 主线程状态码

    // ===== 关键线程 =====
    String binderThreadTrace; // Binder 线程堆栈
    String finalizerTrace;    // FinalizerWatchdog 堆栈（如有）
    String allThreadTraces;   // 所有线程堆栈（压缩后上报）

    // ===== 系统状态 =====
    long availableMem;        // 可用内存(KB)
    long totalMem;            // 总内存(KB)
    float cpuUsage;           // CPU 使用率
    int gcCount;              // 最近一段时间 GC 次数

    // ===== 触发场景 =====
    String activityName;      // ANR 时前台 Activity
    long foregroundDuration;  // 前台持续时间(ms)

    // ===== appContext + bizTags（同上）=====
}
```

---

## 四、性能 —— 启动 / 页面 / 卡顿

### 4.1 冷启动耗时上报

```
  冷启动时间线:

  attachBaseContext ─┬─ ContentProvider.onCreate ─┬─ Application.onCreate ─┬─ Activity.onCreate
                     │                            │                        │
                     ▼                            ▼                        ▼
                 [pre-main阶段]              [Application阶段]         [Activity阶段]
                     │                            │                        │
                     ▼                            ▼                        ▼
             ┌─────────────┐            ┌──────────────┐          ┌────────────────┐
             │ 进程创建      │            │ 库初始化       │          │ setContentView  │
             │ ClassLoader  │            │ 业务SDK初始化   │          │ 首帧绘制         │
             └─────────────┘            └──────────────┘          └────────────────┘
                 耗时1                      耗时2                      耗时3

  完整冷启动 = 耗时1 + 耗时2 + 耗时3 (以首帧 display 为准)
```

```java
public class LaunchReport {
    // ===== 标识 =====
    String launchId;           // UUID
    String launchType;         // "cold" / "warm" / "hot"

    // ===== 各阶段耗时(ms) =====
    long processCreateTime;    // 进程创建到 attachBaseContext（系统值近似）
    long applicationOnCreate;  // Application.onCreate 耗时
    long contentProviderOnCreate; // ContentProvider.onCreate 总耗时
    long activityOnCreate;     // Activity.onCreate 耗时
    long firstFrameTime;       // 首帧绘制完成时间（相对 processStart）
    long firstInteractiveTime; // 首屏可交互时间
    long totalLaunchTime;      // 完整冷启动耗时

    // ===== 阻塞原因打点 =====
    List<MethodTrace> heavyMethods; // 耗时方法 TopN
    int sdkInitCount;          // SDK 初始化数量
    long classLoadTime;        // 类加载耗时

    // ===== 是否首启 =====
    boolean isFirstLaunch;     // 安装后首次启动
    boolean isFirstDayLaunch;  // 每日首次启动
}
```

### 4.2 页面性能上报（每个 Activity/Fragment 关键页面）

```java
public class PagePerfReport {
    // ===== 标识 =====
    String pageName;           // Activity/Fragment 类名
    String pageId;             // 页面唯一 ID（一次访问）
    String fromPage;           // 来源页面

    // ===== 页面耗时(ms) =====
    long pageCreateTime;       // onCreate 耗时
    long pageRenderTime;       // 从 onCreate 到首帧绘制
    long pageInteractiveTime;  // 可交互时间（数据加载+渲染完成）
    long pageVisibleTime;      // 可见时长（停留时长）

    // ===== 页面状态 =====
    boolean isFirstOpen;       // 本次进程内首次打开（冷页面/热页面）
    boolean hasCacheData;      // 是否命中缓存
    int dataLoadType;          // 0=缓存, 1=网络, 2=混合
    long networkCostInPage;    // 页面内网络请求总耗时

    // ===== 卡顿(见4.3) =====
    int dropFrameCount;        // 丢帧数（首帧后1s内）
    int freezeEventCount;      // 卡顿事件次数（>200ms）
    long maxFreezeMs;          // 最大卡顿时长
}
```

### 4.3 卡顿上报

```java
public class LagReport {
    // ===== 标识 =====
    String lagId;              // UUID
    String scene;              // 场景标识

    // ===== 卡顿详情 =====
    long freezeDuration;       // 卡顿时长(ms)，>200ms才上报
    long fps;                  // 卡顿发生时的 fps
    StackTraceElement[] stackTrace; // 卡顿发生时的主线程堆栈

    // ===== Choreographer 监听数据 =====
    long[] skippedFrames;      // 丢帧分布（最近N帧的doFrame耗时）

    // ===== 分类 =====
    int lagType;               // 0=主线程耗时操作, 1=GC, 2=Binder阻塞, 3=IO阻塞
    String suspectMethod;      // 可疑方法名（堆栈采样+符号化）
}
```

### 4.4 FPS 监控（周期性采样上报）

```java
public class FPSReport {
    String pageName;
    long timestamp;
    float avgFps;              // 采样周期平均 FPS
    float minFps;              // 采样周期最低 FPS
    int dropFrameCount;        // 掉帧数
    float p50FrameMs;          // 帧耗时 P50
    float p90FrameMs;          // 帧耗时 P90
    float p99FrameMs;          // 帧耗时 P99
    int totalFrames;           // 总帧数
}
```

---

## 五、内存

### 5.1 内存快照上报

```java
public class MemoryReport {
    // ===== 基础 =====
    long timestamp;
    String scene;              // 采样场景（前台/后台/特定页面）

    // ===== Java 堆 =====
    long usedJavaHeap;         // Runtime.getRuntime().totalMemory() - freeMemory()
    long maxJavaHeap;          // Runtime.getRuntime().maxMemory()
    long allocatedJavaHeap;    // VMRuntime.getRuntime().totalMemory()（更精准 8.0+）

    // ===== Native =====
    long nativeHeapSize;       // Debug.MemoryInfo.nativePrivateDirty
    long nativeHeapAlloc;      // Debug.MemoryInfo.nativeHeapAllocatedSize

    // ===== PSS =====
    long totalPss;             // Debug.MemoryInfo.getTotalPss()
    long dalvikPss;
    long nativePss;
    long otherPss;

    // ===== VSS/RSS（非必须，native OOM排查时有用）=====
    long vss;
    long rss;

    // ===== GC 信息 =====
    int gcCount;               // 采样周期内 GC 次数
    long gcTime;               // 采样周期内 GC 总耗时(ms)
    long lastGcCause;          // 最近一次 GC 原因

    // ===== 对象 =====
    int activityCount;         // Activity 实例数（泄漏初筛）
    int bitmapCount;           // Bitmap 实例数

    // ===== 系统 =====
    long systemAvailableMem;   // 系统可用内存
    boolean isLowMemory;       // 是否触发 onLowMemory
    int osProcessImportance;   // 进程优先级
}
```

### 5.2 内存泄漏检测

```
  触发方式：
  - Activity.onDestroy 后 5s，WeakReference 仍可达 → 泄漏
  - 定期（前台每 5min）dump hprof 并通过 shark 分析

  上报内容（不是完整 hprof，是指纹）：
  {
    "leakActivity": "LoginActivity",
    "leakTrace": "LoginActivity → LoginViewModel → ... → GC Root",
    "leakSizeBytes": 1234567,
    "leakCategory": "handler" / "static_field" / "inner_class" / "listener"
  }
```

---

## 六、CPU & 电池

```java
public class CpuReport {
    long timestamp;
    String scene;

    // ===== App CPU =====
    float appCpuUsage;         // 进程 CPU 占比（%）
    long appCpuTime;           // 进程 CPU 时间(ms)
    long appCpuTimeUser;       // 用户态时间
    long appCpuTimeSystem;     // 内核态时间

    // ===== 系统 CPU =====
    float systemCpuUsage;      // 系统 CPU 占比（%）

    // ===== 线程 CPU =====
    List<ThreadCpuInfo> topThreads; // CPU 占用 TopN 线程

    // ===== 电池 =====
    float batteryLevel;        // 电池电量 %
    boolean isCharging;        // 是否充电中
    long batteryDrainPerHour;  // 每小时耗电(mAh)，近似值
    int thermalStatus;         // 温控状态 (Android 10+)
}

public class ThreadCpuInfo {
    String threadName;
    float cpuUsage;            // 该线程 CPU 占比
    int priority;
    String state;              // RUNNABLE / BLOCKED / WAITING
}
```

---

## 七、网络

### 7.1 网络请求全链路上报

```java
public class NetworkReport {
    // ===== 标识 =====
    String requestId;          // UUID
    String apiPath;            // 接口路径
    String httpMethod;         // GET / POST / ...

    // ===== 各阶段耗时(ms) =====
    long dnsTime;              // DNS 解析耗时
    long tcpTime;              // TCP 建连耗时
    long tlsTime;              // TLS 握手耗时
    long requestTime;          // 发送请求体耗时
    long waitingTime;          // TTFB: 等待首字节耗时
    long responseTime;         // 接收响应体耗时
    long totalTime;            // 总耗时

    // ===== 结果 =====
    int httpCode;              // HTTP 状态码
    int bizCode;               // 业务状态码（json body 中的 code）
    String errorMessage;       // 错误信息
    String exceptionType;      // 异常类型（SocketTimeoutException等）

    // ===== 数据 =====
    long requestSize;          // 请求体大小(bytes)
    long responseSize;         // 响应体大小(bytes)
    String contentType;        // Content-Type

    // ===== 连接 =====
    String protocol;           // "http/1.1" / "h2" / "h3"
    String remoteIp;           // 服务器 IP
    boolean isReusedConnection; // 是否复用连接
    boolean isHttps;
    String tlsVersion;         // "TLSv1.2" / "TLSv1.3"

    // ===== 网络环境 =====
    String networkType;        // "wifi" / "4g" / "5g"
    String carrier;            // 运营商 "中国移动" / "中国联通" / ...
}
```

### 7.2 网络错误单独上报

```java
public class NetworkErrorReport {
    String requestId;
    String apiPath;
    String errorStage;         // "dns" / "connect" / "tls" / "send" / "receive"
    String errorType;          // "timeout" / "unknown_host" / "connection_refused" / "ssl_error"
    String errorMessage;
    long totalTime;
    boolean isRetry;           // 是否重试后的请求
    int retryCount;            // 重试次数
}
```

---

## 八、行为 & 业务

### 8.1 用户行为路径

```java
public class UserActionReport {
    // ===== 标识 =====
    String userId;
    String sessionId;          // 一次 App 使用会话
    long sessionStartTime;

    // ===== 页面路径 =====
    String pageName;           // 当前页面
    String referPage;          // 来源页面
    String action;             // "page_in" / "page_out" / "click" / "slide" / ...

    // ===== 行为详情 =====
    String elementId;          // 点击的元素 ID
    String elementType;        // "button" / "tab" / "list_item"
    long duration;             // 停留时长(ms)

    // ===== 业务定制 =====
    Map<String, String> bizParams; // 业务参数
}
```

### 8.2 关键业务流程漏斗

```
  例如支付流程漏斗：

  点击购买 ──▶ 确认订单 ──▶ 支付中 ──▶ 支付成功
     100%        85%         70%        60%
                  ↓           ↓          ↓
                流失15%      流失15%     流失10%

  每个节点的上报 {
    "funnel": "purchase",
    "step": "confirm_order",
    "stepIndex": 2,
    "bizParams": { "orderId": "xxx", "amount": "99" }
  }
```

---

## 九、资源 & IO

```java
public class ResourceReport {
    // ===== 存储 =====
    long appDiskSize;          // App 占用磁盘大小
    long cacheSize;            // 缓存目录大小
    long availableStorage;     // 设备可用存储

    // ===== IO =====
    long fileReadBytes;        // 采样周期内读磁盘字节数
    long fileWriteBytes;       // 采样周期内写磁盘字节数
    long dbReadCount;          // 数据库读次数
    long dbWriteCount;         // 数据库写次数
    long dbQuerySlowCount;     // 慢查询次数（>阈值）
    long fileDescriptorCount;  // 打开的文件描述符数

    // ===== 流量 =====
    long wifiRxBytes;          // WiFi 接收流量
    long wifiTxBytes;          // WiFi 发送流量
    long mobileRxBytes;        // 移动网络接收流量
    long mobileTxBytes;        // 移动网络发送流量
}
```

---

## 十、公共环境字段（AppContext）

所有上报都携带此结构，做多维分析的基础：

```java
public class AppContext {
    // ===== 设备 =====
    String deviceId;           // 设备唯一 ID
    String deviceModel;        // "Xiaomi 14"
    String deviceBrand;        // "Xiaomi"
    String osVersion;          // "Android 14"
    int osApiLevel;            // 34
    String cpuAbi;             // "arm64-v8a"
    int screenWidth;           // px
    int screenHeight;          // px
    float density;             // dpi 密度

    // ===== App =====
    String appVersion;         // 3.2.1
    int appVersionCode;        // 30201
    String buildType;          // "release" / "debug"
    String channel;            // 渠道 "huawei" / "google_play"
    String processName;        // 进程名

    // ===== 用户 =====
    String userId;             // 登录用户 ID
    String userType;           // 用户类型（vip/normal）

    // ===== 环境 =====
    long appInstallTime;       // App 安装时间戳
    long appUpdateTime;        // App 更新时间戳
    long appStartTime;         // 本次进程启动时间戳
    long sessionDuration;      // 本次会话时长(ms)
    int launchCount;           // 累计启动次数

    // ===== 网络环境 =====
    String networkType;        // "wifi" / "4g" / "5g" / "none"
    String carrier;            // 运营商
    String countryCode;        // 国家码

    // ===== 系统状态 =====
    long availableDisk;        // 可用磁盘
    long availableMem;         // 可用内存
    boolean isRooted;          // 是否 Root
    boolean isEmulator;        // 是否模拟器
    boolean isAppDebuggable;   // 是否可调试
    String webViewVersion;     // WebView 版本
}
```

---

## 十一、上报策略

```
  上报优先级分层：

  ┌──────────────────────────────────────────────────────┐
  │  P0 —— 实时上报（立即，不压缩）                       │
  │  Crash / ANR / 用户主动反馈                           │
  ├──────────────────────────────────────────────────────┤
  │  P1 —— 批量上报（内存攒满 50 条 / 30s 定时）          │
  │  网络错误 / 页面加载失败 / 关键业务漏斗                │
  ├──────────────────────────────────────────────────────┤
  │  P2 —— 本地聚合后上报（分钟级）                       │
  │  FPS / 内存 / CPU / 页面耗时                          │
  ├──────────────────────────────────────────────────────┤
  │  P3 —— 下次 Wi-Fi 上报                               │
  │  日志文件 / 堆 dump / 全量 trace                      │
  └──────────────────────────────────────────────────────┘

  上报数据协议：
  - List<Event> 打包，Protobuf 序列化
  - GZIP 压缩
  - 大小限制：单次上报 body < 256KB
  - 失败重试：指数退避，最大 3 次
```

---

## 十二、面试题

**Q1: APM 平台需要采集哪些核心指标？**

> 四大类：**稳定性**（Crash率、ANR率）、**性能**（冷启动耗时、页面渲染耗时、FPS、卡顿率）、**网络**（成功率、各阶段耗时分布）、**资源**（内存占用、CPU占用、耗电）。每一类都需要带上下文（设备、系统版本、App版本、页面场景）做多维分析。

**Q2: 冷启动耗时怎么精确测量？**

> 起点：`Process.getStartElapsedRealtime()`（进程创建时间点，8.0+）或 `Application.attachBaseContext` 之前记录。终点：首页 `ViewTreeObserver.OnDrawListener` 首次回调（首帧绘制）或 `reportFullyDrawn()`（4.4+）。分段打点：App attach → ContentProvider → Application.onCreate → Activity.onCreate → 首帧。

**Q3: 卡顿/ANR 的上报如何尽量减少对 App 的影响？**

> 卡顿监控用 Choreographer 的 FrameCallback，子线程异步处理，超过阈值才采集堆栈。ANR 监控用 FileObserver 监听 `/data/anr/traces.txt` 并 SIGQUIT 获取堆栈。所有采集在子进程或低优先级线程，堆栈传输前压缩。上报策略上 P0 实时、P1/P2 批量合并，避免频繁网络唤醒。

**Q4: 怎么做多维下钻分析？比如“Android 14 + 小米 + 4G 网络下首页耗时”？**

> 所有上报的 Event 都携带 AppContext（设备型号、OS版本、网络类型等），后端存入 Elasticsearch 或 ClickHouse。下钻时就是在不同维度上做 filter + group by + 聚合（avg/p50/p90）。监控平台侧建好多维 Cube 预聚合来加速。

**Q5: 线上内存泄漏怎么监控？**

> 被动检测：Activity.onDestroy 后持 WeakReference，5秒后判活。主动检测：前台每 N 分钟 dump hprof（Debug.dumpHprofData），子进程通过 shark 库分析，提取泄漏链路和泄漏类型（Handler/静态引用/内部类/Listener），只上报泄漏指纹，不上传完整 hprof。

**Q6: 上报数据量太大怎么办？**

> ① 采样：全量 Crash/ANR，性能数据按比例采样（1%~10%）。② 聚合：CPU/内存/FPS 每分钟聚合为均值+P90，而非每秒上报。③ 压缩：Protobuf + GZIP，JSON 换 Protobuf 可省 60%+。④ 去重：同设备同版本同 Crash 堆栈，hash 指纹去重，只记次数。⑤ 分优先级：WiFi 才上报 P3 级数据。
