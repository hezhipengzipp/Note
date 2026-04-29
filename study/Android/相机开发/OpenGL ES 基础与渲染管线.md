# OpenGL ES 基础与相机渲染管线

## 一、OpenGL ES 是什么

OpenGL ES（Embedded Systems）是 OpenGL 的子集，专为嵌入式设备（手机、平板）设计。**Android 相机滤镜、美颜、特效全部基于它**。

面试官通常会从渲染管线开始问，能讲清以下流程就算过关。

---

## 二、OpenGL ES 渲染管线

```
顶点数据 (Vertex Array)
     │
     ▼
┌──────────────┐
│ 顶点着色器    │  → 顶点变换（MVP 矩阵）
│ Vertex Shader │
└──────┬───────┘
       │ 图元组装 (Primitive Assembly)
       ▼
┌──────────────┐
│ 光栅化        │  → 把顶点转成像素片元
│ Rasterization │
└──────┬───────┘
       │
┌──────────────┐
│ 片元着色器    │  → 每个像素的颜色计算
│ Fragment     │    （纹理采样、光照、滤镜都在这）
│ Shader       │
└──────┬───────┘
       │
┌──────────────┐
│ 逐片元操作    │  → 深度测试、模板测试、Alpha 混合
│ Per-Fragment │
│ Operations   │
└──────┬───────┘
       ▼
   帧缓冲区 (FrameBuffer)
```

**面试重点**：
- 顶点着色器 **只跑顶点次数**（三角形 3 次），片元着色器 **跑每个像素**
- 滤镜计算放片元着色器 → **天然 GPU 并行**
- 逐片元操作不可编程（但可以配置）

---

## 三、坐标系统

### 3.1 标准化设备坐标 (NDC)

```
OpenGL 坐标系 (右手系)：

      y
      │
      │
      ├──── x
     ╱
    z  (朝屏幕外)

NDC: x ∈ [-1, 1], y ∈ [-1, 1], z ∈ [-1, 1]
```

### 3.2 纹理坐标

```
纹理坐标系 (s, t)：

(0,0) ──── (1,0)
  │          │
  │          │
(0,1) ──── (1,1)

注意：t 轴是朝下的
```

**相机场景坑**：Camera 预览帧是旋转的（通常 90° 或 270°），纹理坐标要对应旋转：

```glsl
// 旋转 90° 的纹理坐标映射
attribute vec2 aTexCoord;
varying vec2 vTexCoord;

void main() {
    // 顺时针旋转 90°
    vTexCoord = vec2(aTexCoord.y, 1.0 - aTexCoord.x);
}
```

---

## 四、EGL — OpenGL 与窗口系统的桥梁

OpenGL ES 自己不直接和屏幕打交道，中间有 **EGL**。

```
App 进程
┌──────────────────────────────────────┐
│  ┌────────┐   ┌──────────────────┐   │
│  │ EGL    │   │  OpenGL ES       │   │
│  │ API    │──▶│  API             │   │
│  └───┬────┘   └────────┬─────────┘   │
│      │                 │             │
│      ▼                 ▼             │
│  ┌────────────────────────────┐      │
│  │   EGLDriver (GPU 驱动)     │      │
│  └───────────┬────────────────┘      │
└──────────────┼───────────────────────┘
               │
               ▼
          GPU 硬件
```

### 4.1 EGL 关键步骤

```java
// 1. 获取 EGL 显示设备
EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
eglInitialize(display, null, null);

// 2. 配置 EGL 属性
EGLConfig config = chooseConfig(display, attribList);
// 关键属性：RGBA 位数、depth/stencil 位数

// 3. 创建 EGL Context（OpenGL 的"工作环境"）
EGLContext context = eglCreateContext(
    display, config, EGL_NO_CONTEXT, contextAttribs
);
// 第三个参数是 share context，用于多线程共享纹理

// 4. 创建 EGLSurface（绑定到目标 Surface）
EGLSurface eglSurface = eglCreateWindowSurface(
    display, config, surface, null
// surface 可以是 SurfaceView / TextureView / Surface
);

// 5. 绑定上下文（线程级别！）
eglMakeCurrent(display, eglSurface, eglSurface, context);

// 6. 开始画...
glClear(GL_COLOR_BUFFER_BIT);
// ... 渲染命令 ...

// 7. 交换缓冲区（显示到屏幕）
eglSwapBuffers(display, eglSurface);
```

