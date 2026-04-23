# Janus handleId 详解

本文档说明 `attachPlugin` 返回的 `handleId` 在项目中的作用、使用位置，以及 Janus 三级寻址模型。

---

## 一、一句话定位

**`handleId` 是客户端与某个 Janus 插件实例的唯一通信凭证**，后续所有业务消息都必须带上它，服务器才知道这条消息要路由到哪个插件上。

---

## 二、为什么需要 handleId —— Janus 三级寻址

Janus 的设计：**一个 Session 可以 attach 多个 Plugin，每次 attach 产生一个 Handle**。

```
Session (会话级，WebSocket 连接)
  │
  ├── Handle 1 → 绑定在 "janus.plugin.videocall"  (视频通话)
  ├── Handle 2 → 绑定在 "janus.plugin.audiobridge" (音频会议)
  └── Handle 3 → 绑定在 "janus.plugin.streaming"  (直播推流)
```

服务器收到消息时，三层路由：`session_id` → `handle_id` → 插件业务逻辑。

如果没有 `handle_id`，服务器无法判断这条 `register` / `call` / `hangup` 是发给视频通话插件还是音频会议插件。

---

## 三、handleId 在本项目的 4 类用途

### ① 客户端本地保存（两个地方）

**位置 1**：`JanusClient._plugins` map（`janus_client.dart:149`）

```dart
_plugins[handleId] = PluginHandle(handleId);
```

用途：**服务器推送事件时反查**。服务器推消息带 `sender = handleId` 字段，JanusClient 用它查出本地的 PluginHandle（`onMessage` 中 `_plugins[sender]`），告诉上层这条事件属于哪个 handle。

**位置 2**：`JanusVideoCallUseCase._handlerId`（`janus_video_call_usecase.dart:487`）

```dart
onJanusAttached(int handleId) {
  _handlerId = handleId;          // ← 存起来，后续所有上行请求都用它
  ...
}
```

用途：**所有上行请求都要引用它**。

### ② 当作所有上行 Janus message 的必填字段

| 方法 | 用途 | 是否需要 handleId |
| --- | --- | --- |
| `registerUser(handleId, name)` | 注册用户名 | ✅ 必须 |
| `call(handleId, sdp, name)` | 发起呼叫 + SDP offer | ✅ 必须 |
| `setOffer(handleId, sdp)` | SDP answer / 更新 | ✅ 必须 |
| `hangup(handleId)` | 挂断 | ✅ 必须 |
| `trickleCandidate(handleId, c)` | 上报 ICE candidate | ✅ 必须 |
| `trickleCandidateComplete(handleId)` | ICE 完成 | ✅ 必须 |
| `createSession()` / `claimSession()` | 建 session | ❌ 不需要（比 handle 更底层）|
| `destroySession()` / `keepalive` | session 级操作 | ❌ 不需要 |

**规律**：所有业务级消息（videocall 插件 API）都要 handleId；session 级消息不要。

### ③ 服务器推送事件的路由键（下行）

Janus 推消息时会带 `sender = handleId`：

```json
{
  "janus": "event",
  "sender": 98765432,
  "plugindata": {
    "plugin": "janus.plugin.videocall",
    "data": {"videocall": "event", "result": {"event": "accepted"}}
  },
  "jsep": {"type": "answer", "sdp": "..."}
}
```

客户端 `JanusClient.onMessage` 里：

```dart
int? sender = obj[janusKey.SENDER];
PluginHandle? handle = _plugins[sender];            // ← 用 handleId 反查
_janusCb.onMessage(sender, handle.handleId, data, jsep);
```

即使同一个 Session 里挂了多个插件，也能正确分发：videocall 的 `accepted` 事件不会被误送给 audiobridge 处理。

### ④ 生命周期收尾

- `hangup(handleId)` — 通知服务器挂断这个 handle 对应的通话
- 收到 `detached` 事件时通过 handleId 识别是哪个插件实例被拆除
  ```dart
  // janus_video_call_usecase.dart:903
  onDetached(int handleId) {
    _handlerId = null;         // 清空本地引用
  }
  ```

---

## 四、完整调用链示例

以「主叫发起 → 收到接听」为例，handleId 贯穿全程：

```
[attach 成功]
  _handlerId = 98765432 (假设值)
        │
[后续所有请求都要带它]
        │
  registerUser(98765432, "randUserFrom")
        │  发出: {"janus":"message","handle_id":98765432,"body":{"request":"register",...}}
        │
  call(98765432, sdpOffer, "randUserTo")
        │  发出: {"janus":"message","handle_id":98765432,"body":{"request":"call"},"jsep":{...}}
        │
  trickleCandidate(98765432, candidate)   (多次)
        │
  trickleCandidateComplete(98765432)
        │
[服务器推回 accepted]
  收到: {"janus":"event","sender":98765432,"plugindata":{...}}
        ↓
  _plugins[98765432] → 找到 PluginHandle
        ↓
  _janusCb.onMessage(98765432, 98765432, data, jsep)
        ↓
  handleVideoCallEvent → onCallAccepted(sdp)
```

---

## 五、核心理解（面试答题框架）

> **sessionId 是"会话身份证"，handleId 是"插件工位号"**。
>
> - **sessionId** 标识「哪个客户端」
> - **handleId** 标识「这个客户端在哪个插件上开了工位」
>
> 服务器的消息分发靠 `sessionId + handleId` 两级路由：没有 sessionId 找不到人，没有 handleId 找不到这个人的哪项业务。

### 本项目的实际情况

虽然只 attach 了 videocall 一个插件，handleId 看起来像"多余的"一层，但：

1. **协议层的通用性要求必须带**——Janus 对所有插件都这么设计
2. **handleId 变更就代表整个通话重来**——例如 `_handlerId = null` 表示当前无通话
3. **服务器的 plugin 实例隔离依赖它**——同一客户端重复 attach 会拿到不同 handleId，彼此独立

---

## 六、延伸思考：多方会议场景

如果本项目改用 `janus.plugin.videoroom`（多方会议），一个 session 可能要同时 attach **多个 handle**：

- Handle A：publisher（自己推流）
- Handle B：subscriber（订阅张三的流）
- Handle C：subscriber（订阅李四的流）

这时 handleId 的重要性立刻凸显——同一条 WebSocket 收到 candidate 事件，必须靠 handleId 区分是"我自己的流"还是"张三的流"的 ICE candidate。

> **handleId 的存在就是为多插件、多订阅这种场景预留的扩展能力**，videocall 插件只是它的简化使用。

---

## 七、sessionId vs handleId 对比表

| 维度 | sessionId | handleId |
| --- | --- | --- |
| **层级** | 会话级（WebSocket 连接级）| 插件实例级（Session 下的子会话）|
| **来源** | `create` / `claim` 响应 | `attach` 响应 |
| **生命周期** | WebSocket 重连可 `claim` 复用 | 每次 attach 都是新的 |
| **客户端保存** | `JanusClient._sessionId` | `JanusClient._plugins[handleId]` + `JanusVideoCallUseCase._handlerId` |
| **何时携带** | 所有 Janus message | 所有插件业务 message（非 session 级）|
| **服务器用途** | 定位是哪个客户端 | 定位客户端下的哪个插件实例 |
| **一个 session 能有几个** | 1 个 | 多个（每次 attach 一个）|
