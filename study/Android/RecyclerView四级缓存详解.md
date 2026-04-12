# RecyclerView 四级缓存详解

## 全局视角

```
手指向上滑动，列表向下滚动

┌─────────────────────────┐
│  即将进入屏幕的 Item ↓      │  ← 需要从缓存中找 ViewHolder
├─────────────────────────┤
│                         │
│   屏幕可见区域            │  ← 第 0 级：mAttachedScrap
│   Item 3                │
│   Item 4                │
│   Item 5                │
│   Item 6                │
│                         │
├─────────────────────────┤
│  刚滑出屏幕的 Item ↓      │  ← 第 1 级：mCachedViews
│  Item 1                 │
│  Item 2                 │  ← 第 3 级：RecycledViewPool
└─────────────────────────┘
```

## 一、缓存查找顺序

```
需要一个 position=10 的 ViewHolder
    │
    ▼
① mAttachedScrap / mChangedScrap ── 命中？─→ 直接用，不调 onBind
    │ 未命中
    ▼
② mCachedViews ── 命中？─→ 直接用，不调 onBind
    │ 未命中
    ▼
③ ViewCacheExtension ── 命中？─→ 自定义逻辑
    │ 未命中
    ▼
④ RecycledViewPool ── 命中？─→ 需要重新调 onBindViewHolder()
    │ 未命中
    ▼
⑤ 调用 onCreateViewHolder() 创建全新的 ViewHolder
```

---

## 二、第 0 级：mAttachedScrap / mChangedScrap

**作用**：临时存放当前屏幕上的 ViewHolder，用于 `notifyDataSetChanged()` 等场景。

```
调用 notifyDataSetChanged()
    │
    ▼
RecyclerView 需要重新布局（onLayoutChildren）
    │
    ├── 1. 先把屏幕上所有 ViewHolder 暂存到 scrap
    │      ┌─ mAttachedScrap：数据没变的 ViewHolder
    │      └─ mChangedScrap：数据变了的 ViewHolder（notifyItemChanged）
    │
    ├── 2. 重新布局，按 position 从 scrap 里取回来
    │
    └── 3. 布局完成，scrap 清空
```

```
布局前：                     暂存到 scrap：           重新布局后：
┌────────────┐              ┌──────────────┐       ┌────────────┐
│ Item 0 (VH0)│    detach   │ scrap:        │  取回  │ Item 0 (VH0)│
│ Item 1 (VH1)│  ────────→  │  VH0, VH1,   │ ────→ │ Item 1 (VH1)│
│ Item 2 (VH2)│             │  VH2, VH3    │       │ Item 2 (VH2)│
│ Item 3 (VH3)│             └──────────────┘       │ Item 3 (VH3)│
└────────────┘                                     └────────────┘
```

**特点**：
- 只在 layout 过程中存在，layout 完就清空
- 按 **position** 精确匹配
- 命中后**不需要** `onBindViewHolder()`，因为数据没变
- 开发者几乎感知不到，RecyclerView 内部自动使用

---

## 三、第 1 级：mCachedViews

**作用**：缓存**刚滑出屏幕**的 ViewHolder，默认容量 **2 个**。

```
手指向上滑 ↑

                          mCachedViews (最多 2 个)
┌────────────┐           ┌───────────────────────┐
│ ↑ Item 5   │           │ VH(pos=1, data=完整)   │  ← 先滑出的
│ ↑ Item 4   │  滑出屏幕  │ VH(pos=2, data=完整)   │  ← 后滑出的
│   Item 3   │ ────────→ │                       │
│   Item 2   │           │ 如果再滑出 Item 3：     │
│   Item 1   │           │  VH(pos=1) 被挤到 Pool │
└────────────┘           │  VH(pos=3) 进入 Cache  │
                         └───────────────────────┘
```

**特点**：
- 按 **position** 精确匹配（position=2 的 Cache 只能给 position=2 用）
- 命中后**不需要** `onBindViewHolder()`，ViewHolder 保留了完整的数据和状态
- 默认容量 2，可以通过 `setItemViewCacheSize()` 修改
- 满了之后，最早进入的会被挤到 RecycledViewPool

