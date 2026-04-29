# Binder 原理与 AIDL 详解

## 一、Binder 是什么

Binder 是 Android 特有的**跨进程通信（IPC）机制**，也是 Android 系统的基石。四大组件的启动、Activity 和 AMS 的通信、应用与系统服务的交互，底层都是 Binder。

## 二、为什么不用 Linux 已有的 IPC

| | 拷贝次数 | 安全性 | 易用性 |
|--|---------|--------|--------|
| 管道 | 2 次 | 一般 | 简单 |
| 消息队列 | 2 次 | 一般 | 一般 |
| 共享内存 | 0 次 | **差（无访问控制）** | 复杂 |
| Socket | 2 次 | 一般 | 一般 |
| **Binder** | **1 次** | **好（校验 UID/PID）** | **好（C/S 架构）** |

## 三、Binder 的一次拷贝原理

**核心：mmap（内存映射）**

```
传统 IPC（两次拷贝）：
┌──────────┐    copy_from_user    ┌──────────┐    copy_to_user    ┌──────────┐
│ 进程 A    │ ──────────────────→ │  内核空间  │ ──────────────→  │ 进程 B    │
│ 用户空间   │      第 1 次拷贝     │           │     第 2 次拷贝   │ 用户空间   │
└──────────┘                     └──────────┘                    └──────────┘


Binder（一次拷贝）：
┌──────────┐    copy_from_user    ┌──────────────────────────────┐
│ 进程 A    │ ──────────────────→ │  内核空间 (Binder 驱动)        │
│ 用户空间   │      第 1 次拷贝     │                              │
└──────────┘                     │  内核缓冲区                    │
                                 │      ↕  mmap 映射（同一块物理内存）│
                                 └──────────────────────────────┘
                                          ↕ 无需拷贝！
                                 ┌──────────┐
                                 │ 进程 B    │
                                 │ 用户空间   │  ← 直接读取映射区域的数据
                                 └──────────┘
```

Binder 驱动通过 mmap 把内核缓冲区和进程 B 的用户空间映射到同一块物理内存，进程 A 的数据拷贝到内核缓冲区后，进程 B 不用再拷贝，直接能读到。

## 四、Binder 的架构

```
┌──────────────────────────────────────────────────────────┐
│                      用户空间                              │
│                                                          │
│  ┌──────────┐          ┌──────────┐         ┌─────────┐ │
│  │  Client   │          │  Server   │         │ Service │ │
│  │  (App)    │          │  (系统服务) │        │ Manager │ │
│  │          │          │          │         │ (管家)   │ │
│  │ Proxy    │          │  Stub    │         │         │ │
│  └────┬─────┘          └────┬─────┘         └────┬────┘ │
│       │                     │                    │      │
├───────┼─────────────────────┼────────────────────┼──────┤
│       │              内核空间                      │      │
│       │      ┌──────────────────────────┐        │      │
│       └─────→│     Binder 驱动            │←──────┘      │
│              │  /dev/binder              │              │
│              │  管理跨进程通信              │              │
│              └──────────────────────────┘              │
└──────────────────────────────────────────────────────────┘
```

| 角色 | 职责 |
|------|------|
| **Client** | 服务的调用者（如 App） |
| **Server** | 服务的提供者（如 AMS、WMS） |
| **ServiceManager** | Binder 的"DNS"，管理所有 Server 的注册和查询 |
| **Binder 驱动** | 内核模块，负责跨进程数据传输 |

## 五、一次 Binder 通信的完整流程

