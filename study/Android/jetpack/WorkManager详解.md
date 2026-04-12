# Android WorkManager 详解

## 一、是什么

WorkManager 是 Android Jetpack 中用于**可靠执行后台任务**的组件。即使应用退出或设备重启，任务也能保证执行。

## 二、适用场景

```
┌──────────────────────────────────────────────────────────┐
│                    需要后台任务？                           │
│                        │                                  │
│            ┌───────────┴───────────┐                      │
│            ▼                       ▼                      │
│      需要保证执行？           只在应用存活时运行？            │
│            │                       │                      │
│            ▼                       ▼                      │
│      WorkManager              Coroutine / Thread          │
│                                                          │
│  - 上传日志                    - 网络请求                   │
│  - 同步数据                    - 加载列表                   │
│  - 定期清理缓存                - 计算 UI 数据               │
│  - 下载文件                                               │
│  - 发送埋点                                               │
└──────────────────────────────────────────────────────────┘
```

**简单记忆**：任务需要"杀了 App 也要跑完" → WorkManager；只在前台用 → 协程就够了。

## 三、基本使用

### 1. 添加依赖

```kotlin
// build.gradle
dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
```

### 2. 定义 Worker

```kotlin
class UploadLogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val logData = inputData.getString("log_data") ?: return Result.failure()

        return try {
            // 执行上传
            ApiService.uploadLog(logData)

            // 可以传数据给下一个任务
            val output = workDataOf("upload_id" to "12345")
            Result.success(output)
        } catch (e: Exception) {
            if (runAttemptCount < 3) {
                Result.retry()   // 重试
            } else {
                Result.failure() // 彻底失败
            }
        }
    }
}
```

三种 Result：

| Result | 含义 |
|--------|------|
| `Result.success()` | 任务成功，可携带输出数据 |
| `Result.failure()` | 任务失败，不再重试 |
| `Result.retry()` | 任务失败，按退避策略重试 |

### 3. 构建任务请求

```kotlin
// 一次性任务
val uploadRequest = OneTimeWorkRequestBuilder<UploadLogWorker>()
    .setInputData(workDataOf("log_data" to "crash info..."))
    .setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)  // 需要网络
            .setRequiresBatteryNotLow(true)                 // 电量不低
            .build()
    )
    .setBackoffCriteria(
        BackoffPolicy.EXPONENTIAL,  // 指数退避
        30, TimeUnit.SECONDS        // 初始 30 秒
    )
    .addTag("upload_log")
    .build()

// 周期性任务（最小间隔 15 分钟）
val syncRequest = PeriodicWorkRequestBuilder<SyncDataWorker>(
    1, TimeUnit.HOURS           // 每小时
)
    .setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED) // 需要 WiFi
            .build()
    )
    .build()
```

### 4. 提交任务

```kotlin
val workManager = WorkManager.getInstance(context)

// 提交一次性任务
workManager.enqueue(uploadRequest)

// 提交周期性任务（唯一任务，防止重复）
workManager.enqueueUniquePeriodicWork(
    "sync_data",                          // 唯一任务名
    ExistingPeriodicWorkPolicy.KEEP,      // 已存在就保留旧的
    syncRequest
)
```

## 四、任务链

多个任务可以串行或并行组合：

```kotlin
// 串行：压缩 → 上传 → 清理
workManager
    .beginWith(compressWork)
    .then(uploadWork)
    .then(cleanupWork)
    .enqueue()

// 并行 + 串行：先并行压缩图片和压缩日志，都完成后再上传
workManager
    .beginWith(listOf(compressImageWork, compressLogWork))  // 并行
    .then(uploadWork)                                       // 都完成后串行
    .enqueue()
```

```
compressImage ──┐
                ├──→ upload ──→ cleanup
compressLog  ──┘
```

## 五、观察任务状态