**为什么不需要 rebind？**

```
用户快速来回滑动时：
  向上滑一点 → Item 0 滑出 → 存入 Cache（保留 position=0 的完整数据）
  向下滑回来 → 需要 Item 0 → 从 Cache 命中 → 直接用，数据还是对的

相当于"刚拿走的东西原封不动放回去"，所以不需要重新绑定
```

---

## 四、第 2 级：ViewCacheExtension

**作用**：开发者自定义的缓存层，插在 CachedViews 和 RecycledViewPool 之间。

```kotlin
recyclerView.setViewCacheExtension(object : RecyclerView.ViewCacheExtension() {
    override fun getViewForPositionAndType(
        recycler: RecyclerView.Recycler,
        position: Int,
        type: Int
    ): View? {
        // 自定义缓存逻辑
        // 返回 null 表示没命中，继续找 Pool
        return null
    }
})
```

**实际开发中几乎不用**，了解即可。适用于一些特殊场景，比如广告 Item 需要特殊的缓存策略。

---

## 五、第 3 级：RecycledViewPool

**作用**：最终的回收池，按 **ViewType** 分类存放，默认每种类型最多 **5 个**。

```
RecycledViewPool
┌──────────────────────────────────────────────┐
│                                              │
│  ViewType 0 (普通 Item):                      │
│  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐   │
│  │ VH  │ │ VH  │ │ VH  │ │     │ │     │   │  ← 最多 5 个
│  │(脏) │ │(脏) │ │(脏) │ │     │ │     │   │
│  └─────┘ └─────┘ └─────┘ └─────┘ └─────┘   │
│                                              │
│  ViewType 1 (Header):                        │
│  ┌─────┐ ┌─────┐                             │
│  │ VH  │ │ VH  │                             │  ← 最多 5 个
│  └─────┘ └─────┘                             │
│                                              │
│  ViewType 2 (广告):                           │
│  ┌─────┐                                     │
│  │ VH  │                                     │
│  └─────┘                                     │
└──────────────────────────────────────────────┘
```

**特点**：
- 按 **ViewType** 匹配，不关心 position
- ViewHolder 的数据被清空（`onViewRecycled()` 被调用）
- 命中后**必须重新调** `onBindViewHolder()` 绑定新数据
- 默认每种 ViewType 存 5 个，可修改：

```kotlin
recyclerView.recycledViewPool.setMaxRecycledViews(VIEW_TYPE_NORMAL, 10)
```

- **可以跨多个 RecyclerView 共享**（ViewPager 中的多个列表）：

```kotlin
// 多个 RecyclerView 共享同一个 Pool
val sharedPool = RecyclerView.RecycledViewPool()
recyclerView1.setRecycledViewPool(sharedPool)
recyclerView2.setRecycledViewPool(sharedPool)
```

### RecycledViewPool 内部数据结构

```java
// RecycledViewPool 内部结构（简化）
class RecycledViewPool {
    // 核心：一个 SparseArray，key 是 viewType，value 是 ScrapData
    SparseArray<ScrapData> mScrap = new SparseArray<>();

    static class ScrapData {
        ArrayList<ViewHolder> mScrapHeap = new ArrayList<>();  // 存 ViewHolder 的列表
        int mMaxScrap = 5;  // 默认最多 5 个
    }
}
```

展开看：

```
mScrap (SparseArray)
│
├── key=0 (ViewType 0: 普通 Item)
│   └── ScrapData
│       └── mScrapHeap: [VH, VH, VH]     ← 当前回收了 3 个，最多放 5 个
│
├── key=1 (ViewType 1: Header)
│   └── ScrapData
│       └── mScrapHeap: [VH]             ← 当前回收了 1 个
│
├── key=2 (ViewType 2: 广告卡片)
│   └── ScrapData
│       └── mScrapHeap: [VH, VH]         ← 当前回收了 2 个
```