```
以 App 调用 AMS.startActivity() 为例：

1. 注册阶段（系统启动时）
   AMS 启动 → 向 ServiceManager 注册 "activity" 服务

2. 获取代理
   App 向 ServiceManager 查询 "activity" 服务
   → 拿到 AMS 的 Binder 代理（BinderProxy / IActivityManager.Stub.Proxy）

3. 发起调用
   App: proxy.startActivity(intent)
     │
     ▼
   Proxy 将方法名、参数序列化（打包成 Parcel）
     │
     ▼
   调用 transact()，通过 ioctl 进入 Binder 驱动
     │
     ▼
   Binder 驱动：
     ├── copy_from_user：数据从 App 进程拷贝到内核缓冲区（唯一一次拷贝）
     ├── 找到目标进程（AMS 所在的 system_server）
     └── 唤醒 AMS 的 Binder 线程

4. 服务端处理
   AMS 的 Binder 线程被唤醒
     │
     ▼
   Stub.onTransact()：反序列化参数
     │
     ▼
   调用 AMS.startActivity() 的真正实现
     │
     ▼
   将结果写入 reply Parcel
     │
     ▼
   通过 Binder 驱动返回给 App

5. App 收到结果
   transact() 返回 → 解析 reply → 拿到结果
```

---

## 六、AIDL 详解

### 是什么

AIDL（Android Interface Definition Language）是 Android 提供的**定义跨进程接口的语言**。编译器根据 .aidl 文件自动生成 Binder 通信的 Stub 和 Proxy 代码。

```
你写的代码           编译器自动生成           底层
  .aidl 文件  ──→  Stub + Proxy 类  ──→  Binder 驱动

AIDL 只是一个代码生成工具，帮你省去手写 Binder 模板代码的麻烦
```

### 编译后自动生成的代码结构

```
IUserService.java（自动生成）
│
├── IUserService (接口)
│   ├── getUser(int id): User
│   ├── getAllUsers(): List<User>
│   └── addUser(User user)
│
├── Stub (抽象类，服务端继承)
│   ├── extends Binder implements IUserService
│   ├── onTransact()  ← 接收跨进程请求，反序列化，调用实际方法
│   └── asInterface(IBinder)  ← 判断同进程/跨进程，返回 Stub 或 Proxy
│
└── Stub.Proxy (代理类，客户端使用)
    ├── implements IUserService
    ├── getUser() → 序列化参数 → transact() → 反序列化结果
    └── addUser() → ...
```

### 完整使用示例

**1. 定义 AIDL 接口**

```aidl
// IUserService.aidl
package com.example.app;

import com.example.app.User;

interface IUserService {
    User getUser(int id);
    List<User> getAllUsers();
    void addUser(in User user);
}
```

```aidl
// User.aidl（自定义 Parcelable 需要声明）
package com.example.app;
parcelable User;
```

```kotlin
// User.kt
@Parcelize
data class User(val id: Int, val name: String) : Parcelable
```

**2. 服务端实现**

```kotlin
class UserService : Service() {

    private val userList = mutableListOf(
        User(1, "张三"),
        User(2, "李四")
    )

    private val binder = object : IUserService.Stub() {
        override fun getUser(id: Int): User? {
            return userList.find { it.id == id }
        }

        override fun getAllUsers(): List<User> {
            return userList
        }

        override fun addUser(user: User) {
            userList.add(user)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder
}
```

**3. 客户端调用**

```kotlin
class ClientActivity : AppCompatActivity() {

    private var userService: IUserService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            // asInterface：同进程返回 Stub 本身，跨进程返回 Proxy
            userService = IUserService.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            userService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, UserService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun fetchUser() {
        // 像本地方法一样调用，Proxy 内部处理了跨进程通信
        val user = userService?.getUser(1)
        textView.text = user?.name
    }
}
```

### asInterface 的同进程 / 跨进程判断

```java
public static IUserService asInterface(IBinder obj) {
    IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
    if (iin != null && iin instanceof IUserService) {
        return (IUserService) iin;  // 同进程，直接返回 Stub，不走 IPC
    }
    return new Proxy(obj);          // 跨进程，返回 Proxy
}
```

```
同进程调用：Client → Stub.方法()     直接调用，不走 Binder
跨进程调用：Client → Proxy.方法() → transact() → Binder驱动 → Stub.onTransact()
```

### AIDL 的 in、out、inout 关键字

```aidl
void printPerson(in Person person);         // 客户端 → 服务端（只传入，最常用）
void getPerson(out Person person);          // 服务端 → 客户端（只传出）
void updatePersonAge(inout Person person);  // 双向传递（开销最大，两次序列化）
```

