# Android XML 布局加载流程详解

## 一、整体流程

```
     setContentView(R.layout.activity_main)
              │
              ▼
  ┌───────────────────────────────────┐
  │  编译期 aapt2 编译                 │
  │  XML 文本 → 二进制 .xml            │
  │  打包进 resources.arsc / APK       │
  └───────────────┬───────────────────┘
                  │ 运行时
                  ▼
  ┌───────────────────────────────────┐
  │  Resources.getLayout(id)           │
  │  返回 XmlResourceParser(二进制流)  │
  └───────────────┬───────────────────┘
                  │
                  ▼
  ┌───────────────────────────────────┐
  │  LayoutInflater.inflate()          │
  │  递归解析 XML 标签                 │
  └───────────────┬───────────────────┘
                  │
                  ▼
  ┌───────────────────────────────────┐
  │  createViewFromTag()               │
  │  ├─ Factory/Factory2 钩子          │
  │  ├─ 反射创建 View                  │
  │  └─ 解析 attrs → 设置属性          │
  └───────────────┬───────────────────┘
                  │
                  ▼
  ┌───────────────────────────────────┐
  │  addView 构建 View 树              │
  │  再交给 ViewRootImpl 三大流程      │
  │  measure / layout / draw           │
  └───────────────────────────────────┘
```

---

## 二、LayoutInflater 获取

```kotlin
// 三种等价写法
LayoutInflater.from(context)
context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
context.getSystemService(LayoutInflater::class.java)
```

**本质**：ContextImpl 持有一个 `PhoneLayoutInflater`（LayoutInflater 子类），负责处理前缀简写：

```java
// PhoneLayoutInflater.sClassPrefixList
{ "android.widget.", "android.webkit.", "android.app." }
```

所以 XML 里写 `<TextView>` 等同于 `<android.widget.TextView>`。

---

## 三、inflate 方法核心流程

```java
// LayoutInflater.java 简化
public View inflate(int resource, ViewGroup root, boolean attachToRoot) {
    // 1. 拿二进制 XML parser
    XmlResourceParser parser = getContext().getResources().getLayout(resource);
    return inflate(parser, root, attachToRoot);
}

public View inflate(XmlPullParser parser, ViewGroup root, boolean attachToRoot) {
    synchronized (mConstructorArgs) {
        // 2. 跳到第一个标签
        advanceToRootNode(parser);
        final String name = parser.getName();

        // 3. 特殊标签处理
        if (TAG_MERGE.equals(name)) {         // <merge>
            rInflate(parser, root, ...);       // 必须传非 null root
        } else {
            // 4. 创建根 View
            View temp = createViewFromTag(root, name, ...);
            ViewGroup.LayoutParams params = root?.generateLayoutParams(attrs);

            // 5. 递归创建子 View
            rInflateChildren(parser, temp, attrs, true);

            // 6. 根据 attachToRoot 决定是否 addView
            if (root != null && attachToRoot) root.addView(temp, params);
        }
    }
    return result;
}
```

### `attachToRoot` 三种情况

| root | attachToRoot | 结果                                             |
| ---- | ------------ | ------------------------------------------------ |
| null | 任意         | 直接返回 inflate 出来的 View，不设 LayoutParams  |
| 非 null | false     | 返回 View，有 root 的 LayoutParams（**常用**）   |
| 非 null | true      | 返回 root，把 View addView 到 root 上            |

**RecyclerView 的 onCreateViewHolder** 必须传 `(parent, false)` —— 要 LayoutParams 但不 addView。

---

## 四、createViewFromTag（核心）

```java
View createViewFromTag(View parent, String name, Context ctx, AttributeSet attrs) {
    // 1. Factory2 钩子（AppCompat 在这里替换）
    View view = mFactory2 != null ? mFactory2.onCreateView(parent, name, ctx, attrs)
              : mFactory  != null ? mFactory.onCreateView(name, ctx, attrs)
              : null;

    if (view == null) {
        // 2. 处理前缀
        if (-1 == name.indexOf('.')) {
            // TextView → android.widget.TextView
            view = onCreateView(parent, name, attrs);
        } else {
            // 自定义 View 必须全限定名
            view = createView(name, null, attrs);
        }
    }
    return view;
}
```

### createView 反射创建

```java
public final View createView(String name, String prefix, AttributeSet attrs) {
    // 1. 构造器缓存（全局 HashMap）
    Constructor<? extends View> constructor = sConstructorMap.get(name);

    if (constructor == null) {
        // 2. 反射找类
        Class<? extends View> clazz = mContext.getClassLoader()
            .loadClass(prefix != null ? prefix + name : name)
            .asSubclass(View.class);
        // 3. 找 (Context, AttributeSet) 构造器
        constructor = clazz.getConstructor(mConstructorSignature);
        sConstructorMap.put(name, constructor);
    }

    // 4. 反射 new
    return constructor.newInstance(mContext, attrs);
}
```