### 同一个 ViewType 的 5 个 VH 是什么关系？

**5 个独立的 ViewHolder 实例，View 结构相同但数据已被清空。** 以商品列表为例：

```
屏幕上显示：
┌──────────────┐
│ 🖼 iPhone 16  │  ← VH_A，绑定了 iPhone 数据
│ ¥7999         │
├──────────────┤
│ 🖼 小米 15    │  ← VH_B，绑定了小米数据
│ ¥3999         │
├──────────────┤
│ 🖼 华为 Mate  │  ← VH_C，绑定了华为数据
│ ¥5999         │
└──────────────┘
```

滑出屏幕经过 mCachedViews 后最终进入 Pool：

```
RecycledViewPool (ViewType 0 的 mScrapHeap):

  [0] VH_A  →  ImageView + TextView + PriceView (数据清空)
  [1] VH_B  →  ImageView + TextView + PriceView (数据清空)
  [2] VH_C  →  ImageView + TextView + PriceView (数据清空)
  [3] 空
  [4] 空

  它们是 3 个不同的对象实例
  但 View 结构一模一样（都是商品卡片布局）
  数据（图片、文字、价格）已经被清空
```

取出来复用时：

```
需要显示 position=20 的商品（三星 Galaxy）
    │
    ▼
从 Pool 的 ViewType 0 列表里取一个（取最后一个，类似栈）
    │
    ▼
拿到 VH_C（一个空壳，View 结构在，数据没了）
    │
    ▼
调用 onBindViewHolder(VH_C, 20)
    ├── holder.imageView.load("三星图片URL")
    ├── holder.textView.text = "三星 Galaxy"
    └── holder.priceView.text = "¥6999"
    │
    ▼
VH_C 现在显示的是三星的数据，被复用了
```

### 5 个上限的含义

不是"取出 5 个一起用"，而是**池子里最多囤 5 个空壳**，用的时候一次只取一个：

```
1. 用户快速向上滑，Item 不断滑出屏幕
   VH 进入 Pool → Pool: [VH1]
   VH 进入 Pool → Pool: [VH1, VH2]
   VH 进入 Pool → Pool: [VH1, VH2, VH3]
   VH 进入 Pool → Pool: [VH1, VH2, VH3, VH4]
   VH 进入 Pool → Pool: [VH1, VH2, VH3, VH4, VH5]
   VH 进入 Pool → 满了！VH6 被丢弃（不缓存了）

2. 新 Item 要进入屏幕，从 Pool 取
   取出 VH5 → Pool: [VH1, VH2, VH3, VH4]
   onBindViewHolder(VH5, newPosition)  ← 绑定新数据

3. 又有 Item 滑出，又存进去
   VH 进入 Pool → Pool: [VH1, VH2, VH3, VH4, VH7]
```

**5 个是为了平衡内存和性能**：
- 太少（比如 1 个）→ 频繁 `onCreateViewHolder()`，inflate XML 很耗时
- 太多（比如 100 个）→ 占用过多内存，大部分用不上
- 5 个足够应对正常滑动场景

如果 Item 很复杂（创建成本高），可以加大：

```kotlin
recyclerView.recycledViewPool.setMaxRecycledViews(VIEW_TYPE_NORMAL, 15)
```

### 为什么需要 rebind？

```
Cache 是按 position 缓存的：
  VH 记着"我是 position=5 的数据" → 精确匹配 → 不用 rebind

Pool 是按 ViewType 缓存的：
  VH 只知道"我是普通 Item 类型" → 不知道该显示谁的数据 → 必须 rebind
  相当于"一个干净的模板"，需要重新填入数据
```

---

## 六、完整对比

| 级别 | 缓存 | 匹配方式 | 需要 onBind? | 需要 onCreate? | 默认容量 |
|------|------|---------|-------------|---------------|---------|
| 0 | mAttachedScrap | position | 不需要 | 不需要 | 屏幕内数量 |
| 1 | mCachedViews | position | 不需要 | 不需要 | 2 |
| 2 | ViewCacheExtension | 自定义 | 自定义 | 不需要 | 自定义 |
| 3 | RecycledViewPool | viewType | **需要** | 不需要 | 每种类型 5 个 |
| - | 全部未命中 | - | **需要** | **需要** | - |

