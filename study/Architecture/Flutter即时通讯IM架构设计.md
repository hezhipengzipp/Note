# Flutter 即时通讯 IM 架构设计

## 一、整体架构分层

```
┌────────────────────────────────────────────────────────────────────┐
│                         Presentation Layer                         │
│   ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐             │
│   │  ChatPage│ │ ConvList │ │ Contacts │ │ Settings │  ... Pages  │
│   └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘             │
│        │             │            │            │                   │
├────────┴─────────────┴────────────┴────────────┴───────────────────┤
│                       State Management Layer                       │
│   ┌────────────┐ ┌────────────┐ ┌────────────┐                     │
│   │ ChatBloc   │ │ ConvBloc   │ │ AuthBloc   │  ... BLoC / Cubit  │
│   └─────┬──────┘ └─────┬──────┘ └─────┬──────┘                     │
│         │              │              │                             │
├─────────┴──────────────┴──────────────┴────────────────────────────┤
│                         Domain Layer                               │
│   ┌────────────┐ ┌────────────┐ ┌────────────┐                     │
│   │ UseCases   │ │ Entities   │ │ Repository │ ← 接口定义           │
│   │ SendMessage│ │ Message    │ │ Interfaces │                     │
│   └─────┬──────┘ └────────────┘ └─────┬──────┘                     │
│         │                             │                             │
├─────────┴─────────────────────────────┴────────────────────────────┤
│                         Data Layer                                 │
│   ┌──────────┐  ┌──────────┐  ┌──────────┐                        │
│   │ ImRepo   │  │ UserRepo │  │ FileRepo │  ... Repository Impl   │
│   └────┬─────┘  └────┬─────┘  └────┬─────┘                        │
│        │             │              │                               │
├────────┴─────────────┴──────────────┴──────────────────────────────┤
│                      Infrastructure Layer                          │
│   ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐             │
│   │ WebSocket│ │ HTTP     │ │ Drift DB │ │ File     │             │
│   │ Manager  │ │ (Dio)    │ │ (SQLite) │ │ Storage  │             │
│   └──────────┘ └──────────┘ └──────────┘ └──────────┘             │
└────────────────────────────────────────────────────────────────────┘
```

---

## 二、技术选型

```
┌──────────────────┬────────────────────┬──────────────────────────┐
│      层面         │      选型           │          理由             │
├──────────────────┼────────────────────┼──────────────────────────┤
│ 状态管理          │ BLoC + Cubit       │ 事件驱动天然匹配IM场景，    │
│                  │                    │ 消息流用 Stream 非常自然    │
│ 路由             │ go_router          │ 声明式路由，深度链接支持     │
│ 数据库           │ Drift (SQLite)     │ 类型安全，流式查询，        │
│                  │                    │ 支持复杂消息查询            │
│ WebSocket连接     │ web_socket_channel │ 跨平台，Dart 原生支持      │
│ HTTP             │ Dio               │ 拦截器、全局配置            │
│ 序列化           │ Protobuf           │ 体积小、速度快，IM消息量大   │
│ 推送             │ Firebase FCM       │ 后台唤醒 + 数据消息推送     │
│ 依赖注入          │ GetIt + Injectable │ 轻量级 DI                │
│ 日志             │ Logger             │ 分级日志，Release最小输出   │
└──────────────────┴────────────────────┴──────────────────────────┘
```

---

## 三、消息生命周期全景

```
  发送端 App                          服务端                      接收端 App
     │                                 │                              │
     │ ─1─ Save to local DB (sending)  │                              │
     │ ─2─ WebSocket: SEND_MSG ─────▶  │                              │
     │                                 │ ── 入库 + 推送给在线设备 ──▶  │
     │ ◀3── ACK {msgId, serverId} ─    │                           │
     │                                 │                              │
     │ ─4─ Update local: sending→sent   │                              │
     │                                 │                           ┌5─ Save to local DB
     │                                 │                           └6─ Push notification
     │                                 │                              │
     │                                 │ ◀7── ACK_DELIVERED ─────    │
     │ ◀8── DELIVERED ──────────────  │                              │
     │ ─9─ Update local: sent→delivered│                              │
     │                                 │                              │
     │                                 │                              │ ─10─ Open chat page
     │                                 │ ◀11─ ACK_READ ───────────   │
     │ ◀12─ READ ───────────────────  │                              │
     │ ─13─ Update local: delivered→read│                             │
     │                                 │                              │
```