**out 参数返回值是 void 怎么理解？**

out/inout 的"返回"不是通过方法返回值，而是**直接修改传入的对象**：

```kotlin
// 客户端调用
val person = Person()              // 创建一个空对象
personManager.getPerson(person)    // 传进去
Log.d("TAG", person.name)         // person 已经被服务端填充了数据！
```

**底层原理：Parcel 的双向传递**

每次 Binder 调用都有两个 Parcel：data（发送）和 reply（返回）：

```
                        data Parcel        reply Parcel
                        (客户端→服务端)     (服务端→客户端)

in Person               写入 person        不写
out Person              不写               写入 person
inout Person            写入 person        写入 person
```

自动生成的 Proxy 代码对比：

```java
// in：只写入 data
void printPerson(Person person) {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    person.writeToParcel(data, 0);     // 写入 data（发过去）
    mRemote.transact(CODE, data, reply, 0);
    // reply 里没有 person
}

// out：只从 reply 读回
void getPerson(Person person) {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    // data 里不写 person（服务端不需要原始数据）
    mRemote.transact(CODE, data, reply, 0);
    person.readFromParcel(reply);      // 从 reply 读取，修改了传入的 person
}

// inout：写入 data + 从 reply 读回
void updatePersonAge(Person person) {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    person.writeToParcel(data, 0);     // 写入 data（发过去）
    mRemote.transact(CODE, data, reply, 0);
    person.readFromParcel(reply);      // 从 reply 读回修改后的 person
}
```

out 的完整流程：

```
客户端                       Binder                      服务端
  │                            │                           │
  │ person = Person()（空）     │                           │
  │ getPerson(person)          │                           │
  │ ─── data: 空 ────────────→│──→ Stub.onTransact():     │
  │                            │    创建并填充 person       │
  │                            │    person.name = "张三"    │
  │                            │    person.writeToParcel(reply)
  │ ←── reply: person数据 ────│←──────────────────────────│
  │ person.readFromParcel(reply)                           │
  │ person.name == "张三" ✅                                │
```

**为什么不直接用返回值？**

```aidl
// 返回值只能返回一个值
Person getPerson(int id);

// out 参数可以一次调用"返回"多个对象
void getPersonAndAddress(out Person person, out Address address);
```

**类比理解：**

```
in:    你把一份文件交给对方看（对方看完就完了，不改你的原件）
out:   你递一张白纸过去，对方填好内容还给你
inout: 你把一份文件交给对方，对方改完还给你
```

基本类型（int、String）默认是 in，不需要标注。

### 注册 Binder 死亡监听

```kotlin
userService?.asBinder()?.linkToDeath({
    // 服务端进程被杀时收到通知
    userService = null
    reconnect()
}, 0)
```

---

## 七、跨进程传输大于 1MB 数据

### 为什么有 1MB 限制

```
Binder 驱动为每个进程分配的缓冲区大小：
  普通进程：(1024 * 1024) - (4096 * 2) ≈ 1MB
  ServiceManager：128KB

这个缓冲区被该进程的所有 Binder 通信共享
不是"一次调用 1MB"，而是"所有并发 Binder 调用共享 1MB"

超过限制时：
android.os.TransactionTooLargeException
```

### 核心思路：传文件描述符，不传数据本身

```
直接传大数据（不行）：
  进程 A ──── 5MB 数据 ────→ Binder 驱动 ──→ 进程 B
                              ↑
                         缓冲区只有 1MB，炸了！

换个思路（可以）：
  1. 在内核创建一块共享内存区域
  2. 这块区域有一个"门牌号" → 文件描述符 (fd)
  3. 通过 Binder 只传这个"门牌号"（几十字节，远小于 1MB）
  4. 对方拿到门牌号，就能直接访问那块共享内存

  进程 A ──── fd（几十字节）────→ Binder ──→ 进程 B
                                              │
                                              ▼
                                        拿着 fd 去读共享内存里的 5MB 数据
```

