# Java 常用数据结构原理大全

## 一、List 体系

### 1.1 ArrayList — 动态数组

**底层结构**：`Object[]` 数组

```java
transient Object[] elementData;
private int size;
```

**关键原理**：

```
初始容量 = 10（懒加载，首次 add 才初始化）

add 流程：
  → 检查是否需要扩容
  → 需要则 grow() → 1.5 倍扩容 (oldCapacity + oldCapacity >> 1)
  → 数组拷贝 Arrays.copyOf(elementData, newCapacity)
  → elementData[size++] = e

remove 流程：
  → 计算要移动的元素个数 (numMoved = size - index - 1)
  → System.arraycopy(elementData, index+1, elementData, index, numMoved)
  → 最后一个置 null（让 GC 回收）
```

**时间复杂度**：

| 操作 | 时间复杂度 | 说明 |
|------|-----------|------|
| get(i) | O(1) | 直接数组下标 |
| add(e) | O(1) 摊还 | 均摊扩容成本，大部分情况 O(1) |
| add(i, e) | O(n) | 需要移动元素 |
| remove(i) | O(n) | 需要移动元素 |
| contains(e) | O(n) | 遍历查找 |

**面试坑**：
- `subList()` 返回的是 **视图**，不是新 List，修改会影响原 List
- 删除元素时用 `Iterator.remove()` 而不是 for-i remove（会抛 ConcurrentModificationException）
- 批量 addAll 时容量预估：`new ArrayList<>(expectedSize)` 或 `ensureCapacity()`

### 1.2 LinkedList — 双向链表

**底层结构**：Node 节点

```java
private static class Node<E> {
    E item;
    Node<E> next;
    Node<E> prev;
}

transient Node<E> first;  // 头节点
transient Node<E> last;   // 尾节点
```

**时间复杂度**：

| 操作 | 时间复杂度 |
|------|-----------|
| get(i) | O(n) — 遍历到第 i 个 |
| add(e) | O(1) — 尾部追加 |
| add(i, e) | O(n) — 先找到位置 |
| remove(e) | O(n) — 遍历查找 |
| addFirst/addLast | O(1) |
| removeFirst/removeLast | O(1) |

**既是 List 又是 Deque**：

```java
// 作为队列 Queue
Queue<String> queue = new LinkedList<>();
queue.offer("a");    // 尾部入队
queue.poll();        // 头部出队
queue.peek();        // 查看头部

// 作为双端队列 Deque
Deque<String> deque = new LinkedList<>();
deque.addFirst("a");
deque.addLast("b");
deque.removeFirst();
```

**ArrayList vs LinkedList 对比**：

| | ArrayList | LinkedList |
|---|---|---|
| 内存 | 连续空间，无额外指针开销 | 每个节点多 2 个指针 (~16 bytes) |
| 随机访问 | O(1) | O(n) |
| 尾部插入 | O(1) 摊还 | O(1) |
| 头部插入 | O(n) | O(1) |
| 内存碎片 | 少（连续） | 多（离散） |
| 实际使用 | **绝大部分场景** | 只在频繁头尾操作时 |

### 1.3 CopyOnWriteArrayList — 线程安全 ArrayList

**原理**：写时复制

```java
private transient volatile Object[] array;

// 读：不加锁，直接读
public E get(int index) {
    return get(getArray(), index);
}

// 写：加锁 + 复制新数组
public boolean add(E e) {
    synchronized (lock) {
        Object[] elements = getArray();
        int len = elements.length;
        Object[] newElements = Arrays.copyOf(elements, len + 1);
        newElements[len] = e;
        setArray(newElements);  // volatile write → 读线程可见
        return true;
    }
}
```

**特点**：
- **读无锁**，适合读多写少的场景
- **弱一致性**：迭代器遍历的是创建时的快照，不会反映后续修改
- 写操作复制整个数组，**不适合大集合 + 频繁写**

**适用场景**：监听器列表、缓存路由表、黑白名单。

---

## 二、Map 体系

