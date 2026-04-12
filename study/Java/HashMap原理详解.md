# HashMap 原理详解

## 一、数据结构演进

```
JDK 1.7：数组 + 链表
┌───┬───┬───┬───┬───┬───┬───┬───┐
│ 0 │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │ 7 │   ← 数组（Entry[]，默认 16）
└─┬─┴───┴─┬─┴───┴───┴─┬─┴───┴───┘
  │       │           │
  ▼       ▼           ▼
 [A]     [C]         [E]
  │       │
  ▼       ▼
 [B]     [D]                          ← 链表（头插法）

  问题：链表过长时查找退化为 O(n)


JDK 1.8：数组 + 链表 + 红黑树
┌───┬───┬───┬───┬───┬───┬───┬───┐
│ 0 │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │ 7 │   ← 数组（Node[]，默认 16）
└─┬─┴───┴─┬─┴───┴───┴─┬─┴───┴───┘
  │       │           │
  ▼       ▼           ▼
 [A]     [C]        ┌─[E]─┐
  │       │         │     │
  ▼       ▼       [F]   [G]          ← 红黑树（链表长度 > 8 时转换）
 [B]     [D]      │ │   │ │
                 [H][I] [J][K]

  链表 → 红黑树：查找从 O(n) 优化到 O(log n)
```

## 二、核心参数

```java
static final int DEFAULT_INITIAL_CAPACITY = 16;    // 默认初始容量
static final int MAXIMUM_CAPACITY = 1 << 30;       // 最大容量
static final float DEFAULT_LOAD_FACTOR = 0.75f;    // 默认负载因子
static final int TREEIFY_THRESHOLD = 8;            // 链表转红黑树的阈值
static final int UNTREEIFY_THRESHOLD = 6;          // 红黑树退化为链表的阈值
static final int MIN_TREEIFY_CAPACITY = 64;        // 转红黑树时数组最小容量
```

```
为什么容量是 2 的幂次？

  容量 16 = 10000（二进制）
  容量 - 1 = 15 = 01111

  计算下标：index = hash & (capacity - 1)
  等价于：index = hash % capacity

  位运算比取模快得多
  且 capacity - 1 的二进制全是 1，保证 hash 的低位都参与运算
  分布更均匀
```

---

## 三、Hash 算法

### JDK 1.7 的 hash

```java
static int hash(int h) {
    h ^= (h >>> 20) ^ (h >>> 12);
    return h ^ (h >>> 7) ^ (h >>> 4);
}
// 4 次扰动（右移 + 异或），比较复杂
```

### JDK 1.8 的 hash（简化）

```java
static final int hash(Object key) {
    int h;
    return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
}
```

```
为什么要 h ^ (h >>> 16)？

原始 hashCode:     1111 0101 0010 1100 0011 1010 0110 1001
右移 16 位:        0000 0000 0000 0000 1111 0101 0010 1100
异或结果:          1111 0101 0010 1100 1100 1111 0100 0101

目的：让高 16 位也参与下标计算

如果不扰动：
  index = hash & 15（数组长度 16）
  只有 hash 的低 4 位参与计算，高位完全浪费
  不同 key 的高位不同但低位相同 → 大量碰撞

扰动后：
  高位信息混入低位，减少碰撞
  JDK 1.8 只做 1 次扰动（够用了，因为有红黑树兜底）
```

### 下标计算

```
index = hash & (capacity - 1)

例：capacity = 16, key.hashCode() = 0b...1010 0110 1001
  hash = hashCode ^ (hashCode >>> 16) = 0b...0100 0101
  index = 0100 0101 & 0000 1111 = 0000 0101 = 5

  → 放入数组下标 5 的位置
```

---

## 四、put 流程（JDK 1.8）

```
put(key, value)
    │
    ▼
① 计算 hash = hash(key)
    │
    ▼
② 数组为空？
    │
    ├── 是 → resize() 初始化数组（懒加载，第一次 put 才创建）
    │
    └── 否 ↓
    │
    ▼
③ index = hash & (length - 1)，该位置为空？
    │
    ├── 是 → 直接放入，new Node(hash, key, value)
    │
    └── 否 → 发生碰撞 ↓
    │
    ▼
④ 首节点的 key 相同？（hash 相同 && equals 相同）
    │
    ├── 是 → 覆盖旧 value
    │
    └── 否 ↓
    │
    ▼
⑤ 首节点是红黑树节点？
    │
    ├── 是 → 红黑树的插入逻辑
    │
    └── 否 → 链表 ↓
    │
    ▼
⑥ 遍历链表
    │
    ├── 找到相同 key → 覆盖旧 value
    │
    └── 遍历到末尾没找到 → 尾插法插入新节点
        │
        ▼
    ⑦ 插入后链表长度 >= 8？
        │
        ├── 是 → treeifyBin()
        │     │
        │     ├── 数组长度 < 64 → 先扩容，不转树
        │     └── 数组长度 >= 64 → 链表转红黑树
        │
        └── 否 → 不转换
    │
    ▼
⑧ size > threshold（容量 * 负载因子）？
    │
    ├── 是 → resize() 扩容
    │
    └── 否 → 结束
```

