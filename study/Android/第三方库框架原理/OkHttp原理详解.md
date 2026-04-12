# OkHttp 原理详解

## 整体架构

```
OkHttpClient.newCall(request)
    │
    ▼
RealCall
    │
    ├── execute()   → 同步请求（当前线程阻塞）
    └── enqueue()   → 异步请求（线程池执行，回调返回）
    │
    ▼
Dispatcher（调度器）
    │
    ▼
拦截器链（责任链模式，核心！）
    │
    ├── 应用拦截器（用户添加的）
    ├── RetryAndFollowUpInterceptor    重试与重定向
    ├── BridgeInterceptor              补全请求头 / 处理响应头
    ├── CacheInterceptor               缓存处理
    ├── ConnectInterceptor             建立连接
    ├── 网络拦截器（用户添加的）
    └── CallServerInterceptor          发送请求 / 读取响应
    │
    ▼
Response
```

## 一、基本使用

```kotlin
val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .addInterceptor(loggingInterceptor)       // 应用拦截器
    .addNetworkInterceptor(cacheInterceptor)  // 网络拦截器
    .build()

val request = Request.Builder()
    .url("https://api.example.com/users")
    .header("Authorization", "Bearer xxx")
    .build()

// 同步请求
val response = client.newCall(request).execute()

// 异步请求
client.newCall(request).enqueue(object : Callback {
    override fun onResponse(call: Call, response: Response) {
        // 在子线程回调！不是主线程
    }
    override fun onFailure(call: Call, e: IOException) {
        // 在子线程回调
    }
})
```

---

## 二、Dispatcher 调度器

```java
class Dispatcher {
    private int maxRequests = 64;           // 最大并发请求数
    private int maxRequestsPerHost = 5;     // 每个主机最大并发数
    private ExecutorService executorService; // 线程池

    // 三个队列
    private final Deque<AsyncCall> readyAsyncCalls;   // 等待执行的异步请求
    private final Deque<AsyncCall> runningAsyncCalls;  // 正在执行的异步请求
    private final Deque<RealCall> runningSyncCalls;    // 正在执行的同步请求
}
```

```
异步请求调度流程：

enqueue(call)
    │
    ▼
正在运行的请求数 < 64 且 同一 Host < 5 ?
    │
    ├── 是 → 放入 runningAsyncCalls → 提交到线程池立即执行
    │
    └── 否 → 放入 readyAsyncCalls（排队等待）

某个请求完成后：
    │
    ▼
finished() → 从 runningAsyncCalls 移除
    │
    ▼
promoteAndExecute() → 检查 readyAsyncCalls
    │
    ▼
把满足条件的等待请求提升到 running 并执行
```

```
Dispatcher 的线程池：
  核心线程数 = 0
  最大线程数 = Integer.MAX_VALUE
  存活时间 = 60 秒
  队列 = SynchronousQueue（不存储，直接交给线程）

  本质：有请求就创建线程，空闲 60 秒就回收
  并发控制靠 maxRequests 和 maxRequestsPerHost，不靠线程池
```

---

## 三、拦截器链（核心中的核心）

**责任链模式**：请求从第一个拦截器开始，依次往下传递；响应从最后一个拦截器开始，依次往上返回。

```
请求方向 ──────────────────────────────────────→

  应用拦截器 → Retry → Bridge → Cache → Connect → 网络拦截器 → CallServer
                                                                  │
←────────────────────────────────────────────── 响应方向           │
                                                              服务器
```

```kotlin
// 拦截器链的执行（简化版）
class RealInterceptorChain(
    private val interceptors: List<Interceptor>,
    private val index: Int,
    private val request: Request
) : Interceptor.Chain {

    override fun proceed(request: Request): Response {
        // 创建下一个链节点
        val next = RealInterceptorChain(interceptors, index + 1, request)
        // 当前拦截器处理
        val interceptor = interceptors[index]
        return interceptor.intercept(next)
    }
}

// 每个拦截器的结构
class MyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // 前置处理（修改 request）
        val newRequest = request.newBuilder().addHeader("token", "xxx").build()

        // 交给下一个拦截器
        val response = chain.proceed(newRequest)

        // 后置处理（修改 response）
        return response
    }
}
```

---

## 四、五大内置拦截器详解

### 1. RetryAndFollowUpInterceptor（重试与重定向）

