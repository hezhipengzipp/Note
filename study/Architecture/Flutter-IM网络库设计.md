# Flutter IM 网络库设计

## 一、网络库在 IM 架构中的位置

```
  ┌─────────────────────────────────────────────────────┐
  │                   业务层                              │
  │  ChatBloc / ConvBloc / AuthBloc                     │
  │  只调用 MessageRepository.send(msg)                  │
  │  只监听 MessageRepository.messageStream              │
  └────────────────────────┬────────────────────────────┘
                           │  依赖接口，不碰网络细节
  ┌────────────────────────▼────────────────────────────┐
  │                  Repository 层                       │
  │  协议转换：业务对象 ↔ TransportPacket               │
  └────────────────────────┬────────────────────────────┘
                           │
  ┌────────────────────────▼────────────────────────────┐
  │                 IM 网络库（本文重点）                  │
  │                                                     │
  │  ┌──────────┐  ┌──────────┐  ┌──────────────┐      │
  │  │ Connection│  │ Request  │  │ MessageQueue  │      │
  │  │ Manager  │  │ Response │  │ (重试/离线)    │      │
  │  └────┬─────┘  └────┬─────┘  └──────┬───────┘      │
  │       │             │               │                │
  │  ┌────▼─────────────▼───────────────▼───────┐      │
  │  │            Transport Layer               │      │
  │  │   WebSocket (主要)  +  HTTP (文件上传)     │      │
  │  └──────────────────────────────────────────┘      │
  └─────────────────────────────────────────────────────┘
```

---

## 二、整体设计目标

```
  网络库对外暴露什么？

  ┌─────────────────────────────────────────────────────┐
  │                                                     │
  │  // 1. 连接管理                                     │
  │  imClient.connect(token)                            │
  │  imClient.disconnect()                              │
  │  imClient.connectionState    // Stream<ConnState>    │
  │                                                     │
  │  // 2. 请求-响应（RPC 风格，像 HTTP 一样简单）         │
  │  final resp = await imClient.request(                │
  │    path: '/message/send',                           │
  │    body: SendMsgReq(...),                           │
  │  );                                                 │
  │                                                     │
  │  // 3. 服务端推送监听                                 │
  │  imClient.onPush<NewMsgPush>().listen((msg) {       │
  │    // 收到新消息                                     │
  │  });                                                │
  │                                                     │
  │  // 4. 断线重连后自动同步                              │
  │  // 网络库内部处理，业务层无感知                        │
  │                                                     │
  └─────────────────────────────────────────────────────┘
```

---

## 三、传输层 —— ConnectionManager

### 3.1 连接状态机

```
                    ┌──────────────┐
                    │   IDLE       │ ← 初始状态 / disconnect() 后
                    └──────┬───────┘
                           │ connect(token)
                           ▼
                    ┌──────────────┐
               ┌───▶│ CONNECTING   │──────────── 超时 ──────┐
               │    └──────┬───────┘                        │
               │           │ WebSocket 握手成功              │
               │           ▼                                │
               │    ┌──────────────┐                        │
               │    │  CONNECTED   │                        │
               │    └──────┬───────┘                        │
               │           │ 心跳失败 / WS 断开               │
               │           ▼                                ▼
               │    ┌──────────────┐              ┌──────────────┐
               └────│ RECONNECTING │◀─────────────│   DISCONNECTED│
                    │ 指数退避等待  │              │  (最终失败)    │
                    └──────────────┘              └──────────────┘
```

### 3.2 核心实现

