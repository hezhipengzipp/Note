# Retrofit 原理详解

## 一、是什么

Retrofit 是一个**类型安全的 HTTP 客户端**，本质是 OkHttp 的上层封装。你只需要定义接口 + 注解，Retrofit 通过**动态代理**自动生成实现，把接口方法调用转换成 OkHttp 请求。

## 二、基本使用

```kotlin
// 1. 定义接口
interface ApiService {
    @GET("users/{id}")
    suspend fun getUser(@Path("id") id: Int): User

    @POST("users")
    suspend fun createUser(@Body user: User): Response<User>

    @GET("users")
    suspend fun getUsers(@Query("page") page: Int): List<User>
}

// 2. 创建 Retrofit 实例
val retrofit = Retrofit.Builder()
    .baseUrl("https://api.example.com/")
    .client(okHttpClient)
    .addConverterFactory(GsonConverterFactory.create())
    .build()

// 3. 创建接口实例（动态代理）
val api = retrofit.create(ApiService::class.java)

// 4. 调用
val user = api.getUser(1)
```

## 三、整体架构

```
api.getUser(1)
    │
    ▼
动态代理（Proxy.newProxyInstance）
    │
    ▼
解析方法上的注解（@GET、@Path、@Query...）
    │
    ▼
构建 OkHttp Request
    │
    ▼
CallAdapter 适配返回类型（Call / suspend / Flow / RxJava）
    │
    ▼
OkHttpClient.newCall(request).enqueue()
    │
    ▼
Converter 转换响应体（JSON → 对象）
    │
    ▼
返回结果
```

---

## 四、动态代理（核心入口）

```kotlin
// retrofit.create() 的源码（简化版）
public <T> T create(final Class<T> service) {
    return (T) Proxy.newProxyInstance(
        service.getClassLoader(),
        new Class<?>[] { service },
        new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                // 每次调用接口方法都会走到这里
                return loadServiceMethod(method).invoke(args);
            }
        }
    );
}
```

```
api.getUser(1) 发生了什么？

api 不是一个真实的对象，而是动态代理生成的代理对象
    │
    ▼
调用 getUser(1)
    │
    ▼
被 InvocationHandler.invoke() 拦截
    │
    ├── method = getUser 方法的反射对象
    ├── args = [1]
    │
    ▼
loadServiceMethod(method)
    │
    ├── 解析方法上的注解
    ├── 解析参数上的注解
    ├── 构建 ServiceMethod 对象
    ├── 缓存起来（下次不用重复解析）
    │
    ▼
ServiceMethod.invoke(args)
    │
    ▼
构建 OkHttp Request → 发起请求 → 转换响应 → 返回结果
```

### 动态代理的好处

```
没有动态代理：
  class ApiServiceImpl : ApiService {
      override fun getUser(id: Int): User {
          val request = Request.Builder()
              .url("https://api.example.com/users/$id")
              .get()
              .build()
          val response = client.newCall(request).execute()
          return gson.fromJson(response.body?.string(), User::class.java)
      }
      // 每个接口方法都要手写，大量重复代码
  }

有动态代理：
  只需要定义接口 + 注解
  Retrofit 在运行时自动生成上面的实现
  所有接口方法共享同一套逻辑（解析注解 → 构建请求 → 发起调用）
```

---

## 五、ServiceMethod 解析注解

```
loadServiceMethod(method) 会解析方法上所有注解，构建请求模板：

@GET("users/{id}")
suspend fun getUser(@Path("id") id: Int): User

解析结果：
┌──────────────────────────────────────┐
│  ServiceMethod                        │
│                                      │
│  httpMethod = "GET"                  │  ← 来自 @GET
│  relativeUrl = "users/{id}"          │  ← 来自 @GET("users/{id}")
│  parameterHandlers = [               │
│    PathHandler("id")                 │  ← 来自 @Path("id")
│  ]                                   │
│  responseType = User                 │  ← 来自返回值
│  callAdapter = SuspendCallAdapter    │  ← suspend 函数适配
│  responseConverter = GsonConverter   │  ← User 的反序列化
└──────────────────────────────────────┘
```

### ServiceMethod 缓存机制

```java
private final Map<Method, ServiceMethod<?>> serviceMethodCache = new ConcurrentHashMap<>();

ServiceMethod<?> loadServiceMethod(Method method) {
    // 先从缓存找
    ServiceMethod<?> result = serviceMethodCache.get(method);
    if (result != null) return result;

    synchronized (serviceMethodCache) {
        result = serviceMethodCache.get(method);
        if (result == null) {
            // 解析注解（反射，比较耗时）
            result = ServiceMethod.parseAnnotations(this, method);
            // 放入缓存（下次直接用，不再反射）
            serviceMethodCache.put(method, result);
        }
    }
    return result;
}

// 第一次调用 getUser() → 反射解析注解 → 缓存
// 第二次调用 getUser() → 直接从缓存取 → 不反射
```