```
请求发出
    │
    ▼
while (true) {
    try {
        response = chain.proceed(request)
    } catch (e: Exception) {
        // 连接失败、路由失败 → 重试
        // 协议错误、证书错误 → 不重试
        if (isRecoverable(e)) continue
        else throw e
    }

    // 判断是否需要重定向
    followUp = followUpRequest(response)
    //   301/302 → 新 URL
    //   401     → 添加认证信息重试
    //   407     → 代理认证
    //   408     → 超时重试

    if (followUp == null) return response
    if (++followUpCount > MAX_FOLLOW_UPS) throw exception  // 最多重定向 20 次
    request = followUp
}
```

### 2. BridgeInterceptor（桥接）

```
请求方向（补全请求头）：
┌──────────────────────────────────────────┐
│  你的 Request:                            │
│    url: https://api.example.com/users    │
│    header: Authorization: Bearer xxx      │
│                                          │
│  BridgeInterceptor 补全：                 │
│    Content-Type: application/json         │
│    Content-Length: 42                     │
│    Host: api.example.com                 │
│    Connection: Keep-Alive                │
│    Accept-Encoding: gzip                 │  ← 自动添加 gzip
│    User-Agent: okhttp/4.x                │
│    Cookie: xxx                           │  ← 从 CookieJar 读取
└──────────────────────────────────────────┘

响应方向（处理响应头）：
┌──────────────────────────────────────────┐
│  如果响应是 gzip 压缩的：                  │
│    自动解压缩                              │
│    移除 Content-Encoding 头               │
│    移除 Content-Length 头                  │
│                                          │
│  保存 Cookie 到 CookieJar                 │
└──────────────────────────────────────────┘
```

### 3. CacheInterceptor（缓存）

```
请求进来
    │
    ▼
从 DiskLruCache 中查找缓存
    │
    ├── 有缓存
    │   ├── 缓存未过期（Cache-Control: max-age 没到）
    │   │   → 直接返回缓存，不发网络请求（304 都不需要）
    │   │
    │   └── 缓存过期了
    │       → 发起条件请求（带 If-None-Match / If-Modified-Since）
    │       │
    │       ├── 服务器返回 304 Not Modified
    │       │   → 用缓存的 body + 新的 header → 返回
    │       │
    │       └── 服务器返回 200 + 新数据
    │           → 更新缓存 → 返回新数据
    │
    └── 无缓存
        → 发起正常网络请求
        → 如果响应可缓存（Cache-Control 允许）→ 写入缓存
```

```
缓存策略由 HTTP 头控制：

Cache-Control: max-age=3600       → 缓存 1 小时
Cache-Control: no-cache           → 每次都要验证（条件请求）
Cache-Control: no-store           → 不缓存
ETag: "abc123"                    → 条件请求用 If-None-Match
Last-Modified: Wed, 09 Apr 2025   → 条件请求用 If-Modified-Since
```

### 4. ConnectInterceptor（建立连接）

```
需要一个到 api.example.com:443 的连接
    │
    ▼
从连接池（ConnectionPool）找可复用的连接
    │
    ├── 找到了 → 直接复用（省去 TCP 握手 + TLS 握手）
    │
    └── 没找到 → 创建新连接
                  │
                  ├── DNS 解析（域名 → IP）
                  ├── TCP 三次握手
                  ├── TLS 握手（HTTPS）
                  └── 连接建立完成，放入连接池
```

### 5. CallServerInterceptor（真正的网络 IO）

```
拿到已建立的连接
    │
    ├── 发送请求头
    ├── 发送请求体（POST/PUT）
    ├── 读取响应头
    ├── 读取响应体
    │
    ▼
返回 Response
```

---

## 五、连接池（ConnectionPool）

```
ConnectionPool
┌──────────────────────────────────────────────┐
│                                              │
│  ┌─────────────────────────────────┐         │
│  │ 连接 1: api.example.com:443     │  空闲中  │
│  │ 连接 2: api.example.com:443     │  使用中  │
│  │ 连接 3: cdn.example.com:443     │  空闲中  │
│  └─────────────────────────────────┘         │
│                                              │
│  默认配置：                                    │
│    最大空闲连接数: 5                            │
│    空闲连接存活时间: 5 分钟                      │
│                                              │
│  清理机制：                                    │
│    后台线程定期扫描                              │
│    超过 5 分钟未使用的空闲连接 → 关闭             │
│    空闲连接数 > 5 → 关闭最久没用的               │
└──────────────────────────────────────────────┘
```

### 连接复用的条件

```
同一个连接可以复用的条件：
  1. 相同的 Host（域名）
  2. 相同的 Port（端口）
  3. 相同的协议 Scheme（http/https）
  4. 连接没有被关闭
  5. TLS 相关配置一致

HTTP/2 额外优势：
  同一个连接可以并发多个请求（多路复用）
  所以 HTTP/2 下同一个 Host 通常只需要 1 个连接
```