---

## 四、数据模型设计

### 4.1 数据库表结构（Drift）

```dart
// ===== 会话表 =====
class Conversations extends Table {
  IntColumn get id => integer().autoIncrement()();
  TextColumn get conversationId => text().unique()();   // 服务端ID
  TextColumn get type => text()();                       // "single" / "group"
  TextColumn get title => text()();                      // 对方昵称/群名
  TextColumn get avatar => text().nullable()();
  TextColumn get draft => text().nullable()();           // 草稿
  TextColumn get lastMsgPreview => text().nullable()();  // 最后一条消息摘要
  IntColumn get lastMsgTime => integer().nullable()();
  IntColumn get unreadCount => integer().withDefault(const Constant(0))();
  IntColumn get isTop => bool().withDefault(const Constant(false))();
  IntColumn get isMuted => bool().withDefault(const Constant(false))();
  IntColumn get updatedAt => integer()();                // 排序用
}

// ===== 消息表 =====
class Messages extends Table {
  IntColumn get id => integer().autoIncrement()();
  TextColumn get localId => text().unique()();           // 客户端生成的UUID
  TextColumn get serverId => text().nullable()();        // 服务端生成的消息ID
  TextColumn get conversationId => text()();             // 所属会话
  TextColumn get type => text()();  // "text"/"image"/"voice"/"video"/"file"/"system"
  TextColumn get content => text()();                    // JSON 格式的富内容
  IntColumn get status => integer()();   // 0=sending, 1=sent, 2=delivered, 3=read, -1=failed
  TextColumn get senderId => text()();
  BoolColumn get isMe => bool()();
  IntColumn get timestamp => integer()();
  IntColumn get quoteMsgLocalId => text().nullable()();  // 引用消息
}

// ===== 联系人表 =====
class Contacts extends Table {
  IntColumn get id => integer().autoIncrement()();
  TextColumn get userId => text().unique()();
  TextColumn get nickname => text()();
  TextColumn get avatar => text().nullable()();
  TextColumn get remark => text().nullable()();
  TextColumn get firstLetter => text()();                // 首字母，排序用
}
```

### 4.2 Protobuf 消息协议

```protobuf
// message.proto
syntax = "proto3";

// 顶层传输包
message TransportPacket {
  PacketType type = 1;
  bytes payload = 2;        // 序列化后的具体消息体
  int64 timestamp = 3;
}

enum PacketType {
  UNKNOWN = 0;
  HEARTBEAT = 1;
  HEARTBEAT_ACK = 2;
  SEND_MSG = 3;
  SEND_MSG_ACK = 4;
  NEW_MSG = 5;             // 服务端推送新消息
  DELIVERED = 6;           // 送达回执
  READ = 7;                // 已读回执
  SYNC = 8;                // 同步离线消息
  SYNC_ACK = 9;
}

// 消息实体
message Message {
  string local_id = 1;       // 客户端ID（去重）
  string server_id = 2;      // 服务端ID
  string conversation_id = 3;
  MsgType type = 4;
  bytes content = 5;         // 序列化后的具体内容
  string sender_id = 6;
  int64 timestamp = 7;
  optional string quote_msg_id = 8;
}

enum MsgType {
  TEXT = 0;
  IMAGE = 1;
  VOICE = 2;
  VIDEO = 3;
  FILE = 4;
  SYSTEM = 5;
}

// 不同类型的消息体
message TextContent {
  string text = 1;
}

message ImageContent {
  string thumb_url = 1;     // 缩略图
  string origin_url = 2;    // 原图
  int32 width = 3;
  int32 height = 4;
  int64 size = 5;           // 文件大小 bytes
}

message VoiceContent {
  string url = 1;
  int32 duration = 2;       // 秒
}
```

---

## 五、核心模块设计

### 5.1 WebSocket 连接管理器

```
  连接状态机:

          ┌─────────────────┐
          │   DISCONNECTED   │
          └───────┬─────────┘
                  │ connect()
                  ▼
          ┌─────────────────┐
          │   CONNECTING     │──────────── 超时 ──────┐
          └───────┬─────────┘                        │
                  │ 握手成功                           │
                  ▼                                  ▼
          ┌─────────────────┐              ┌─────────────────┐
          │   CONNECTED      │              │   RECONNECTING   │
          └───────┬─────────┘              └────────┬────────┘
                  │ 连接断开/心跳失败                  │
                  └──────────────────────────────────┘
                  │
          ┌───────▼─────────┐
          │ heartbeat loop  │ ← 每 30s 发送 PING
          │ message listener│ ← 接收服务端推送
          │ message sender  │ ← 发送消息
          └─────────────────┘
```

