# Shader 编程与相机滤镜实现

## 一、GLSL 基础

着色器用 GLSL（OpenGL Shading Language）编写，Android 中用的是 GLSL ES。

### 1.1 顶点着色器 (Vertex Shader)

```glsl
// 顶点着色器：每个顶点执行一次
attribute vec4 aPosition;    // 顶点坐标 (x, y, z, w)
attribute vec2 aTexCoord;    // 纹理坐标 (s, t)

uniform mat4 uMVPMatrix;     // MVP 变换矩阵
uniform mat4 uTexMatrix;     // 纹理变换矩阵（SurfaceTexture 旋转）

varying vec2 vTexCoord;      // 传递给片元着色器的纹理坐标

void main() {
    gl_Position = uMVPMatrix * aPosition;    // 变换后的顶点位置
    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0, 1)).xy;
    //   ^ 纹理坐标经过变换（Camera 旋转校正）
}
```

### 1.2 片元着色器 (Fragment Shader)

```glsl
// 片元着色器：每个像素执行一次
precision mediump float;     // 精度声明（低/中/高）

varying vec2 vTexCoord;      // 来自顶点着色器的纹理坐标
uniform sampler2D uTexture;  // 纹理采样器

void main() {
    gl_FragColor = texture2D(uTexture, vTexCoord);
    //            ← 采样纹理颜色
}
```

### 1.3 精度修饰符

| 修饰符 | 适用 | 范围 | 精度 |
|--------|------|------|------|
| `lowp` | 颜色 | -2 ~ 2 | 10bit |
| `mediump` | 纹理坐标 | -16384 ~ 16384 | 16bit |
| `highp` | 顶点位置 | -2^60 ~ 2^60 | 32bit |

片元着色器中默认用 `mediump`，不需要高精度的地方别用 highp（性能浪费）。

---

## 二、基础滤镜实现

### 2.1 灰度滤镜 — 最简单的入门

```glsl
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D uTexture;

void main() {
    vec4 color = texture2D(uTexture, vTexCoord);

    // 标准亮度加权灰度
    float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    //                     红 ↑    绿 ↑     蓝 ↑
    // 人眼对绿色最敏感，权重最高

    gl_FragColor = vec4(vec3(gray), color.a);
}
```

**为什么用 dot 不是平均值？** 人眼对 R/G/B 敏感度不同，加权更自然。

### 2.2 ColorMatrix 滤镜（色调、饱和度、亮度）

```glsl
uniform mat4 uColorMatrix;   // 4x4 颜色矩阵

void main() {
    vec4 color = texture2D(uTexture, vTexCoord);
    gl_FragColor = uColorMatrix * color;
}
```

示例矩阵：

```
// 饱和度增加 (Saturation = 1.5)
// 原理：把 RGB 转亮度 + 按比例增强色差
| R' |   | 0.308  0.609  0.082  0 |   | R |
| G' | = | 0.308  0.609  0.082  0 | × | G |
| B' |   | 0.308  0.609  0.082  0 |   | B |
| A' |   | 0      0      0      1 |   | A |

// 更通用的饱和度公式：
float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));
vec3 satColor = mix(vec3(lum), color.rgb, saturationFactor);
```

**面试考点**：ColorMatrix 是 4x5 矩阵（行向量），为什么 glsl 用 4x4？

> 因为 GLSL 矩阵是列主序，4x4 矩阵第 4 列存偏移量（亮度/色调偏移），第 4 行通常 0 0 0 1。

### 2.3 查找表 (LUT) 滤镜 — 商业级滤镜方案

LUT（Look Up Table）是**最常用的专业滤镜方案**（Instagram、抖音都用）。

```
原理：
                     R
原始颜色 ─→ 用 RGB 做索引 ─→ 查表得到新颜色
                     G
                     B

3D LUT 是一个 三维颜色映射：
每个 R/G/B 值组合映射到一个新的 RGB 值
```

**64³ 3D LUT 图**（512x512 的 PNG）：

```
纹理图布局（8x8 的 64x64 小方块）：
┌─────┬─────┬─────┐
│ B=0  │ B=1  │ ...  │  → B 维度
├─────┼─────┼─────┤
│ B=8  │ B=9  │ ...  │
├─────┼─────┼─────┤
│ ...  │ ...  │ ...  │
└─────┴─────┴─────┘
   每个小方块 64x64 = R x G
```

