# Glide 原理详解

## 整体架构

```
Glide.with(activity).load(url).into(imageView)
  │           │           │
  ▼           ▼           ▼
生命周期绑定   构建请求     提交请求
  │
  ▼
RequestManager
  │
  ▼
Engine（核心引擎）
  │
  ├── 1. 活动资源缓存（ActiveResources）    → 正在使用的图片
  ├── 2. 内存缓存（LruResourceCache）       → 最近使用过的图片
  ├── 3. 磁盘缓存（DiskLruCache）           → 持久化到磁盘
  └── 4. 网络/文件加载（ModelLoader）        → 全部未命中，从源头加载
  │
  ▼
解码（Decoder）→ 变换（Transform）→ 转码（Transcoder）
  │
  ▼
Target.onResourceReady() → 显示到 ImageView
```

---

## 一、with() — 生命周期绑定

**Glide 最巧妙的设计：通过添加无 UI 的 Fragment 感知生命周期。**

```
Glide.with(activity)
    │
    ▼
传入的是什么？
    │
    ├── Activity → 添加 SupportRequestManagerFragment 到 Activity
    ├── Fragment → 添加 SupportRequestManagerFragment 到 Fragment
    ├── View     → 找到 View 所在的 Activity/Fragment
    └── Application → 不绑定生命周期（全局，不自动取消）
    │
    ▼
返回 RequestManager（与生命周期绑定）
```

```
Activity                          SupportRequestManagerFragment（无 UI）
┌──────────────┐                 ┌──────────────────────┐
│              │                 │                      │
│  你的 UI      │                 │  Lifecycle 监听       │
│              │  自动添加 ──→    │    │                  │
│              │                 │    ├── onStart()  → 恢复请求
│              │                 │    ├── onStop()   → 暂停请求
│              │                 │    └── onDestroy()→ 取消请求 + 清理
│              │                 │                      │
└──────────────┘                 └──────────────────────┘

Activity 销毁 → Fragment 跟着销毁 → 自动取消所有图片请求
不需要手动管理，不会内存泄漏
```

和 Lifecycle 组件的方式一样，都是无 UI Fragment 方案。

---

## 二、load() — 构建请求

```
Glide.with(activity)
    .load(url)                     // ModelLoader：URL → InputStream
    .placeholder(R.drawable.ph)    // 占位图
    .error(R.drawable.err)         // 错误图
    .override(300, 300)            // 目标尺寸
    .centerCrop()                  // 变换
    .diskCacheStrategy(DiskCacheStrategy.ALL)  // 磁盘缓存策略
    │
    ▼
构建 RequestBuilder
    │
    ▼
封装成 SingleRequest（包含所有配置参数）
```

---

## 三、into() — 提交请求，触发加载

```
.into(imageView)
    │
    ▼
1. 创建 ViewTarget（包装 ImageView）
    │
    ▼
2. 如果 ImageView 之前有请求 → 取消旧请求（防止列表复用时图片错乱）
    │
    ▼
3. 生成缓存 Key
   Key = url + 宽 + 高 + 变换 + 签名 + ...
   （相同 URL 不同尺寸 = 不同的 Key）
    │
    ▼
4. 提交给 Engine 开始加载
```

---

## 四、Engine 加载流程（三级缓存）

```
Engine.load(key)
    │
    ▼
① 活动资源缓存（ActiveResources）
    │
    ├── 命中 → 直接返回（引用计数 +1）
    │
    └── 未命中 ↓
    │
    ▼
② 内存缓存（LruResourceCache）
    │
    ├── 命中 → 从 LRU 移除，放入活动资源 → 返回
    │
    └── 未命中 ↓
    │
    ▼
③ 磁盘缓存（DiskLruCache）—— 在子线程执行
    │
    ├── 命中 → 解码 → 放入内存缓存 + 活动资源 → 返回
    │
    └── 未命中 ↓
    │
    ▼
④ 网络/文件加载（DataFetcher）—— 在子线程执行
    │
    ├── 下载/读取 → 解码 → 变换 → 缓存到磁盘 + 内存 + 活动资源 → 返回
    │
    └── 失败 → 回调 onLoadFailed → 显示 error 图
```

---

