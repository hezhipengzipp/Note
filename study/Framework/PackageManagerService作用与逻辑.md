# PackageManagerService（PMS）作用与逻辑

> PMS 是 Android 的**包管家**，管理系统中所有 APK 的安装、卸载、查询、权限。你调用 `getPackageManager()` 拿到的就是它的客户端代理。

---

## 一、PMS 管什么

```
你写的代码                              系统服务
──────────                            ──────────

context.packageManager               PackageManagerService
    .getPackageInfo("com.xx", 0)          │
    .queryIntentActivities(intent)        │ 所有包信息都在这里
    .checkPermission(perm, pkg)           │
    .installPackage(uri)                  │
        │                                 │
        └──── Binder IPC ───────────────→ │
```

```
┌─ PackageManagerService ──────────────────────────────────┐
│                                                          │
│  ① 包信息管理                                             │
│     所有 APK 的 AndroidManifest.xml 解析结果              │
│     包名、版本号、四大组件、权限声明...                      │
│                                                          │
│  ② 安装 / 卸载                                           │
│     APK 的安装、更新、卸载流程                              │
│     dex 优化（dex2oat）                                   │
│     签名验证                                              │
│                                                          │
│  ③ 权限管理                                               │
│     声明权限（<permission>）                               │
│     请求权限（<uses-permission>）                          │
│     运行时权限授予 / 撤销                                   │
│                                                          │
│  ④ Intent 解析                                           │
│     startActivity(intent) 时                             │
│     根据 IntentFilter 匹配目标 Activity                   │
│     "谁能处理这个 Intent？"                                │
│                                                          │
│  ⑤ 包查询                                                │
│     getInstalledPackages()                               │
│     resolveActivity() / queryIntentActivities()          │
│     getPackageInfo() / getApplicationInfo()              │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

---

## 二、开机启动——扫描所有 APK

PMS 最重要的工作在开机时完成：**扫描所有目录下的 APK，解析 AndroidManifest.xml，建立内存数据结构**。

```
SystemServer 启动
    │
    ▼
new PackageManagerService()
    │
    ▼
扫描阶段（耗时最长，开机慢的主要原因之一）
    │
    ├─ ① 扫描系统 APK
    │     /system/app/           ← 系统预装应用
    │     /system/priv-app/      ← 系统特权应用（Settings、SystemUI）
    │     /vendor/app/           ← 厂商预装应用
    │     /product/app/          ← 产品定制应用
    │
    ├─ ② 扫描用户安装的 APK
    │     /data/app/             ← 用户安装的应用
    │
    ├─ ③ 对每个 APK 执行：
    │     ┌──────────────────────────────────────┐
    │     │  PackageParser.parsePackage(apkFile)  │
    │     │      │                                │
    │     │      ▼                                │
    │     │  解析 AndroidManifest.xml              │
    │     │      │                                │
    │     │      ├─ 包名、版本号、SDK 版本          │
    │     │      ├─ 四大组件（Activity、Service...）│
    │     │      ├─ 权限声明和请求                   │
    │     │      ├─ IntentFilter                   │
    │     │      ├─ 签名信息                        │
    │     │      └─ Native 库路径                   │
    │     │      │                                │
    │     │      ▼                                │
    │     │  生成 PackageParser.Package 对象        │
    │     └──────────────────────────────────────┘
    │
    ├─ ④ 签名验证
    │     检查 APK 签名是否合法
    │     系统应用的签名是否和上次一致
    │
    ├─ ⑤ 把解析结果存入内存数据结构
    │
    └─ ⑥ 持久化到 packages.xml
```

---

## 三、核心数据结构

```
PMS 内存中维护的数据（全局缓存，查询时不用再解析 APK）：

┌─ Settings（持久化配置）──────────────────────────────────┐
│                                                         │
│  packages.xml    ← 所有包的安装信息                       │
│  ┌───────────────────────────────────────────────────┐  │
│  │  <package name="com.example.app"                  │  │
│  │    codePath="/data/app/com.example.app-1"         │  │
│  │    version="28"                                   │  │
│  │    userId="10086"                                 │  │
│  │    installer="com.android.vending">               │  │
│  │    <perms>                                        │  │
│  │      <item name="android.permission.INTERNET"/>   │  │
│  │    </perms>                                       │  │
│  │  </package>                                       │  │
│  └───────────────────────────────────────────────────┘  │
│                                                         │
│  packages-stopped.xml  ← 被强行停止的应用                 │
│  packages.list         ← 简化列表（供底层快速查询）        │
│                                                         │
└─────────────────────────────────────────────────────────┘