### 2.1 HashMap（已有单独文档，这里列重点）

- 数组 + 链表 + 红黑树（JDK 8+）
- 默认负载因子 0.75
- 扩容为 2 倍（`tableSizeFor` 保证容量是 2^n）
- 链表转红黑树：链表长度 ≥ 8 且数组容量 ≥ 64
- 红黑树转链表：树节点 ≤ 6

### 2.2 LinkedHashMap — 有序 HashMap

**原理**：HashMap + 双向链表维护顺序

```java
static class Entry<K,V> extends HashMap.Node<K,V> {
    Entry<K,V> before, after;  // 双向链表指针
}

// accessOrder = false → 插入顺序（默认）
// accessOrder = true  → 访问顺序（LRU Cache）
final boolean accessOrder;
```

**LRU 缓存实现**：

```java
class LRUCache<K,V> extends LinkedHashMap<K,V> {
    private final int maxCapacity;

    public LRUCache(int maxCapacity) {
        super(16, 0.75f, true);  // accessOrder = true
        this.maxCapacity = maxCapacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K,V> eldest) {
        return size() > maxCapacity;
    }
}
```

`removeEldestEntry` 在每次 put 后调用，返回 true 则移除最久未访问的 entry。

### 2.3 TreeMap — 红黑树实现的有序 Map

**底层**：红黑树（自平衡二叉查找树）

```java
// key 必须实现 Comparable，或传入 Comparator
TreeMap<String, Integer> map = new TreeMap<>();
map.put("b", 2);
map.put("a", 1);
map.put("c", 3);
// 遍历顺序：a → b → c（key 的自然顺序）

// 常用方法：
map.firstKey();       // 最小 key
map.lastKey();        // 最大 key
map.lowerKey(k);      // 小于 k 的最大 key
map.higherKey(k);     // 大于 k 的最小 key
map.subMap(from, to); // 子视图
```

**时间复杂度**：所有操作 O(log n)

**适用场景**：需要按 key 排序的范围查询、求上下界。

### 2.4 ConcurrentHashMap — 线程安全 HashMap

#### JDK 7：分段锁 (Segment)

```java
// Segment 继承 ReentrantLock
static final class Segment<K,V> extends ReentrantLock {
    transient volatile HashEntry<K,V>[] table;
}

// 默认 16 个 Segment，最多支持 16 线程并发写
final Segment<K,V>[] segments;
```

```
put 流程：
  → hash 定位到 Segment
  → tryLock() 获取段锁（自旋 + 阻塞）
  → 在 Segment 内部的 table 操作（类似 HashMap）
  → 扩容也是 Segment 级别，不影响其他 Segment
```

#### JDK 8：CAS + synchronized + 红黑树

```
put 流程：
  → 计算 hash
  → 循环 CAS：
    ① 数组为空 → CAS 初始化 table
    ② 位置为空 → CAS 放置新 Node（无锁）
    ③ 位置有数据 → synchronized(头节点) 写入链表/红黑树
    ④ 链表长度 ≥ 8 → treeifyBin（转红黑树）
  → addCount（CAS 更新 size）

get 流程：
  → 完全无锁
  → Node 的 value 和 next 都是 volatile
  → 保证可见性
```

**size 计算**：

```java
// 先不加锁累加，如果两次结果不一致才加锁
// 使用 CounterCell 数组分散计数，避免多线程 CAS 竞争同一变量
public int size() {
    long sum = sumCount();  // 累加所有 CounterCell + baseCount
    return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
}
```

**面试重点**：

| 特性 | JDK 7 | JDK 8 |
|------|-------|-------|
| 并发控制 | Segment + ReentrantLock | CAS + synchronized |
| 锁粒度 | Segment（多个桶） | 链表头节点（单个桶） |
| 并发度 | segments 数组长度（默认 16） | 数组容量 |
| 迭代 | 弱一致性 | 弱一致性 |
| 红黑树 | ❌ | ✅ |

**为什么 JDK 8 改用 synchronized 而不是 ReentrantLock？**