## 五、三级缓存详解

### 第 1 级：活动资源（ActiveResources）

```
存储：正在被 ImageView 显示的图片
数据结构：HashMap<Key, WeakReference<Resource>>
特点：弱引用，GC 时可回收

为什么需要这一级？
  如果只有 LRU 缓存，同一张图片被多个 ImageView 显示时
  LRU 的淘汰机制可能把正在使用的图片回收了
  活动资源用弱引用持有，保证"正在使用的图片"不会被 LRU 淘汰

生命周期：
  图片被 ImageView 使用 → 放入 ActiveResources（引用计数 +1）
  ImageView 释放图片   → 引用计数 -1
  引用计数 = 0          → 从 ActiveResources 移除，放回 LRU 缓存
```

### 第 2 级：内存缓存（LruResourceCache）

```
存储：最近使用过但当前没有被显示的图片
数据结构：LruCache<Key, Resource>（LinkedHashMap，访问排序）
默认大小：可用内存的 1/8

LRU 淘汰策略：
  ┌───┬───┬───┬───┬───┐
  │ A │ B │ C │ D │ E │  ← 最近使用的在右边
  └───┴───┴───┴───┴───┘
  访问 B → B 移到右边：
  ┌───┬───┬───┬───┬───┐
  │ A │ C │ D │ E │ B │
  └───┴───┴───┴───┴───┘
  缓存满了，新图片 F 进来 → 淘汰最左边的 A：
  ┌───┬───┬───┬───┬───┐
  │ C │ D │ E │ B │ F │
  └───┴───┴───┴───┴───┘
```

### 活动资源 vs 内存缓存的流转

```
加载图片 → 显示到 ImageView
    │
    ▼
图片在 ActiveResources 中（引用计数 = 1）
    │
    ▼
ImageView 被回收 / 加载新图片 → 引用计数 = 0
    │
    ▼
从 ActiveResources 移除 → 放入 LruResourceCache
    │
    ▼
下次需要这张图片 → 从 LruCache 取出 → 放回 ActiveResources

流转方向：
  加载 → ActiveResources（使用中）
                ↓ 释放
         LruResourceCache（备用）
                ↓ 淘汰
              被回收
```

### 第 3 级：磁盘缓存（DiskLruCache）

```
DiskCacheStrategy 策略：

┌────────────────────┬────────────────────────────────────┐
│ NONE               │ 不缓存                              │
│ DATA               │ 缓存原始数据（未解码）                │
│ RESOURCE           │ 缓存变换后的图片（已裁剪/缩放）        │
│ ALL                │ 原始数据 + 变换后的图片都缓存          │
│ AUTOMATIC（默认）   │ 根据数据源自动选择                    │
└────────────────────┴────────────────────────────────────┘
```

```
磁盘缓存的两个目录：

Data Cache（原始数据）：
  缓存从网络下载的原始图片文件
  Key = url + 签名
  优点：不同尺寸的 ImageView 可以共用原始文件

Resource Cache（变换后的数据）：
  缓存经过裁剪、缩放、变换后的图片
  Key = url + 宽 + 高 + 变换 + 签名
  优点：下次完全匹配时不需要重新解码和变换

加载顺序：先找 Resource Cache → 再找 Data Cache → 都没有才网络加载
```

---

## 六、线程池架构

```
Glide 内部有多个线程池，各司其职：

┌────────────────────────────────────────────┐
│  磁盘缓存线程池（diskCacheExecutor）          │
│  核心线程 1 个                               │
│  → 从磁盘读取缓存                            │
├────────────────────────────────────────────┤
│  源数据线程池（sourceExecutor）               │
│  核心线程数 = CPU 核心数（最多 4 个）          │
│  → 网络下载、文件加载                         │
├────────────────────────────────────────────┤
│  动画线程池（animationExecutor）              │
│  核心线程 1-2 个                             │
│  → GIF 解码                                 │
└────────────────────────────────────────────┘

加载流程的线程切换：
  主线程: into() → Engine.load()
      ↓
  磁盘线程: 检查磁盘缓存
      ↓ 未命中
  源数据线程: 网络下载 → 解码 → 变换 → 写入缓存
      ↓
  主线程: Target.onResourceReady() → 显示图片
```