```dart
enum ConnState { idle, connecting, connected, reconnecting, disconnected }

class ConnectionManager {
  final String _baseUrl;
  final Duration _heartbeatInterval;
  final Duration _connectTimeout;
  final int _maxRetryAttempts;

  WebSocketChannel? _channel;
  ConnState _state = ConnState.idle;
  final _stateController = StreamController<ConnState>.broadcast();
  Timer? _heartbeat;
  int _retryCount = 0;

  Stream<ConnState> get state => _stateController.stream;
  ConnState get currentState => _state;
  bool get isConnected => _state == ConnState.connected;

  /// WebSocket 裸数据流，上层协议层会订阅
  final _dataController = StreamController<List<int>>.broadcast();
  Stream<List<int>> get rawData => _dataController.stream;
  Sink<List<int>> get sendRaw => _dataController.sink;  // 仅协议层用

  ConnectionManager({
    required String baseUrl,
    Duration heartbeatInterval = const Duration(seconds: 30),
    Duration connectTimeout = const Duration(seconds: 10),
    int maxRetryAttempts = 10,
  }) : _baseUrl = baseUrl,
       _heartbeatInterval = heartbeatInterval,
       _connectTimeout = connectTimeout,
       _maxRetryAttempts = maxRetryAttempts;

  Future<void> connect(String token) async {
    if (_state == ConnState.connected || _state == ConnState.connecting) return;

    _setState(ConnState.connecting);

    try {
      final uri = Uri.parse('$_baseUrl/ws?token=$token');
      _channel = WebSocketChannel.connect(uri);

      await _channel!.ready.timeout(_connectTimeout);

      _setState(ConnState.connected);
      _retryCount = 0;
      _startHeartbeat();
      _startListening();
    } catch (e) {
      _setState(ConnState.disconnected);
      _scheduleReconnect(token);
    }
  }

  void _startListening() {
    _channel!.stream.listen(
      (data) {
        // 如果是心跳响应，不往外投递
        if (_isHeartbeatAck(data)) return;
        _dataController.add(data is List<int> ? data : List<int>.from(data));
      },
      onError: (_) => _onConnectionLost(),
      onDone: () => _onConnectionLost(),
      cancelOnError: false,
    );
  }

  void send(List<int> bytes) {
    if (_state != ConnState.connected) return;
    _channel?.sink.add(bytes);
  }

  void _startHeartbeat() {
    _heartbeat?.cancel();
    _heartbeat = Timer.periodic(_heartbeatInterval, (_) {
      if (_state != ConnState.connected) return;
      send(_buildHeartbeat());
    });
  }

  void _onConnectionLost() {
    _heartbeat?.cancel();
    if (_state == ConnState.connected) {
      // 需要外部传入 token 用于重连
      // 简化：通过回调获取
      _scheduleReconnect(null);
    }
  }

  void _scheduleReconnect(String? token) {
    if (_retryCount >= _maxRetryAttempts) {
      _setState(ConnState.disconnected);
      return;
    }

    _setState(ConnState.reconnecting);

    // 指数退避: 1s, 2s, 4s, 8s, 16s, 32s ... 最大 60s
    final delay = Duration(
      seconds: min(pow(2, _retryCount).toInt(), 60),
    );

    Future.delayed(delay, () {
      _retryCount++;
      connect(token ?? '');
    });
  }

  Future<void> disconnect() async {
    _heartbeat?.cancel();
    await _channel?.sink.close();
    _setState(ConnState.idle);
  }

  void dispose() {
    _heartbeat?.cancel();
    _stateController.close();
    _dataController.close();
    _channel?.sink.close();
  }

  void _setState(ConnState s) {
    _state = s;
    _stateController.add(s);
  }
}
```

---

## 四、协议层 —— 请求-响应 + 推送分流

### 4.1 协议设计：TransportPacket 统一包

```
  ┌───────────────────────────────────────────────────────┐
  │                 TransportPacket                       │
  │                                                       │
  │  ┌──────────┬──────────┬──────────┬─────────────────┐ │
  │  │  type(1B)│ flags(1B)│ seqId(4B)│ payload       │ │
  │  │          │          │ (请求序号) │ (Protobuf bytes) │ │
  │  └──────────┴──────────┴──────────┴─────────────────┘ │
  │                                                       │
  │  type:                                                 │
  │    0 = REQUEST     业务请求（需要响应）                  │
  │    1 = RESPONSE    请求响应                            │
  │    2 = PUSH        服务端主动推送                       │
  │    3 = HEARTBEAT   心跳                                │
  │    4 = ACK         确认                                │
  │                                                       │
  │  flags:                                                │
  │    bit0 = 是否需要 ACK                                 │
  │    bit1 = 是否压缩                                     │
  │                                                       │
  │  seqId:                                                │
  │    单调递增，REQUEST 和 RESPONSE 用同一个 seqId 关联    │
  │    PUSH 的 seqId 恒为 0                                │
  └───────────────────────────────────────────────────────┘
```

