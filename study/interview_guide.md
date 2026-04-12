# Android 资深工程师面试准备指南

> 10年 Android 经验 + Flutter + React/Swift 基础 + Framework 接触

---

## 优先级总览

根据你的经验和市场面试趋势，按重要程度排序：

| 优先级 | 模块 | 文档 | 说明 |
|--------|------|------|------|
| P0 必考 | Android 核心 | [android.md](android.md) | 四大组件、View、性能优化、Jetpack |
| P0 必考 | Java/Kotlin 基础 | [java_kotlin.md](java_kotlin.md) | JVM、并发、集合、协程 |
| P0 必考 | Framework 原理 | [framework.md](framework.md) | 10年经验必问深度，AMS/WMS/Binder |
| P1 重要 | Flutter | [flutter.md](flutter.md) | 跨平台能力加分项 |
| P1 重要 | 网络与综合能力 | [comprehensive.md](comprehensive.md) | 网络、设计模式、算法、架构 |
| P2 加分 | Kotlin 协程 | [kotlin.md](kotlin.md) | 已整理 |

---

## 面试准备节奏建议

### 第一周：夯实基础（P0）
- [ ] Java/Kotlin 语言基础过一遍，重点看并发和 JVM
- [ ] Android 四大组件生命周期、启动模式能脱口而出
- [ ] View 绘制流程和事件分发手写流程图
- [ ] Handler 机制默写级别

### 第二周：深度突破（P0 + P1）
- [ ] Framework 层 AMS、WMS、Binder 原理
- [ ] 性能优化实战经验整理（内存、卡顿、包体积、启动速度）
- [ ] Jetpack 组件原理（Lifecycle、ViewModel、LiveData、Compose）
- [ ] Flutter 渲染机制和混合开发方案

### 第三周：查漏补缺（P1 + P2）
- [ ] 算法刷题（LeetCode Hot 100 中的高频题）
- [ ] 设计模式结合 Android 源码实例
- [ ] 架构设计题练习（设计一个图片加载库/IM系统）
- [ ] 项目经验 STAR 法则整理

### 持续：
- [ ] 每天 2-3 道算法题
- [ ] 模拟面试，练习表达

---

## 10年经验面试特别注意

作为资深开发者，面试官的期望和初中级不同：

1. **不会只问"是什么"，更多问"为什么这样设计"**
   - 不只是说 Handler 怎么用，要说清 epoll + MessageQueue 的设计考量
   
2. **架构能力是核心考察点**
   - 组件化/模块化方案选型与落地经验
   - 如何做技术选型、如何推动技术方案
   
3. **跨端能力是加分项**
   - Flutter + React 经验在当下市场非常吃香
   - 重点准备 Flutter 和原生混合开发方案
   
4. **带团队 / 技术影响力**
   - 有没有制定过开发规范
   - 有没有推动过技术升级
   - Code Review 怎么做的

5. **系统设计题**
   - 设计一个 App 启动框架
   - 设计一个长连接保活方案
   - 设计一个跨平台通信桥

### https://github.com/zhpanvip/AndroidNote
