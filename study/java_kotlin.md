# Java / Kotlin 基础（P0 必考）

---

## 一、JVM（必考）

### 1. 内存模型

```
┌─────────────────────────────────────────────┐
│                   JVM 内存                    │
├──────────┬──────────┬───────────────────────┤
│  线程私有  │  线程共享  │                       │
├──────────┼──────────┤                       │
│ 虚拟机栈   │   堆     │  方法区（元空间）         │
│ 本地方法栈 │          │  运行时常量池            │
│ 程序计数器 │          │                       │
└──────────┴──────────┴───────────────────────┘
```

- **虚拟机栈**：每个方法对应一个栈帧（局部变量表、操作数栈、动态链接、返回地址）
- **堆**：对象实例分配的地方，GC 主要区域
- **方法区（元空间）**：类信息、常量、静态变量。JDK 8 后用本地内存实现（Metaspace）
- **StackOverflowError**：栈深度超限；**OutOfMemoryError**：堆/元空间不足

### 2. 垃圾回收

#### 判断对象是否存活
- **引用计数法**：循环引用问题 → JVM 不用
- **可达性分析**：从 GC Roots 出发，不可达的对象回收
- **GC Roots**：栈中引用的对象、静态变量、JNI 引用

#### 回收算法
| 算法 | 原理 | 优缺点 |
|------|------|--------|
| 标记-清除 | 标记后清除 | 内存碎片 |
| 标记-整理 | 标记后移动整理 | 无碎片但慢 |
| 复制算法 | 存活对象复制到另一半 | 快但浪费空间 |
| 分代收集 | 新生代用复制，老年代用标记-整理 | 主流方案 |

#### 分代模型
```
新生代（1/3 堆）                    老年代（2/3 堆）
┌───────────┬──────┬──────┐      ┌───────────────┐
│   Eden    │  S0  │  S1  │      │               │
│  (8/10)   │(1/10)│(1/10)│      │               │
└───────────┴──────┴──────┘      └───────────────┘
   Minor GC                        Major/Full GC
```

- 对象优先在 Eden 分配
- 经过一次 Minor GC 存活 → 进入 Survivor，年龄+1
- 年龄达到阈值（默认 15）→ 晋升老年代
- 大对象直接进入老年代

### 3. 类加载

#### 类加载过程
```
加载 → 验证 → 准备 → 解析 → 初始化
```

#### 双亲委派模型
```
BootstrapClassLoader（rt.jar）
       ▲
ExtClassLoader（ext 目录）
       ▲
AppClassLoader（classpath）
       ▲
自定义 ClassLoader
```

- **工作机制**：先委托父加载器加载，父加载器无法加载时才自己加载
- **目的**：避免重复加载，保证核心类安全（如 java.lang.String 不会被篡改）
- **Android 中**：PathClassLoader（加载已安装 APK）、DexClassLoader（加载任意路径 dex）
- **热修复原理**：替换 DexElements 数组中的 dex 顺序，让修复后的类先被加载

---

## 二、Java 并发（高频）

### 1. synchronized

- **锁升级**：无锁 → 偏向锁 → 轻量级锁（CAS 自旋）→ 重量级锁（Monitor）
- **底层**：monitorenter / monitorexit 指令，对象头 Mark Word 记录锁状态
- **修饰方法**：锁的是 this（实例方法）或 Class 对象（静态方法）

### 2. volatile

- **可见性**：写入后立刻刷新到主内存，读取时从主内存读
- **禁止指令重排**：内存屏障（Memory Barrier）
- **不保证原子性**：`i++` 不是原子操作，需要 AtomicInteger

### 3. ReentrantLock vs synchronized

| 特性 | synchronized | ReentrantLock |
|------|-------------|---------------|
| 实现 | JVM 层 | API 层 |
| 可中断 | 不可 | lockInterruptibly() |
| 公平锁 | 非公平 | 可选 |
| 条件变量 | 一个 wait/notify | 多个 Condition |
| 自动释放 | 是 | 手动 unlock（finally） |

### 4. 线程池

```java
ThreadPoolExecutor(
    corePoolSize,     // 核心线程数
    maximumPoolSize,  // 最大线程数
    keepAliveTime,    // 非核心线程空闲存活时间
    TimeUnit,
    BlockingQueue,    // 任务队列
    ThreadFactory,
    RejectedExecutionHandler  // 拒绝策略
)
```

**任务提交流程**：
```
提交任务
  │
  ├── 核心线程未满 → 创建核心线程执行
  │
  ├── 核心线程已满 → 放入队列
  │
  ├── 队列已满 + 最大线程未满 → 创建非核心线程执行
  │
  └── 队列已满 + 最大线程已满 → 拒绝策略
```