### 4.2 Protobuf 定义

```protobuf
syntax = "proto3";

// ---- 传输包 ----
message TransportPacket {
  PacketType type = 1;
  int32 flags = 2;
  int32 seq_id = 3;
  string path = 4;       // 路由路径: "/message/send"
  bytes payload = 5;     // 序列化后的请求体/响应体
  int64 timestamp = 6;
}

enum PacketType {
  REQUEST = 0;
  RESPONSE = 1;
  PUSH = 2;
  HEARTBEAT = 3;
  ACK = 4;
}

// ---- 业务请求/响应使用 Any 做动态分发 ----
message Request {
  string path = 1;
  bytes body = 2;  // 具体业务消息序列化后的 bytes
}

message Response {
  int32 code = 1;       // 业务状态码
  string message = 2;   // 错误信息
  bytes data = 3;       // 具体业务响应序列化后的 bytes
}

// ---- 推送消息 ----
message PushMessage {
  string push_type = 1;  // "new_msg" / "friend_request" / ...
  bytes body = 2;
}
```

### 4.3 核心——请求-响应匹配

这是网络库最关键的设计：**如何把异步的 WebSocket 消息映射回对应的 Future？**

```
  请求方:
    request('/message/send', body)
       │
       ├─ 生成 seqId = 42
       ├─ 创建 Completer<Response>，存入 _pendingRequests[42]
       ├─ 设置超时定时器（10s 后自动失败）
       └─ 通过 WebSocket 发送 TransportPacket(type=REQUEST, seqId=42, ...)

  响应方（收到 WebSocket 消息）:
    收到 TransportPacket(type=RESPONSE, seqId=42, ...)
       │
       ├─ 从 _pendingRequests 取出 Completer
       ├─ 用 resp.payload 完成 Completer
       └─ 返回 Future → 业务层拿到结果
```

```dart
class ImProtocol {
  final ConnectionManager _connection;
  final Map<int, _PendingRequest> _pending = {};
  int _seqId = 0;

  /// 推送消息流 —— 业务层订阅
  final _pushController = StreamController<PushMessage>.broadcast();
  Stream<PushMessage> get pushStream => _pushController.stream;

  ImProtocol(this._connection) {
    // 订阅传输层的裸数据流
    _connection.rawData.listen(_onData);
  }

  /// 发送请求，返回 Future<Response>
  Future<Response> request(String path, Uint8List body,
      {Duration timeout = const Duration(seconds: 10)}) async {
    if (!_connection.isConnected) {
      throw ImException('未连接');
    }

    final seqId = ++_seqId;
    final completer = Completer<Response>();
    final timer = Timer(timeout, () {
      if (!completer.isCompleted) {
        completer.completeError(TimeoutException('请求超时: $path'));
      }
      _pending.remove(seqId);
    });

    _pending[seqId] = _PendingRequest(
      completer: completer,
      timer: timer,
      path: path,
      timestamp: DateTime.now().millisecondsSinceEpoch,
    );

    final packet = TransportPacket()
      ..type = PacketType.REQUEST
      ..seqId = seqId
      ..path = path
      ..payload = body
      ..timestamp = DateTime.now().millisecondsSinceEpoch;

    _connection.send(packet.writeToBuffer());
    return completer.future;
  }

  /// 处理收到的数据
  void _onData(List<int> raw) {
    final packet = TransportPacket.fromBuffer(raw);

    switch (packet.type) {
      case PacketType.RESPONSE:
        _handleResponse(packet);
        break;
      case PacketType.PUSH:
        _handlePush(packet);
        break;
    }
  }

  void _handleResponse(TransportPacket packet) {
    final pending = _pending.remove(packet.seqId);
    if (pending == null) return;

    pending.timer.cancel();
    try {
      final resp = Response.fromBuffer(packet.payload);
      pending.completer.complete(resp);
    } catch (e) {
      pending.completer.completeError(e);
    }
  }

  void _handlePush(TransportPacket packet) {
    try {
      final push = PushMessage.fromBuffer(packet.payload);
      _pushController.add(push);
    } catch (e) {
      // 解析失败，忽略
    }
  }

  /// 连接恢复后，清理所有超时的 pending 请求
  void onReconnected() {
    for (final entry in _pending.entries) {
      // 重连后保留 pending，业务层如需重发由上层决定
      // 或者全部 fail，让业务层感知并重试
    }
  }

  void dispose() {
    for (final p in _pending.values) {
      p.timer.cancel();
      p.completer.completeError(ImException('连接已关闭'));
    }
    _pushController.close();
  }
}

class _PendingRequest {
  final Completer<Response> completer;
  final Timer timer;
  final String path;
  final int timestamp;
}
```

