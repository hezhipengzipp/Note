# Surface 与 EGLSurface 关系详解

一句话总结：**Surface 是 Android 系统的原生窗口对象（生产者端），EGLSurface 是 OpenGL ES 的绘制表面（渲染目标），`eglCreateWindowSurface` 的作用是将两者绑定，使得 GPU 渲染的内容能够直接输出到 Android 的 Surface 上。**

---

## 一、核心概念

```
Android Framework 层
┌────────────────────────────────────────────┐
│  Surface                                    │
│  ● BufferQueue 的生产者端                    │
│  ● 可跨进程传递（Binder 代理）               │
│  ● 可接收 Canvas / Camera / 解码器输出       │
└──────────────────┬─────────────────────────┘
                   │
                   │ eglCreateWindowSurface(display, config, surface, ...)
                   ▼
EGL 层
┌────────────────────────────────────────────┐
│  EGLSurface                                 │
│  ● OpenGL ES 的渲染目标                     │
│  ● 绑定到当前 EGLContext                    │
│  ● 调用 eglSwapBuffers 后内容到达 Surface   │
└────────────────────────────────────────────┘
                   │
                   │ eglSwapBuffers
                   ▼
GPU 渲染内容 → Surface → BufferQueue → SurfaceFlinger → 屏幕
```

---

## 二、详细对比

| 维度 | Surface | EGLSurface |
|------|---------|------------|
| **所属层级** | Android Framework | EGL (OpenGL ES 窗口系统接口) |
| **本质** | BufferQueue 的生产者端 Binder 代理 | 绘图表面抽象（可跨平台） |
| **创建方式** | `new Surface(SurfaceTexture)` / `SurfaceView.getHolder().getSurface()` | `eglCreateWindowSurface()` / `eglCreatePbufferSurface()` |
| **主要方法** | `lockCanvas()` / `unlockCanvasAndPost()` | `eglSwapBuffers()` |
| **跨进程能力** | ✅ 是（Binder 代理） | ❌ 否（本进程内） |
| **用途** | 接收图像数据（Canvas / 相机 / 解码器） | 作为 OpenGL 的渲染目标 |

---

## 三、从 Surface 到 EGLSurface 的完整链路

### 3.1 Android 层的 Surface

```java
// Java/Kotlin 层
Surface surface = new Surface(surfaceTexture);
// 或
Surface surface = surfaceView.getHolder().getSurface();
```

### 3.2 Native 层的 ANativeWindow

Surface 在 Native 层的对应物是 `ANativeWindow`：

```cpp
// frameworks/native/libs/gui/Surface.cpp
class Surface : public ANativeWindow {
    // 实现了 dequeueBuffer、queueBuffer、cancelBuffer 等方法
};
```

### 3.3 EGL 层的绑定

```cpp
// EGL 实现中（以 Android 的 eglCreateWindowSurface 为例）
EGLSurface eglCreateWindowSurface(EGLDisplay dpy, EGLConfig config,
                                   EGLNativeWindowType window,  // ← 这里就是 ANativeWindow*
                                   const EGLint *attrib_list) {
    // 1. 验证 window 参数
    // 2. 创建 EGLSurface 对象，内部持有 ANativeWindow 引用
    // 3. 返回 EGLSurface 句柄
}
```

关键：`EGLNativeWindowType` 在 Android 上就是 `ANativeWindow*`，而 `Surface` 是 `ANativeWindow` 的子类。

---

## 四、与其他 EGLSurface 类型对比

| 类型 | 创建方式 | 用途 |
|------|---------|------|
| **WindowSurface** | `eglCreateWindowSurface()` | 显示到屏幕 / SurfaceView |
| **PbufferSurface** | `eglCreatePbufferSurface()` | 离屏渲染（不可见） |
| **PixmapSurface** | `eglCreatePixmapSurface()` | 渲染到系统内存（已较少使用） |

```kotlin
// 离屏渲染示例（常用于 FBO 或截图）
val pbufferAttribs = intArrayOf(
    EGL14.EGL_WIDTH, 1024,
    EGL14.EGL_HEIGHT, 1024,
    EGL14.EGL_NONE
)
val pbufferSurface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0)
```

---

## 五、典型使用流程

```kotlin
// 1. 拿到 Android Surface（可能来自 Client 进程）
val surface = getSurfaceFromClient()

// 2. 创建 EGLSurface（包装）
val eglSurface = createWindowSurface(surface)

// 3. 绑定到当前 GL 上下文
EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)

// 4. 执行 OpenGL 渲染
GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
// ... 绘制命令

// 5. 交换缓冲区（画面送到 Surface）
EGL14.eglSwapBuffers(display, eglSurface)

// 6. 释放
EGL14.eglDestroySurface(display, eglSurface)
```

---

## 六、总结速记

```
┌──────────────────────────────────────────────────────────────┐
│               Surface vs EGLSurface 关系                      │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  Surface（Android 层）：                                     │
│    • 生产者接口，BufferQueue 的 Binder 代理                  │
│    • 可跨进程传递                                            │
│    • 支持 Canvas / 相机 / 解码器输出                         │
│                                                              │
│  EGLSurface（EGL 层）：                                     │
│    • OpenGL 渲染目标                                         │
│    • 不能直接跨进程                                          │
│    • 需要绑定到 Surface 才能显示                             │
│                                                              │
│  绑定关系：                                                   │
│    eglCreateWindowSurface(display, config, surface, ...)    │
│    → 将 Android Surface 包装成 EGLSurface                    │
│    → GPU 渲染的内容通过 swapBuffers 送到 Surface             │
│                                                              │
│  类比理解：                                                   │
│    Surface   = 显示器的物理接口（HDMI 口）                   │
│    EGLSurface = GPU 驱动程序写入的显存缓冲区                 │
│    绑定操作   = 把显存缓冲区连接到显示器接口                   │
│                                                              │
│  项目场景：                                                   │
│    Server 进程渲染 → EGLSurface → Surface（代理）            │
│    → 跨进程 → Client 的 SurfaceView 显示                     │
└──────────────────────────────────────────────────────────────┘
```
