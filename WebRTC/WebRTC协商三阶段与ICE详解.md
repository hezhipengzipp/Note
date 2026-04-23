# WebRTC 协商三阶段与 ICE 详解

本文档解释完整通话流程中**阶段 ④ SDP Offer、阶段 ⑤ ICE Candidate 交换、阶段 ⑥ SDP Answer** 三个核心步骤的作用，以及 ICE 层是什么、处在 WebRTC 分层中的哪一层。

---

## 一、一句话定位

阶段 4/5/6 是 **WebRTC 建连的三大核心阶段**，合起来解决一个问题：

> **两个位于不同 NAT 后的客户端，如何找到一条能互通的路径？**

用生活化类比：

- **阶段 ④** 本端广播"我长这样"（SDP offer）
- **阶段 ⑤** 双方互相列出"我的所有家庭住址候选"（ICE）
- **阶段 ⑥** 对端回复"好，我们按这个规格通话"（SDP answer）

三个阶段 **异步并行**——④ 发完 offer 后立刻开始 ⑤ 收集 ICE，不等 ⑥。

---

## 二、阶段 ④ ：发 SDP Offer —— "告诉对方我的媒体能力"

**目的**：生成一份 SDP 文档，描述本端的 **媒体能力 + 加密指纹 + ICE 凭证**，发给对端。

### SDP 长什么样（简化）

```
m=audio 9 UDP/TLS/RTP/SAVPF 111           ← 我要一条音频，用 opus
a=rtpmap:111 opus/48000/2                  ← codec 细节
m=video 9 UDP/TLS/RTP/SAVPF 96             ← 我要一条视频，用 H264
a=rtpmap:96 H264/90000
a=fingerprint:sha-256 AB:CD:EF:...         ← DTLS 指纹（防中间人攻击）
a=ice-ufrag:4ZCN                           ← ICE 用户名
a=ice-pwd:BYQhxWmkx5q2QIoUn3RKmSve         ← ICE 密码
```

### 关键动作（`doCall` 里）

```dart
createOffer → setLocalDescription → _janusClient.call(offer)
```

**产物**：对端收到后就知道"我要送什么格式的媒体过来、用什么加密、怎么验证身份"。

**这一步还不建连**——只是协商规格。

---

## 三、阶段 ⑤ ：ICE Candidate 交换 —— "交换彼此的网络地址"

这是 **4/5/6 里最复杂、最核心的一步**，先讲什么是 ICE。

### 什么是 ICE？

**ICE = Interactive Connectivity Establishment（交互式连接建立）**，WebRTC 的 **NAT 穿透框架**（RFC 8445）。

它解决的问题：

- 你家里的电脑真实 IP 是 `192.168.1.5`（内网）
- 对方家里是 `10.0.0.8`（内网）
- 两台内网设备 **互相不可见**，没法直接连
- **ICE 就是帮它们找到一条绕过 NAT 的通路**

### ICE 会收集三种"候选地址"（Candidate）

| 类型 | 含义 | 成功率 | 延迟 |
| --- | --- | --- | --- |
| **host** | 本机网卡 IP（内网）| 同局域网可达 | 最低 |
| **srflx**（Server Reflexive）| STUN 服务器看到的公网 IP | 一般 NAT 可达 | 低 |
| **relay** | TURN 中继服务器地址 | 100%（兜底）| 高（要中转）|

### ICE 框架示意

```
本端                 STUN 服务器        对端
 │                        │              │
 │─── "我是谁？" ────────→│              │
 │←── "你的公网 IP 是 x"──│              │
 │                        │              │
 │ 收集到 3 种 candidate： │              │
 │  1. host: 192.168.1.5  │              │
 │  2. srflx: 1.2.3.4     │              │ (对端也在做同样的事)
 │  3. relay: turn1.com   │              │
 │                        │              │
 │ ──── Trickle 发给对端（经 Janus）────→│
 │ ←─── 对端的 candidate（经 Janus）────│
 │                                       │
 │ ═══ 两端互相 ping 所有 candidate ═══ │
 │ ═══ 选出延迟最低、能通的一条 ══════ │
```

### 项目代码里的体现

```dart
// PeerConnection 每发现一个 candidate 就立即 trickle 出去
_peerConnection.onIceCandidate = (candidate) {
  _janusClient.trickleCandidate(_handlerId!, candidate);  // 发送
};

// 收到对端的 candidate 就加到本端 PeerConnection
onIceCandidate(handleId, candidate) {
  _peerConnection.addCandidate(RTCIceCandidate(...));     // 接收
}
```

**Trickle 模式**（本项目用的）：边收集边发送，不等全部收集完。比起传统 ICE（等全部收集完再发 SDP）能 **省 2~5 秒建连时间**。

---

## 四、阶段 ⑥ ：对端回 SDP Answer —— "好，按这个规格通话"

**目的**：对端根据本端的 offer 生成一个匹配的 answer，确认最终协商的媒体规格。

### 类比

- Offer：我能唱 "opus 音频 + H264 视频 + VP8 视频"
- Answer：那我们只用 "opus 音频 + H264 视频" 吧（VP8 我不支持）

### 项目代码

