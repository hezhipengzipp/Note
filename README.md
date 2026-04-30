# Note

Android / Flutter 面试知识点整理仓库。

---

## 目录

### Android

| 分类 | 文档 |
|------|------|
| **核心机制** | [View 绘制流程与事件分发](study/Android/View绘制流程与事件分发详解.md) · [Handler 机制](study/Android/Handler机制详解.md) · [ActivityThread](study/Android/ActivityThread详解.md) · [ViewRootImpl 与 WindowManager](study/Android/ViewRootImpl与WindowManager详解.md) · [XML 布局加载流程](study/Android/XML布局加载流程详解.md) · [RecyclerView 四级缓存](study/Android/RecyclerView四级缓存详解.md) · [界面显示流程](study/Android/界面显示流程详解.md) | |
| **Jetpack** | [Lifecycle](study/Android/jetpack/Lifecycle原理详解.md) · [LiveData](study/Android/jetpack/LiveData原理详解.md) · [ViewModel](study/Android/jetpack/ViewModel原理详解.md) · [WorkManager](study/Android/jetpack/WorkManager详解.md) |
| **跨进程通信** | [Binder 原理与 AIDL](study/Android/跨进程通信/Binder原理与AIDL详解.md) · [Messenger](study/Android/跨进程通信/Messenger详解.md) |
| **性能优化** | [内存优化](study/Android/性能优化/内存优化详解.md) · [卡顿检测](study/Android/性能优化/卡顿自动检测方案详解.md) · [启动耗时统计](study/Android/性能优化/启动耗时统计详解.md) · [布局优化](study/Android/性能优化/布局优化方案详解.md) · [GC 频繁导致卡顿](study/Android/性能优化/GC频繁导致卡顿详解.md) · [性能优化工具](study/Android/性能优化/性能优化工具详解.md) · [Perfetto 使用指南](study/Android/性能优化/Perfetto使用指南.md) · [Perfetto 实战](study/Android/性能优化/Perfetto实战指南.md) · [AOP 实现方式](study/Android/性能优化/AOP实现方式详解.md) |
| **第三方库** | [OkHttp](study/Android/第三方库框架原理/OkHttp原理详解.md) · [Retrofit](study/Android/第三方库框架原理/Retrofit原理详解.md) · [Glide](study/Android/第三方库框架原理/Glide原理详解.md) · [LeakCanary](study/Android/第三方库框架原理/LeakCanary原理详解.md) |
| **相机开发** | [OpenGL ES 基础与渲染管线](study/Android/相机开发/OpenGL%20ES%20基础与渲染管线.md) · [Shader 编程与滤镜实现](study/Android/相机开发/Shader%20编程与相机滤镜实现.md) · [相机图像处理与性能优化](study/Android/相机开发/相机图像处理与性能优化.md) · [Surface 与 EGLSurface 关系](study/Android/相机开发/Surface%20与%20EGLSurface%20关系详解.md) · [摄像头跨进程共享方案](study/Android/摄像头跨进程共享方案.md) |
| **自动化构建** | [CI/CD 完全解析](study/Android/自动化构建/Android%20CI-CD%20完全解析.md) · [Fastlane 蒲公英自动发布](study/Android/自动化构建/Fastlane蒲公英自动发布配置.md) |

### Framework

| 文档 |
|------|
| [SurfaceFlinger 详解](study/Framework/SurfaceFlinger详解.md) · [SurfaceFlinger 双缓冲与三缓冲](study/Framework/SurfaceFlinger双缓冲与三缓冲机制.md) · [PackageManagerService](study/Framework/PackageManagerService作用与逻辑.md) · [Zygote 与 AMS 为什么用 Socket](study/Framework/Zygote与AMS为什么用Socket通信.md) |

### Java

| 分类 | 文档 |
|------|------|
| **集合** | [HashMap 原理](study/Java/HashMap原理详解.md) · [常用数据结构原理大全](study/Java/Java常用数据结构原理大全.md) |
| **并发** | [Java 锁原理](study/Java/Java锁原理.md) · [线程池](study/Java/线程池详解与最佳实践.md) · [并发工具类](study/Java/并发工具类CountDownLatch_CyclicBarrier_Semaphore.md) · [NIO](study/Java/Java非阻塞IO(NIO).md) |
| **JVM** | [JVM 内存模型与垃圾回收](study/Java/JVM内存模型与垃圾回收.md) |
| **泛型** | [Java 泛型详解](study/Java/Java泛型详解.md) |

### Kotlin

| 文档 |
|------|
| [Kotlin 泛型型变 (out/in)](study/kotlin/Kotlin泛型型变(out与in).md) · [Kotlin 内联类](study/kotlin/Kotlin内联类.md) |

### Flutter

