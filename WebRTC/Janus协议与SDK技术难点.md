# Janus 协议与 SDK 技术难点

本文档整理了 `lib_flutter_video` SDK 的核心设计：底层的 Janus 信令协议、以及在这套协议之上实现稳定远程视频通话时遇到的技术难点和解决方案。用于团队内部知识沉淀与面试答辩参考。

---

## 第一部分 · Janus 是什么

### 1.1 定位

**Janus** 是一个开源的 **WebRTC 媒体服务器**（不只是信令），由 Meetecho 维护。它的角色通常叫 **SFU**（Selective Forwarding Unit）。

### 1.2 为什么需要 Janus（不能直接 P2P）

WebRTC 的原始设想是**浏览器/设备直连**（P2P），但现实中有三个硬问题：

#### ① NAT 穿透失败

手机在 4G/5G，车机在车载 WiFi 后面，两边都被运营商 NAT 藏起来。

- 只靠 STUN 打洞：成功率 ~70%
- 加 TURN 中转：成功率 ~99%，但 TURN 只是盲转流量，不懂业务

Janus 相当于一个**有公网 IP 的"中继 + 交换机"**，两边都连它，不需要互相穿透。

#### ② 信令协议 WebRTC 不管

WebRTC 规范只定义了 **SDP、ICE 这些媒体协商格式**，**但是怎么把 offer 传给对端，完全不管**（文档原话：signaling is out of scope）。

每个产品都要自己实现信令通道。Janus 提供了一套**标准化的信令协议**（create session / attach plugin / trickle candidate 等），不用自己设计。

#### ③ 多端推流 / 录制 / 转码

P2P 只能一对一，如果要一对多广播、服务端录制、RTP 转 HLS 等，纯 P2P 做不了，需要媒体服务器。

### 1.3 Janus 在本项目中的角色

```
 [手机 App]                              [车机 DVP5]
     │                                        │
     │  ① WSS 信令（本 SDK 操作的层）          │
     │──────────────┐            ┌────────────│
     │              ▼            ▼            │
     │         ┌──────────────────────┐       │
     │         │    Janus Server      │       │
     │         │  (videoroom plugin)  │       │
     │         └──────────────────────┘       │
     │              ▲            ▲            │
     │  ② WebRTC 音视频媒体流                  │
     │──────────────┘            └────────────│
```

**分工：**

| 通道 | 传什么 | 谁管 |
|------|--------|------|
| 信令（WebSocket） | session 建立、SDP 交换、ICE candidate、在线/挂断事件、keepalive | 本 SDK 的 `janus_client.dart` |
| 媒体（SRTP/RTP） | 音频、视频、DataChannel 数据 | flutter_webrtc 原生层自动处理 |

**Janus 帮我们做的事：**

1. **NAT 穿透代理**：手机和车机都拨号到 Janus 公网地址，不用关心彼此在哪个内网
2. **媒体路由**：把手机音频转发给车机，把车机前 / 后摄像头视频转发给手机
3. **插件化业务**：videoroom / videocall 插件封装了"房间、成员、推拉流"的业务语义
4. **房间管理**：谁在线、谁订阅谁的流、谁是发布者，Janus 帮你维护

---

## 第二部分 · Janus 协议详解

### 2.1 核心概念：3 层抽象

Janus 协议的所有消息都围绕 3 个对象展开：

```
Session（会话）─┬─ Handle（插件实例）─┬─ Transaction（请求-响应）
                │                       └─ Transaction
                └─ Handle ─────────────── Transaction
```

| 层级 | 类比 | 代表什么 | 生命周期 |
|------|------|----------|----------|
| **Session** | 登录态 | 一个客户端与 Janus 的连接会话 | WebSocket 断开 / destroy 才销毁 |
| **Handle** | 打开一个 APP | Session 里挂载的插件实例 | 一次业务结束就 detach |
| **Transaction** | 一次 API 调用 | 一次请求-响应配对 | 响应到达 / 超时就结束 |

**打比方**：Session 像你登录了 QQ；Handle 像你在 QQ 里打开了一个聊天窗口；Transaction 像你按了一次"发送"按钮等对方回复。

### 2.2 消息格式

每条消息统一格式：