```kotlin
// 通过 ID 观察
workManager.getWorkInfoByIdLiveData(uploadRequest.id)
    .observe(this) { workInfo ->
        when (workInfo.state) {
            WorkInfo.State.ENQUEUED   -> { /* 等待执行 */ }
            WorkInfo.State.RUNNING    -> { /* 执行中 */ }
            WorkInfo.State.SUCCEEDED  -> {
                val uploadId = workInfo.outputData.getString("upload_id")
            }
            WorkInfo.State.FAILED     -> { /* 失败 */ }
            WorkInfo.State.CANCELLED  -> { /* 被取消 */ }
            WorkInfo.State.BLOCKED    -> { /* 被前置任务阻塞 */ }
        }
    }

// 通过 Tag 观察
workManager.getWorkInfosByTagLiveData("upload_log")
    .observe(this) { workInfoList -> /* ... */ }
```

## 六、取消任务

```kotlin
// 按 ID 取消
workManager.cancelWorkById(uploadRequest.id)

// 按 Tag 取消
workManager.cancelAllWorkByTag("upload_log")

// 按唯一任务名取消
workManager.cancelUniqueWork("sync_data")

// 取消所有
workManager.cancelAllWork()
```

## 七、约束条件汇总

```kotlin
Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)    // 任意网络
    .setRequiredNetworkType(NetworkType.UNMETERED)    // WiFi
    .setRequiresBatteryNotLow(true)                   // 电量不低
    .setRequiresCharging(true)                        // 充电中
    .setRequiresStorageNotLow(true)                   // 存储空间充足
    .setRequiresDeviceIdle(true)                      // 设备空闲（API 23+）
    .build()
```

## 八、底层实现原理

```
WorkManager
    │
    ▼
┌─────────────────────────────────┐
│  任务信息存储在 Room 数据库中       │  ← 保证 App 被杀/重启后任务不丢失
│  (androidx.work.workdb)         │
└────────────┬────────────────────┘
             │
             ▼ 根据 API 级别选择执行方式
┌─────────────────────────────────┐
│  API 23+:  JobScheduler          │  ← 系统级调度，最优选择
│  API 14-22: AlarmManager +       │
│             BroadcastReceiver    │
└─────────────────────────────────┘
```

WorkManager 本身不执行任务，它是一个**调度器**，把任务委托给系统最合适的机制。

## 九、面试高频问题

### Q1: WorkManager 和 Service 的区别？

| | WorkManager | Service |
|--|------------|---------|
| 生命周期 | 独立于 App，App 被杀也能执行 | 依附于 App 进程 |
| 适合场景 | 可延迟的、需要保证完成的任务 | 需要立即执行的前台任务（如音乐播放） |
| 系统限制 | 遵守系统省电策略，系统统一调度 | Android 8.0+ 后台 Service 限制严格 |
| 约束条件 | 内置支持（网络、电量、充电等） | 需要自己实现 |

### Q2: WorkManager 怎么保证任务一定执行？

- 任务信息持久化在 **Room 数据库**中，App 被杀或设备重启后，系统会重新调度
- 底层使用 JobScheduler / AlarmManager，由系统负责唤醒执行
- 但"保证执行"不等于"立即执行"，系统会根据 Doze 模式、省电策略等延迟执行

### Q3: PeriodicWork 的最小间隔为什么是 15 分钟？

- 底层依赖 JobScheduler，而 JobScheduler 的最小周期就是 15 分钟
- 这是 Android 系统为了省电做的限制
- 如果需要更短的间隔，应该用 ForegroundService + 自己的定时逻辑

### Q4: Worker、CoroutineWorker、RxWorker 怎么选？

| 类型 | 线程模型 | 适用场景 |
|------|---------|---------|
| `Worker` | 在后台线程同步执行 | 简单的同步任务 |
| `CoroutineWorker` | 在协程中挂起执行 | Kotlin 项目首选，支持 suspend |
| `RxWorker` | 返回 Single/Completable | RxJava 项目 |

### Q5: WorkManager 的任务在 Doze 模式下能执行吗？

- 普通任务会被延迟到 Doze 的维护窗口期执行
- 如果确实需要在 Doze 模式下执行，需要用 `setExpedited()` 加急任务（Android 12+），或者 ForegroundService

### Q6: 怎么避免周期性任务重复注册？

```kotlin
// 使用 enqueueUniquePeriodicWork，同名任务只会有一个实例
workManager.enqueueUniquePeriodicWork(
    "sync_data",                       // 唯一名称
    ExistingPeriodicWorkPolicy.KEEP,   // KEEP=已存在就保留, UPDATE=替换
    syncRequest
)
```
