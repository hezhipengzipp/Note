# Flutter 架构详解——渲染如何做到平台一致性

---

## 一、传统跨平台方案为什么"不一致"

```
React Native / Weex 的做法：
┌─────────────────────────────────────────────┐
│           JS / 业务逻辑                       │
│           <Button title="OK"/>              │
└──────────────────┬──────────────────────────┘
                   │  Bridge（序列化/反序列化）
        ┌──────────┴──────────┐
        ▼                     ▼
  Android 原生控件         iOS 原生控件
  android.widget.Button    UIButton
  (Material 风格)          (Cupertino 风格)
  (各厂商魔改)              (Apple 统一)
```

**问题在哪？**
- 同一个 `<Button>`，Android 上走的是 `android.widget.Button`，iOS 上走的是 `UIButton`
- 它们是**完全不同的代码实现**，圆角、阴影、点击动画全都不一样
- 更糟的是，小米/三星/华为各自魔改了 Android 控件，同一个 Android 都不一致

---

## 二、Flutter 的做法：绕过原生控件，自己画

```
┌─────────────────────────────────────────────────┐
│              Framework（Dart）                    │
│                                                 │
│   你写的代码：                                    │
│   ElevatedButton(onPressed: ..., child: ...)    │
│          │                                      │
│          ▼                                      │
│   Widget → Element → RenderObject               │
│          │                                      │
│          ▼  paint() 方法                         │
│   canvas.drawRRect(...)   // 画圆角矩形           │
│   canvas.drawShadow(...)  // 画阴影              │
│   canvas.drawText(...)    // 画文字              │
│                                                 │
│   所有 UI 都是一笔一笔画出来的！                     │
├─────────────────────────────────────────────────┤
│              Engine（C++）                        │
│                                                 │
│   Skia 2D 渲染引擎                               │
│   接收上层的绘制指令（drawRRect, drawText...）       │
│   光栅化为像素数据                                 │
│                                                 │
├─────────────────────────────────────────────────┤
│              Embedder（平台嵌入层）                 │
│                                                 │
│   只做一件事：提供一块画布（Surface）                 │
│                                                 │
│   Android: SurfaceView / TextureView            │
│   iOS:     CAMetalLayer / CAEAGLLayer           │
│   Desktop: 窗口系统的 Surface                     │
│                                                 │
│   + 转发触摸事件、键盘事件、生命周期等给 Engine       │
└─────────────────────────────────────────────────┘
```

**核心思路**：Flutter 对操作系统只有一个要求——**给我一块画布**。剩下的所有像素，我自己画。

---

## 三、类比 Android 理解 Flutter 渲染

可以把 Flutter 理解为：**整个 App 就是一个全屏的自定义 View，在 `onDraw(Canvas)` 里画一切**。

```
Android 原生渲染                    Flutter 渲染
─────────────────                  ─────────────────
Activity                           FlutterActivity
  └─ ViewGroup                       └─ FlutterView (只有这一个 View!)
       ├─ TextView                        └─ SurfaceView
       ├─ ImageView                            │
       ├─ RecyclerView                         │ 提供 Canvas
       └─ Button                               ▼
       各自独立 measure/layout/draw         Skia 在这块 Canvas 上
       每个都是独立的原生控件                 画出所有的"文字""图片""列表""按钮"
```

用代码感受一下：

```dart
// Flutter 的 RenderBox（类似 Android 的 View）
class RenderMyButton extends RenderBox {
  @override
  void paint(PaintingContext context, Offset offset) {
    final canvas = context.canvas;

    // 画背景（圆角矩形）
    canvas.drawRRect(
      RRect.fromRectAndRadius(rect, Radius.circular(8)),
      Paint()..color = Colors.blue,
    );

    // 画文字
    textPainter.paint(canvas, textOffset);

    // 这段代码在 Android、iOS、Windows、Web 上
    // 执行的是 完 全 一 样 的 逻辑
    // 因为 Canvas API 是 Skia 提供的，和平台无关
  }
}
```

---

## 四、为什么能保证一致性

关键在于**渲染链路完全不经过平台控件**：

```
传统方案的渲染链路（平台相关 ❌）：
  代码 → Bridge → 平台原生控件 → 平台渲染引擎 → 像素
                   ↑
               这里每个平台不一样

Flutter 的渲染链路（平台无关 ✅）：
  代码 → RenderObject.paint() → Skia 绘制指令 → GPU 光栅化 → 像素
                                  ↑
                         这是 Flutter 自带的，
                      在所有平台上是同一份 C++ 代码
```

| 渲染环节 | 是否跨平台统一 | 说明 |
|---------|-------------|------|
| Widget / RenderObject | 统一 | Dart 代码，所有平台同一份 |
| Skia 引擎 | 统一 | C++ 编译到各平台，同一份源码 |
| 绘制指令 (drawRect等) | 统一 | Skia API，和平台无关 |
| GPU 调用 | **这里适配** | Android 用 OpenGL/Vulkan，iOS 用 Metal |
| Surface 画布 | **这里适配** | 各平台提供自己的 Surface |

**平台差异被压缩到了最底层的两件事**：
1. 给 Skia 一块 GPU 画布
2. 把触摸/键盘事件转发上来

这两件事非常简单，几乎不会引入不一致。

---

## 五、代价

```
好处                              代价
─────────────────────            ─────────────────────
✅ 像素级跨平台一致                ❌ 包体积大（自带 Skia 引擎 ~4MB）
✅ 不受 OEM 魔改影响               ❌ 原生控件集成复杂（PlatformView）
✅ 无 Bridge 性能瓶颈              ❌ 无法使用系统级无障碍控件，需自行实现
✅ 动画流畅（直接控制每一帧）         ❌ 系统级功能（地图、WebView）仍需桥接
```

---

## 六、面试回答模板

**Q: Flutter 是怎么做到跨平台 UI 一致性的？**

> Flutter 没有使用原生控件，而是自带了 Skia 2D 渲染引擎。所有 UI 组件（按钮、文字、列表等）都是通过 RenderObject 的 paint 方法在 Canvas 上一笔一笔画出来的。Flutter 对平台的唯一要求就是提供一块 Surface 画布和转发输入事件。因为绘制逻辑和 Skia 引擎在所有平台上是同一份代码，所以渲染结果是像素级一致的。这和 RN 等方案通过 Bridge 调用原生控件有本质区别——RN 的一致性取决于各平台原生控件的实现，Flutter 则完全自主可控。