```json
{
  "janus": "create",           // 消息类型（动作）
  "transaction": "abc123",     // 事务 ID，唯一
  "token": "xxx",              // 鉴权
  "session_id": 12345,         // 属于哪个 session（除了 create）
  "handle_id": 67890           // 属于哪个 handle（插件级操作才有）
}
```

Janus 的响应也带同样的 `transaction`，用它**匹配请求和响应**——这就是 SDK 中 `_transactionMap` 的作用。

### 2.3 消息类型速查

| `janus` 字段 | 用途 | 谁发起 |
|-------------|------|--------|
| `create` | 创建 session | 客户端 |
| `claim` | 重连后接管旧 session | 客户端 |
| `attach` | 挂载插件 | 客户端 |
| `detach` | 卸载插件 | 客户端 |
| `destroy` | 销毁 session | 客户端 |
| `keepalive` | 心跳 | 客户端 |
| `message` | 发业务请求（带 body 和可选 jsep） | 客户端 |
| `trickle` | 发 ICE candidate | 客户端 |
| `success` | 响应成功 | 服务端 |
| `error` | 响应失败 | 服务端 |
| `ack` | 已收到，异步处理中 | 服务端 |
| `event` | 服务端主动推事件 | 服务端 |
| `hangup` | 通话挂断通知 | 服务端 |
| `timeout` | session 被服务端超时回收 | 服务端 |

### 2.4 完整通话的消息流

#### 阶段 1：建立会话

```
客户端 → Janus:  { janus: "create", transaction: "t1", token: "xxx" }
Janus → 客户端:  { janus: "success", transaction: "t1", data: { id: 12345 } }
                                                              ↑
                                                       这就是 session_id
```

对应 SDK：`janus_client.dart:78` 的 `createSession()`

#### 阶段 2：挂插件

```
客户端 → Janus:  { janus: "attach", transaction: "t2",
                  session_id: 12345, plugin: "janus.plugin.videocall" }
Janus → 客户端:  { janus: "success", transaction: "t2", data: { id: 67890 } }
                                                              ↑
                                                        这就是 handle_id
```

对应 SDK：`attachPlugin()`

Janus 插件决定业务语义：

- **videocall**：一对一通话（类似微信视频）
- **videoroom**：多人会议室

#### 阶段 3：注册身份

```
客户端 → Janus:  { janus: "message", transaction: "t3",
                  session_id: 12345, handle_id: 67890,
                  body: { request: "register", username: "zhipeng" } }
Janus → 客户端:  { janus: "event", ... plugindata: { result: "registered" } }
```

此后所有消息都带 `session_id + handle_id`。

#### 阶段 4：发起呼叫（SDP offer）

```
客户端 → Janus:  { janus: "message", transaction: "t4",
                  session_id: 12345, handle_id: 67890,
                  body: { request: "call", username: "car_machine" },
                  jsep: { type: "offer", sdp: "v=0\r\n..." } }
                              ↑
                   jsep 就是 WebRTC 的 SDP 封装
Janus → 客户端:  { janus: "ack", transaction: "t4" }      // 先 ack
...（稍后异步）
Janus → 客户端:  { janus: "event", ... jsep: { type: "answer", sdp: "..." } }
                                                ↑
                                         对方回 answer
```

#### 阶段 5：ICE 候选地址交换（Trickle）

WebRTC 边跑边产生 candidate，不是一次性的：

```
客户端 → Janus:  { janus: "trickle", transaction: "t5",
                  session_id: ..., handle_id: ...,
                  candidate: { candidate: "candidate:xxx ...", sdpMid: "0" } }

... 重复 N 次 ...

客户端 → Janus:  { janus: "trickle", session_id: ..., handle_id: ...,
                  candidate: { completed: true } }   // 结束信号
```

#### 阶段 6：挂断

```
客户端 → Janus:  { janus: "message", ... body: { request: "hangup" } }
客户端 → Janus:  { janus: "detach", ... handle_id: 67890 }      // 卸插件
客户端 → Janus:  { janus: "destroy", ... session_id: 12345 }    // 销毁会话
```

#### 穿插：keepalive

```
客户端 → Janus:  { janus: "keepalive", session_id: 12345, transaction: "t99" }
Janus → 客户端:  { janus: "ack", transaction: "t99" }
```

每 10 秒一次，告诉 Janus"我还活着"——对应 SDK 的 `startKeepAliveTimer()`。

### 2.5 SDK 代码与协议对照