```dart
enum ConnectionState { disconnected, connecting, connected, reconnecting }

class WebSocketManager {
  final String _url;
  WebSocketChannel? _channel;
  ConnectionState _state = ConnectionState.disconnected;
  Timer? _heartbeatTimer;
  Timer? _reconnectTimer;
  int _retryCount = 0;

  static const _maxRetryDelay = Duration(seconds: 30);
  static const _baseRetryDelay = Duration(seconds: 1);
  static const _heartbeatInterval = Duration(seconds: 30);
  static const _heartbeatTimeout = Duration(seconds: 10);

  // 上行消息流：业务层 → WebSocket
  final _outgoingController = StreamController<TransportPacket>.broadcast();

  // 下行消息流：WebSocket → 业务层
  final _incomingController = StreamController<TransportPacket>.broadcast();

  Stream<TransportPacket> get incoming => _incomingController.stream;
  StreamSink<TransportPacket> get send => _outgoingController.sink;

  /// 连接，返回是否成功
  Future<bool> connect() async {
    _state = ConnectionState.connecting;

    try {
      _channel = WebSocketChannel.connect(Uri.parse(_url))
        ..ready.timeout(const Duration(seconds: 10));

      await _channel!.ready;
      _state = ConnectionState.connected;
      _retryCount = 0;
      _startHeartbeat();
      _listenIncoming();
      _listenOutgoing();
      return true;
    } catch (e) {
      _state = ConnectionState.disconnected;
      _scheduleReconnect();
      return false;
    }
  }

  /// 监听服务端推送
  void _listenIncoming() {
    _channel!.stream.listen(
      (data) {
        final packet = TransportPacket.fromBuffer(data as List<int>);
        if (packet.type == PacketType.HEARTBEAT_ACK) {
          return; // 心跳响应，静默处理
        }
        _incomingController.add(packet);
      },
      onError: (_) => _handleDisconnect(),
      onDone: () => _handleDisconnect(),
    );
  }

  /// 监听业务层发送
  void _listenOutgoing() {
    _outgoingController.stream.listen((packet) {
      _channel?.sink.add(packet.writeToBuffer());
    });
  }

  /// 心跳
  void _startHeartbeat() {
    _heartbeatTimer?.cancel();
    _heartbeatTimer = Timer.periodic(_heartbeatInterval, (_) {
      if (_state != ConnectionState.connected) return;
      _channel?.sink.add(
        TransportPacket(type: PacketType.HEARTBEAT).writeToBuffer(),
      );
      // 心跳超时检测
      // 如果在 heartbeatTimeout 内没收到 HEARTBEAT_ACK，断开
    });
  }

  /// 重连（指数退避）
  void _scheduleReconnect() {
    if (_state == ConnectionState.reconnecting) return;
    _state = ConnectionState.reconnecting;

    final delay = _baseRetryDelay * (1 << min(_retryCount, 5));
    _reconnectTimer = Timer(delay.clamp(Duration.zero, _maxRetryDelay), () {
      _retryCount++;
      connect();
    });
  }

  void _handleDisconnect() {
    _heartbeatTimer?.cancel();
    _state = ConnectionState.disconnected;
    _scheduleReconnect();
  }

  void dispose() {
    _heartbeatTimer?.cancel();
    _reconnectTimer?.cancel();
    _outgoingController.close();
    _incomingController.close();
    _channel?.sink.close();
  }
}
```

### 5.2 消息可靠性 —— 发送 + ACK + 重试

