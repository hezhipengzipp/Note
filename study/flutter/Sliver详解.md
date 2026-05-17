# Flutter Sliver 详解

---

## 一、什么是 Sliver

```
Sliver = 薄片

Sliver 是 Flutter 中可以滚动的组件的抽象。
CustomScrollView 接收多个 Sliver，组合成复杂的滚动布局。

普通 ListView：一个整体滚动
CustomScrollView + Sliver：多个区域各自滚动，统一管理
```

```
CustomScrollView
    ├── SliverAppBar（可折叠头部）
    ├── SliverList（列表）
    ├── SliverGrid（网格）
    ├── SliverToBoxAdapter（普通 Widget）
    └── SliverFixedExtentList（固定高度列表）
```

---

## 二、常用 Sliver 组件

### 1. SliverAppBar — 可折叠头部

```dart
CustomScrollView(
  slivers: [
    SliverAppBar(
      expandedHeight: 200,       // 展开高度
      floating: false,           // 下滑时是否立即展开
      pinned: true,              // 是否固定在顶部
      snap: false,               // 是否 snap 效果
      flexibleSpace: FlexibleSpaceBar(
        title: Text('标题'),
        background: Image.asset('bg.jpg', fit: BoxFit.cover),
      ),
    ),
    SliverList(
      delegate: SliverChildBuilderDelegate(
        (context, index) => ListTile(title: Text('Item $index')),
        childCount: 50,
      ),
    ),
  ],
)
```

```
三种模式：

pinned: true, floating: false（默认）
  向上滑：AppBar 缩小到最小高度，固定在顶部
  向下滑：先滚列表，列表到顶后再展开 AppBar

pinned: false, floating: true
  向上滑：AppBar 完全消失
  向下滑：AppBar 立即出现

pinned: true, floating: true
  AppBar 始终可见，固定在顶部
```

### 2. SliverList — 列表

```dart
SliverList(
  delegate: SliverChildBuilderDelegate(
    (context, index) => ListTile(title: Text('Item $index')),
    childCount: 100,
  ),
)

// 等价于 ListView.builder
```

### 3. SliverFixedExtentList — 固定高度列表（性能更好）

```dart
SliverFixedExtentList(
  itemExtent: 80,  // 固定高度
  delegate: SliverChildBuilderDelegate(
    (context, index) => ListTile(title: Text('Item $index')),
    childCount: 100,
  ),
)

// 等价于 ListView.builder + itemExtent
```

### 4. SliverGrid — 网格

```dart
SliverGrid(
  gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
    crossAxisCount: 2,         // 两列
    mainAxisSpacing: 10,
    crossAxisSpacing: 10,
    childAspectRatio: 1.0,     // 宽高比
  ),
  delegate: SliverChildBuilderDelegate(
    (context, index) => Container(
      color: Colors.blue,
      child: Center(child: Text('$index')),
    ),
    childCount: 20,
  ),
)

// 等价于 GridView.builder
```

### 5. SliverToBoxAdapter — 包装普通 Widget

```dart
SliverToBoxAdapter(
  child: Container(
    height: 100,
    color: Colors.red,
    child: Center(child: Text('我不是 Sliver')),
  ),
)
```

### 6. SliverPersistentHeader — 吸顶 Header

```dart
SliverPersistentHeader(
  pinned: true,  // 吸顶
  delegate: _MyHeaderDelegate(),
)

class _MyHeaderDelegate extends SliverPersistentHeaderDelegate {
  @override
  double get minExtent => 50;  // 最小高度

  @override
  double get maxExtent => 100; // 最大高度

  @override
  Widget build(BuildContext context, double shrinkOffset, bool overlapsContent) {
    // shrinkOffset: 0 = 完全展开，maxExtent-minExtent = 完全折叠
    return Container(
      color: Colors.blue,
      child: Center(child: Text('吸顶 Header')),
    );
  }

  @override
  bool shouldRebuild(covariant SliverPersistentHeaderDelegate oldDelegate) {
    return false;
  }
}
```

### 7. SliverFillRemaining — 填充剩余空间

```dart
SliverFillRemaining(
  hasScrollBody: false,
  child: Center(
    child: Text('内容不足时填满屏幕'),
  ),
)
```

---

## 三、组合示例

### 电商首页布局