### 4.2 面试必问：EGL Context 是多线程的吗？

**一个 EGL Context 只能在一个线程上 current**。所以：

- GL 渲染必须在**专用渲染线程**（Render Thread）
- 不能直接在 UI 主线程调 OpenGL
- 多线程共享纹理 → 用 `share context`

```
共享 Context 方案：
EGLContext mainContext = createContext(...);

EGLContext thread1Ctx = createContextWithShare(mainContext);
EGLContext thread2Ctx = createContextWithShare(mainContext);

// thread1 和 thread2 可以访问 mainContext 创建的纹理
// 但各自 current 自己的 context
```

---

## 五、纹理 (Texture) — 图像在 GPU 中的存在形式

### 5.1 纹理创建与上传

```java
// 生成纹理 ID
int[] textures = new int[1];
glGenTextures(1, textures, 0);
int textureId = textures[0];

// 绑定纹理类型
glBindTexture(GL_TEXTURE_2D, textureId);

// 设置纹理参数（过滤 + 包裹模式）
glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

// 上传像素数据到 GPU
glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA,
    width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, bitmapData);

// 释放 CPU 侧数据，GPU 已有一份拷贝
```

### 5.2 纹理对象 (Texture Object) vs 纹理单元 (Texture Unit)

```
纹理单元 (Texture Unit)
┌─────────────────────────────┐
│  Unit 0  ───→ Texture ID 5  │   glActiveTexture(GL_TEXTURE0)
│  Unit 1  ───→ Texture ID 8  │   glBindTexture(GL_TEXTURE_2D, 8)
│  Unit 2  ───→ Texture ID 0  │
│  ...                        │
│  Unit 31                    │   最大单元数取决于 GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS
└─────────────────────────────┘
```

采样时：

```glsl
uniform sampler2D uTexture;   // 默认绑到纹理单元 0
// 如果有多张纹理：
uniform sampler2D uTexture1;  // 绑到纹理单元 0
uniform sampler2D uTexture2;  // 绑到纹理单元 1
```

---

## 六、SurfaceTexture — Camera 进 GPU 的关键

**`SurfaceTexture`** 是 Android 提供的桥接类，它同时是：

- 一个 `Surface`（生产者写入）
- 一个 `GLTexture`（消费者读出）

```
Camera2
  │  produce frames
  ▼
SurfaceTexture  ──内部──→  BufferQueue
    │                          │
    │                   dequeueBuffer
    │                     queueBuffer
    │                          │
    ▼                          ▼
  OpenGL ES 纹理 (textureId)
    │
    ▼
  渲染线程 (滤镜/特效/裁剪)
```

### 关键代码

```kotlin
// 1. 创建 SurfaceTexture，绑定到 OpenGL 纹理
val textures = IntArray(1)
GLES20.glGenTextures(1, textures, 0)
val textureId = textures[0]

val surfaceTexture = SurfaceTexture(textureId)
surfaceTexture.setDefaultBufferSize(previewWidth, previewHeight)

// 2. 把 SurfaceTexture 交给 Camera2
val surface = Surface(surfaceTexture)
captureSession = cameraDevice.createCaptureSession(
    listOf(surface),
    callback, handler
)

// 3. 渲染循环中，每次绘制前更新纹理
// ★ 必须在 GL 线程调用
surfaceTexture.updateTexImage()

// 获取纹理变换矩阵（Camera 旋转、翻转等）
val transformMatrix = FloatArray(16)
surfaceTexture.getTransformMatrix(transformMatrix)

// 在 shader 中使用这个矩阵旋转纹理坐标
```

### 关键方法说明

| 方法 | 作用 | 注意 |
|------|------|------|
| `updateTexImage()` | 从 BufferQueue 取最新帧到纹理 | 必须在 GL 线程，否则抛异常 |
| `getTransformMatrix(float[])` | 获取纹理变换矩阵 | Camera 旋转 90° 后的修正 |
| `setDefaultBufferSize(w, h)` | 设置 buffer 大小 | 必须在 Camera 打开前设 |

---

## 七、GLSurfaceView vs TextureView vs SurfaceView