---

## 五、消息队列层 —— 可靠性保证

### 5.1 为什么需要消息队列

```
  业务层调用 send(msg) 时，不能假设网络一定连通。

  场景1: 网络断开时用户点击发送
    → 消息存入离线队列 → 连接恢复后自动发送

  场景2: 发送了但没收到 ACK（网络闪断）
    → 消息在待确认队列 → 超时后重发

  场景3: 重连后，上次没发出去的消息怎么办
    → 本地队列持久化（SQLite），重启 App 后还能继续发
```

### 5.2 三层队列设计

```
  ┌─────────────────────────────────────────────────────┐
  │                    消息队列                          │
  │                                                     │
  │  ┌──────────────────────────────┐                   │
  │  │   L1: 离线队列（持久化到 DB）  │                   │
  │  │   网络不通时暂存              │                    │
  │  └────────────┬─────────────────┘                   │
  │               │ 网络恢复                               │
  │               ▼                                      │
  │  ┌──────────────────────────────┐                   │
  │  │   L2: 发送中队列（内存）       │                   │
  │  │   已通过 WS 发出，等 ACK      │                    │
  │  └────────────┬─────────────────┘                   │
  │               │ ACK 到达                               │
  │               ▼                                      │
  │  ┌──────────────────────────────┐                   │
  │  │   L3: 已完成（更新 DB 状态）   │                   │
  │  │   sent / delivered / read   │                    │
  │  └──────────────────────────────┘                   │
  └─────────────────────────────────────────────────────┘
```

```dart
class MessageQueue {
  final ImProtocol _protocol;
  final LocalDatabase _db;

  // L2: 待确认队列（内存）
  final Map<String, _PendingMsg> _pending = {};

  /// 发送消息（带可靠性保证）
  Future<void> send(String localId, String path, Uint8List body) async {
    // 1. 先存本地 DB（已持久化，不怕 App 杀进程）
    await _db.markPending(localId);

    // 2. 如果网络通，立即发送
    if (_protocol.isConnected) {
      _doSend(localId, path, body);
    }
    // 否则不处理——网络恢复后 onReconnected 会统一重发
  }

  void _doSend(String localId, String path, Uint8List body) async {
    try {
      // 加入待确认队列
      _pending[localId] = _PendingMsg(
        path: path,
        body: body,
        sendTime: DateTime.now().millisecondsSinceEpoch,
        retryCount: 0,
      );

      final resp = await _protocol.request(path, body);

      if (resp.code == 0) {
        // 成功：从待确认移除，更新 DB
        _pending.remove(localId);
        await _db.markSent(localId, serverId: extractServerId(resp));
      } else {
        _pending.remove(localId);
        await _db.markFailed(localId, resp.message);
      }
    } catch (e) {
      // 超时——调度重试
      _scheduleRetry(localId);
    }
  }

  void _scheduleRetry(String localId) {
    final p = _pending[localId];
    if (p == null || p.retryCount >= 3) {
      _pending.remove(localId);
      _db.markFailed(localId, '发送失败，已重试3次');
      return;
    }

    p.retryCount++;
    // 指数退避重试
    Future.delayed(Duration(seconds: pow(2, p.retryCount).toInt()), () {
      _doSend(localId, p.path, p.body);
    });
  }

  /// 连接恢复：重发所有 DB 中 pending 的消息
  Future<void> onReconnected() async {
    final pendingMsgs = await _db.getAllPendingMessages();
    for (final msg in pendingMsgs) {
      _doSend(msg.localId, msg.path, msg.body);
    }
  }
}
```