---

## 七、Bitmap 复用（BitmapPool）

```
没有 BitmapPool：
  加载图片 A → 创建 Bitmap A（分配内存）
  图片 A 不用了 → Bitmap A 回收（GC）
  加载图片 B → 创建 Bitmap B（又分配内存）
  频繁分配回收 → GC 频繁 → 卡顿

有 BitmapPool：
  加载图片 A → 创建 Bitmap A
  图片 A 不用了 → Bitmap A 放入 BitmapPool（不回收）
  加载图片 B → 从 BitmapPool 找到尺寸匹配的 Bitmap A
              → 直接复用 A 的内存，把 B 的像素写进去
  避免频繁分配和 GC
```

```kotlin
// BitmapPool 的核心方法
interface BitmapPool {
    fun put(bitmap: Bitmap)               // 不用的 Bitmap 放入池中
    fun get(width: Int, height: Int,      // 从池中找匹配尺寸的 Bitmap
            config: Bitmap.Config): Bitmap
}

// 解码时复用：
val options = BitmapFactory.Options()
options.inBitmap = bitmapPool.get(width, height, config)  // 复用已有 Bitmap
BitmapFactory.decodeStream(stream, null, options)
```

---

## 八、防止列表图片错乱

```
RecyclerView 中 Item 复用导致的图片错乱：

Item 0 加载 url_A（请求中...）
    ↓ 滑出屏幕，Item 0 被复用为 Item 10
Item 10 加载 url_B（请求中...）
    ↓
url_A 先返回 → 显示到 Item 10 上 → 错乱！

Glide 的解决方式：
  into(imageView) 时
      │
      ├── 检查 imageView 是否已有关联的请求
      ├── 有 → 取消旧请求（url_A 的请求被取消）
      └── 绑定新请求（url_B）

  url_A 即使返回了，也找不到对应的 Target → 不会显示
  只有 url_B 的结果会显示 → 不会错乱
```

---

## 九、面试高频问题

### Q1: Glide 的缓存是几级？

| 级别 | 缓存 | 数据结构 | 线程 |
|------|------|---------|------|
| 1 | 活动资源 ActiveResources | HashMap + WeakReference | 主线程 |
| 2 | 内存缓存 LruResourceCache | LruCache | 主线程 |
| 3 | 磁盘缓存 DiskLruCache | 文件 | 子线程 |
| 4 | 网络加载 | - | 子线程 |

### Q2: 活动资源和内存缓存为什么要分开？

- 活动资源存"正在使用"的图片（弱引用），防止被 LRU 淘汰
- 内存缓存存"用过但当前没显示"的图片，按 LRU 淘汰
- 如果合并成一个 LRU，正在显示的图片可能被淘汰，导致闪烁

### Q3: Glide 怎么感知生命周期的？

- 向 Activity/Fragment 添加一个无 UI 的 `SupportRequestManagerFragment`
- Fragment 的 onStart/onStop/onDestroy 回调中控制请求的恢复/暂停/取消
- 和 Lifecycle 组件、Jetpack Lifecycle 的方式相同

### Q4: Glide 怎么防止内存泄漏？

- 生命周期绑定：Activity 销毁时自动取消请求
- 活动资源用弱引用：GC 时可回收
- `Glide.with(context)` 传 Activity 而不是 Application，确保生命周期正确

### Q5: Glide 和 Picasso 的区别？

| | Glide | Picasso |
|--|-------|---------|
| 缓存 | 内存 + 磁盘（变换后的图也缓存） | 内存 + 磁盘（只缓存原图） |
| 生命周期 | 自动绑定 Activity/Fragment | 不绑定 |
| GIF | 支持 | 不支持 |
| Bitmap 格式 | 默认 RGB_565（省内存） | 默认 ARGB_8888 |
| 包大小 | 较大 | 较小 |

### Q6: 磁盘缓存的 Key 包含什么？

- url + 宽 + 高 + 变换（CenterCrop 等）+ 签名 + 其他选项
- 所以**同一个 URL 不同尺寸的 ImageView 会有不同的缓存**
- 这也是 Glide 加载速度快的原因：缓存的是变换后的图，不需要重复解码和裁剪