**拒绝策略**：AbortPolicy（抛异常）、CallerRunsPolicy（调用者执行）、DiscardPolicy（丢弃）、DiscardOldestPolicy（丢弃最早的）

**为什么不推荐 Executors 创建线程池？**
- `newFixedThreadPool`：队列无界，可能 OOM
- `newCachedThreadPool`：最大线程数无上限，可能创建过多线程
- `newSingleThreadExecutor`：队列无界，可能 OOM

### 5. CAS（Compare And Swap）

- **原理**：比较内存值是否等于预期值，相等则更新为新值，否则重试
- **ABA 问题**：值从 A → B → A，CAS 检测不到变化。解决：`AtomicStampedReference` 加版本号
- **应用**：AtomicInteger、ConcurrentHashMap、无锁队列

### 6. ConcurrentHashMap

- **JDK 7**：Segment 分段锁，每段一把锁
- **JDK 8**：Node 数组 + CAS + synchronized（锁单个桶），粒度更细
- **扩容**：多线程协助扩容（transfer）

---

## 三、Java 集合

### HashMap
- **结构**：数组 + 链表 + 红黑树（JDK 8，链表长度 ≥ 8 且数组长度 ≥ 64 时转红黑树）
- **扩容**：默认负载因子 0.75，扩容为 2 倍，rehash
- **线程不安全**：并发 put 可能数据覆盖（JDK 7 还可能链表成环）
- **key 的 hashCode 和 equals 必须一致**

### ArrayList vs LinkedList
| | ArrayList | LinkedList |
|------|-----------|------------|
| 结构 | 动态数组 | 双向链表 |
| 随机访问 | O(1) | O(n) |
| 插入/删除 | O(n) 移动元素 | O(1) 修改指针 |
| 内存 | 连续 | 不连续，每个节点额外存指针 |

---

## 四、Kotlin 特性（面试加分）

### 1. 空安全
- `?`：可空类型，`!!`：非空断言（NPE 风险），`?.`：安全调用，`?:`：Elvis 操作符
- 编译器层面的空安全检查，减少运行时 NPE

### 2. 扩展函数
```kotlin
fun String.isEmail(): Boolean = this.matches(Regex("..."))
// 编译后：static boolean isEmail(String $this) { ... }
```
- **本质**：静态函数，第一个参数是接收者对象
- **不能访问 private 成员**，不是真正的继承

### 3. 内联函数（inline）
- 编译时将函数体直接插入调用处，避免 Lambda 创建匿名类的开销
- `noinline`：不内联某个参数；`crossinline`：禁止非局部返回

### 4. 密封类（sealed class）
- when 表达式必须覆盖所有子类，编译期检查，比枚举更灵活
- 常用于 UI 状态、网络结果封装

### 5. 委托（by）
```kotlin
class MyList<T>(private val list: List<T>) : List<T> by list
// 属性委托
val name: String by lazy { "default" }  // lazy 就是一种委托
```

### 6. 协程

详见 [kotlin.md](kotlin.md)

### 7. Flow
- **冷流**：只有收集时才执行，类似 RxJava 的 Observable
- **StateFlow**：热流，有初始值，替代 LiveData
- **SharedFlow**：热流，可配置 replay，支持多个收集者
- **操作符**：`map`、`filter`、`flatMapLatest`、`combine`、`zip` 等

---

## 五、泛型

### 协变与逆变

| | Java | Kotlin | 含义 |
|------|------|--------|------|
| 协变 | `? extends T` | `out T` | 只读，生产者 |
| 逆变 | `? super T` | `in T` | 只写，消费者 |
| PECS | Producer Extends, Consumer Super | | 记忆口诀 |

### 类型擦除
- Java 泛型在编译后擦除类型信息，运行时 `List<String>` 和 `List<Int>` 一样
- Kotlin `reified` + `inline` 可以在内联函数中保留泛型类型信息

```kotlin
inline fun <reified T> isType(value: Any): Boolean = value is T
```

---

## 六、反射与注解

### 反射
- 运行时获取类信息、调用方法、修改字段
- 性能开销较大，可用缓存优化
- Android 中常用场景：序列化框架（Gson）、依赖注入（Dagger/Hilt 编译时）

### 注解处理
| 阶段 | 技术 | 场景 |
|------|------|------|
| 编译时 | APT / KSP | Dagger、Room、ButterKnife |
| 运行时 | 反射 | Gson、Retrofit |

- **KSP vs KAPT**：KSP 直接处理 Kotlin 符号，比 KAPT（需要生成 Java stubs）快 2 倍以上