---

## 六、ImClient —— 统一门面

```dart
/// IM 网络库的统一入口
class ImClient {
  late final ConnectionManager _connection;
  late final ImProtocol _protocol;
  late final MessageQueue _queue;

  ImClient({required String baseUrl, required LocalDatabase db}) {
    _connection = ConnectionManager(baseUrl: baseUrl);
    _protocol = ImProtocol(_connection);
    _queue = MessageQueue(protocol: _protocol, db: db);

    // 连接状态变化 → 队列处理
    _connection.state.listen((state) {
      if (state == ConnState.connected) {
        _queue.onReconnected();
        _protocol.onReconnected();
      }
    });
  }

  // ---- 对外 API ----

  /// 连接
  Future<void> connect(String token) async {
    await _connection.connect(token);
  }

  /// 连接状态流
  Stream<ConnState> get connectionState => _connection.state;
  bool get isConnected => _connection.isConnected;

  /// RPC 请求（不需要可靠队列的场景：如拉取历史消息）
  Future<Response> request(String path, Uint8List body) {
    return _protocol.request(path, body);
  }

  /// 可靠发送消息（IM 消息走这个）
  Future<void> sendMessage(String localId, String path, Uint8List body) {
    return _queue.send(localId, path, body);
  }

  /// 监听推送
  Stream<PushMessage> get pushStream => _protocol.pushStream;

  /// 断开
  Future<void> disconnect() async {
    await _connection.disconnect();
  }

  void dispose() {
    _protocol.dispose();
    _connection.dispose();
  }
}
```

---

## 七、文件上传走 HTTP，不走 WebSocket

```
  为什么文件不经过 WebSocket？

  ┌─────────────────────────────────────────────┐
  │  WebSocket 是单路、有序的                      │
  │                                              │
  │  如果 10MB 图片从 WebSocket 上传：              │
  │  - 它会阻塞后续所有小消息（队头阻塞）             │
  │  - 长连接超时风险增加                           │
  │  - 服务端 ws 连接不只是给你传文件的              │
  │                                              │
  │  正确做法：                                     │
  │  文件 → HTTP multipart → 文件服务器 → CDN URL    │
  │  URL → WebSocket → IM 消息服务                 │
  └─────────────────────────────────────────────┘
```

```dart
/// 文件上传走独立的 HTTP Client
class FileUploader {
  final Dio _dio;

  FileUploader()
      : _dio = Dio(BaseOptions(
          connectTimeout: const Duration(seconds: 30),
          receiveTimeout: const Duration(seconds: 60),
        ));

  /// 上传图片（带进度回调）
  Future<UploadResult> uploadImage(
    File file, {
    void Function(int sent, int total)? onProgress,
  }) async {
    final formData = FormData.fromMap({
      'file': await MultipartFile.fromFile(file.path),
      'type': 'image',
    });

    final resp = await _dio.post(
      '/upload/image',
      data: formData,
      onSendProgress: onProgress,
    );

    return UploadResult.fromJson(resp.data);
  }
}
```

---

## 八、线程模型

