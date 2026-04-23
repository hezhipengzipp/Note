# SSE 与 WebSocket 在项目中的分工详解

本文档详细说明 SSE（下行）与 WebSocket（上行）在项目中各自承担的职责、消息类型、触发时机，以及两者如何串联起完整的 WebRTC 呼叫流程。

---

## 一、SSE（eventflux）—— 业务服务器 → 客户端

**建立位置**：`lib/janus/janus_video_call_usecase.dart:182` `_startSSEConnect()`

**触发链**：`RtcInit.videoCall()` → `requestVideoCall()` → `_startSSEConnect()`

**作用**：**呼叫控制面信令**——业务服务器推送呼叫状态变更，是整个通话流程的**起点和调度中心**。

### SSE 具体承担的事（按时序）

| 阶段 | 触发 | 消息 | 效果 |
| --- | --- | --- | --- |
| **1. 呼叫唤醒** | 主叫发起 | `SSE_SUCCESS` + `status=STATUS_WAKE_UP` | 下发 Janus 连接参数，驱动 `JanusConnect` 任务 |
| **2. 对端注册通知** | 被叫已在 Janus register | `SSE_SUCCESS` + `status=STATUS_REGISTERED` | 触发 `JanusCall` 发 SDP offer |
| **3. 会话参数下发** | SSE_SUCCESS 的 data 字段 | token / tokenHost / consistentHashId / connId / cameraId / cameras / accOn / traceId | 写入 `RtcInit` 和 `JanusClient`，驱动 WebSocket 握手 |
| **4. 呼叫失败** | 各种错误 | 12 种错误码 | 翻译成 `DisConnected` 事件上抛 |
| **5. token 失效** | 服务器强制刷新 | `SSE_FORCE_NEW_TOKEN (403)` / `DEVICE_TOKEN_EXPIRED (506)` | 触发 `forceNewToken` 重登 |
| **6. 对端占线 / 离线** | 业务层判断 | `SSE_CALLING (516)` / `SSE_TIMEOUT (514)` + `wakeUpStatus=PHONE_CALLING` | 直接发 `DisConnected` 终止 |
| **7. 对端挂断** | 业务层转发 | `USER_HANGUP (1)` / `WEBRTC_HANGUP (3)` / `ANSWER_TIMEOUT (2)` | 终止通话 |

### SSE 消息处理分发表

```
_parseSseData()          [janus_video_call_usecase.dart:246]
├─ SSE_SUCCESS(200)     → _handleSuccessSseData()  [:369-447]  推进状态机
├─ SSE_FORCE_NEW_TOKEN  → DisConnected
├─ SSE_CALLING          → DisConnected
├─ SSE_TIMEOUT          → INIT_FAILURE / CALLEE_OFFLINE
├─ SSE_UNAUTHORIZED     → DisConnected
├─ DEVICE_TOKEN_EXPIRED → DisConnected
├─ SSE_FAILED           → DisConnected
├─ DEVICE_UNAUTHORIZED  → DisConnected
├─ WAITING_TIMEOUT      → DisConnected
├─ USER_HANGUP          → DisConnected (USER_REFUSE)
├─ ANSWER_TIMEOUT       → DisConnected
├─ WEBRTC_HANGUP        → DisConnected
└─ OTHER                → DisConnected
```

### SSE 的一句话定位

> **"呼叫生命周期的调度器"**——告诉客户端"现在该干什么"（唤醒 / 注册 / 呼叫 / 失败 / 挂断），不传递 WebRTC 协议本身的信令。

---

## 二、WebSocket（Janus 协议）—— 客户端 ↔ Janus Gateway

**建立位置**：`lib/wss/web_socket_channel.dart:65`
```dart
WebSocket.connect(wssUrl, headers: {
  Sec-WebSocket-Protocol: janus-protocol,
  x-hash-id: <consistentHashId>,
})
```

**封装层**：`lib/janus/janus_client.dart` 的 `_channel.sendMessage()`