### 方案一：SharedMemory（共享内存，零拷贝，API 27+ 最推荐）

`android.os.SharedMemory` 是 **API 27+（Android 8.1+）** 才有的 Java/Kotlin API，不是 Android 10 才能用。  
如果要兼容 Android 8.0 或更低版本，不能只暴露 `SharedMemory` 这一种返回值，需要准备 `ParcelFileDescriptor` 兜底方案。

**AIDL 接口**

```aidl
import android.os.SharedMemory;

interface ILargeDataService {
    // API 27+ 才调用
    SharedMemory getLargeData();
}
```

**服务端**

```kotlin
private val binder = object : ILargeDataService.Stub() {

    override fun getLargeData(): SharedMemory {
        // 1. 准备大数据
        val bytes = loadLargeData()  // 比如 5MB

        // 2. 创建共享内存（在内核开辟一块区域）
        val sharedMemory = SharedMemory.create("large_data", bytes.size)  // API 27+

        // 3. 映射到服务端的用户空间，写入数据
        val buffer = sharedMemory.mapReadWrite()
        buffer.put(bytes)
        SharedMemory.unmap(buffer)

        // 4. 设为只读（安全）
        sharedMemory.setProtect(OsConstants.PROT_READ)

        // 5. 返回 SharedMemory 对象
        //    AIDL 框架会自动提取内部的 fd 通过 Binder 传给客户端
        //    fd 只有几十字节，不受 1MB 限制
        return sharedMemory
    }
}
```

**客户端**

```kotlin
fun fetchLargeData() {
    // 6. 调用 AIDL 方法，拿到 SharedMemory 对象
    //    底层：Binder 传了 fd → 客户端重建 SharedMemory 对象
    val sharedMemory = largeDataService.getLargeData()

    // 7. 映射到客户端的用户空间，读取数据
    val buffer = sharedMemory.mapReadOnly()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    // 8. 清理
    SharedMemory.unmap(buffer)
    sharedMemory.close()

    // bytes 就是那 5MB 数据
}
```

**内存视角**

```
服务端进程                    内核                      客户端进程
┌──────────┐          ┌──────────────┐           ┌──────────┐
│          │  mmap →  │              │  ← mmap   │          │
│ buffer ──┼────────→ │  共享内存区域  │ ←────────┼── buffer │
│ (写入5MB) │          │  (物理内存)   │           │ (读取5MB) │
│          │          │              │           │          │
└──────────┘          │  fd = 42     │           └──────────┘
                      └──────┬───────┘
                             │
                      Binder 只传 fd=42
                      （几十字节）
```

### 方案二：Pipe（管道，适合流式传输）

**AIDL 接口**

```aidl
interface ILargeDataService {
    ParcelFileDescriptor getLargeData();
}
```

**服务端**

```kotlin
private val binder = object : ILargeDataService.Stub() {

    override fun getLargeData(): ParcelFileDescriptor {
        // 1. 创建管道（产生两个 fd：读端和写端）
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]    // 读端 → 给客户端
        val writeFd = pipe[1]   // 写端 → 服务端自己用

        // 2. 子线程往写端写大数据
        thread {
            ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { output ->
                val largeData = loadLargeData()  // 5MB
                output.write(largeData)
                // use 会自动关闭 writeFd
            }
        }

        // 3. 把读端 fd 返回给客户端
        //    Binder 只传 readFd（几十字节），不受 1MB 限制
        return readFd
    }
}
```

**客户端**

```kotlin
fun fetchLargeData() {
    // 4. 拿到读端 fd
    val readFd = largeDataService.getLargeData()

    // 5. 从读端读取数据
    ParcelFileDescriptor.AutoCloseInputStream(readFd).use { input ->
        val bytes = input.readBytes()  // 读取 5MB
    }
}
```

**管道视角**

```
服务端                                            客户端
┌──────────────────┐                        ┌──────────────────┐
│                  │                        │                  │
│  writeFd ────写入──→ [管道缓冲区] ──读取──→ readFd           │
│  (服务端持有)     │   (内核中)              │  (客户端持有)     │
│                  │                        │                  │
│  5MB 数据流式写入 │                        │  流式读取         │
└──────────────────┘                        └──────────────────┘
                              ↑
                     Binder 只传了 readFd（几十字节）
```