```dart
onCallAccepted(sdp) {
  _peerConnection.setRemoteDescription(sdp);  // 把对端 answer 设进去
  sendJanusEvent(Connecting());                // UI 显示"正在接通"
}
```

此时本端 WebRTC 栈知道：

- 最终用哪个 codec
- 对端的 DTLS 指纹是什么（后续握手时校验）
- 对端 ICE 凭证是什么

---

## 五、三个阶段的真实时序（**不是严格串行！**）

很多人会误以为是 ④→⑤→⑥ 严格顺序执行，其实是：

```
时间轴 →
  ┌── ④ 发 offer ──┐
  │                │
  │           ┌────┴──── ⑤ ICE 候选交换（持续几秒）────────┐
  │           │                                            │
  │           │   ┌── ⑥ 对端回 answer ──┐                 │
  │           │   │                     │                 │
  │           │   └── setRemoteDesc ────┤                 │
  │           │                         └──── ICE 连通性检查 ──→ [打通]
  └───────────┴─────────────────────────┴─────────────────┘
```

**关键观察**：

- ④ 发完 offer 立刻就开始收集 ICE 并 trickle
- ⑤ 的 ICE 交换 **持续整个协商期间**
- ⑥ 收到 answer 时 ⑤ 可能还在进行中

这也是为什么项目代码里 `onIceCandidate` 和 `onCallAccepted` 是 **独立的回调**，不互相等待。

---

## 六、三者合起来干了什么事？

**建立一条"两端都同意、网络能到达、安全可验证"的 P2P 传输通道**。缺一不可：

| 阶段 | 缺了会怎样 |
| --- | --- |
| ④ 缺失 | 对端不知道你要发什么格式的媒体，媒体包到了也无法解码 |
| ⑤ 缺失 | 两端根本找不到彼此，媒体包发不出去 |
| ⑥ 缺失 | 本端不知道对端的协商结果和加密指纹，不敢收对端的流 |

三步完成后才会触发 `onWebRTC`（webrtcup 事件），此时媒体真正流通。

---

## 七、ICE 层到底在哪一层？

一张图看清 WebRTC 分层：

```
┌─────────────────────────────────────┐
│  应用层（你的 Dart 代码）            │
├─────────────────────────────────────┤
│  WebRTC API                         │
│    · PeerConnection                 │
│    · DataChannel                    │
│    · MediaStream                    │
├─────────────────────────────────────┤
│  媒体层：RTP/RTCP + SRTP（加密）      │
├─────────────────────────────────────┤
│  传输层：SCTP（DataChannel）+ DTLS   │
├─────────────────────────────────────┤
│  **ICE 层**（NAT 穿透）              │  ← 阶段 ⑤ 在这里工作
│    · STUN（探测公网地址）            │
│    · TURN（中继兜底）                │
├─────────────────────────────────────┤
│  UDP（底层传输）                     │
└─────────────────────────────────────┘
```

**ICE 是独立于信令、独立于媒体的一层**——它只负责一件事：**找到两端能互通的 UDP 通路**。路找到后，上层的媒体 / DataChannel 就沿着这条路跑。

---

## 八、面试答题模板

> 阶段 4、5、6 是 WebRTC 建连的三大核心步骤，合起来解决 **"两个 NAT 后面的客户端怎么互通"**：
>
> - **阶段 4**：本端发 **SDP offer**，声明媒体能力和加密指纹；
> - **阶段 5**：**ICE 层**持续交换网络地址候选（host / srflx / relay），找出能互通的最佳路径；
> - **阶段 6**：对端回 **SDP answer**，确认最终协商结果。
>
> **ICE（Interactive Connectivity Establishment）** 是 WebRTC 的 NAT 穿透层，通过 STUN 探测公网地址、TURN 做中继兜底，解决「内网互联网无法直连」的本质问题。本项目用 **Trickle ICE 模式**——边收集边发送 candidate，比传统 ICE 建连快 2~5 秒。
>
> **三个阶段不是严格串行，而是异步并行**：4 发完 offer 立刻开始 5，5 的 ICE 交换持续整个协商期间，6 接听后还可能继续收 candidate。最终 `webrtcup` 事件触发才代表连接真正打通。

---

## 九、相关术语速查

| 术语 | 全称 | 作用 |
| --- | --- | --- |
| **SDP** | Session Description Protocol | 会话描述文本，列出媒体能力 |
| **Offer / Answer** | — | SDP 协商的请求 / 应答 |
| **JSEP** | JavaScript Session Establishment Protocol | SDP 的 JSON 包装 |
| **ICE** | Interactive Connectivity Establishment | NAT 穿透框架 |
| **Candidate** | — | 一个候选网络地址 |
| **host / srflx / relay** | — | 三种候选类型 |
| **STUN** | Session Traversal Utilities for NAT | 探测公网 IP |
| **TURN** | Traversal Using Relays around NAT | 中继兜底 |
| **Trickle ICE** | — | 增量交换 candidate 模式 |
| **DTLS** | Datagram TLS | UDP 上的 TLS，用于密钥协商 |
| **SRTP** | Secure RTP | 加密的 RTP 流 |
