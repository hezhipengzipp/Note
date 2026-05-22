# H5 活动页 → 下载 App → 自动进入目标页（延迟深度链接）

## 一、场景描述

```
  用户在微信/浏览器看到一个 H5 活动页
       │
       ▼
  H5 检测到未安装 App → 引导下载
       │
       ▼
  用户下载安装、首次打开 App
       │
       ▼
  App 自动跳转到刚才那个 H5 活动页对应的原生页面

  核心问题：下载发生在 App 安装之前，App 安装后怎么知道
  "用户是从哪个 H5 页面来的"？
```

---

## 二、全链路流程

```
  H5 活动页 (浏览器/微信)                  App 商店                App (首次启动)
  ──────────────────────                ────────                ──────────────

  URL: https://h5.example.com/
        activity?id=123&
        inviteCode=ABC
        │
        ├─ 1. 生成带追踪参数的
        │    下载链接
        │    ?utm_source=h5
        │    &utm_campaign=activity
        │    &targetPage=/activity/123
        │    &inviteCode=ABC
        │
        ├─ 2. 尝试唤起 App
        │    (URL Scheme /
        │     Universal Links)
        │
        ├─ 已安装? ───▶ 直接打开 App
        │              进入目标页
        │
        └─ 未安装 ──▶ 跳转应用商店下载
                        │
                        │  安装完成
                        ▼
                    ┌──────────────────────────────┐
                    │  App 首次启动                  │
                    │  1. 获取 Install Referrer     │
                    │  2. 解析 targetPage=          │
                    │      /activity/123            │
                    │  3. 解析 inviteCode=ABC       │
                    │  4. 缓存参数，登录后跳转目标页  │
                    └──────────────────────────────┘
```

---

## 三、H5 侧 —— 检测 + 跳转 + 下载

```html
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>活动页</title>
</head>
<body>
  <div class="container">
    <h1>限时活动</h1>
    <button id="openApp">打开 App 参与活动</button>
  </div>

  <script>
    // ===== 配置 =====
    const CONFIG = {
      // Android
      packageName: 'com.example.app',
      customScheme: 'myapp://activity/123?inviteCode=ABC',
      androidStoreUrl: 'https://play.google.com/store/apps/details?id=com.example.app'
        + '&referrer=utm_source%3Dh5%26targetPage%3D%252Factivity%252F123%26inviteCode%3DABC',

      // iOS
      appStoreId: '1234567890',
      appStoreUrl: 'https://apps.apple.com/app/id1234567890',
      universalLink: 'https://app.example.com/activity/123?inviteCode=ABC',
    };

    // ===== 1. 尝试唤起 App =====
    function tryOpenApp() {
      const startTime = Date.now();

      // Android: 尝试 URL Scheme
      if (isAndroid()) {
        window.location.href = CONFIG.customScheme;
      }

      // iOS: 优先 Universal Link
      if (isIOS()) {
        window.location.href = CONFIG.universalLink;
      }

      // ===== 2. 超时未唤起 = 未安装 → 跳转商店 =====
      setTimeout(() => {
        const elapsed = Date.now() - startTime;
        // 如果 2 秒内页面没有隐藏（App 唤起了页面会进入后台），
        // 说明没装 App，跳下载
        if (elapsed < 2500 && !document.hidden) {
          window.location.href = isAndroid()
            ? CONFIG.androidStoreUrl
            : CONFIG.appStoreUrl;
        }
      }, 2000);
    }

    document.getElementById('openApp').addEventListener('click', tryOpenApp);

    // ===== 页面可见性变化：App 唤起成功 =====
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) {
        // App 被成功唤起，取消下载跳转
        console.log('App 已唤起');
      }
    });
  </script>
</body>
</html>
```

---

## 四、Android 侧 —— 获取 Install Referrer

### 4.1 Google Play Install Referrer API（推荐）

```
  流程：

  用户点击下载链接
    https://play.google.com/store/apps/details?id=com.example.app
    &referrer=targetPage%3D%2Factivity%2F123%26inviteCode%3DABC

  Google Play 在安装过程中广播:
    com.android.vending.INSTALL_REFERRER
    extra: referrer="targetPage=/activity/123&inviteCode=ABC"

  App 启动后通过 InstallReferrerClient 获取
```

```kotlin
// Android 原生代码（MainActivity.kt 或 专用 Plugin）

class InstallReferrerHelper(private val context: Context) {

    private var referrerClient: InstallReferrerClient? = null

    fun fetchInstallReferrer(callback: (Map<String, String>) -> Unit) {
        referrerClient = InstallReferrerClient.newBuilder(context).build()

        referrerClient?.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                when (responseCode) {
                    InstallReferrerClient.InstallReferrerResponse.OK -> {
                        val response = referrerClient?.installReferrer
                        val referrer = response?.installReferrer ?: ""
                        val params = parseReferrer(referrer)
                        callback(params)
                    }
                    InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> {
                        // 非 Google Play 渠道（如华为、小米应用商店），走方案B
                        callback(emptyMap())
                    }
                    else -> {
                        // 暂时不可用，延迟重试
                        callback(emptyMap())
                    }
                }
                referrerClient?.endConnection()
            }

            override fun onInstallReferrerServiceDisconnected() {
                // 重连
            }
        })
    }

    private fun parseReferrer(referrer: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        referrer.split("&").forEach { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                params[Uri.decode(parts[0])] = Uri.decode(parts[1])
            }
        }
        return params
    }
}
```