**触发链**：SSE 下发 wssUrl 后 → `_handleSuccessSseData` → `sendTask(JanusConnect())` → `_janusClient.connect()`

**作用**：**WebRTC 协议信令**——所有 SDP / ICE 协商、Janus 会话管理都走这里。

### WebSocket 上行消息（客户端 → Janus）

| 阶段 | Janus API | 客户端方法 | 消息内容 |
| --- | --- | --- | --- |
| **1. 建立 Janus 会话** | `create` / `claim` | `createSession()` / `claimSession()` (janus_client.dart:80, 110) | 首连 create，重连 claim 复用 |
| **2. 绑定插件** | `attach` | `attachPlugin("janus.plugin.videocall")` (:145) | 获取 handleId |
| **3. 注册用户名** | `message.register` | `registerUser(handleId, userName)` (:180) | 带 randUserFrom 注册 |
| **4. 发起呼叫（主叫）** | `message.call` + JSEP | `call(handleId, sdp, userName)` (:210) | **带 SDP offer** |
| **5. 接听 / SDP 更新** | `message.set` + JSEP | `setOffer(handleId, sdp)` (:235) | **带 SDP answer** 或重协商 |
| **6. 上报 ICE candidate** | `trickle` | `trickleCandidate(handleId, candidate)` (:270) | 每收集到一个 candidate 发一条 |
| **7. ICE 收集完成** | `trickle + completed` | `trickleCandidateComplete(handleId)` (:290) | 告诉服务器没有新 candidate 了 |
| **8. 主动挂断** | `message.hangup` | `hangup(handleId)` (:253) | 通知对端挂断 |
| **9. 心跳保活** | `keepalive` | `sendAliveMessage()` (:448) | 每 10s 一次，防 Janus 回收 session |
| **10. 销毁会话** | `destroy` | `destroySession()` (:125) | 收尾 |

### WebSocket 下行消息（Janus → 客户端）

同一条 WebSocket **也是双向的**，Janus 通过它推送事件：

| Janus 事件 | 本项目处理 | 对应业务动作 |
| --- | --- | --- |
| `success` / `error` | `handleTransactionMessage()` | 匹配 tid 回调 success/error |
| `event` + `REGISTERED` | `onJanusRegistered()` | 本端注册成功 |
| `event` + `CALLING` | 发 `InCallingEvent` | 对端振铃中 |
| `event` + `ACCEPTED` + JSEP | `onCallAccepted(sdp)` | 对端接听，收到 SDP answer |
| `event` + `UPDATE` + JSEP | `_onUpdate(sdp)` | SDP 重协商 |
| `trickle` | `onIceCandidate()` | 对端 ICE candidate |
| `webrtcup` | `onWebRTC()` → `Connected` | ICE 打通，媒体通了 |
| `media` | `onMedia(type, receiving)` | 某路媒体开始接收 |
| `hangup` | `onHangup()` → `_handleWebrtcHangup` | Janus / 对端挂断 |
| `detached` | `onDetached()` | 插件已拆除 |

### WebSocket 的一句话定位

> **"WebRTC 协议层搬运工"**——SDP offer/answer、ICE candidate、Janus session/handle 管理全走这里。服务器是 Janus Gateway，不是业务服务器。

---

## 三、两者的分工对比

| 维度 | SSE | WebSocket (Janus) |
| --- | --- | --- |
| **服务器** | 业务服务器 | Janus Gateway |
| **方向** | 下行单向 | 上下行双向 |
| **消息类型** | 业务状态码（200 / 403 / 514...）| Janus 协议（create / attach / message / trickle...）|
| **消息内容** | 呼叫状态 + 会话参数 | SDP + ICE + session/handle 管理 |
| **何时建立** | `videoCall()` 发起时 | 收到 `SSE_SUCCESS` 后 |
| **何时断开** | `onDestroy()` 时 | `destroySession` 后 |
| **库** | `eventflux` | `dart:io WebSocket` |
| **是否能单独使用** | ❌ 不能，SSE 只下发参数不建媒体 | ❌ 不能，WebSocket 需要 SSE 先下发 wssUrl/token |