### JDK 1.7 vs 1.8 的 put 区别

| | JDK 1.7 | JDK 1.8 |
|--|---------|---------|
| 插入方式 | **头插法** | **尾插法** |
| 数据结构 | 数组 + 链表 | 数组 + 链表 + 红黑树 |
| 扩容时机 | 先判断是否需要扩容再插入 | 先插入再判断是否需要扩容 |
| hash 算法 | 4 次扰动 | 1 次扰动（高 16 位异或低 16 位） |

---

## 五、扩容机制（resize）

### 触发条件

```
size > capacity * loadFactor

默认：size > 16 * 0.75 = 12 时触发扩容
扩容为原来的 2 倍：16 → 32 → 64 → 128 ...
```

### JDK 1.7 扩容（rehash）

```
1. 创建新数组（2 倍大小）
2. 遍历旧数组每个桶的链表
3. 对每个节点重新计算下标：index = hash & (newCapacity - 1)
4. 头插法插入新数组

问题：多线程下头插法会导致链表成环 → 死循环！

单线程没问题：
  旧: A → B → C
  新: C → B → A（头插法，顺序反转）

多线程可能成环：
  线程1: 正在搬 A → B
  线程2: 也在搬，把 B → A
  结果: A → B → A → B → ...  死循环！
  get() 时遍历链表永远不会停 → CPU 100%
```

### JDK 1.8 扩容（优化，不需要 rehash）

```
巧妙的设计：扩容后，每个节点要么在原位置，要么在 原位置 + 旧容量

旧容量 = 16 = 10000
新容量 = 32 = 100000

旧 index = hash & 01111   (capacity - 1 = 15)
新 index = hash & 011111  (capacity - 1 = 31)

区别只在于多看 hash 的第 5 位（从右数）：
  第 5 位 = 0 → 新 index = 旧 index（不动）
  第 5 位 = 1 → 新 index = 旧 index + 16（偏移旧容量）
```

```
例：旧容量 16，扩容到 32

hash = ...0 0101 → index = 5，第5位=0 → 新 index = 5（不动）
hash = ...1 0101 → index = 5，第5位=1 → 新 index = 5+16 = 21

原来在同一个桶里的节点，扩容后只可能在两个位置：
  低位桶（原位置 5）
  高位桶（原位置 + 旧容量 = 21）

不需要重新算 hash！只需要看多出来的那一位
```

```
JDK 1.8 扩容过程：

旧数组 index=5 的链表：A → B → C → D

检查每个节点 hash 的新增位：
  A: hash & oldCap = 0 → 低位
  B: hash & oldCap = 1 → 高位
  C: hash & oldCap = 0 → 低位
  D: hash & oldCap = 1 → 高位

拆成两条链（尾插法，保持原有顺序）：
  低位链：A → C  → 放到新数组 index=5
  高位链：B → D  → 放到新数组 index=21

用尾插法，不会出现 1.7 的链表成环问题
```

---

## 六、链表转红黑树的条件

```
转红黑树必须同时满足两个条件：
  1. 链表长度 >= 8
  2. 数组容量 >= 64

如果链表 >= 8 但数组 < 64 → 先扩容，不转树

为什么是 8？
  泊松分布：在合理的 hash 算法下
  链表长度达到 8 的概率约为 0.00000006（千万分之六）
  几乎不会发生，除非 hash 算法极差或恶意构造

为什么退化阈值是 6 而不是 8？
  如果转和退化都是 8 → 链表长度在 8 附近反复变化时
  会频繁 树化↔链表化，开销很大
  设置为 6，留一个缓冲区间（6-8），避免频繁转换
```

```
红黑树的五个性质：
  1. 节点是红色或黑色
  2. 根节点是黑色
  3. 叶子节点（NIL）是黑色
  4. 红色节点的子节点必须是黑色（不能有连续红节点）
  5. 从任一节点到其叶子的所有路径，黑色节点数量相同
```

---

## 七、get 流程

```
get(key)
    │
    ▼
① hash = hash(key)
② index = hash & (length - 1)
    │
    ▼
③ 该位置为空？
    │
    ├── 是 → return null
    │
    └── 否 ↓
    │
    ▼
④ 首节点 key 匹配？（hash == && equals）
    │
    ├── 是 → return 首节点.value
    │
    └── 否 ↓
    │
    ▼
⑤ 首节点是红黑树？
    │
    ├── 是 → 红黑树查找 O(log n)
    │
    └── 否 → 遍历链表查找 O(n)
```

---

## 八、JDK 1.7 vs 1.8 完整对比