### 4.2 国内应用商店 —— 剪贴板方案（兜底）

Google Play Referrer 在华为/小米/VIVO 等国内应用商店不可用，需要降级方案：

```
  方案A（主流）：第三方 SDK
    → 友盟、TalkingData、AppsFlyer
    → 原理：设备指纹匹配（IP + UserAgent + 时间窗口）

  方案B（自建）：剪贴板传递
    → H5 将参数写入剪贴板
    → App 首次启动读取剪贴板
    → 不适合敏感数据（Android 10+ 对后台读剪贴板有限制）

  方案C（自建）：服务端匹配
    → H5 点击下载时，服务端记录: {deviceFingerprint, targetPage, inviteCode}
    → App 首次启动上报: {deviceFingerprint}
    → 服务端匹配 → 返回 targetPage 和 inviteCode
```

---

## 五、iOS 侧 —— Universial Links + 指纹匹配

iOS 没有 Install Referrer 机制，只能用设备指纹匹配：

```
  H5 页面上报:                     App 首次启动上报:
  ┌──────────────────┐           ┌──────────────────┐
  │ IP: 1.2.3.4      │           │ IP: 1.2.3.4      │
  │ UA: Safari/15.0  │           │ 设备名: iPhone    │
  │ 时间: 14:30:00    │           │ IDFV: xxx        │
  │ targetPage: /act │           │ 时间: 14:32:30    │
  │ inviteCode: ABC  │           └────────┬─────────┘
  └────────┬─────────┘                    │
           │                              │
           ▼                              ▼
  ┌─────────────────────────────────────────────┐
  │              服务端匹配引擎                    │
  │                                              │
  │  同一 IP + 时间窗口 (< 5min) → 匹配成功       │
  │  → 返回 { targetPage: "/activity/123",       │
  │           inviteCode: "ABC" }                │
  └─────────────────────────────────────────────┘
```

---

## 六、Flutter 侧实现（Android + iOS 统一入口）

### 6.1 Platform Channel 定义

```dart
/// 延迟深度链接管理器
class DeferredDeepLinkManager {
  static const _channel = MethodChannel('com.example.app/deferred_deeplink');

  /// App 启动时调用，获取安装来源信息
  /// 返回 null 表示没有延迟链接
  Future<DeferredLinkData?> fetchDeferredLink() async {
    try {
      final result = await _channel.invokeMethod<Map>('getDeferredLink');
      if (result == null || result.isEmpty) return null;

      return DeferredLinkData(
        targetPage: result['targetPage'] as String? ?? '/',
        inviteCode: result['inviteCode'] as String?,
        utmSource: result['utmSource'] as String?,
        utmCampaign: result['utmCampaign'] as String?,
      );
    } catch (e) {
      return null;
    }
  }
}

class DeferredLinkData {
  final String targetPage;
  final String? inviteCode;
  final String? utmSource;
  final String? utmCampaign;

  DeferredLinkData({
    required this.targetPage,
    this.inviteCode,
    this.utmSource,
    this.utmCampaign,
  });
}
```

### 6.2 Android 端实现

```kotlin
// MainActivity.kt
class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.example.app/deferred_deeplink"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                if (call.method == "getDeferredLink") {
                    fetchInstallReferrer { params ->
                        if (params.isEmpty()) {
                            // 兜底：尝试剪贴板
                            val clipboard = getFromClipboard()
                            result.success(clipboard)
                        } else {
                            result.success(params)
                        }
                    }
                } else {
                    result.notImplemented()
                }
            }
    }

    private fun fetchInstallReferrer(callback: (Map<String, String>) -> Unit) {
        try {
            val client = InstallReferrerClient.newBuilder(this).build()
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(code: Int) {
                    if (code == InstallReferrerClient.InstallReferrerResponse.OK) {
                        val ref = client.installReferrer?.installReferrer ?: ""
                        callback(parseReferrer(ref))
                    } else {
                        callback(emptyMap())
                    }
                    client.endConnection()
                }

                override fun onInstallReferrerServiceDisconnected() {
                    callback(emptyMap())
                }
            })
        } catch (e: Exception) {
            callback(emptyMap())
        }
    }

    private fun parseReferrer(referrer: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        referrer.split("&").forEach { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                map[Uri.decode(parts[0])] = Uri.decode(parts[1])
            }
        }
        return map
    }

    private fun getFromClipboard(): Map<String, String>? {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clip?.primaryClip?.getItemAt(0)?.text?.toString() ?: return null
        try {
            return parseReferrer(text)
        } catch (e: Exception) {
            return null
        }
    }
}
```