```glsl
// 3D LUT 片元着色器（核心实现）
precision highp float;
varying vec2 vTexCoord;
uniform sampler2D uTexture;      // 原始图像
uniform sampler2D uLUTTexture;   // LUT 图 (512x512)

void main() {
    vec4 color = texture2D(uTexture, vTexCoord);

    // LUT 查找 — 从 RGBA 映射到新颜色
    float blue = color.b * 63.0;                // 蓝通道 → 0~63

    // 相邻两个 B 层之间插值（三线性插值）
    vec2 quad1 = vec2(0.0, floor(blue) / 8.0);  // 选中行
    vec2 quad2 = vec2(0.0, (floor(blue) + 1.0) / 8.0);

    // R/G 映射到小方块内的坐标
    vec2 rg = color.rg * (63.0 / 512.0);         // 缩放

    vec2 pos1 = quad1 + vec2(floor(blue) / 8.0, 0.0) + rg;
    vec2 pos2 = quad2 + vec2((floor(blue) + 1.0) / 8.0, 0.0) + rg;

    vec4 newColor1 = texture2D(uLUTTexture, pos1);
    vec4 newColor2 = texture2D(uLUTTexture, pos2);

    gl_FragColor = mix(newColor1, newColor2, fract(blue));
    //            ← 线性插值两个 LUT 层的颜色
}
```

**为什么 LUT 是商业滤镜首选？**

| 方案 | LUT | ColorMatrix | 逐像素计算 |
|------|-----|-------------|-----------|
| 调色效果 | 任意（非线性） | 线性变换 | 任意 |
| 性能 | 1 次纹理采样 | 1 次 4x4 乘法 | N 次计算 |
| 文件大小 | 512x512 PNG | 16 个 float | 整个算法代码 |
| 新增滤镜 | 设计师出图即可 | 调矩阵 | 写 shader |

### 2.4 卷积滤镜（模糊、锐化、边缘检测）

```glsl
// 3x3 卷积核
uniform sampler2D uTexture;
uniform vec2 uTexOffset;       // 1/width, 1/height

// 卷积核系数（以 3x3 高斯模糊为例）
// 1/16 × | 1 2 1 |
//        | 2 4 2 |
//        | 1 2 1 |
const float kernel[9] = float[](
    1.0/16, 2.0/16, 1.0/16,
    2.0/16, 4.0/16, 2.0/16,
    1.0/16, 2.0/16, 1.0/16
);

void main() {
    vec2 texel = uTexOffset;         // 每个纹素的大小
    vec4 sum = vec4(0.0);

    // 采样周围 3x3 的像素
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 offset = vec2(float(x) * texel.x, float(y) * texel.y);
            sum += texture2D(uTexture, vTexCoord + offset)
                   * kernel[(y+1)*3 + (x+1)];
        }
    }

    gl_FragColor = sum;
}
```

**常见卷积核**：

| 效果 | 核 | 说明 |
|------|----|------|
| 高斯模糊 | 1/16[1,2,1;2,4,2;1,2,1] | 加权平均，去噪 |
| 锐化 | [0,-1,0;-1,5,-1;0,-1,0] | 增强边缘对比 |
| Sobel | Gx: [1,0,-1;2,0,-2;1,0,-1] | 边缘检测 |
| 浮雕 | [2,0,2;0,1,0;2,0,-2] | 浮雕效果 |

**大半径模糊优化**：3x3 只能模糊 ~1px。要更大的模糊（如美颜磨皮），用**分离卷积**：

```
5x5 高斯核 = [1,4,6,4,1] × [1,4,6,4,1]ᵀ
                    ↓
       先水平方向采样 5 次
       再垂直方向采样 5 次

复杂度：O(2n) 而不是 O(n²)
```

---

## 三、美颜滤镜核心算法

### 3.1 双边滤波（Bilateral Filter）— 保边去噪

```glsl
// 美颜核心：保留边缘的同时磨皮
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D uTexture;
uniform vec2 uTexOffset;

void main() {
    vec4 center = texture2D(uTexture, vTexCoord);
    vec4 sum = vec4(0.0);
    float totalWeight = 0.0;

    float sigmaSpace = 4.0;    // 空间域 sigma
    float sigmaColor = 0.1;    // 颜色域 sigma

    for (int y = -3; y <= 3; y++) {
        for (int x = -3; x <= 3; x++) {
            vec2 offset = vec2(float(x) * uTexOffset.x,
                               float(y) * uTexOffset.y);
            vec4 sample = texture2D(uTexture, vTexCoord + offset);

            // 空间权重：靠近中心权重高
            float spaceW = exp(-float(x*x + y*y) / (2.0 * sigmaSpace*sigmaSpace));
            // 颜色权重：颜色接近的权重高（关键！）
            float colorW = exp(-distance(sample.rgb, center.rgb)
                              / (2.0 * sigmaColor*sigmaColor));

            float w = spaceW * colorW;
            sum += sample * w;
            totalWeight += w;
        }
    }

    gl_FragColor = sum / totalWeight;
}
```