```dart
CustomScrollView(
  slivers: [
    // 1. 可折叠 Banner
    SliverAppBar(
      expandedHeight: 200,
      pinned: true,
      flexibleSpace: FlexibleSpaceBar(
        title: Text('商城'),
        background: Image.asset('banner.jpg', fit: BoxFit.cover),
      ),
    ),

    // 2. 分类 Grid
    SliverGrid(
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 5,
        childAspectRatio: 1.0,
      ),
      delegate: SliverChildBuilderDelegate(
        (context, index) => Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.category, size: 30),
            Text('分类$index'),
          ],
        ),
        childCount: 10,
      ),
    ),

    // 3. 吸顶标题
    SliverPersistentHeader(
      pinned: true,
      delegate: _SectionHeader('热门推荐'),
    ),

    // 4. 商品列表
    SliverList(
      delegate: SliverChildBuilderDelegate(
        (context, index) => ProductItem(product: products[index]),
        childCount: products.length,
      ),
    ),
  ],
)
```

### 个人主页布局

```dart
CustomScrollView(
  slivers: [
    // 头像 + 用户信息
    SliverToBoxAdapter(
      child: Container(
        padding: EdgeInsets.all(20),
        child: Column(
          children: [
            CircleAvatar(radius: 40),
            Text('用户名'),
            Text('个性签名'),
          ],
        ),
      ),
    ),

    // Tab 栏（吸顶）
    SliverPersistentHeader(
      pinned: true,
      delegate: _TabHeader(),
    ),

    // 内容列表
    SliverList(
      delegate: SliverChildBuilderDelegate(
        (context, index) => ContentItem(index: index),
        childCount: 100,
      ),
    ),
  ],
)
```

---

## 四、Sliver 原理

### Sliver 协议

```
Sliver 遵循 Sliver 协议，每个 Sliver 实现：

SliverConstraints（输入）：
  - scrollOffset: 当前滚动偏移
  - remainingPaintExtent: 剩余绘制空间
  - cacheOrigin: 缓存区域起点
  - overlap: 与前面 Sliver 的重叠

SliverGeometry（输出）：
  - scrollExtent: 总滚动高度
  - paintExtent: 实际绘制高度
  - cacheExtent: 缓存高度
  - hitTestExtent: 点击测试范围
```

### 渲染流程

```
CustomScrollView.performLayout()
    │
    ├── 遍历所有 Sliver child
    │
    ├── 对每个 Sliver：
    │     1. 计算 SliverConstraints
    │     2. 调用 sliver.performLayout()
    │     3. 获取 SliverGeometry
    │     4. 根据 geometry 决定是否继续布局下一个
    │
    └── 超出可视区域的 Sliver 不会布局（懒加载）
```

---

## 五、Sliver vs 普通组件

| | 普通组件 | Sliver |
|---|---|---|
| 滚动 | 每个组件独立滚动 | 统一由 CustomScrollView 管理 |
| 性能 | 全部构建 | 按需构建（懒加载） |
| 吸顶 | 难实现 | SliverPersistentHeader 原生支持 |
| 嵌套滚动 | 冲突 | 自然嵌套 |
| 使用场景 | 简单列表 | 复杂混合布局 |

---

## 六、自定义 Sliver

```dart
class SliverDivider extends SingleChildRenderObjectWidget {
  final double height;
  final Color color;

  const SliverDivider({this.height = 1, this.color = Colors.grey});

  @override
  RenderObject createRenderObject(BuildContext context) {
    return RenderSliverDivider(height: height, color: color);
  }
}

class RenderSliverDivider extends RenderSliverSingleBoxAdapter {
  final double height;
  final Color color;

  RenderSliverDivider({required this.height, required this.color});

  @override
  void performLayout() {
    final extent = height;
    geometry = SliverGeometry(
      scrollExtent: extent,
      paintExtent: extent,
      maxPaintExtent: extent,
    );
    if (child != null) {
      child!.layout(constraints.asBoxConstraints(), parentUsesSize: true);
    }
  }
}
```

---

## 七、面试常问

### 1. Sliver 和 ListView 区别？

```
ListView = SliverList 的封装
Sliver 可以在 CustomScrollView 中和其他 Sliver 混合使用
ListView 不能和 SliverAppBar 等组合
```

### 2. SliverAppBar 怎么实现折叠？

```
SliverAppBar 通过 SliverPersistentHeader 实现
根据滚动偏移量动态调整高度
expandedHeight → minExtent 之间平滑过渡
```

### 3. Sliver 的懒加载原理？

```
CustomScrollView 只布局可视区域内的 Sliver
超出可视区域的 Sliver 不会调用 performLayout
SliverChildBuilderDelegate 的 builder 按需调用
```

### 4. 什么时候用 CustomScrollView？

```
1. 需要 SliverAppBar（可折叠头部）
2. 多个不同类型的列表组合（Grid + List）
3. 需要吸顶效果
4. 复杂滚动布局，避免嵌套 ScrollView
```