### 注解到 Request 的转换

```
@POST("users")
suspend fun createUser(
    @Header("Token") token: String,
    @Query("source") source: String,
    @Body user: User
): Response<User>

调用：api.createUser("abc", "android", User("张三", 25))

        ↓ 注解解析 + 参数填充

POST https://api.example.com/users?source=android
Header: Token: abc
Content-Type: application/json
Body: {"name":"张三","age":25}

常见注解对应关系：
┌──────────────┬─────────────────────────────┐
│ @GET/@POST   │ HTTP 方法 + 相对路径          │
│ @Path        │ 替换 URL 中的 {占位符}        │
│ @Query       │ 拼接 URL 查询参数 ?key=value  │
│ @Body        │ 序列化为请求体                 │
│ @Header      │ 添加请求头                    │
│ @Field       │ 表单字段（配合 @FormUrlEncoded）│
│ @Multipart   │ 文件上传                      │
└──────────────┴─────────────────────────────┘
```

---

## 六、CallAdapter（适配返回类型）

**CallAdapter 负责把 OkHttp 的 `Call<T>` 适配成你想要的返回类型。**

```
Retrofit 内部统一产出的是 OkHttp 的 Call<ResponseBody>

但你的接口方法可能返回各种类型：
  fun getUser(): Call<User>              → 默认，不需要适配
  suspend fun getUser(): User            → 协程适配
  fun getUser(): Observable<User>        → RxJava 适配
  fun getUser(): Flow<User>              → Flow 适配

CallAdapter 就是做这个转换的：
  Call<ResponseBody> → 你想要的类型
```

```
不同 CallAdapter 的工作方式：

1. 默认（Call）：
   返回 Call<User>，用户自己调 call.enqueue()

2. 协程适配（suspend）：
   Retrofit 内置支持
   suspend fun getUser(): User
       ↓
   内部调用 call.enqueue()
   通过 suspendCancellableCoroutine 桥接到协程
   直接返回 User 对象

3. RxJava 适配（需要 addCallAdapterFactory）：
   fun getUser(): Observable<User>
       ↓
   RxJava3CallAdapterFactory 把 Call 包装成 Observable
   订阅时执行请求，onNext 发射结果
```

```kotlin
// 添加 RxJava 适配
Retrofit.Builder()
    .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
    .build()

// 协程适配 Retrofit 2.6.0+ 内置，不需要额外添加
```

---

## 七、Converter（数据转换）

**Converter 负责请求体的序列化和响应体的反序列化。**

```
请求方向：
  @Body user: User
      ↓ Converter（序列化）
  RequestBody: {"name":"张三","age":25}

响应方向：
  ResponseBody: {"id":1,"name":"张三","age":25}
      ↓ Converter（反序列化）
  User(id=1, name="张三", age=25)
```

```
常见 Converter：

GsonConverterFactory     → 用 Gson 做 JSON 转换
MoshiConverterFactory    → 用 Moshi 做 JSON 转换
JacksonConverterFactory  → 用 Jackson 做 JSON 转换
ScalarsConverterFactory  → 基本类型（String、Int）
ProtobufConverterFactory → Protocol Buffers
```

```kotlin
// Converter 的接口
interface Converter<F, T> {
    fun convert(value: F): T
}

// 请求体转换器：User → RequestBody
class GsonRequestBodyConverter<T>(
    private val gson: Gson,
    private val adapter: TypeAdapter<T>
) : Converter<T, RequestBody> {

    override fun convert(value: T): RequestBody {
        val json = gson.toJson(value)     // User → JSON 字符串
        return json.toRequestBody("application/json".toMediaType())
    }
}

// 响应体转换器：ResponseBody → User
class GsonResponseBodyConverter<T>(
    private val gson: Gson,
    private val adapter: TypeAdapter<T>
) : Converter<ResponseBody, T> {

    override fun convert(value: ResponseBody): T {
        return adapter.fromJson(value.charStream())  // JSON → User
    }
}
```

### 多个 Converter 的匹配顺序