> synchronized 在 JDK 8 有锁升级（偏向锁 → 轻量锁 → 重量锁），竞争不激烈时性能好。synchronized 不需要内存释放，代码更简洁。

---

## 三、Set 体系

| Set | 底层 | 顺序 | 允许 null | 线程安全 |
|-----|------|------|-----------|---------|
| **HashSet** | HashMap (value = PRESENT) | 无序 | ✅ 一个 | ❌ |
| **LinkedHashSet** | LinkedHashMap | 插入顺序 | ✅ 一个 | ❌ |
| **TreeSet** | TreeMap (NavigableMap) | 自然/比较器排序 | ❌ | ❌ |
| **CopyOnWriteArraySet** | CopyOnWriteArrayList | 插入顺序 | ✅ | ✅ |

```java
// HashSet 本质上就是 HashMap
public HashSet() {
    map = new HashMap<>();
}

// add 时 value 固定为一个 dummy 对象
private static final Object PRESENT = new Object();

public boolean add(E e) {
    return map.put(e, PRESENT) == null;  // key 存在返回 false
}
```

---

## 四、Queue 体系

### 4.1 总览

```
Queue (接口)
├── Deque (双端队列接口)
│   ├── ArrayDeque       ← 循环数组，无锁
│   ├── LinkedList       ← 双向链表
│   └── ConcurrentLinkedDeque  ← CAS 链表
├── PriorityQueue        ← 二叉堆，无锁
├── BlockingQueue (接口)
│   ├── ArrayBlockingQueue     ← 有界数组 + 锁
│   ├── LinkedBlockingQueue    ← 可选有界 + 锁
│   ├── PriorityBlockingQueue  ← 二叉堆 + 锁
│   ├── SynchronousQueue       ← 零容量 / 直接交付
│   ├── DelayQueue             ← 延迟执行
│   └── LinkedTransferQueue    ← TransferQueue 实现
└── ConcurrentLinkedQueue      ← CAS 链表
```

### 4.2 ArrayDeque — 循环数组双端队列

**底层**：循环数组

```java
transient Object[] elements;  // 存储元素
transient int head;           // 头指针
transient int tail;           // 尾指针

// 初始容量 = 8，始终是 2 的幂
// 扩容为 2 倍
```

```
空队列：
[ ][ ][ ][ ][ ][ ][ ][ ]
  ↑
 head = tail = 0

addFirst(a)：
[a][ ][ ][ ][ ][ ][ ][ ]
 ↑
head = 0, tail = 1

addFirst(b)：
[a][ ][ ][ ][ ][ ][ ][b]     ← head 向前循环
 ↑                       ↑
tail=1                  head=-1 & (len-1) = 7

计算方式：
  head = (head - 1) & (elements.length - 1)  // 利用 2^n 取模
  tail = (tail + 1) & (elements.length - 1)
```

**特点**：
- 比 Stack 快（Stack 用 Vector，同步开销大）
- 比 LinkedList 做队列快（连续内存，CPU cache 友好）
- 不支持 null
- **JDK 官方推荐**用 ArrayDeque 替代 Stack 和 LinkedList（做队列时）

**时间复杂度**：头尾操作 O(1)，**没有扩容的摊还成本**（翻倍扩容）。

### 4.3 PriorityQueue — 二叉堆实现的优先队列

**底层**：二叉堆（最小堆）

```java
transient Object[] queue;  // 堆数组
private int size = 0;
private final Comparator<? super E> comparator;

// 初始容量 = 11
```

```
堆结构（小顶堆）：
       1
     /   \
    3     2
   / \   /
  7   6 5

数组表示：[1, 3, 2, 7, 6, 5]

父节点索引 = (i - 1) >>> 1
左子节点索引 = 2 * i + 1
右子节点索引 = 2 * i + 2
```

**offer (上浮)**：