### 方案一的兼容写法：API 27+ 用 SharedMemory，低版本用 ParcelFileDescriptor

如果业务希望“高版本零拷贝、低版本也能跑”，可以在 AIDL 里同时暴露两条通道：  

- API 27+：返回 `SharedMemory`
- API 26 及以下：返回 `ParcelFileDescriptor`

下面示例用同一个 AIDL 演示分支逻辑。生产项目如果 `minSdk < 27` 且非常在意低版本类加载兼容性，更稳妥的做法是：基础 AIDL 只放 `ParcelFileDescriptor` 方法，`SharedMemory` 放到 API 27+ 才会使用的扩展接口或独立类里。

**AIDL 接口**

```aidl
import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;

interface ILargeDataService {
    // API 27+ 使用，共享内存，适合大块内存数据
    SharedMemory getLargeDataBySharedMemory();

    // 全版本可用，低版本兜底；内部可以用管道或临时文件
    ParcelFileDescriptor getLargeDataByFd();
}
```

**服务端**

```kotlin
private val binder = object : ILargeDataService.Stub() {

    override fun getLargeDataBySharedMemory(): SharedMemory {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            throw UnsupportedOperationException("SharedMemory requires API 27+")
        }

        val bytes = loadLargeData()
        val sharedMemory = SharedMemory.create("large_data", bytes.size)

        val buffer = sharedMemory.mapReadWrite()
        buffer.put(bytes)
        SharedMemory.unmap(buffer)

        sharedMemory.setProtect(OsConstants.PROT_READ)
        return sharedMemory
    }

    override fun getLargeDataByFd(): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]

        thread {
            ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { output ->
                output.write(loadLargeData())
            }
        }

        return readFd
    }
}
```

**客户端**

```kotlin
fun fetchLargeData(): ByteArray {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        val sharedMemory = largeDataService.getLargeDataBySharedMemory()
        val buffer = sharedMemory.mapReadOnly()

        try {
            ByteArray(buffer.remaining()).also { bytes ->
                buffer.get(bytes)
            }
        } finally {
            SharedMemory.unmap(buffer)
            sharedMemory.close()
        }
    } else {
        val readFd = largeDataService.getLargeDataByFd()

        ParcelFileDescriptor.AutoCloseInputStream(readFd).use { input ->
            input.readBytes()
        }
    }
}
```

> 注意：如果项目 `minSdk < 27`，`SharedMemory` 相关代码必须用 `Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1` 包起来，或者放到只在 API 27+ 才会加载的类里。低版本统一走 `ParcelFileDescriptor`，不要在 Android 8.0 及以下调用 `SharedMemory.create()`、`mapReadOnly()` 等方法。

### SharedMemory + ParcelFileDescriptor vs 纯 ParcelFileDescriptor

一句话总结：`SharedMemory + ParcelFileDescriptor` 让高版本可以在不落盘的情况下直接创建共享内存块，实现真正的 mmap 跨进程共享；低版本再用 `ParcelFileDescriptor` 兜底。相比只使用文件型 `ParcelFileDescriptor`，它减少了文件系统开销，并且更适合随机读写、原地修改、反复读取的大块内存数据。

需要注意：`ParcelFileDescriptor` 本身不只代表物理文件，也可以代表 pipe、socket 等 fd。下面的“纯 ParcelFileDescriptor”主要指常见的“临时文件 + ParcelFileDescriptor”方案；如果用的是 pipe，它也不需要落盘，但它更偏单向流式传输，不适合 mmap 后随机访问。