```dart
class MessageSender {
  final WebSocketManager _ws;
  final LocalDatabase _db;

  // 待确认的消息队列（重连时需要重发）
  final Map<String, PendingMessage> _pendingMessages = {};

  /// 发送消息（完整流程）
  Future<void> sendMessage({
    required String conversationId,
    required String type,
    required Map<String, dynamic> content,
  }) async {
    // 1. 生成本地 ID（去重）
    final localId = Uuid().v4();
    final message = Message(
      localId: localId,
      serverId: '',
      conversationId: conversationId,
      type: _toMsgType(type),
      content: jsonEncode(content),
      senderId: UserStore.currentUserId,
      timestamp: DateTime.now().millisecondsSinceEpoch,
    );

    // 2. 先写入本地 DB（sending 状态），UI 立即展示
    await _db.saveMessage(message, status: MessageStatus.sending);

    // 3. 通过 WebSocket 发送
    final packet = TransportPacket(
      type: PacketType.SEND_MSG,
      payload: message.writeToBuffer(),
      timestamp: DateTime.now().millisecondsSinceEpoch,
    );

    if (_ws.state == ConnectionState.connected) {
      _ws.send.add(packet);
    }

    // 4. 加入待确认队列 + 启动超时重试
    _pendingMessages[localId] = PendingMessage(
      packet: packet,
      retryCount: 0,
      timer: Timer(const Duration(seconds: 5), () => _retry(localId)),
    );
  }

  /// 处理 ACK
  void onAck(Message ack) {
    final localId = ack.localId;
    final pending = _pendingMessages.remove(localId);
    pending?.timer.cancel();

    // 更新本地 DB 状态：sending → sent
    _db.updateMessageStatus(localId, MessageStatus.sent, serverId: ack.serverId);
  }

  /// 超时重试（最多3次）
  void _retry(String localId) {
    final pending = _pendingMessages[localId];
    if (pending == null) return;

    if (pending.retryCount >= 3) {
      _pendingMessages.remove(localId);
      _db.updateMessageStatus(localId, MessageStatus.failed);
      return;
    }

    pending.retryCount++;
    _ws.send.add(pending.packet);
    pending.timer = Timer(
      const Duration(seconds: 5) * (1 << pending.retryCount),
      () => _retry(localId),
    );
  }
}
```

### 5.3 重连后的增量同步

```dart
class MessageSyncService {
  final WebSocketManager _ws;
  final LocalDatabase _db;

  /// 连接恢复后，同步离线期间的消息
  Future<void> syncOnReconnected() async {
    // 1. 获取每个会话最后一条消息的 serverId
    final lastMsgIds = await _db.getLastServerIdPerConversation();

    // 2. 发送同步请求
    final syncPacket = TransportPacket(
      type: PacketType.SYNC,
      payload: SyncRequest(latestMsgIds: lastMsgIds).writeToBuffer(),
      timestamp: DateTime.now().millisecondsSinceEpoch,
    );
    _ws.send.add(syncPacket);

    // 3. 接收同步响应（流式分批返回）
    _ws.incoming
        .where((p) => p.type == PacketType.SYNC_ACK)
        .map((p) => SyncResponse.fromBuffer(p.payload))
        .listen((resp) {
          for (final msg in resp.messages) {
            _db.saveMessage(msg, status: MessageStatus.sent);
          }
          // 更新会话未读数
          _db.updateUnreadCount(resp.conversationId, resp.unreadCount);
        });
  }
}
```

---

## 六、BLoC 层级设计

### 6.1 分层 BLoC 架构

```
  ┌──────────────────────────────────────────┐
  │                 UI Layer                  │
  │  ChatPage / ConvListPage / ContactsPage  │
  └────────────┬─────────────────────────────┘
               │  BlocBuilder / BlocListener
               ▼
  ┌──────────────────────────────────────────┐
  │          Feature BLoC (页面级)            │
  │  ChatBloc  │ ConvListBloc │ ContactsBloc │
  └────────────┬─────────────────────────────┘
               │  调用
               ▼
  ┌──────────────────────────────────────────┐
  │        Infrastructure BLoC (全局级)       │
  │  ConnectionBloc (连接状态)                │
  │  AuthBloc (登录/Token)                   │
  │  MessageStreamBloc (全局消息流)            │
  └────────────┬─────────────────────────────┘
               │  注入
               ▼
  ┌──────────────────────────────────────────┐
  │            Repository Layer              │
  │  MessageRepository / UserRepository      │
  │  ConversationRepository                  │
  └──────────────────────────────────────────┘
```

### 6.2 全局消息流 BLoC（所有消息的入口）