```java
public boolean offer(E e) {
    int i = size;
    if (i >= queue.length) grow(i + 1);  // 扩容
    siftUp(i, e);  // 从末尾开始上浮
    size = i + 1;
    return true;
}

private void siftUp(int k, E x) {
    while (k > 0) {
        int parent = (k - 1) >>> 1;  // 父节点
        if (key >= queue[parent]) break;
        queue[k] = queue[parent];    // 父节点下移
        k = parent;                  // 继续上浮
    }
    queue[k] = x;
}
```

**poll (下沉)**：

```java
public E poll() {
    E result = (E) queue[0];
    E x = (E) queue[--size];   // 最后一个元素
    queue[size] = null;
    siftDown(0, x);            // 从堆顶开始下沉
    return result;
}

private void siftDown(int k, E x) {
    int half = size >>> 1;
    while (k < half) {
        int child = (k << 1) + 1;     // 左子
        int right = child + 1;
        if (right < size && queue[child] > queue[right])
            child = right;            // 选较小的子节点
        if (key <= queue[child]) break;
        queue[k] = queue[child];      // 子节点上移
        k = child;                    // 继续下沉
    }
    queue[k] = x;
}
```

**特点**：
- 非线程安全（线程安全版 → PriorityBlockingQueue）
- 不允许 null
- 迭代器不保证顺序（堆的数组不是完全有序的）
- **插入 O(log n)，删除 O(log n)，peek O(1)**

### 4.4 BlockingQueue 体系

```
阻塞队列核心方法对比：

         | 抛异常   | 返回特殊值 | 阻塞等待 | 超时等待
---------|----------|------------|----------|---------
插入     | add(e)   | offer(e)   | put(e)   | offer(e, t, u)
删除     | remove() | poll()     | take()   | poll(t, u)
检查     | element()| peek()     | —        | —
```

#### ArrayBlockingQueue — 有界阻塞队列

```java
final Object[] items;           // 固定数组
int takeIndex;                  // 出队指针
int putIndex;                   // 入队指针
int count;                      // 元素个数
final ReentrantLock lock;       // 全局锁
private final Condition notEmpty;  // 出队条件
private final Condition notFull;   // 入队条件
```

```
循环数组：
[ ][ ][ ][ ][ ][ ][ ][ ]
   ↑        ↑
 takeIndex putIndex
  (出队)    (入队)

  put 阻塞：队列满时 await(notFull)
  take 阻塞：队列空时 await(notEmpty)
  ★ 进出用同一个全局锁（读/写互斥）
```

**特点**：
- **有界**，容量固定
- **公平模式**：构造时设置 `true` 会让线程按 FIFO 排队（性能略降）
- 一把锁，生产者和消费者竞争同一把锁

#### LinkedBlockingQueue — 可选有界阻塞队列

```java
private final int capacity;           // 默认 Integer.MAX_VALUE
private final AtomicInteger count;    // 元素计数（Atomic，因为两把锁共享）
private transient Node<E> head;       // 哨兵节点
private transient Node<E> last;
private final ReentrantLock takeLock; // 出队锁
private final Condition notEmpty;     // 出队条件
private final ReentrantLock putLock;  // 入队锁
private final Condition notFull;      // 入队条件
```

**特点**：
- **两把锁**：takeLock 和 putLock，生产和消费可以并行
- 默认无界（Integer.MAX_VALUE），不设容量时会内存溢出
- 链表结构，理论上无线程竞争时比 ArrayBlockingQueue 快

```
生产者线程 (putLock)        消费者线程 (takeLock)
    │                            │
    ▼                            ▼
  enqueue(node)              dequeue()
    │                            │
    ▼                            ▼
  count.incrementAndGet()    count.decrementAndGet()
    │                            │
    ▼                            ▼
  signalNotEmpty()           signalNotFull()
```

#### PriorityBlockingQueue — 线程安全优先队列

```java
// = PriorityQueue + ReentrantLock + Condition
private final ReentrantLock lock;
private final Condition notEmpty;
private PriorityQueue<E> q;  // 实际就是个 PriorityQueue
```

- 内部用**二叉堆**，插入 O(log n)，取出 O(log n)
- **无界**（可能导致 OOM）
- 插入永不会阻塞（因为无界），take 会在空时阻塞