┌─ 内存数据结构 ──────────────────────────────────────────┐
│                                                         │
│  mPackages: Map<String, PackageParser.Package>          │
│    "com.example.app" → Package 对象                     │
│    包含四大组件、权限、签名等全部信息                       │
│                                                         │
│  mActivities: ActivityIntentResolver                    │
│    所有 Activity 的 IntentFilter 集合                    │
│    用于 resolveIntent 时快速匹配                          │
│                                                         │
│  mServices: ServiceIntentResolver                       │
│  mReceivers: ActivityIntentResolver                     │
│  mProviders: ProviderIntentResolver                     │
│                                                         │
│  mSettings: Settings                                    │
│    每个包的 PackageSetting（uid、权限授予状态、安装路径）   │
│                                                         │
│  mPermissions: Map<String, BasePermission>              │
│    所有已注册的权限定义                                    │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

## 四、APK 安装流程

```
用户点击安装 / adb install / 应用商店下载
    │
    ▼
① 拷贝 APK
    APK 被拷贝到临时目录 /data/app/vmdl{sessionId}.tmp/
    │
    ▼
② 解析 & 验证
    PackageParser 解析 AndroidManifest.xml
    验证签名（V1/V2/V3 签名方案）
    检查版本号（升级时 versionCode 必须递增）
    检查 SDK 版本兼容性
    │
    ▼
③ 冲突检测
    包名是否已存在？
    ├─ 不存在 → 全新安装
    └─ 已存在 → 升级安装
         ├─ 签名一致？ → 允许覆盖
         └─ 签名不一致？ → 拒绝安装
    │
    ▼
④ 分配资源
    分配 UID（Linux 用户 ID，用于进程隔离）
    创建数据目录 /data/data/com.example.app/
    设置目录权限（只有该 UID 可访问）
    │
    ▼
⑤ dex 优化
    dex2oat 将 DEX 字节码编译为机器码（OAT 文件）
    存放到 /data/dalvik-cache/ 或 APK 同级目录
    这一步最耗时
    │
    ▼
⑥ 注册四大组件
    把 Activity、Service、Receiver、Provider
    注册到 PMS 的内存数据结构中
    后续 Intent 解析才能匹配到
    │
    ▼
⑦ 更新 packages.xml
    持久化安装信息
    │
    ▼
⑧ 发送广播
    ACTION_PACKAGE_ADDED（新安装）
    ACTION_PACKAGE_REPLACED（升级）
    Launcher 收到后刷新桌面图标
```

### 安装后的文件分布

```
/data/app/com.example.app-1/          ← APK 文件
    ├─ base.apk                        ← APK 本体
    ├─ lib/arm64-v8a/                  ← Native SO 库
    └─ oat/arm64/                      ← dex2oat 编译产物
         └─ base.odex

/data/data/com.example.app/            ← 应用私有数据目录
    ├─ shared_prefs/                    ← SharedPreferences
    ├─ databases/                       ← SQLite 数据库
    ├─ cache/                           ← 缓存
    └─ files/                           ← 内部文件
```

---

## 五、Intent 解析流程

当你调用 `startActivity(intent)` 时，AMS 会问 PMS："谁能处理这个 Intent？"

```
startActivity(intent)
    │
    ▼
AMS → PMS.resolveIntent(intent)
    │
    ▼
PMS 查询内存中的 IntentResolver
    │
    ├─ 显式 Intent（指定了目标组件）
    │   intent.setClass(this, TargetActivity.class)
    │   → 直接在 mPackages 里找，O(1)
    │
    └─ 隐式 Intent（只声明 action/category/data）
        intent.setAction("android.intent.action.VIEW")
        intent.setData(Uri.parse("https://..."))
            │
            ▼
        遍历 mActivities 中所有 IntentFilter
        逐一匹配 action、category、data
            │
            ├─ 只有一个匹配 → 直接启动
            ├─ 多个匹配 → 弹出选择器（"使用哪个应用打开？"）
            └─ 没有匹配 → 抛出 ActivityNotFoundException
```

### IntentFilter 匹配规则

```
<activity android:name=".BrowserActivity">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:scheme="https" />
    </intent-filter>
</activity>

匹配条件（必须全部满足）：
  ① action 匹配：Intent 的 action 在 Filter 的 action 列表中
  ② category 匹配：Intent 的所有 category 都在 Filter 中
  ③ data 匹配：scheme、host、path 逐级匹配
```

---

## 六、权限管理