```dart
/// 全局 BLoC —— 监听 WebSocket 下行消息，
/// 分发给对应会话的 BLoC
class MessageStreamBloc extends Bloc<MessageStreamEvent, MessageStreamState> {
  final WebSocketManager _ws;
  final MessageRepository _msgRepo;
  final ConversationRepository _convRepo;

  MessageStreamBloc({
    required WebSocketManager ws,
    required MessageRepository msgRepo,
    required ConversationRepository convRepo,
  }) : _ws = ws, _msgRepo = msgRepo, _convRepo = convRepo,
       super(MessageStreamInitial()) {
    // 监听 WebSocket 下行消息
    _ws.incoming.listen((packet) {
      // 收到新消息时通过事件触发
      add(OnNewPacket(packet));
    });

    on<OnNewPacket>(_onPacket);
    on<SendMessage>(_onSend);
  }

  void _onPacket(OnNewPacket event, Emitter emit) {
    final packet = event.packet;
    switch (packet.type) {
      case PacketType.NEW_MSG:
        final msg = Message.fromBuffer(packet.payload);
        _onNewMessage(msg, emit);
        break;
      case PacketType.DELIVERED:
        // 更新送达回执
        break;
      case PacketType.READ:
        // 更新已读回执
        break;
      case PacketType.SEND_MSG_ACK:
        // 发送确认
        break;
    }
  }

  void _onNewMessage(Message msg, Emitter emit) async {
    // 1. 保存到本地 DB
    await _msgRepo.save(msg, status: MessageStatus.sent);
    // 2. 送达回执
    _ackDelivered(msg);
    // 3. 更新会话
    await _convRepo.updateLastMessage(msg);
    // 4. 通知所有监听的页面 Bloc
    emit(NewMessageReceived(msg));
  }
}
```

### 6.3 聊天页 BLoC

```dart
class ChatBloc extends Bloc<ChatEvent, ChatState> {
  final String conversationId;
  final MessageRepository _msgRepo;
  final ConversationRepository _convRepo;
  final MessageSender _sender;

  // 本地消息列表（StreamBuilder 也可用）
  final messages = <Message>[];
  bool hasMore = true;
  String? _cursor; // 分页游标

  ChatBloc({
    required this.conversationId,
    required MessageRepository msgRepo,
    required ConversationRepository convRepo,
    required MessageSender sender,
  }) : _msgRepo = msgRepo, _convRepo = convRepo, _sender = sender,
       super(ChatInitial()) {
    on<LoadMessages>(_onLoadMessages);
    on<LoadMoreMessages>(_onLoadMore);
    on<SendMessage>(_onSend);
    on<NewMessageReceived>(_onNewMessage);
  }

  /// 初始加载最近 30 条
  void _onLoadMessages(LoadMessages event, Emitter emit) async {
    final msgs = await _msgRepo.getMessages(conversationId, limit: 30);
    messages.addAll(msgs);
    _cursor = msgs.lastOrNull?.localId;
    hasMore = msgs.length >= 30;
    await _convRepo.clearUnread(conversationId);
    emit(ChatLoaded(List.from(messages), hasMore: hasMore));
  }

  /// 发送消息
  void _onSend(SendMessage event, Emitter emit) async {
    await _sender.sendMessage(
      conversationId: conversationId,
      type: event.type,
      content: event.content,
    );
  }
}
```

---

## 七、聊天页 UI 架构

```
  ChatPage
     │
     │  BlocBuilder<ChatBloc, ChatState>
     │
     ├── AppBar (对方名称 + 在线状态)
     │
     ├── MessageListView              ← 核心
     │    │
     │    │  ScrollController + 分页加载
     │    │
     │    ├──── 头部（下拉加载更多）
     │    │     └── LoadMoreIndicator (hasMore ? spinner : "没有更多")
     │    │
     │    ├──── 时间分隔线
     │    │     └── if (timeGap > 5min) → "2026-05-19 14:30"
     │    │
     │    ├──── 消息气泡 (根据 type 分发 builder)
     │    │    ├── TextBubble        ← isMe ? 右侧蓝色 : 左侧白色
     │    │    ├── ImageBubble       ← 点击预览大图
     │    │    ├── VoiceBubble       ← 点击播放 + 未读红点
     │    │    ├── VideoBubble       ← 封面 + 播放按钮
     │    │    ├── FileBubble        ← 文件名 + 大小
     │    │    └── SystemBubble      ← 居中灰色文字
     │    │
     │    └──── 消息状态指示
     │         └── sending: ○ 转圈
     │             sent: ✓
     │             delivered: ✓✓
     │             read: ✓✓ 蓝色
     │             failed: ❗ 红色，点击重发
     │
     └── InputBar
          ├── 语音/键盘切换按钮
          ├── 文本输入框 (可扩展多行)
          ├── 图片选择按钮
          ├── 发送按钮
          └── 扩展面板 (表情/更多功能)
```