### 为什么需要连接池

```
不复用连接：
  请求 1: DNS → TCP 握手 → TLS 握手 → 发请求 → 关闭    (200ms)
  请求 2: DNS → TCP 握手 → TLS 握手 → 发请求 → 关闭    (200ms)
  请求 3: DNS → TCP 握手 → TLS 握手 → 发请求 → 关闭    (200ms)
  总计: 600ms

复用连接：
  请求 1: DNS → TCP 握手 → TLS 握手 → 发请求            (200ms)
  请求 2: 复用连接 → 发请求                              (30ms)
  请求 3: 复用连接 → 发请求                              (30ms)
  总计: 260ms
```

---

## 六、应用拦截器 vs 网络拦截器

```
请求经过的顺序：

  应用拦截器
      ↓
  RetryAndFollowUpInterceptor
  BridgeInterceptor
  CacheInterceptor
  ConnectInterceptor
      ↓
  网络拦截器
      ↓
  CallServerInterceptor
```

| | 应用拦截器 (addInterceptor) | 网络拦截器 (addNetworkInterceptor) |
|--|---------------------------|----------------------------------|
| 位置 | 最外层，第一个执行 | 倒数第二个，接近网络 |
| 缓存命中时 | **会执行** | **不会执行**（请求没到网络层） |
| 重定向时 | 只执行一次 | 每次重定向都执行 |
| 能看到的 Request | 原始 Request | Bridge 补全后的 Request |
| 典型用途 | 日志、统一加 Header/Token | 修改网络请求/响应、缓存控制 |

```kotlin
// 常见的应用拦截器：统一添加 Token
class AuthInterceptor(private val tokenProvider: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer ${tokenProvider()}")
            .build()
        return chain.proceed(request)
    }
}

// 常见的应用拦截器：日志
val loggingInterceptor = HttpLoggingInterceptor().apply {
    level = HttpLoggingInterceptor.Level.BODY
}

client = OkHttpClient.Builder()
    .addInterceptor(AuthInterceptor { getToken() })
    .addInterceptor(loggingInterceptor)
    .build()
```

---

## 七、面试高频问题

### Q1: OkHttp 的核心设计模式？

- **责任链模式**：拦截器链，每个拦截器处理自己的逻辑后传给下一个
- **建造者模式**：OkHttpClient.Builder、Request.Builder
- **享元模式**：连接池复用连接
- **策略模式**：缓存策略、DNS 解析策略

### Q2: 为什么拦截器用责任链而不是回调？

- 责任链可以灵活插入/移除拦截器，不需要改原有代码
- 每个拦截器只关心自己的逻辑，职责单一
- 请求和响应都能处理（前置处理 request，后置处理 response）

### Q3: OkHttp 怎么实现的连接复用？

- ConnectionPool 内部用 `Deque<RealConnection>` 存储空闲连接
- 每次请求先从池中找匹配的连接（Host + Port + Scheme）
- 找到就复用，找不到就新建，用完放回池中
- 后台 cleanupRunnable 定期清理过期连接（默认空闲 5 分钟）

### Q4: OkHttp 的缓存是怎么实现的？

- 使用 DiskLruCache 存储在磁盘
- CacheInterceptor 根据 HTTP 缓存头（Cache-Control、ETag、Last-Modified）决定缓存策略
- 缓存命中时不发网络请求，过期时发条件请求（304）

### Q5: Retrofit 和 OkHttp 的关系？

```
Retrofit 是 OkHttp 上层的封装：

  Retrofit 负责：
    接口定义 → 动态代理生成实现
    注解解析 → 构建 Request
    响应转换 → Gson/Moshi 反序列化

  OkHttp 负责：
    连接管理、请求调度、拦截器链
    真正的网络 IO

  Retrofit.create(ApiService::class.java)
      │
      ▼ 动态代理
  解析注解 → 构建 Request → OkHttpClient.newCall(request).enqueue()
```

### Q6: 一个 HTTP 请求在 OkHttp 中的完整流程？

```
1. client.newCall(request)        → 创建 RealCall
2. call.enqueue(callback)         → 交给 Dispatcher
3. Dispatcher 检查并发数          → 放入 running 或 ready 队列
4. 线程池执行                     → 进入拦截器链
5. RetryAndFollowUpInterceptor   → 处理重试和重定向
6. BridgeInterceptor             → 补全 Header，处理 gzip
7. CacheInterceptor              → 检查缓存，命中则直接返回
8. ConnectInterceptor            → 从连接池获取或新建连接
9. CallServerInterceptor         → 发送请求，读取响应
10. 响应沿拦截器链反向返回         → 回到 Callback
```