### 6.3 iOS 端实现

```swift
// AppDelegate.swift
// iOS 没有 Install Referrer，用 Universal Links + 剪贴板 + 服务端匹配

override func application(
    _ application: UIApplication,
    continue userActivity: NSUserActivity,
    restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void
) -> Bool {
    // Universal Links 进来时
    if let url = userActivity.webpageURL {
        handleUniversalLink(url)
        return true
    }
    return false
}

// 首次启动时读取剪贴板（iOS 14+ 需要权限）
func checkClipboardForDeferredLink() -> [String: String]? {
    // iOS 14+ 读取剪贴板会触发系统提示，建议用设备指纹匹配方案
    let pasteboard = UIPasteboard.general
    guard let text = pasteboard.string else { return nil }
    // 解析可能存在的 deep link 参数
    return parseReferrer(text)
}
```

### 6.4 App 启动时统一处理

```dart
// main.dart —— App 启动入口

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // 1. 初始化基础组件（数据库、网络等）
  await initInfrastructure();

  // 2. 获取延迟深度链接
  final deferredManager = DeferredDeepLinkManager();
  final deferredLink = await deferredManager.fetchDeferredLink();

  // 3. 缓存到全局，登录成功后处理
  if (deferredLink != null) {
    GetIt.I<DeepLinkHandler>().cache(deferredLink);
  }

  runApp(MyApp());
}

/// App 启动后的路由处理
class AppStartupHandler {
  Future<void> handleAfterLogin() async {
    final deepLink = GetIt.I<DeepLinkHandler>().getCached();

    if (deepLink != null) {
      // 跳转到目标页面
      final targetPage = deepLink.targetPage; // e.g. "/activity/123"
      final inviteCode = deepLink.inviteCode;

      if (targetPage.startsWith('/activity/')) {
        final activityId = targetPage.split('/').last;
        AppRouter.goToActivity(activityId, inviteCode: inviteCode);
      } else {
        AppRouter.goToHome();
      }

      // 清除缓存，避免每次都跳转
      GetIt.I<DeepLinkHandler>().clear();
    } else {
      AppRouter.goToHome();
    }
  }
}
```

---

## 七、总结——完整时序

```
  时间线 ──────────────────────────────────────────────────────▶

  浏览器/WebView             应用商店                App (首次启动)
  ──────────────            ────────                ──────────────

  H5 页面打开
      │
      ├─ 检测App是否安装
      │
      ├─ 已安装?
      │   └─ URL Scheme/Universal Link → 直接打开目标页 ✅
      │
      └─ 未安装 ──▶ 跳转商店下载
          │              │
          │          Play Store:
          │          记录 referrer
          │          ───▶ 安装完成
          │                    │
          │                    │ App 首次启动
          │                    │
          │                    ├─ Android:
          │                    │  InstallReferrerClient
          │                    │  .getInstallReferrer()
          │                    │  → "targetPage=/activity/123
          │                    │     &inviteCode=ABC"
          │                    │
          │                    ├─ iOS:
          │                    │  设备指纹匹配 / 剪贴板
          │                    │
          │                    ├─ 解析目标页面路径
          │                    │
          │                    └─ 登录后跳转到目标页 ✅
          │
          │
      国内应用商店（华为/小米/VIVO/OPPO）：
      ─────────────────────────────
      没有标准 Referrer API
         ↓
      降级到设备指纹匹配 或 第三方 SDK（友盟/AppsFlyer）
```

---

## 八、常见问题

**Q1: 用户已经安装 App，但 H5 还是跳到了下载页？**

> 原因：URL Scheme 被系统拦截（特别是微信内）或被浏览器屏蔽。解决：
> - 微信内用微信开放标签 `<wx-open-launch-app>`
> - iOS 优先用 Universal Links（不会被拦截）
> - Android 用 Intent URL + `iframe.src` 的方式，比 `window.location` 兼容性更好

**Q2: Install Referrer 读不到怎么办？**

> - Google Play Referrer 只在 Play 商店渠道有效
> - 华为/小米等渠道需要各自接入对应的 Referrer API（华为有 `HuaweiReferrerClient`）
> - 终极兜底：设备指纹匹配 + 剪贴板 + 服务端匹配

**Q3: 延迟链接的有效期是多久？**

> - Google Play Referrer 安装后 90 天内可读取
> - 国内应用商店：无标准，安装后尽快读取
> - 建议：App 第一次启动时就读，读到就缓存到本地 SharedPreferences，未读到时每 24h 重试一次

**Q4: Flutter 如何统一处理深度链接和延迟深度链接？**

> 用一个 `DeepLinkHandler` 单例统一管理：
> - **延迟链接**（首次安装）：`DeferredDeepLinkManager.fetch()`，仅在首次启动调用
> - **实时链接**（已安装 App）：通过 `getInitialUri()` + `onNewLink` Stream 监听
> - 两者解析出 `targetPage` 后，走同一套路由跳转逻辑