---

## 八、图片/文件上传流程

```
  用户选择图片
      │
      ▼
  ┌──────────────────────┐
  │  压缩图片（按边长+质量） │
  │  原图 → 缩略图(200dp)  │
  └──────────┬───────────┘
             │
             ▼
  ┌──────────────────────┐
  │  图片上传到文件服务器   │  ← HTTP multipart upload
  │  返回 thumbUrl +      │
  │  originUrl            │
  └──────────┬───────────┘
             │
             ▼
  ┌──────────────────────┐
  │  发送 IM 消息          │  ← 消息 content = {thumbUrl, originUrl, w, h}
  │  (走 WebSocket)       │     接收端收到消息后先用缩略图占位
  │                      │     点开大图才加载原图
  └──────────────────────┘
```

```dart
/// 图片消息发送（文件同理）
Future<void> sendImage(String conversationId, File imageFile) async {
  // 1. 先插入本地 DB（用本地文件路径作为缩略图），UI 立即展示
  final localId = Uuid().v4();
  final localPath = imageFile.path;
  await _db.saveMessage(Message(
    localId: localId,
    type: MsgType.IMAGE,
    content: jsonEncode({'localPath': localPath, 'status': 'uploading'}),
    // ...
  ));

  // 2. 后台上传
  final result = await _fileRepo.uploadImage(imageFile);

  // 3. 更新消息内容为 CDN URL
  await _db.updateMessageContent(localId, jsonEncode({
    'thumbUrl': result.thumbUrl,
    'originUrl': result.originUrl,
    'width': result.width,
    'height': result.height,
  }));

  // 4. 发送 IM 消息
  _sender.sendWithLocalId(localId);
}
```

---

## 九、推送通知设计

```
  后台/离线消息推送流程:

  服务端
     │
     │  消息到了，但用户 WebSocket 不在线
     │
     ├───────────────────────────── 推送到 APNs/FCM
     │                                    │
     │                                    ▼
     │                              用户手机系统
     │                                    │
     │                          ┌─────────┴─────────┐
     │                          │  系统收到推送通知    │
     │                          │  展示在通知栏       │
     │                          └─────────┬─────────┘
     │                                    │
     │                          用户点击通知
     │                                    │
     │                                    ▼
     │                          App 被拉起 / 从后台恢复
     │                                    │
     │                                    ▼
     │                          WebSocket 自动重连
     │                                    │
     │                                    ▼
     │                          syncOnReconnected()
     │                                    │
     │                                    ▼
     │                          ┌────────────────────┐
     │                          │ 跳转到对应聊天页     │
     │                          │ 消息已通过sync同步到DB│
     │                          └────────────────────┘
```

```dart
/// FCM 消息处理
Future<void> onFcmMessage(RemoteMessage message) async {
  // data 消息（静默）—— 用于后台数据同步
  if (message.data.isNotEmpty) {
    final type = message.data['type'];
    if (type == 'new_msg') {
      // 触发本地通知，带上会话信息
      // 如果是 Android，直接在本地处理通知展示
      await _showLocalNotification(message.data);
    }
  }
}

/// 点击通知时
void onNotificationTap(Map<String, dynamic> data) {
  final conversationId = data['conversation_id'];
  // 跳转到聊天页
  AppRouter.goToChat(conversationId);
}
```

---

## 十、目录结构