| 维度 | 纯 ParcelFileDescriptor（文件型） | SharedMemory + ParcelFileDescriptor |
|------|----------------------------------|-------------------------------------|
| 数据来源 | 通常需要已有文件或先写入临时文件 | 任意内存数据，可动态创建 |
| 数据持久化 | 是，数据会落盘 | 否，纯内存共享 |
| 磁盘 I/O | 有读写开销 | 无文件系统读写开销 |
| 隐私安全 | 临时文件可能残留，需要主动清理 | 随最后引用释放，进程结束后不残留文件 |
| 随机访问 | 文件型支持 seek，pipe 不支持 | mmap 后可按位置访问 |
| 双向通信 | 文件型可读写但同步复杂，pipe 通常单向 | 可读写，需自行做并发同步 |
| 修改数据 | 通常要改文件内容或重建临时文件 | 可在共享内存中原地修改 |
| 生命周期 | 文件生命周期需要自己管理 | 随 fd/对象引用关闭自动释放 |
| 创建开销 | 中，涉及文件系统操作 | 低，主要是内存分配和 mmap |
| 大数据性能 | 中等，受文件系统和拷贝影响 | 高，适合内存态大数据共享 |

### 方案三：ContentProvider

ContentProvider 传输大数据时内部也是用的共享内存：

```kotlin
// 服务端 ContentProvider
class LargeDataProvider : ContentProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = File(context.cacheDir, "large_data.bin")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}

// 客户端
val inputStream = contentResolver.openInputStream(uri)
val data = inputStream?.readBytes()
```

### 方案四：分块传输

```kotlin
// AIDL 接口
interface IChunkService {
    int getTotalChunks();
    byte[] getChunk(int index);  // 每块 < 500KB
}

// 客户端拼接
val totalChunks = chunkService.getTotalChunks()
val outputStream = ByteArrayOutputStream()
for (i in 0 until totalChunks) {
    outputStream.write(chunkService.getChunk(i))
}
val fullData = outputStream.toByteArray()
```

### 方案对比

| 方案 | 性能 | 复杂度 | 数据大小限制 | 适用场景 |
|------|------|--------|------------|---------|
| **SharedMemory** | 最好（零拷贝） | 中 | 几乎无限 | 大块内存数据（API 27+） |
| **SharedMemory + ParcelFileDescriptor 兼容封装** | 高版本最好，低版本好 | 中 | 几乎无限 | 需要兼容 Android 8.0 及以下 |
| **Pipe** | 好 | 中 | 几乎无限 | 流式大数据 |
| **ContentProvider** | 好 | 低 | 几乎无限 | 文件/结构化数据 |
| **分块传输** | 差（多次 IPC） | 高 | 无限 | 兜底方案 |

不管哪种方案，核心思路都一样：**不通过 Binder 传大数据本身，而是传一个文件描述符（fd），对方拿着 fd 去别的通道读数据**。fd 只有几十字节，完全不受 1MB 限制。

---

## 八、Binder 线程池

### 基本结构

```
App 进程启动时：
┌──────────────────────────────────────────┐
│                                          │
│  主线程 (UI 线程)                         │
│  ── 处理 UI、生命周期回调                  │
│                                          │
│  Binder 线程池 (处理其他进程发来的请求)     │
│  ┌────────────────────────────────────┐  │
│  │  Binder 主线程 (Binder:xxx_1)      │  │
│  │  Binder 线程 2 (Binder:xxx_2)      │  │
│  │  Binder 线程 3 (Binder:xxx_3)      │  │
│  │  ...                               │  │
│  │  最多 16 个（包括主 Binder 线程）    │  │
│  └────────────────────────────────────┘  │
│                                          │
└──────────────────────────────────────────┘
```

### 按需创建

```
1. 进程启动时，创建 1 个 Binder 主线程
   ProcessState::startThreadPool()

2. 有请求进来但所有线程都在忙 → Binder 驱动通知进程创建新线程
   BR_SPAWN_LOOPER → 进程创建新的 Binder 线程

3. 线程数达到上限（默认 16 个）→ 不再创建，请求排队等待
```

### 线程安全问题