| | SurfaceView | TextureView | GLSurfaceView |
|---|---|---|---|
| **独立窗口** | ✅ 独立 Surface（WMS） | ❌ 在宿主 Window 内 | ✅ 独立 Surface |
| **透明** | ❌ 不支持 | ✅ 支持 | ❌ 不支持 |
| **GL 直接渲染** | ✅ 需要自己搭 EGL | ✅ 需要自己搭 EGL | ✅ 自带 GLThread + EGL |
| **Z-order** | 固定（最上层） | 在 View 层级中 | 固定（最上层） |
| **动画/平移** | ❌ 难做（需 Surface 同步） | ✅ 可以加 Animator | ❌ 难做 |
| **性能** | 最高（Layer 少一次） | 中间（多一次 GPU copy） | 最高 |
| **API 复杂度** | 高 | 中 | 低 |

### 面试建议

```
问："相机预览用 SurfaceView 还是 TextureView？"

回答思路：
- SurfaceView 性能更好（独立 Layer，直接合成）
- TextureView 更灵活（可动画、可透明、可叠加 UI）
- 相机滤镜 App：通常用 SurfaceView + 自己管理 GL 线程
- 需要做画中画、贴纸 → TextureView 更合适
- Android 7.0+ SurfaceView 可以 z-order 调整了，差距缩小
```

---

## 八、完整 Camera2 + OpenGL 渲染管线

```
Camera2
  │
  ▼
SurfaceTexture (textureId)
  │
  ▼
片元着色器 ← 滤镜参数 (ColorMatrix / LUT / Blur)
  │
  ▼
FBO (FrameBuffer Object) — 离屏渲染中间结果
  │
  ▼
最终的 EGLSurface (显示到屏幕)
  │  或者
  ▼
MediaCodec InputSurface (录制/推流)

也可以一路走 FBO 做多级滤镜：
Camera → FBO1 (美颜) → FBO2 (滤镜) → FBO3 (特效) → 屏幕
          ↑ 每级都是 glDraw 到新的 FBO
```

### FBO 离屏渲染

```kotlin
// 创建 FBO
val fbo = IntArray(1)
GLES20.glGenFramebuffers(1, fbo, 0)
GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])

// 附加纹理作为输出
GLES20.glFramebufferTexture2D(
    GLES20.GL_FRAMEBUFFER,
    GLES20.GL_COLOR_ATTACHMENT0,
    GLES20.GL_TEXTURE_2D,
    textureId,
    0
)

// 之后 glDraw 就会画到这个纹理上
// 再把纹理绑到下一个 FBO 或最终输出
```

---

## 九、面试高频题

### Q1：渲染一帧 OpenGL 的完整流程是什么？

> 1. 创建 EGL Context 并 eglMakeCurrent
> 2. 绑定 VBO/VAO，设置顶点数据（矩形 + 纹理坐标）
> 3. 编译链接着色器程序（glCompileShader → glLinkProgram → glUseProgram）
> 4. 上传纹理（glTexImage2D / 或 SurfaceTexture.updateTexImage）
> 5. 设置 uniform 参数（变换矩阵、滤镜系数等）
> 6. glDrawArrays 触发渲染
> 7. eglSwapBuffers 显示到屏幕

### Q2：一个三角形有几个顶点、几个片元？

> 3 个顶点 → 经过光栅化后，覆盖了多少像素就有多少个片元。
> 1920x1080 的全屏矩形 = 4 个顶点（两个三角形），约 200 万个片元。

### Q3：EGL Context 切换为什么贵？

> eglMakeCurrent 涉及：
> - GPU 驱动的状态刷新
> - 可能 flush 当前 Context 的渲染命令队列
> - 部分 GPU 需要 cache flush / TLB 刷新
> 所以渲染线程和 UI 线程要分开，**不要频繁切换**。

### Q4：SurfaceTexture 为什么能零拷贝？

> Camera HW 直接写 GraphicBuffer → BufferQueue，SurfaceTexture 持有相同 Buffer 的引用。
> updateTexImage() 不是 copy 像素，而是把 Buffer 绑定到 EGLImage，再创建纹理。
> 全程 GPU 读 GPU 写，**CPU 看不到像素数据**。

### Q5：多个 FBO 级联怎么实现？

> ```
> 输入纹理 → FBO1 (滤镜A) → FBO2 (滤镜B) → 显示
>                  ↓              ↓
>          glBindFramebuffer  glBindFramebuffer
>          渲染到纹理A       渲染到纹理B
> ```
> 每级之间切换 FBO，用 glViewport 控制输出区域。