Flutter Dart 是单线程模型，所有网络 I/O 都在 Dart 的事件循环中异步执行，不阻塞 UI。

```
  ┌─────────────────────────────────────────────┐
  │              Main Isolate (UI)               │
  │                                              │
  │  ┌──────────────┐    ┌──────────────────┐   │
  │  │  Widget 树    │    │  网络库(所有代码)   │   │
  │  │  build/layout│    │  WebSocket / HTTP │   │
  │  └──────────────┘    │  回调/Stream      │   │
  │                      └──────────────────┘   │
  │                                              │
  │  关键：所有网络回调直接在主 Isolate 执行         │
  │        Protobuf 反序列化也在主 Isolate          │
  │        → 适合 IM 场景（消息体通常很小）          │
  │        → 如果图片/视频元数据解析量大，           │
  │          可放到 compute() 后台 Isolate          │
  └─────────────────────────────────────────────┘
```

---

## 九、完整文件结构

```
lib/core/im_network/
├── im_client.dart                    # 统一门面
│
├── transport/
│   ├── connection_manager.dart       # WebSocket 连接管理
│   └── connection_state.dart         # 连接状态枚举
│
├── protocol/
│   ├── im_protocol.dart              # 请求-响应、推送分流
│   ├── packet.proto                  # Protobuf 定义
│   ├── packet.pb.dart                # 生成文件
│   └── packet.pbenum.dart
│
├── queue/
│   ├── message_queue.dart            # 消息可靠性队列
│   └── pending_message.dart          # 待确认消息模型
│
├── serializer/
│   ├── message_serializer.dart       # 业务消息 ↔ Protobuf
│   └── push_serializer.dart          # 推送消息 ↔ Protobuf
│
├── http/
│   └── file_uploader.dart            # 文件上传（HTTP）
│
└── exception/
    └── im_exception.dart             # 网络异常定义
```

---

## 十、面试题

**Q1: IM 网络库和普通 HTTP 网络库最大的区别是什么？**

> ① **全双工 vs 请求-响应**：HTTP 是客户端问服务端答；IM 需要服务端主动推送消息，必须用 WebSocket/长连接。
> ② **有状态连接**：HTTP 每次请求可以独立；IM 的连接状态要全局管理（断线重连、心跳保活），上层业务随时感知。
> ③ **可靠性分层**：HTTP 失败就重试；IM 消息需要本地持久化 + ACK + 重试 + 去重 + 有序的完整链条。
> ④ **流式多路复用**：一个 WebSocket 连接上同时有 RPC 请求、消息推送、心跳，需要协议层做路由分发。

**Q2: 为什么在 WebSocket 上还要加一层请求-响应协议？直接用 WebSocket 发不行吗？**

> 裸 WebSocket 只是一条字节流，你发出去后不知道对方什么时候回复、回复的是哪条请求的。加一层协议（seqId + Completer 映射）的作用：
> ① 请求和响应一一对应，上层用 `await` 写代码，和 HTTP 一样简单
> ② 区分 REQUEST / RESPONSE / PUSH，一个连接承载多种消息类型
> ③ 超时控制、错误处理都在协议层统一完成

**Q3: 重连后，发了一半的消息怎么办？**

> 分三种情况：
> ① **已发送+已收到ACK**：消息已被服务端持久化，本地 DB 状态已是 `sent`，不需要处理
> ② **已发送+未收到ACK**：消息在服务端状态未知，重连后应从本地 DB 查询所有 `pending` 状态的消息，重发。服务端用 `localId` 去重，保证不重复。
> ③ **未发送**：仍在本地队列，重连后自动发。

**Q4: 心跳为什么不能替代 TCP keepalive？**

> TCP keepalive 默认 2 小时，且只检测 TCP 层的连通性，无法检测应用层是否正常（比如服务端进程 hang 住但 TCP 还连着）。应用层心跳（30s 间隔）能快速发现服务端不可用，触发重连，也能防止运营商 NAT 设备淘汰空闲连接。