```kotlin
// AIDL 服务端的方法可能同时被多个 Binder 线程调用
private val binder = object : IUserService.Stub() {
    // ⚠️ 可能被多个线程同时调用
    override fun addUser(user: User) {
        userList.add(user)  // ❌ ArrayList 不是线程安全的！
    }
}

// 解决：
//   1. 用 CopyOnWriteArrayList 替代 ArrayList
//   2. 加 synchronized
//   3. 用 ConcurrentHashMap 等线程安全集合
```

---

## 九、同步阻塞调用（默认行为）

默认情况下，Binder 调用是**同步阻塞**的：

```
客户端 (主线程)                               服务端 (Binder 线程)
    │                                           │
    │  proxy.getUser(1)                         │
    │  ──────────────────────────────────────→  │
    │                                           │  处理请求...
    │  阻塞等待！                                │  查询数据库...
    │  什么都不能做                               │  耗时 2 秒...
    │  UI 卡住了！                               │
    │                                           │  处理完成
    │  ←──────────────────────────────────────  │
    │  拿到结果，继续执行                         │
    │                                           │
    
时间: ─────── 2 秒阻塞 ──────→
```

### 同步调用可能导致的问题

```
问题 1：ANR
  主线程调用远程方法 → 服务端处理慢 → 主线程阻塞 5 秒 → ANR

问题 2：死锁
  进程 A 调用进程 B（A 阻塞等 B 的结果）
  同时进程 B 调用进程 A（B 阻塞等 A 的结果）
  → 互相等待 → 死锁

  进程 A ──等待──→ 进程 B
     ↑                │
     └────等待────────┘
```

---

## 十、oneway 机制（异步 Binder 调用）

### 什么是 oneway

```aidl
interface ICallback {
    oneway void onResult(String data);     // 异步，不阻塞
    oneway void onError(int code);         // 异步，不阻塞
}

interface IUserService {
    User getUser(int id);                  // 同步（需要返回值）
    oneway void sendLog(String log);       // 异步（不需要返回值）
}
```

### oneway 的行为

```
客户端 (主线程)                               服务端 (Binder 线程)
    │                                           │
    │  proxy.sendLog("click_event")             │
    │  ──────── 请求发出 ────────→               │
    │  立即返回！不等待！                         │
    │  继续执行后续代码                           │  收到请求
    │  ...                                      │  处理中...
    │  ...                                      │  处理完成
    │                                           │

对比：
同步（默认）：
  客户端: [发送请求]──────阻塞等待──────[拿到结果] 继续执行
  服务端:            [处理请求──────返回结果]

oneway（异步）：
  客户端: [发送请求] 继续执行...
  服务端:           [处理请求──────完成（结果丢弃）]
```

### oneway 的限制

```
1. 不能有返回值
   oneway void doSomething();     // ✅
   oneway User getUser(int id);   // ❌ 编译报错

2. 不能有 out/inout 参数
   oneway void update(in User user);     // ✅ in 可以
   oneway void update(out User user);    // ❌
   oneway void update(inout User user);  // ❌

3. 不能抛异常给客户端
   服务端崩了客户端也不知道（因为没有等结果）
```

### 整个接口标记 oneway

```aidl
// 所有方法都是异步的
oneway interface IListener {
    void onStart();
    void onProgress(int percent);
    void onFinish();
}
```

### oneway 的排队机制

**同一个 Binder 引用上的 oneway 调用是串行排队的：**

```
客户端快速连续发送 3 个 oneway 调用：

客户端:
  proxy.onProgress(10)  → 立即返回
  proxy.onProgress(50)  → 立即返回
  proxy.onProgress(100) → 立即返回

Binder 驱动中的队列（针对同一个 Binder 引用）：
  [onProgress(10)] → [onProgress(50)] → [onProgress(100)]

服务端按顺序处理：
  1. onProgress(10)   ← 处理完才处理下一个
  2. onProgress(50)
  3. onProgress(100)

保证：同一个客户端对同一个 Binder 的 oneway 调用，服务端按发送顺序执行
不保证：不同客户端的 oneway 调用的顺序

注意：oneway 的队列也有大小限制，队列满了时客户端也会阻塞等待空位
```

### 系统中的实际应用