#### SynchronousQueue — 零容量队列

```
SynchronousQueue 不存储元素：
put 线程必须等待一个 take 线程来取走
take 线程必须等待一个 put 线程交过来

相当于"握手"机制：

put(1) ────▶  ────▶ take() → 1
            X   ← 直接交付，不经过容器
put(2) ────▶  等待  take() → 2
```

**内部实现**（两种模式）：
- **公平模式 (TransferQueue)**：FIFO 队列，用 CAS 管理等待线程
- **非公平模式 (TransferStack)**：LIFO 栈，吞吐量更高

**用途**：CachedThreadPool 的任务队列（核心就是 SynchronousQueue，来了任务直接交给线程，不排队）。

#### DelayQueue — 延迟队列

```java
// 内部 = PriorityQueue + ReentrantLock + Condition
// 元素必须实现 Delayed 接口
public interface Delayed extends Comparable<Delayed> {
    long getDelay(TimeUnit unit);  // 返回剩余延迟时间
}

// take 时只返回已到期的元素
public E take() throws InterruptedException {
    for (;;) {
        E first = q.peek();
        if (first == null) {
            available.await();    // 队列空，阻塞
        } else {
            long delay = first.getDelay(NANOSECONDS);
            if (delay <= 0) return q.poll();  // 到期，出队
            // 未到期，等待 delay 时间
            available.awaitNanos(delay);
        }
    }
}
```

**用途**：定时任务调度、Session 超时管理、缓存过期清理。

---

## 五、并发队列（非阻塞）

### 5.1 ConcurrentLinkedQueue — 无锁并发队列

**底层**：单链表 + CAS

```java
private static class Node<E> {
    volatile E item;
    volatile Node<E> next;
}

private transient volatile Node<E> head;
private transient volatile Node<E> tail;
```

**核心算法**：Michael & Scott 非阻塞链表队列

```
offer (CAS 入队)：

初始：head → 哨兵(null) ← tail
                          ↓
               哨兵 → 1 ← tail    CAS(tail.next, null, newNode)
                          ↓
               哨兵 → 1 → 2 ← tail  CAS(tail.next, null, newNode)
```

```java
public boolean offer(E e) {
    Node<E> newNode = new Node<>(e);
    Node<E> t = tail, p = t;
    for (;;) {
        Node<E> q = p.next;
        if (q == null) {
            // ★ CAS 设置尾节点的 next
            if (p.casNext(null, newNode)) {
                // CAS 成功，更新 tail（可能不成功也无所谓）
                if (p != t)
                    casTail(t, newNode);
                return true;
            }
        } else if (p == q) {
            // 被其他线程删除了，重新开始
            p = (t != (t = tail)) ? t : head;
        } else {
            // 向后遍历
            p = (p != t && t != (t = tail)) ? t : q;
        }
    }
}
```

**特点**：
- **无锁**（CAS），高并发下性能好
- size() 是 O(n) 操作（需要遍历链表）
- 弱一致性迭代器

### 5.2 ConcurrentLinkedDeque — 无锁并发双端队列

类似于 ConcurrentLinkedQueue 但支持头尾操作，实现更复杂（用了"链接"和"锚点"两个指针）。

---

## 六、特殊结构

### 6.1 CopyOnWriteArraySet

底层就是 CopyOnWriteArrayList：

```java
public class CopyOnWriteArraySet<E> extends AbstractSet<E> {
    private final CopyOnWriteArrayList<E> al;

    // add 就是调 CopyOnWriteArrayList.addIfAbsent()
    // 每次都要遍历检查是否存在 → O(n)
}
```

适合小集合（元素少）且读多写少的场景。

### 6.2 ConcurrentSkipListMap — 跳表实现的有序并发 Map

```
跳表结构：
Level 3: 1 ────────────────→ 9
Level 2: 1 ──────→ 5 ──────→ 9
Level 1: 1 ─→ 3 ─→ 5 ─→ 7 ─→ 9
Level 0: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10
```