---

## 四、关键串联点（面试必问）

```
SSE 下发 token+wssUrl ───→ WebSocket 建连 ───→ Janus 握手 ───→ WebRTC 协商
  │                          │                   │              │
  业务服务器调度              Janus 协议层         信令交换        P2P 媒体
```

- **SSE 解决 "谁打给谁、拿什么凭证、打到哪个 Janus 节点"**
- **WebSocket 解决 "怎么用 WebRTC 打通这一路"**

两条链路**必须串行**：
- 没有 SSE 下发的参数（tokenHost / consistentHashId / token），WebSocket 根本不知道该连哪个 Janus 节点
- 没有 WebSocket，SSE 拿到的参数再多也没法真正打通媒体

---

## 五、完整通话时序（SSE + WebSocket 协作）

```
主叫客户端            业务服务器(SSE)         Janus Gateway(WebSocket)      被叫客户端
    │                     │                         │                         │
    │─ videoCall() ──────→│                         │                         │
    │                     │─ SSE_SUCCESS           │                         │
    │                     │   + wakeUpStatus ──────→│ (推送唤醒)              │
    │←── SSE_SUCCESS ─────│   + tokenHost           │                         │
    │    + 会话参数       │                         │                         │
    │                     │                         │                         │
    │─ WebSocket connect ─────────────────────────→│                         │
    │─ create ────────────────────────────────────→│                         │
    │←── success ──────────────────────────────────│                         │
    │─ attach(videocall) ─────────────────────────→│                         │
    │←── attached(handleId) ───────────────────────│                         │
    │─ register(randUserFrom) ────────────────────→│                         │
    │                     │                         │                         │
    │                     │  (对端也走同样流程)     │                         │
    │                     │                         │←──── register ─────────│
    │                     │                         │                         │
    │                     │─ SSE_SUCCESS           │                         │
    │                     │   + STATUS_REGISTERED ─→│                         │
    │←── SSE_SUCCESS ─────│                         │                         │
    │                     │                         │                         │
    │─ call(offer) ───────────────────────────────→│                         │
    │                     │                         │─ incomingcall(offer) ──→│
    │                     │                         │                         │
    │                     │                         │←──── accept(answer) ────│
    │←── accepted(answer) ─────────────────────────│                         │
    │                     │                         │                         │
    │─ trickle(candidate) ────────────────────────→│                         │
    │                     │                         │─ trickle ──────────────→│
    │                     │                         │←── trickle ─────────────│
    │←── trickle(candidate) ───────────────────────│                         │
    │                     │                         │                         │
    │←── webrtcup ─────────────────────────────────│                         │
    │                     │                         │                         │
    │ ═══════════════ WebRTC P2P 媒体流 (SRTP) ════════════════════════════════│
    │ ═══════════════ DataChannel (SCTP/DTLS) ════════════════════════════════│
    │                     │                         │                         │
    │─ keepalive (10s) ───────────────────────────→│                         │
    │                     │                         │                         │
    │─ hangup ────────────────────────────────────→│                         │
    │                     │                         │─ hangup ───────────────→│
    │─ destroy ───────────────────────────────────→│                         │
    │                     │                         │                         │
```

### 关键观察

1. **SSE 只在会话"建立前"和"失败时"工作**——会话建立后 Janus WebSocket 接管所有信令
2. **WebSocket 是双工的**——推送事件（incomingcall / accepted / webrtcup 等）走同一条连接下行
3. **媒体流完全不经服务器**——webrtcup 之后 RTP/SRTP 直连两端客户端
4. **挂断后 SSE 可能还会再推一条** `USER_HANGUP`——本项目 `_onSSEClose` 延迟 500ms 就是为了兜住这个最后包（历史 bug fix）