**双边滤波的关键**：颜色权重让边缘（颜色差异大的区域）贡献小，所以**边缘保留**了。

缺点：7x7 的双边滤波 = 49 次纹理采样，性能较差。实际优化用**高斯分离 + 下采样**。

### 3.2 高频增强（锐化 + 细节保留）

```
输入图像 = 低频部分（皮肤纹理） + 高频部分（细节、噪点）

美颜策略：
    输出 = 低频 × 磨皮系数 + 高频 × 细节保留系数

高频提取 → 原图 - 高斯模糊图
```

---

## 四、多滤镜级联 (Filter Chain)

```
实际相机 App 不只用 1 个 shader，是一串：

Camera Texture
    │
    ├─ FBO 1: 美颜（双边滤波 + 肤色调整）
    │
    ├─ FBO 2: LUT 滤镜（风格色调）
    │
    ├─ FBO 3: 特效（贴纸、妆容）
    │
    └─ 最终输出 → 屏幕 / 录制
```

```kotlin
class FilterChain {
    private val filters = mutableListOf<Filter>()
    private var fbo: IntArray = IntArray(1)
    private var fboTexture: IntArray = IntArray(1)

    fun render(inputTexId: Int, width: Int, height: Int) {
        var currentTex = inputTexId

        for (filter in filters) {
            // 绑定 FBO
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                fboTexture[0], 0
            )

            // 在当前 FBO 上运行滤镜
            filter.draw(currentTex)

            // 输出纹理作为下一级的输入
            currentTex = fboTexture[0]
        }

        // 最后一级画到屏幕
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        filters.last().draw(currentTex)
    }
}
```

---

## 五、面试高频题

### Q1：片元着色器为什么是性能关键？

> 全屏渲染 = width × height 个片元，每个片元执行一次片元着色器。
> 1920×1080 ≈ 200 万个像素，每帧片元着色器要跑 200 万次。
> 所以**不要**在片元着色器做复杂计算，能预计算的放 CPU 或查表。

### Q2：LUT 滤镜相比 ColorMatrix 好在哪？

> ColorMatrix 只能做线性变换（亮度、对比度、饱和度），
> LUT 可以实现**非线性映射**（日系清新、胶片曲线、暗调等），
> 而且设计师用 Photoshop/Lightroom 调完直接导出 LUT，**开发不用改代码**。

### Q3：美颜磨皮有哪几种实现方式？

> 1. **双边滤波** — 保边去噪，效果好但性能差
> 2. **引导滤波** — O(1) 时间复杂度，比双边快，边缘保留稍弱
> 3. **表面模糊 (Surface Blur)** — Photoshop 的同款算法
> 4. **GAN 生成** — 深度学习方案，效果最好但模型大
>
> 商业方案一般用**高斯分离 + 下采样 + 双边滤波组合**。

### Q4：如何优化多个滤镜叠加的性能？

> 1. **合并 shader**：多个简单滤镜合并到一个 shader 里，少一次 FBO 切换
> 2. **降采样**：美颜等非线性滤镜在 1/2 或 1/4 分辨率跑，最后升采样
> 3. **延迟渲染**：不每帧全部重算，静态区域复用之前的输出
> 4. **硬件分级**：低端机减少滤镜数量或降低分辨率

### Q5：gl_FragColor 和 gl_FragData 有什么区别？

> - `gl_FragColor`：单输出到默认 FBO（1 个 color attachment）
> - `gl_FragData[n]`：多渲染目标（MRT），一次渲染输出到多个纹理
> - 相机 App 用 MRT 可以同时输出：屏幕预览 + 录制帧 + AI 识别帧

### Q6：抖音类特效（扭曲脸、大眼）怎么实现？

> 不是改颜色，而是**改纹理坐标**：
>
> ```glsl
> // 大眼效果：调整采样坐标
> vec2 scaleCoord = vTexCoord - eyeCenter;
> float dist = length(scaleCoord);
> float factor = 1.0 - smoothstep(0.0, eyeRadius, dist) * strength;
> vec2 newCoord = eyeCenter + scaleCoord * factor;
> gl_FragColor = texture2D(uTexture, newCoord);
> ```
>
> 原理：使眼睛区域的像素**向外扩散**（采样区域缩小），视觉上眼睛变大。
> 人脸关键点（106/468 点）→ 指定眼睛中心 → shader 动态重映射纹理坐标。