```
权限的生命周期：

① 定义权限（某个 App 的 AndroidManifest.xml）
   <permission android:name="com.example.READ_DATA"
       android:protectionLevel="dangerous" />

② 请求权限（另一个 App 的 AndroidManifest.xml）
   <uses-permission android:name="com.example.READ_DATA" />

③ 安装时处理（PMS）
   ├─ normal 权限 → 自动授予
   ├─ dangerous 权限 → 标记待授权（Android 6.0+）
   └─ signature 权限 → 签名一致才授予

④ 运行时授权（Android 6.0+）
   ActivityCompat.requestPermissions()
       │
       ▼
   系统弹出权限对话框
       │
       ├─ 用户同意 → PMS 记录授权状态到 runtime-permissions.xml
       └─ 用户拒绝 → App 自行处理降级逻辑

⑤ 权限检查
   context.checkSelfPermission(permission)
       │
       ▼
   PMS 查内存中该包的权限授予状态
   → PERMISSION_GRANTED / PERMISSION_DENIED
```

### 权限保护级别

| protectionLevel | 含义 | 授权方式 |
|---|---|---|
| normal | 低风险（如 INTERNET） | 安装时自动授予 |
| dangerous | 隐私相关（相机、位置） | 运行时用户手动授予 |
| signature | 系统级（如 INSTALL_PACKAGES） | 签名和定义者一致才授予 |
| signatureOrSystem | signature + 系统应用 | 已废弃，用 privileged 替代 |

---

## 七、PMS 和其他系统服务的关系

```
┌────────────────────────────────────────────────────────┐
│                    SystemServer                         │
│                                                        │
│  ┌─ AMS ─────────────┐    ┌─ PMS ──────────────────┐  │
│  │                    │    │                         │  │
│  │ startActivity()    │───→│ resolveIntent()         │  │
│  │ "谁能处理这个       │    │ "查 IntentFilter 匹配"  │  │
│  │  Intent？"         │    │                         │  │
│  │                    │    │                         │  │
│  │ startProcess()     │───→│ getApplicationInfo()    │  │
│  │ "这个 App 的入口    │    │ "返回 APK 路径、         │  │
│  │  在哪？"           │    │  Native 库路径等"        │  │
│  └────────────────────┘    └─────────────────────────┘  │
│                                                        │
│  ┌─ Installer ────────┐    ┌─ PMS ──────────────────┐  │
│  │                    │    │                         │  │
│  │ 执行 dex2oat       │←──│ 安装时调用               │  │
│  │ 创建数据目录        │    │                         │  │
│  │ 设置文件权限        │    │                         │  │
│  └────────────────────┘    └─────────────────────────┘  │
│                                                        │
│  ┌─ UserManagerService ┐    ┌─ PMS ──────────────────┐  │
│  │                     │    │                         │  │
│  │ 多用户管理           │←→ │ 每个用户独立的           │  │
│  │                     │    │ 权限状态和安装状态        │  │
│  └─────────────────────┘    └─────────────────────────┘  │
│                                                        │
└────────────────────────────────────────────────────────┘
```

---

## 八、面试回答模板

**Q: PMS 的作用是什么？开机时做了什么？**

> PMS 是 Android 的包管理服务，负责所有 APK 的安装、卸载、查询和权限管理。开机时 PMS 扫描 /system/app、/data/app 等目录下的所有 APK，用 PackageParser 解析每个 APK 的 AndroidManifest.xml，提取包名、四大组件、IntentFilter、权限声明等信息，存入内存数据结构，并持久化到 /data/system/packages.xml。这样后续的 Intent 解析、权限检查、包信息查询都直接查内存，不用再解析 APK 文件。开机扫描是开机慢的主要原因之一，安装的应用越多越慢。

**Q: APK 安装流程是怎样的？**

> 拷贝 APK 到 /data/app 临时目录，PackageParser 解析 Manifest 并验证签名（检查升级时签名一致性），分配 UID 和创建数据目录，调用 dex2oat 将 DEX 编译为机器码，把四大组件注册到 PMS 内存中，更新 packages.xml 持久化，最后发送 PACKAGE_ADDED 广播通知 Launcher 等。其中 dex2oat 最耗时。

**Q: startActivity 时 PMS 做了什么？**

> AMS 收到 startActivity 请求后会调用 PMS 的 resolveIntent。显式 Intent 直接按包名和类名查找，O(1)；隐式 Intent 需要遍历 PMS 内存中所有注册的 IntentFilter，按 action、category、data 三个维度逐一匹配，匹配到一个直接启动，多个弹选择器，没有则抛 ActivityNotFoundException。