## 七、一次滑动的完整流程

```
Item 0 滑出屏幕顶部，Item 7 即将从底部进入
    │
    ├── 回收 Item 0 的 ViewHolder：
    │   mCachedViews 没满（<2）→ 存入 mCachedViews
    │   mCachedViews 满了 → 把最早的挤到 RecycledViewPool，Item 0 存入 Cache
    │
    └── 获取 Item 7 的 ViewHolder：
        ① scrap 里找 position=7 → 没有（不在布局过程中）
        ② mCachedViews 里找 position=7 → 没有（Cache 里是 position=0,1）
        ③ ViewCacheExtension → 没设置
        ④ RecycledViewPool 里找 viewType 匹配的 → 找到了！
           取出 VH，调 onBindViewHolder(vh, 7) 绑定数据
           └── 如果 Pool 也没有 → onCreateViewHolder() 创建新的
```

---

## 八、DiffUtil 局部更新

### 没有 DiffUtil 时

```kotlin
// 最暴力的方式：全部刷新
fun updateData(newList: List<Item>) {
    this.dataList = newList
    adapter.notifyDataSetChanged()  // 所有 Item 都会 rebind，没有动画
}
```

问题：列表有 100 个 Item，只改了 1 个，却要把 100 个全部重新绑定。

### DiffUtil 做了什么

对比新旧两个列表，精确算出哪些 Item 新增/删除/移动/修改，只更新变化的部分：

```
旧列表：                新列表：                DiffUtil 算出的差异：
┌─────────┐           ┌─────────┐
│ A       │           │ A       │           A → 没变
│ B       │           │ B (改)  │           B → changed（只 rebind 这一个）
│ C       │           │ D (新)  │           C → removed
│ E       │           │ E       │           D → inserted
└─────────┘           └─────────┘           E → moved
```

自动调用最精确的 notify 方法：

```
notifyItemChanged(1)     // B 数据变了
notifyItemRemoved(2)     // C 被删了
notifyItemInserted(2)    // D 插入了
notifyItemMoved(3, 3)    // E 位置变了
```

### 使用方式一：手动 DiffUtil

```kotlin
class ItemDiffCallback(
    private val oldList: List<Item>,
    private val newList: List<Item>
) : DiffUtil.Callback() {

    override fun getOldListSize() = oldList.size
    override fun getNewListSize() = newList.size

    // 是否是同一个 Item（通常比较 ID）
    override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
        return oldList[oldPos].id == newList[newPos].id
    }

    // 同一个 Item 的内容是否变了（决定是否 rebind）
    override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
        return oldList[oldPos] == newList[newPos]
    }
}

// 使用
fun updateData(newList: List<Item>) {
    val diffResult = DiffUtil.calculateDiff(ItemDiffCallback(oldList, newList))
    oldList = newList
    diffResult.dispatchUpdatesTo(adapter)  // 自动调用精确的 notifyItemXxx
}
```

### 使用方式二：ListAdapter（推荐）

ListAdapter 内部封装了 DiffUtil，并且**自动在后台线程计算差异**：

```kotlin
class ItemAdapter : ListAdapter<Item, ItemViewHolder>(ItemDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_layout, parent, false)
        return ItemViewHolder(view)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

class ItemDiffCallback : DiffUtil.ItemCallback<Item>() {
    override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean {
        return oldItem.id == newItem.id
    }
    override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean {
        return oldItem == newItem
    }
}

// 使用：一行搞定，后台计算差异 + 自动局部刷新 + 自动动画
adapter.submitList(newList)
```

### areItemsTheSame vs areContentsTheSame 的关系

```
areItemsTheSame(old, new)
    │
    ├── false → 不是同一个 Item → 执行删除旧的 + 插入新的
    │
    └── true → 是同一个 Item → 再问 areContentsTheSame
                                    │
                                    ├── true → 内容没变 → 什么都不做
                                    │
                                    └── false → 内容变了 → notifyItemChanged → rebind
```