**特性**：
- 有序（类似 TreeMap），但线程安全
- 查找 O(log n)
- 无锁实现（CAS + volatile）
- 支持 NavigableMap 的所有范围查询

**为什么有 ConcurrentSkipListMap 而不是 ConcurrentTreeMap？**

> 红黑树在并发环境下**重平衡操作复杂**且需要锁大量节点，而跳表通过 CAS 可以无锁实现并发插入/删除。

---

## 七、数据结构选择速查表

| 需求 | 推荐实现 | 原因 |
|------|---------|------|
| 快速随机访问 | **ArrayList** | O(1) get |
| 频繁头尾插入删除 | **LinkedList** / **ArrayDeque** | O(1) |
| 线程安全 List | **CopyOnWriteArrayList** | 读无锁 |
| 键值对，无序 | **HashMap** | 综合性能最优 |
| 键值对，插入有序 | **LinkedHashMap** | 双向链表 |
| 键值对，排序 | **TreeMap** | 红黑树，O(log n) |
| 线程安全 Map | **ConcurrentHashMap** | CAS + synchronized |
| 有序并发 Map | **ConcurrentSkipListMap** | 跳表，无锁 |
| FIFO 队列 | **ArrayDeque** / **LinkedList** |  |
| 有界阻塞队列 | **ArrayBlockingQueue** | 固定容量 |
| 无界阻塞队列 | **LinkedBlockingQueue** | 默认无界，注意 OOM |
| 延迟任务 | **DelayQueue** | 到期才可取 |
| 零容量直接交付 | **SynchronousQueue** | 不存元素 |
| 优先队列 | **PriorityQueue** | 二叉堆 |
| 线程安全优先队列 | **PriorityBlockingQueue** | 堆 + 锁 |
| 无锁并发队列 | **ConcurrentLinkedQueue** | CAS |
| 去重 | **HashSet** | 底层 HashMap |
| 有序去重 | **TreeSet** | 底层 TreeMap |

---

## 八、面试高频题

### Q1：ArrayList 的扩容机制？

> 1.5 倍扩容 (oldCapacity >> 1)，JDK 8 用 `Arrays.copyOf` 拷贝。提前预估容量可避免频繁扩容。

### Q2：HashMap 和 ConcurrentHashMap 的区别？

> 线程安全、锁粒度（JDK 8 的 CAS+synchronized vs 全表锁）、迭代器弱一致性、ConcurrentHashMap 的 size 用 CounterCell 分散计数。

### Q3：ArrayBlockingQueue 和 LinkedBlockingQueue 的区别？

> Array 有界、一把锁、循环数组。Linked 可选有界（默认无界）、两把锁（生产和消费可并行）、链表结构。

### Q4：SynchronousQueue 有什么用？

> CachedThreadPool 用做任务队列，来了任务直接交给线程，不排队，保证任务不会被积压（因为没有容量）。

### Q5：ConcurrentLinkedQueue 和 LinkedBlockingQueue 的区别？

> ConcurrentLinkedQueue 无锁（CAS）、非阻塞、无界。LinkedBlockingQueue 有锁、阻塞、可选有界。前者适合高吞吐不需要阻塞等待的场景，后者适合生产者-消费者模式。

### Q6：PriorityQueue 底层为什么用数组实现堆？

> 完全二叉树适合用数组存储，父节点 = (i-1)/2，子节点 = 2i+1 / 2i+2。不需要额外指针，内存紧凑，CPU cache 友好。

### Q7：ArrayDeque 为什么比 Stack 和 LinkedList 好？

> Stack 继承 Vector（所有方法 synchronized，有同步开销）。LinkedList 用链表（每个元素都有 Node 包装，内存开销大，cache 不友好）。ArrayDeque 用循环数组，连续内存，没有同步，头尾操作都是 O(1)。

### Q8：CopyOnWriteArrayList 的缺点？

> 写时复制，每次写都创建新数组。不适合大集合、写频繁场景。弱一致性，读写可能读到旧数据。