| 分类 | 文档 |
|------|------|
| **核心原理** | [三棵树原理与逻辑关系](study/flutter/三棵树原理与逻辑关系.md) · [三棵树面试深度解析](study/flutter/三棵树面试深度解析.md) · [单线程模型与 EventLoop](study/flutter/单线程模型与EventLoop.md) · [Future 原理](study/flutter/Future原理详解.md) · [PlatformChannel 底层原理](study/flutter/PlatformChannel底层原理.md) · [TaskRunner 工作原理](study/flutter/TaskRunner工作原理.md) · [Flutter 架构](study/flutter/flutter架构.md) |
| **异步** | [IO 异步与 await 原理](study/flutter/IO异步与await原理.md) · [阻塞 IO 与非阻塞 IO](study/flutter/阻塞IO与非阻塞IO对比.md) · [Microtask 与 Isolate](study/flutter/异步场景选择-Microtask与Isolate.md) · [Dart 与 Kotlin 与 JS 异步对比](study/flutter/Dart与Kotlin与JS异步对比.md) · [Socket 与上下层通信](study/flutter/Socket与上下层通信机制.md) |
| **状态管理** | [Bloc 与 GetX 核心 API 速查](study/flutter/Flutter%20Bloc%20与%20GetX%20核心%20API%20速查.md) |
| **泛型** | [Dart 与 Java 与 Kotlin 泛型对比](study/flutter/Dart与Java与Kotlin泛型对比.md) |
| **工程** | [Mixin 使用与原理](study/flutter/Mixin使用与原理.md) · [part 与 import](study/flutter/part与import使用场景.md) · [扩展方法](study/flutter/扩展方法.md) · [混合开发方案](study/flutter/混合开发方案.md) · [多图下载并发设计](study/flutter/多图下载并发设计.md) |
| **性能优化** | [性能优化总览](study/flutter/性能优化/Flutter性能优化总览.md) · [Widget 构建与重建优化](study/flutter/性能优化/Widget构建与重建优化.md) · [内存优化与泄漏排查](study/flutter/性能优化/内存优化与泄漏排查.md) · [列表性能优化](study/flutter/性能优化/列表性能优化.md) · [动画与渲染管线优化](study/flutter/性能优化/动画与渲染管线优化.md) · [图片加载与缓存优化](study/flutter/性能优化/图片加载与缓存优化.md) · [启动性能与包体积优化](study/flutter/性能优化/启动性能与包体积优化.md) · [DevTools 性能分析实战](study/flutter/性能优化/DevTools性能分析实战.md) |

### WebRTC

| 文档 |
|------|
| [WebRTC 协商三阶段与 ICE 详解](WebRTC/WebRTC协商三阶段与ICE详解.md) · [Janus 协议与 SDK 技术难点](WebRTC/Janus协议与SDK技术难点.md) · [Janus handleId 详解](WebRTC/Janus_handleId详解.md) · [SSE 与 WebSocket 分工](WebRTC/SSE与WebSocket分工详解.md) · [信令服务器](WebRTC/信令服务器.md) · [信令通道架构说明](WebRTC/信令通道架构说明.md) · [视频通话详细流程图](WebRTC/视频通话详细流程图.md) · [码率/分辨率/帧率关系](WebRTC/码率、分辨率、帧率三者关系详解.md) · [专业术语解释](WebRTC/专业术语解释.md) · [简历关键词拆解](WebRTC/简历关键词逐个拆解.md) |

### SystemUI

| 文档 |
|------|
| [SystemUI 开发指南](SystemUi/Android_SystemUI_Development_Guide.md) · [SystemUI 概述](SystemUi/SystemUI.md) · [App 调用 SystemUI 快速入门](SystemUi/App_To_SystemUI_Quick_Start_Guide.md) · [StatusBar 模块](SystemUi/statusbar-module.md) · [NavigationBar 模块](SystemUi/m1-navigationbar-module.md) · [Notification 模块](SystemUi/notification-module.md) · [QS 模块](SystemUi/qs-module.md) · [下拉视图模块](SystemUi/pulldownview-module.md) · [项目指南](SystemUi/project-guide.md) |

### 网络

| 文档 |
|------|
| [网络分层模型](study/网络/网络分层模型.md) · [TCP 与 HTTP 协议详解](study/网络/TCP与HTTP协议详解.md) · [HTTPS 证书验证与密钥交换](study/网络/HTTPS证书验证与密钥交换.md) |

### 设计模式

| 文档 |
|------|
| [单例模式](study/设计模式/单例模式详解.md) · [工厂方法模式](study/设计模式/工厂方法模式详解.md) · [代理模式](study/设计模式/代理模式详解.md) |

### 计算机基础

| 文档 |
|------|
| [计算机基本原理与组成](study/计算机/计算机基本原理与组成.md) |

### 面试速查

| 文档 |
|------|
| [面试指南](study/interview_guide.md) · [Android 综合](study/android.md) · [Framework 综合](study/framework.md) · [Java/Kotlin 综合](study/java_kotlin.md) · [Kotlin 综合](study/kotlin.md) · [Flutter 综合](study/flutter.md) · [综合](study/comprehensive.md) |