### DiffUtil 的算法

```
使用 Eugene Myers 差异算法
时间复杂度：O(N + D²)  N=列表长度，D=差异数量
空间复杂度：O(N)

列表很大时（>1000），calculateDiff() 可能耗时
  → 手动方式：必须放到后台线程计算
  → ListAdapter：已经自动在后台线程处理
```

---

## 九、setHasFixedSize(true)

### 默认行为（不设置时）

```
数据变化（比如 notifyItemInserted）
    │
    ▼
RecyclerView.requestLayout()    ← 触发自身的 measure + layout
    │
    ▼
父布局也可能被触发重新测量
    │
    ▼
最终整棵 View 树可能被波及
```

RecyclerView 默认会想："子 Item 数量变了，我自己的大小可能也会变，需要重新 measure 自己。"

### 设置之后

```kotlin
recyclerView.setHasFixedSize(true)
```

```
数据变化（比如 notifyItemInserted）
    │
    ▼
只做内部子 View 的布局（layoutChildren）
    │
    ▼
不会调 requestLayout()
不会触发 RecyclerView 自身的重新测量
不会波及父布局
```

告诉 RecyclerView："不管 Item 怎么增删改，我自己的大小不会变，别浪费时间重新测量我了。"

### 什么时候可以设置？

```
✅ 可以设置的场景：
  - RecyclerView 的宽高是 match_parent 或固定 dp
  - Item 的增删不会导致 RecyclerView 整体大小变化

  ┌─────────────────────────┐
  │ RecyclerView              │
  │ layout_width=match_parent │  ← 不管 Item 多少个，RV 永远这么大
  │ layout_height=match_parent│
  └─────────────────────────┘

❌ 不能设置的场景：
  - RecyclerView 的高度是 wrap_content
  - Item 数量变化会改变 RV 的大小

  ┌────────────────┐
  │ RecyclerView     │
  │ height=wrap_content│  ← Item 增加，RV 要变高，必须重新 measure
  └────────────────┘
```

### 源码层面的区别

```java
// RecyclerView 内部
void triggerUpdateProcessor() {
    if (mHasFixedSize) {
        layoutChildren();          // 只重新布局子 View
    } else {
        requestLayout();           // 整个 RV 重新 measure + layout
    }
}
```

---

## 十、setItemPrefetchEnabled 预加载

### 背景：RenderThread 的空闲时间

```
正常一帧的流水线：

主线程:      [Input + 动画 + measure/layout/draw]  [空闲...]
RenderThread:                                     [GPU 渲染]
             ├──────── 16.6ms ────────────────────┤

主线程做完 draw 后，在等 RenderThread 渲染期间是空闲的
这段空闲时间可以用来提前准备即将进入屏幕的 ViewHolder
```

### 预加载做了什么

```
没有预加载：
┌────────────────────┐
│  屏幕可见区域        │
│  Item 3             │
│  Item 4             │
│  Item 5             │
│  Item 6             │
└────────────────────┘
│  ← 用户继续滑动，Item 7 需要进入屏幕
│     此时才开始 create/bind Item 7（可能来不及，掉帧）

有预加载：
┌────────────────────┐
│  屏幕可见区域        │
│  Item 3             │
│  Item 4             │
│  Item 5             │
│  Item 6             │
└────────────────────┘
│  Item 7 (已提前准备好) ← 利用 RenderThread 渲染时的空闲，提前 create + bind
│  Item 8 (已提前准备好)
```

### 工作原理

```
第 N 帧：
主线程:      [处理当前帧]  [预加载: create/bind 即将可见的 Item]
RenderThread:              [渲染第 N 帧]
                            ↑
                            主线程在这段时间做预加载

第 N+1 帧：
主线程:      [Item 进入屏幕 → 直接用，不需要临时 create]
             快！不掉帧！
```