| SDK 函数 | 发送的 `janus` 值 | 作用 |
|-------------|-------------------|------|
| `createSession()` | `create` | 开 session |
| `claimSession()` | `claim` | 重连续命 |
| `attachPlugin()` | `attach` | 挂 videocall 插件 |
| `registerUser()` | `message` + body.request=register | 报身份 |
| `call()` | `message` + body.request=call + jsep | 发 offer |
| `trickleCandidate()` | `trickle` | 发单个 candidate |
| `trickleCandidateComplete()` | `trickle` + completed | 发结束信号 |
| `startKeepAliveTimer()` | `keepalive` | 保活 |
| `destroySession()` | `destroy` | 挂断销毁 |

---

## 第三部分 · SDK 技术难点与解决方案

### 难点 1 · Janus 协议状态机的严格时序

**场景**：Janus 要求严格顺序 `createSession → attachPlugin → registerUser → sendOffer → trickleICE`，中间任何一步异步出错或消息乱序，后续全崩。

**解决**：**Task 链 + Transaction Map 双保险**

- `janus_video_call_usecase.dart:462` 的 `runTasks()` 把 Connect/Created/Attached/Register/Call 串成显式状态机
- `janus_client.dart:78` 每个请求生成 transactionId，存到 `_transactionMap`，对应响应到达时 `.remove(id)` 触发回调
- **每个请求都带 15s 超时**（`janus_client.dart:93/164/190`）——超时自动从 map 移除并调用 `onError`，避免僵尸事务卡死状态机
- 双端就绪判定：`_localRegistered && _remoteRegistered` 都为 true 才进入 `JanusCall`

---

### 难点 2 · 双层心跳机制（Janus 会话 + DataChannel）

**场景**：单一心跳不够用：

- 只有 WebSocket ping：Janus 服务端会因 session 超时踢掉
- 只有 Janus keepalive：DataChannel 可能静默 stall，媒体不通但信令还活着

**解决**：两层独立心跳

| 层级 | 频率 | 作用 |
|------|------|------|
| Janus session keepalive | **10s** 一次（`janus_client.dart:435`） | 维持 Janus 服务端 session |
| DataChannel heartbeat | **2s** 一次（`keep_alive_channel.dart:91`） | 检测端到端实时链路 |

- DataChannel 心跳用**计数器**：每发一次 `_issuelessStat++`，收到对端应答清零
- **3 次未回** → 触发 Loading UI（提示"网络波动"）
- **10 次未回**（≈20s） → `onHeartbeatTimeout` 断开，重建连接
- **暂停 / 恢复语义**（`keep_alive_channel.dart:42`）：WebSocket 瞬时断开时暂停心跳计数，reopen 后 resume，避免瞬断被误判为硬断

---

### 难点 3 · 断线重连不重建 session（claim 语义）

**场景**：WebSocket 抖动 3s 后恢复，如果重新 `createSession`，之前 attach 的 plugin handle、已注册用户全丢，正在进行的通话直接挂断。

**解决**：

- `_sessionId` 持久化，不随 socket 重连清空
- `onOpen()` 判断：`_sessionId == null` → `createSession()`；否则 → `claimSession()`（`janus_client.dart:64-71`）
- `CLAIM` 消息把现有 sessionId 发给服务端激活原 session，plugin handle 全部复用
- Socket 重连后调 `_restartOffer()` 而非重新 register，快速恢复媒体流
- SSE auth 并行做 token 续期（`janus_video_call_usecase.dart:178`），**指数退避 + 最多 5 次重试**，区分"流打开但数据错"（retry）和"HTTP 非 200"（abort）两种错误

---

### 难点 4 · ICE Trickling 的顺序陷阱

**场景**：WebRTC 特性——candidate 不是一次性生成，PeerConnection 会边收集边触发 `onIceCandidate`。如果发送时机错了：

- 发 candidate 在 offer 之前：服务端直接拒
- 发完 offer 不等 gathering 结束：对端拿不全路径，连不通

**解决**：

- `onIceCandidate` 回调里立刻 `trickleCandidate()` 逐个发送（`janus_video_call_usecase.dart:91`）
- gathering 完成时（`RTCIceGatheringStateComplete`）发一个 **`completed: true`** 终止信号（`janus_client.dart:284`）
- 对端 candidate 通过 `onIceCandidate` 回调进来时，检查 `COMPLETED` 标志决定是否还 addIceCandidate