| | JDK 1.7 | JDK 1.8 |
|--|---------|---------|
| 数据结构 | 数组 + 链表 | 数组 + 链表 + 红黑树 |
| 数组类型 | `Entry[]` | `Node[]` |
| 插入方式 | **头插法** | **尾插法** |
| hash 算法 | 4 次扰动 | 1 次（高 16 位 ^ 低 16 位） |
| 扩容 | rehash 重新计算每个节点的 index | 利用高位判断，不需要 rehash |
| 链表遍历时间 | O(n) | 链表 O(n)，红黑树 O(log n) |
| 多线程安全 | 不安全（头插法导致死循环） | 不安全（但不会死循环） |
| 树化条件 | 无 | 链表 >= 8 且数组 >= 64 |

### 头插法 vs 尾插法

```
头插法（1.7）：新节点插到链表头部
  原来: A → B → C
  插入 D: D → A → B → C

  原因：作者认为新插入的数据更可能被访问（时间局部性）
  问题：多线程扩容时链表成环

尾插法（1.8）：新节点插到链表尾部
  原来: A → B → C
  插入 D: A → B → C → D

  好处：保持插入顺序，扩容时不会成环
```

---

## 九、线程安全问题

```
HashMap 不是线程安全的！

JDK 1.7 的问题：
  多线程同时 put → 同时触发扩容 → 头插法导致链表成环 → 死循环

JDK 1.8 的问题（不会死循环，但仍不安全）：
  1. 数据覆盖：两个线程同时 put 到同一个空桶，一个会被覆盖
  2. size 不准：size++ 不是原子操作
  3. 扩容数据丢失：多线程同时扩容
```

### 线程安全的替代方案

```
1. Collections.synchronizedMap(new HashMap<>())
   每个方法加 synchronized → 粒度太粗，性能差

2. Hashtable
   和上面一样，方法级 synchronized → 性能差

3. ConcurrentHashMap（推荐）
   JDK 1.7: 分段锁（Segment），每段独立加锁
   JDK 1.8: CAS + synchronized（锁单个桶）
   并发性能好得多
```

```
ConcurrentHashMap 1.8 的锁粒度：

┌───┬───┬───┬───┬───┬───┬───┬───┐
│ 0 │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │ 7 │
└─┬─┴───┴─┬─┴───┴───┴─┬─┴───┴───┘
  🔒      🔒          🔒

线程 A 操作桶 0（锁桶 0）
线程 B 操作桶 2（锁桶 2）
  → 互不影响，并行执行

只有操作同一个桶时才竞争锁
```

---

## 十、面试高频问题

### Q1: HashMap 的容量为什么是 2 的幂次？

- `index = hash & (capacity - 1)` 等价于 `hash % capacity`，位运算更快
- `capacity - 1` 的二进制全是 1（如 15 = 01111），保证 hash 低位都参与运算，分布均匀
- 扩容时可以利用高位判断节点位置，不需要重新计算 hash

### Q2: 负载因子为什么是 0.75？

- 0.75 是时间和空间的折中
- 太小（如 0.5）：空间浪费大，频繁扩容
- 太大（如 1.0）：碰撞概率高，链表变长，查找变慢
- 0.75 时，根据泊松分布，桶中元素个数超过 8 的概率极低

### Q3: HashMap 的 key 可以为 null 吗？

- 可以。null key 的 hash 固定为 0，放在数组下标 0 的位置
- Hashtable 和 ConcurrentHashMap 的 key 不能为 null

### Q4: HashMap 和 Hashtable 的区别？

| | HashMap | Hashtable |
|--|---------|-----------|
| 线程安全 | 不安全 | 安全（synchronized） |
| null key/value | 允许 | 不允许 |
| 性能 | 快 | 慢（全局锁） |
| 继承 | AbstractMap | Dictionary |
| 推荐 | 单线程用 HashMap | 多线程用 ConcurrentHashMap |

### Q5: 两个对象的 hashCode 相同，equals 一定相同吗？

- 不一定。hashCode 相同只是 hash 碰撞，equals 不一定为 true
- 但 equals 为 true → hashCode 必须相同（这是约定，重写 equals 必须重写 hashCode）

```
equals 和 hashCode 的关系：
  equals 相同 → hashCode 必须相同（否则 HashMap 找不到 key）
  hashCode 相同 → equals 不一定相同（hash 碰撞）
  hashCode 不同 → equals 一定不同
```

### Q6: HashMap 在 Android 上有什么替代？

```
ArrayMap / SparseArray（Android 特有）：

ArrayMap：
  两个数组：hash 数组（排序）+ key-value 数组
  二分查找，内存占用小
  适合小数据量（< 1000）

SparseArray：
  key 是 int 类型（避免 Integer 装箱）
  内部用两个数组：int[] keys + Object[] values
  比 HashMap<Integer, Object> 省内存

推荐：
  数据量小（< 1000）→ ArrayMap / SparseArray
  数据量大 → HashMap
```