GapWorker（预加载的执行者）利用 Choreographer 在每帧渲染后、下一帧输入前，把即将可见的 ViewHolder 提前准备好。

### 使用方式

```kotlin
// 默认就是开启的（API 21+），一般不需要手动设置
recyclerView.layoutManager?.isItemPrefetchEnabled = true

// 自定义预加载数量（LinearLayoutManager）
// 默认预加载 1 个，可以增加
layoutManager.initialPrefetchItemCount = 3
```

### 嵌套 RecyclerView 场景（最有用的地方）

横向列表嵌套在纵向列表中（如首页多个横滑模块）：

```
纵向 RecyclerView
┌──────────────────────────┐
│  Banner                   │
├──────────────────────────┤
│  横向 RV: [A][B][C][D]    │  ← 这个横向 RV 整体作为一个 Item
├──────────────────────────┤
│  横向 RV: [E][F][G][H]    │  ← 即将滑入屏幕
├──────────────────────────┤
```

横向 RV 滑入屏幕时，内部的 Item 也需要 create + bind，开销很大。预加载可以提前准备：

```kotlin
class OuterAdapter : RecyclerView.Adapter<...>() {
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val innerLayoutManager = LinearLayoutManager(context, HORIZONTAL, false)
        // 告诉外层 RV：这个内嵌 RV 初始需要 4 个 Item，请提前准备
        innerLayoutManager.initialPrefetchItemCount = 4
        holder.innerRecyclerView.layoutManager = innerLayoutManager
    }
}
```

### 预加载 vs mCachedViews 的区别

| | 预加载 (Prefetch) | mCachedViews |
|--|------------------|-------------|
| 时机 | 帧间空闲时提前准备 | Item 滑出屏幕后缓存 |
| 方向 | 面向未来（即将可见的） | 面向过去（刚滑走的） |
| 是否 bind | 是，提前 bind 好 | 已经 bind 过，不需要重新 bind |
| 解决的问题 | 减少滑动时的临时 create/bind 卡顿 | 回滑时快速复用 |

---

## 十一、面试高频问题

### Q1: 为什么 mCachedViews 不需要 rebind，RecycledViewPool 需要？

- mCachedViews 按 position 匹配，ViewHolder 保留了完整的数据和视图状态，"拿出来就能直接用"
- RecycledViewPool 按 ViewType 匹配，只是一个"空壳模板"，数据已经被清除，必须重新绑定

### Q2: 怎么优化 RecyclerView 性能？

```
1. 增大 mCachedViews 容量（减少 rebind）
   recyclerView.setItemViewCacheSize(5)

2. 增大 RecycledViewPool 容量（减少 create）
   recyclerView.recycledViewPool.setMaxRecycledViews(type, 20)

3. 多个 RecyclerView 共享 Pool（ViewPager 场景）

4. setHasFixedSize(true)
   Item 增减不会改变 RecyclerView 自身大小时设置
   避免 requestLayout()，只做子 View 的局部更新

5. DiffUtil 替代 notifyDataSetChanged()
   精确计算差异，只更新变化的 Item

6. setHasStableIds(true)
   给每个 Item 稳定的 ID，缓存命中率更高
```

### Q3: notifyDataSetChanged() 和 notifyItemChanged() 的区别？

| | notifyDataSetChanged() | notifyItemChanged(pos) |
|--|----------------------|----------------------|
| 范围 | 全部刷新 | 只刷新指定 position |
| 缓存 | 所有 VH 进入 mChangedScrap 或 Pool | 只有该 VH 需要 rebind |
| 动画 | 没有 | 有默认的 change 动画 |
| 性能 | 差 | 好 |

### Q4: RecyclerView 和 ListView 缓存的区别？

| | ListView | RecyclerView |
|--|---------|-------------|
| 缓存级别 | 2 级（ActiveViews + ScrapViews） | 4 级 |
| 缓存单位 | View | ViewHolder |
| 按类型缓存 | 有 | 有，且支持跨 RV 共享 Pool |
| 局部刷新 | 不支持 | 支持（notifyItemXxx） |