```
lib/
├── main.dart
├── app.dart                              # MaterialApp 配置
│
├── core/                                 # 核心基础设施
│   ├── di/                               # 依赖注入 (GetIt)
│   │   ├── injection_container.dart
│   │   └── modules/
│   ├── network/
│   │   ├── websocket_manager.dart        # WebSocket 连接管理
│   │   ├── http_client.dart              # Dio 封装
│   │   └── interceptors/                 # Token 刷新、重试
│   ├── database/
│   │   ├── app_database.dart             # Drift DB 定义
│   │   ├── tables/                       # 表定义
│   │   └── daos/                         # DAO 操作
│   ├── proto/                            # Protobuf 生成文件
│   │   ├── message.pb.dart
│   │   └── message.pbenum.dart
│   └── utils/
│       ├── uuid_generator.dart
│       ├── image_compressor.dart
│       └── logger.dart
│
├── data/                                 # 数据层
│   ├── repositories/
│   │   ├── message_repository.dart
│   │   ├── conversation_repository.dart
│   │   ├── user_repository.dart
│   │   └── file_repository.dart
│   └── models/
│       ├── message_model.dart            # 数据模型（Drift → Entity 映射）
│       └── conversation_model.dart
│
├── domain/                               # 领域层（可选，如果复杂）
│   ├── entities/
│   │   ├── message.dart
│   │   └── conversation.dart
│   └── usecases/
│       ├── send_message.dart
│       └── load_messages.dart
│
├── feature/                              # 功能模块
│   ├── auth/
│   │   ├── bloc/
│   │   └── ui/
│   ├── conversation/
│   │   ├── bloc/
│   │   │   ├── conversation_list_bloc.dart
│   │   │   └── conversation_list_state.dart
│   │   └── ui/
│   │       ├── conversation_list_page.dart
│   │       └── widgets/
│   ├── chat/
│   │   ├── bloc/
│   │   │   ├── chat_bloc.dart
│   │   │   └── chat_state.dart
│   │   └── ui/
│   │       ├── chat_page.dart
│   │       └── widgets/
│   │           ├── message_bubble.dart
│   │           ├── text_bubble.dart
│   │           ├── image_bubble.dart
│   │           ├── voice_bubble.dart
│   │           └── input_bar.dart
│   └── contacts/
│
├── shared/                               # 共享 Bloc / Widget
│   ├── blocs/
│   │   ├── message_stream_bloc.dart      # 全局消息流
│   │   └── connection_bloc.dart          # 连接状态
│   └── widgets/
│       ├── avatar.dart
│       └── loading_indicator.dart
│
└── router/
    └── app_router.dart                   # go_router 路由配置
```

---

## 十一、面试题

**Q1: IM 消息如何保证不丢、不重、不乱序？**

> - **不丢**：发送方先存本地 DB（sending 状态）+ WebSocket 发送 + 服务端 ACK 确认 + 超时重试（最多3次）。重连后增量同步离线消息。
> - **不重**：每条消息有客户端生成的 localId（UUID），服务端用 localId 去重，防止重传导致重复消息。
> - **不乱序**：消息时间戳以服务端时间为准。本地 DB 按 serverId + timestamp 排序。A 先发 B 后发但 B 先收到 ACK，用服务端 serverId 排序修正顺序。

**Q2: WebSocket 断线重连后，如何快速恢复消息？**

> 维护每个会话的「最后一条消息 serverId」，重连时发给服务端。服务端只推送该 serverId 之后的新消息。同时服务端返回每个会话的未读数。整个过程分批次返回（每批50条），避免一次性拉取大量数据阻塞。

**Q3: 图片/视频消息怎么处理？**

> 文件上传和消息发送分离。用户选图后：① 压缩+本地先展示 → ② HTTP 上传文件服务器得到 CDN URL → ③ WebSocket 发送消息（只带 URL、尺寸等元数据）。接收端收到后用缩略图占位，原图按需加载。好处：消息体小，文件存储和 IM 服务解耦。

**Q4: BLoC 在 IM 场景下如何组织？**

> 分层 BLoC：全局层（ConnectionBloc 管理连接状态、MessageStreamBloc 管理所有消息的接收和分发）、页面层（ChatBloc 管理单聊消息列表和发送、ConvListBloc 管理会话列表）。全局 Bloc 收到新消息后，页面 Bloc 通过监听全局 Bloc 的 Stream 来更新 UI。

**Q5: 如何做消息已读/未读？**

> 发送方视角：发送 → 服务端 ACK（sent）→ 接收方在线收到（delivered ACK）→ 接收方打开聊天页（read ACK）。接收方本地 DB 维护会话的 lastReadServerId，未读数 = 该会话消息总数 - 最后已读位置。加载聊天页时批量发送 read ACK。

**Q6: 大量消息时列表性能如何保证？**

> ① 分页加载（每页30条），`ScrollController` 滑到顶部时加载更多；② 消息气泡按 type 拆分 widget，避免一个大 Widget 根据 type 条件判断重建所有内容；③ 使用 `ListView.builder` + `itemExtent`（固定高度消息）或 `ListView.builder` + `findChildIndexCallback`（混合高度消息）复用 item；④ 时间戳相同的消息不重复显示时间分割线，减少 Widget 数量；⑤ 图片使用缩略图 + 按需加载原图。