**关键点**：
- 反射创建 + 构造器缓存（静态 Map 全局共享）
- 每个自定义 View 必须有 `(Context, AttributeSet)` 构造器

---

## 五、递归构建 View 树

```java
void rInflate(XmlPullParser parser, View parent, ...) {
    while ((type = parser.next()) != END_DOCUMENT) {
        if (type != START_TAG) continue;
        final String name = parser.getName();

        if (TAG_REQUEST_FOCUS.equals(name)) {...}
        else if (TAG_TAG.equals(name)) {...}
        else if (TAG_INCLUDE.equals(name)) parseInclude(...);        // <include>
        else if (TAG_MERGE.equals(name)) throw ...;                   // merge 不能嵌套
        else {
            View view = createViewFromTag(parent, name, ...);
            ViewGroup viewGroup = (ViewGroup) parent;
            rInflateChildren(parser, view, attrs, true);              // 递归
            viewGroup.addView(view, viewGroup.generateLayoutParams(attrs));
        }
    }
}
```

执行顺序：

```
Root (LinearLayout)
  ├─ 创建 Root ──▶ 递归 ─┐
  │                      │
  ├─ Child1 ──▶ 创建、递归其子
  ├─ Child2
  └─ Child3
        │
        └─ 深度优先遍历，叶子节点先 addView 完再回退
```

---

## 六、AppCompat 如何"偷梁换柱"

```
   <TextView ...>
         │
         ▼
  inflater.inflate()
         │
         ▼
  mFactory2.onCreateView()   ← AppCompat 注册的 Factory2
         │
         ▼
  AppCompatViewInflater.createView()
         │
         ▼
   TextView → AppCompatTextView
   Button   → AppCompatButton
   ...
         │
         ▼
  返回 AppCompatXxx 实例（支持 Tint 染色、夜间模式等）
```

**注入时机**：`AppCompatDelegate.installViewFactory(layoutInflater)`，在 `AppCompatActivity.onCreate` 里调 `super.onCreate(...)` 时完成。

**所以** AppCompatActivity 里 XML 里写 `<TextView>` 运行时实际是 `AppCompatTextView`。

---

## 七、特殊标签处理

### `<include>`

```java
void parseInclude(...) {
    int layout = attrs.getAttributeResourceValue("layout");
    // 递归加载 include 的 layout
    XmlResourceParser child = ctx.getResources().getLayout(layout);
    rInflate(child, parent, ...);
    // 注意：include 标签本身不产生 View，直接把子 View 挂到 parent
}
```

### `<merge>`

只能作为 inflate 的根，且必须传非 null 的 root，子 View 直接挂到 root 上。

### `<ViewStub>`

ViewStub 本身是个轻量 View（0 宽高），只有在 `inflate()` 时才加载子布局并替换自己。

### `<requestFocus>` / `<tag>`

元数据标签，不生成 View，只是给父 View 设置属性。

---

## 八、性能瓶颈

```
inflate 总耗时 = IO + 解析 + 反射 + 属性设置
                ↑
           每一环都慢
```

| 阶段        | 开销                                   |
| ----------- | -------------------------------------- |
| 读 XML       | IO 小但多次                            |
| 解析         | 二进制格式已优化，XmlPullParser 还要走 |
| 反射创建     | **最慢**，Class.forName + 构造器反射    |
| 属性遍历     | `obtainStyledAttributes` 逐个属性解析  |
| 添加到父容器 | addView 可能触发 requestLayout         |

**实测**：复杂页面 inflate 可达 100~300ms。

---

## 九、优化方案

### 1. AsyncLayoutInflater（Jetpack）

子线程 inflate，回调主线程 add：

```kotlin
AsyncLayoutInflater(context).inflate(R.layout.heavy, parent) { view, _, _ ->
    parent.addView(view)
}
```

### 2. X2C（字节开源，编译期转换）

把 XML 编译期转成 Java 代码，省去反射：

```java
// xml 编译后生成
public class X2C_activity_main {
    public View getView(Context ctx, int... ids) {
        LinearLayout root = new LinearLayout(ctx);
        TextView tv = new TextView(ctx);
        tv.setText("...");
        root.addView(tv);
        return root;
    }
}
```

**效果**：inflate 速度提升 **1-3 倍**。

### 3. Compose（终极方案）

无 XML，直接代码生成 UI，无反射：

```kotlin
@Composable
fun Greeting() {
    Text("Hello")
}
```

### 4. 缓存 + 复用

- 相同 Activity 跳转复用 View（单 Activity 架构）
- 预加载：IdleHandler 空闲时提前 inflate
- ViewHolder 复用