```kotlin
Retrofit.Builder()
    .addConverterFactory(ScalarsConverterFactory.create())  // 先添加
    .addConverterFactory(GsonConverterFactory.create())     // 后添加
    .build()

// 匹配顺序：按添加顺序遍历，第一个能处理的就用
// 返回 String → Scalars 能处理 → 用 Scalars
// 返回 User   → Scalars 不能处理 → Gson 能处理 → 用 Gson

// 所以 Scalars 要放在 Gson 前面，否则 Gson 会把 String 也当 JSON 处理
```

---

## 八、一次完整请求的流程

```
api.getUser(1)     // suspend fun getUser(@Path("id") id: Int): User
    │
    ▼
1. 动态代理拦截
   InvocationHandler.invoke(proxy, method=getUser, args=[1])
    │
    ▼
2. 加载 ServiceMethod（有缓存直接取）
   解析 @GET("users/{id}") → httpMethod=GET, relativeUrl=users/{id}
   解析 @Path("id")        → PathHandler
   解析返回类型 User        → GsonResponseConverter
   解析 suspend             → SuspendCallAdapter
    │
    ▼
3. 填充参数，构建 OkHttp Request
   relativeUrl: users/{id} + args[0]=1 → users/1
   Request:
     GET https://api.example.com/users/1
     Headers: [补全的默认头]
    │
    ▼
4. 创建 OkHttpCall（包装了 OkHttp 的 RealCall）
    │
    ▼
5. CallAdapter 适配
   suspend → suspendCancellableCoroutine {
       call.enqueue(callback)    // 异步执行
   }
    │
    ▼
6. OkHttp 拦截器链执行
   Retry → Bridge → Cache → Connect → CallServer
    │
    ▼
7. 拿到 ResponseBody
   {"id":1,"name":"张三","age":25}
    │
    ▼
8. Converter 转换
   GsonResponseBodyConverter.convert(responseBody)
   → User(id=1, name="张三", age=25)
    │
    ▼
9. 协程恢复，返回 User 对象
```

```
整体分工：

┌─────────────┐
│  Retrofit    │  接口定义、注解解析、动态代理、类型适配、数据转换
├─────────────┤
│  OkHttp     │  连接管理、请求调度、拦截器链、缓存、真正的网络 IO
└─────────────┘

Retrofit 不做任何网络请求，它只负责"翻译"：
  接口方法 → OkHttp Request
  OkHttp Response → 你要的对象
```

---

## 九、面试高频问题

### Q1: Retrofit 的核心原理是什么？

- **动态代理**：`Proxy.newProxyInstance` 拦截接口方法调用
- 解析方法和参数上的注解，构建 OkHttp Request
- 通过 CallAdapter 适配返回类型，通过 Converter 转换数据

### Q2: 为什么用动态代理而不是注解处理器（APT）？

| | 动态代理（Retrofit） | APT（编译时生成） |
|--|-------------------|------------------|
| 时机 | 运行时 | 编译时 |
| 性能 | 首次调用有反射开销 | 无运行时开销 |
| 灵活性 | 高，可动态修改行为 | 低，编译后固定 |
| 实现复杂度 | 低 | 高 |

Retrofit 选择动态代理的原因：接口方法太多样，编译时难以穷举所有情况；反射开销通过 ServiceMethod 缓存后可忽略。

### Q3: ServiceMethod 的缓存为什么重要？

- 注解解析依赖反射，反射的性能开销大
- 同一个接口方法可能被调用上百次
- 第一次解析后缓存到 ConcurrentHashMap，后续直接取，避免重复反射
- 从 O(反射) 降到 O(HashMap 查找)

### Q4: CallAdapter 和 Converter 的区别？

```
CallAdapter：适配返回类型的"外壳"
  Call<User> → Observable<User>
  Call<User> → suspend User
  问：返回值用什么类型包装？

Converter：转换数据的"内容"
  RequestBody → User (JSON反序列化)
  User → RequestBody (JSON序列化)
  问：数据怎么从 JSON 变成对象？
```

### Q5: Retrofit 怎么支持协程的？

```kotlin
// suspend fun getUser(): User 的处理流程
// Retrofit 检测到 suspend 函数后：
// 1. 最后一个参数其实是编译器插入的 Continuation
// 2. 内部用 suspendCancellableCoroutine 桥接

suspend fun <T> Call<T>.await(): T {
    return suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback<T> {
            override fun onResponse(call: Call<T>, response: Response<T>) {
                continuation.resume(response.body()!!)
            }
            override fun onFailure(call: Call<T>, t: Throwable) {
                continuation.resumeWithException(t)
            }
        })
        continuation.invokeOnCancellation {
            cancel()  // 协程取消时取消请求
        }
    }
}
```

### Q6: Retrofit 和 OkHttp 的关系？

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