```
1. AMS → App 的通知（oneway）
   ApplicationThread 的方法大部分是 oneway：
   oneway void scheduleResumeActivity(...)
   oneway void schedulePauseActivity(...)
   oneway void scheduleLaunchActivity(...)
   → AMS 不需要等 App 执行完，发完通知就继续处理下一个

2. App → AMS 的请求（同步）
   int startActivity(...)           // App 需要知道结果
   ComponentName startService(...)

3. 回调监听器（oneway）
   oneway interface IOnClickListener {
       void onClick();
   }
   → 通知方不需要等接收方处理完
```

### 同步 vs oneway 完整对比

| | 同步（默认） | oneway（异步） |
|--|------------|---------------|
| 客户端是否阻塞 | **阻塞**，等服务端返回 | **不阻塞**，发完就走 |
| 能否有返回值 | 能 | **不能** |
| 能否抛异常 | 能 | **不能**（客户端收不到） |
| 参数方向 | in / out / inout | **只能 in** |
| 服务端执行线程 | Binder 线程池（并发） | Binder 线程池（同一引用串行） |
| 适用场景 | 需要结果的请求 | 通知、回调、日志 |
| ANR 风险 | 有（主线程调用时） | 几乎没有 |
| 死锁风险 | 有（A↔B 互调） | 没有 |

---

## 十一、面试高频问题

### Q1: Binder 为什么只需要一次拷贝？

- Binder 驱动通过 mmap 把内核缓冲区和接收进程的用户空间映射到同一块物理内存
- 发送方数据 copy_from_user 到内核缓冲区后，接收方可以直接读取
- 省去了从内核到接收进程的第二次拷贝

### Q2: Binder 怎么保证安全？

- Binder 驱动在内核中运行，可以获取调用方的 UID/PID
- 这些信息是内核填入的，**应用层无法伪造**
- 服务端可以根据 UID/PID 做权限校验

### Q3: AIDL 生成的 Stub 和 Proxy 分别在哪个进程？

```
Client 进程              Server 进程
  Proxy                   Stub
  (自动生成)               (你继承实现)
  序列化参数               反序列化参数
  transact()          →   onTransact()
  反序列化结果         ←   写入 reply
```

### Q4: 同进程调用 AIDL 会走 Binder 吗？

- 不会。`asInterface()` 会判断，同进程直接返回 Stub 对象
- 调用方直接调用 Stub 的方法，不走序列化/反序列化和 Binder 驱动

### Q5: Binder 线程池有多大？

- 默认最多 **16 个线程**（包括 1 个主 Binder 线程）
- AIDL 的服务端方法可能在任意 Binder 线程被调用，需要注意线程安全
- Messenger 之所以串行，就是因为它用 Handler 把 Binder 线程的请求转到了主线程

### Q6: Binder 线程池默认多大？能改吗？

- 默认最多 16 个（包括 1 个主 Binder 线程 + 15 个普通 Binder 线程）
- 可以通过 `ProcessState::setThreadPoolMaxThreadCount()` 修改，但一般不需要
- 16 个通常够用，如果不够说明服务端处理太慢，应该优化业务逻辑

### Q7: oneway 一定不会阻塞吗？

- 几乎不会，但有一个例外：oneway 的异步队列满了时，客户端也会阻塞等待队列有空位
- 正常使用不会遇到这个问题

### Q8: AMS 通知 App 启动 Activity 为什么用 oneway？

- AMS 要管理所有 App 的生命周期
- 如果同步等每个 App 处理完，一个 App 卡顿就会拖慢所有 App
- 用 oneway 发完通知就继续处理下一个，App 自己在主线程排队执行

### Q9: TransactionTooLargeException 怎么排查？

```
常见触发场景：
1. Intent 传大数据（Bitmap、大 List）
2. onSaveInstanceState 存了太多数据
3. AIDL 传输大对象

解决：
  - Intent 传大数据 → 改用文件/数据库/ViewModel
  - onSaveInstanceState → 只存关键 ID，用 ViewModel 存大数据
  - AIDL → 用 SharedMemory / ParcelFileDescriptor
```