---

### 难点 5 · Token 失效不中断通话

**场景**：通话持续 10 分钟，JWT 15 分钟过期，第二次 `keepalive` 被 Janus 返回 403，通话突然挂断。

**解决**：

- `onUnauthorized(code)` 回调（`janus_video_call_usecase.dart:787`）：
  - code == 403 → 不断开，走 `requestVideoCall(forceNewToken: true)` 从 SSE 拿新 token
  - 新 token 通过 `_janusClient.setToken()` 热替换，后续请求无感
- SSE 层（`sse_auth_entity`）作为独立的 auth 通道，与 Janus WS 解耦

---

### 难点 6 · 资源释放的原子性

**场景**：挂断瞬间有 N 个资源需要释放：PeerConnection、DataChannel ×2、多个 RTCVideoRenderer、keepalive 定时器、SSE 订阅、pending transactions。漏任何一个都会泄漏 camera / audio device / 纹理内存，下次通话必 crash。

**解决**：集中式 `onDestroy(reason)`（`janus_video_call_usecase.dart:749`）按顺序执行：

1. `_isClose = true` → 阻断后续 SSE 事件分发
2. 取消 calling / webrtc 所有 timeout task
3. 关 PeerConnection + dispose 所有 VideoRenderer
4. 关两个 DataChannel
5. 销毁 Janus session + 关 WebSocket
6. 重置所有状态标志

---

### 难点 7 · 两条 DataChannel 分工

**场景**：应用数据（比如"对方开/关麦"）和心跳都走 DataChannel，如果混在一起：

- 应用消息堵塞会延迟心跳 → 误判超时
- 心跳丢失不影响应用，但应用消息丢了就是 bug

**解决**：两条独立 channel（`janus_video_call_usecase.dart:128`）

- 应用 channel：`id=0, ordered=true` — 保证麦克风状态、业务命令顺序
- 心跳 channel：`id=1, ordered=false` — 允许乱序，丢了就丢了，反正马上下一次

---

## 第四部分 · 面试答辩模板

### 一句话概述

> 本 SDK 是基于 Janus 信令服务器 + WebRTC 的手机↔车机远程视频方案。设计了 Task 链 + TransactionMap 的协议状态机，实现 Janus session claim 语义下的断线续传，Janus 10s + DataChannel 2s 的双层心跳，以及 JWT 过期热续期、资源原子化释放等核心机制，保障弱网环境下通话稳定性。

### Janus 协议解释

> Janus 协议本质是一套 **JSON over WebSocket** 的请求-响应模型。它有 3 层抽象：
>
> - **Session 层**管连接生命周期（create / claim / destroy / keepalive）
> - **Handle 层**管业务插件实例（attach / detach）
> - **Transaction 层**管请求-响应配对——每条消息都有 transaction id，响应回来时按 id 找到原请求的回调
>
> 除了这三类控制消息，还有两个关键异步事件：**jsep**（封装 WebRTC 的 SDP）和 **trickle**（ICE candidate 逐个下发）。客户端通过 WebSocket 把这些 JSON 消息按协议状态机的顺序发出去，响应和异步事件通过 transaction id + event 分发到对应的回调。

### 可能的追问 & 准备方向

| 问题 | 回答要点 |
|------|---------|
| 为什么选 Janus 不用 SFU/Mesh？ | 车机场景多对一（车主↔车机），Janus videoroom 插件成熟、信令协议明确 |
| 为什么不用 socket.io？ | Janus 协议是自定义 JSON-over-WebSocket，transaction id + event type，跟 socket.io 语义不同 |
| 弱网下为什么心跳阈值定 10 次不是 3 次？ | 车载 WiFi 有瞬断特性，3 次内丢包很普遍，10 次 ≈ 20s 才是真正硬中断 |
| 为什么 session keepalive 和 DataChannel 心跳要分两层？ | 服务端和端到端是两种故障域，分层才能精准定位 |

---

## 第五部分 · 扩展资料

- Janus 官方文档：<https://janus.conf.meetecho.com/docs/rest.html>（REST 与 WebSocket JSON 格式一致）
- 重点章节：
  1. **Janus API** — 通用的 session / handle 消息
  2. 本项目用到的插件文档（videocall / videoroom） — 插件内部的 request 类型（register / call / join / publish 等）