### 5. 简化布局（治本）

- 减少嵌套
- 用 `<merge>` 省层级
- `<ViewStub>` 延迟不必要的分支

---

## 十、完整时序图

```
App 调用 setContentView
      │
      ▼
PhoneWindow.setContentView
      │
      ▼
LayoutInflater.inflate(id, decor)
      │
      ├─ Resources.getLayout(id) → XmlBlock.Parser
      │      (aapt2 编译出的二进制 XML)
      │
      ├─ createViewFromTag (根节点)
      │      ├─ Factory2.onCreateView  ← AppCompat 替换
      │      └─ 反射 createView
      │
      ├─ rInflateChildren (递归)
      │      └─ 每个子节点重复 createViewFromTag
      │
      └─ addView 构建树
             │
             ▼
ViewRootImpl.setView
      │
      └─ scheduleTraversals → measure / layout / draw
```

---

## 十一、面试题

### Q1：setContentView 内部做了什么？

1. `PhoneWindow.setContentView(id)` 被调用
2. 如果还没创建 DecorView，先创建（根据 feature 选 screen_simple.xml 等）
3. 拿到 DecorView 里的 `@android:id/content` 容器
4. 调 `LayoutInflater.inflate(id, contentParent)` 把用户布局挂到 content 上
5. 后续由 ViewRootImpl 调度 measure/layout/draw

### Q2：为什么自定义 View 必须有 `(Context, AttributeSet)` 构造器？

LayoutInflater 反射创建时调用 `Class.getConstructor(Context.class, AttributeSet.class)`，找不到就抛 `NoSuchMethodException`。如果只是代码 new，有 `(Context)` 构造器就够。

### Q3：`<merge>` 为什么必须传非 null root？

因为 `<merge>` 本身不对应任何 View，它的子 View 必须挂到一个已有的父容器上。如果 root 为 null，子 View 没地方放，所以源码直接抛 InflateException。

### Q4：`attachToRoot = true` 和 `false` 的区别？

- `true`：inflate 完直接 addView 到 root，返回的是 root
- `false`：只 inflate 出 View 并带上 root 的 LayoutParams，不 addView，返回的是新 View

RecyclerView/ListView 适用 false，因为 ViewHolder 的 View 由 RecyclerView 自己管理添加时机。

### Q5：XML 里 `<TextView>` 运行时真的是 TextView 吗？

在 `Activity` 下是。在 `AppCompatActivity` 下**不是**，AppCompat 通过 Factory2 拦截，替换为 `AppCompatTextView`，为了实现 Tint、夜间模式等特性。

### Q6：Factory 和 Factory2 区别？

- `Factory`：`onCreateView(name, context, attrs)`，没有 parent
- `Factory2`（API 11+）：`onCreateView(parent, name, context, attrs)`，多了 parent 参数

Factory2 更强大，AppCompat、DataBinding 都用 Factory2 实现。**同一个 inflater 只能 setFactory 一次**，否则抛异常。

### Q7：inflate 为什么慢？

三大开销：
1. **IO**：读 binary XML
2. **反射**：`Class.forName` + `Constructor.newInstance`
3. **属性解析**：`obtainStyledAttributes` 遍历 TypedArray

虽然单次开销不大，但复杂布局累积起来几百毫秒，启动慢的常见原因之一。

### Q8：AsyncLayoutInflater 的原理？

1. 主线程创建 AsyncLayoutInflater，内部用一个 HandlerThread 叫 `BasicInflater`
2. `inflate()` 把任务丢到 BasicInflater 执行（子线程 inflate）
3. inflate 完成后通过主线程 Handler 回调 `OnInflateFinishedListener`

**要求**：View 的构造必须线程安全（访问主线程资源就会崩溃）。

### Q9：`<include>` 标签处理时有什么特殊点？

1. 不产生任何 View，直接把 include 的布局子节点挂到父容器
2. include 标签上的 `layout_*` 属性会覆盖被包含布局根节点的属性
3. include 标签的 `android:id` 也会覆盖根节点 id

### Q10：XML 是怎么从文本变成运行时对象的？

1. **编译期**：aapt2 把 XML 文本编译成 **二进制格式**（字符串池、资源引用 ID 化），存入 APK 的 `res/` 或 `resources.arsc`
2. **运行时**：`Resources.getLayout(id)` 返回 `XmlResourceParser`，其实是 `XmlBlock.Parser`，解析二进制流
3. **反射**：LayoutInflater 根据标签名反射创建 View 对象
4. **属性设置**：构造器内部通过 `obtainStyledAttributes` 把 attrs 转成 View 字段

**二进制 XML 比文本 XML 解析快 5-10 倍**。
