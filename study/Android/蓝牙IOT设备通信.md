# 蓝牙 IoT 设备通信 —— 数据正确性保证

## 一、蓝牙通信基础知识

### BLE（蓝牙低功耗）协议栈分层

```
┌──────────────────────────────────────┐
│          Application Layer           │  ← 我们的业务代码
├──────────────────────────────────────┤
│         GATT / GAP Profile           │  ← 服务/特征/描述符定义
├──────────────────────────────────────┤
│         ATT (Attribute Protocol)     │  ← 读写通知的底层协议
├──────────────────────────────────────┤
│         L2CAP (Logic Link Control)   │  ← 数据分包/重组 (MTU)
├──────────────────────────────────────┤
│         HCI (Host Controller I/F)    │  ← Host 与 Controller 通信
├──────────────────────────────────────┤
│         Link Layer                   │  ← 物理信道、CRC校验
├──────────────────────────────────────┤
│         Physical Layer (PHY)         │  ← 2.4GHz 无线
└──────────────────────────────────────┘
```

**关键参数：**
- **MTU (Maximum Transmission Unit)**：BLE 4.0/4.1 默认 23 字节，4.2+ 支持协商到 247~512 字节
- **ATT Payload = MTU - 3**：实际单次传输数据量
- **Connection Interval**：7.5ms ~ 4s，决定通信频率
- **每个 Connection Event** 可发多个包（受 CE Length 限制）

---

## 二、核心问题 —— 黏包、分包、校验

### 2.1 蓝牙为什么不存在传统 TCP 的“黏包”？

先澄清一个常见误区：

```
TCP 黏包示意图：
┌──────┬──────┐
│ MSG1 │ MSG2 │  →  接收端收到: [MSG1+MSG2]（黏在一起）
└──────┴──────┘

BLE GATT 通信：
┌────────────┐     ┌────────────┐
│  Packet A  │ ... │  Packet B  │  ← 每个 Write/Notify 是独立操作
└────────────┘     └────────────┘
      ↓                  ↓
  onCharacteristicChanged 回调，每次一个完整 ATT 包
```

BLE 的 ATT 层本身是**消息边界明确**的——每次 `onCharacteristicChanged` 回调拿到的是一个**完整的 ATT PDU**，不会出现两个独立 Notify 的数据黏在一起的情况。

**但在 IoT 场景存在“应用层黏包”：**

```
设备快速连续发送多个业务包时：
时间: t0────t1────t2
包:   [PKG1][PKG2][PKG3]
         ↓
Android BLE 栈回调:
  onCharacteristicChanged(pkg1)
  onCharacteristicChanged(pkg2)  ← 如果处理慢，pkg2 和 pkg3 可能在同一个回调里？
         ↓
实际上 BLE 内核有缓冲队列，不会丢，但应用层可能由于序列化/异步
导致“收到几个包但只处理了一个”的假黏包现象。
```

**真正的黏包发生在：** 大量数据通过**同一个特征值**快速写入（Write Without Response）时，Android BLE 栈内部排队发送，如果协议设计不佳，接收端可能无法区分每个包的边界。
这个问题通过**自定义分帧协议**解决。

### 2.2 IoT 通信面临的数据完整性问题

```
┌─────────────────────────────────────────────────────────────┐
│                    数据完整性问题分类                          │
├──────────────┬──────────────────┬───────────────────────────┤
│    问题类型   │      原因         │          现象              │
├──────────────┼──────────────────┼───────────────────────────┤
│    数据丢失   │  无线干扰/距离过远 │  Notify 丢失，包不完整      │
│    数据重复   │  重传机制         │  同一包收到多次              │
│    数据错乱   │  误码率           │  字节反转/错误              │
│    分包乱序   │  并发传输         │  先发的包后到               │
│    包长越界   │  超过MTU强行发送   │  数据被截断                │
└──────────────┴──────────────────┴───────────────────────────┘
```

---

## 三、自定义分帧协议设计（核心）

### 3.1 通用帧格式

```
┌─────────┬─────────┬─────────┬──────────┬───────────┬─────────┐
│ 帧头(2B) │ 版本(1B)│ 命令(1B)│ 序号(2B)  │ 长度(2B)  │ 载荷(NB) │ 校验(2B) │
├─────────┼─────────┼─────────┼──────────┼───────────┼─────────┤
│ 0xAA55  │  0x01   │  CMD    │  SEQ     │   N       │  DATA   │  CRC16  │
└─────────┴─────────┴─────────┴──────────┴───────────┴─────────┘
                                       最大: MTU-3-帧开销
                                       单帧载荷 ≈ MTU - 10 字节
```

### 3.2 分包协议 —— 大数据传输

当数据 > 单帧载荷时，需要分包：

```
                       发送端
                         │
              ┌──────────┴──────────┐
              │   分包序号: 总包数    │
              │   SEQ = (index << 8) | total   │
              │   index: 当前包号(0~total-1)   │
              │   total: 总包数(1~255)         │
              └─────────────────────┘
                         │
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
    ┌─────────┐    ┌─────────┐    ┌─────────┐
    │ 第0包    │    │ 第1包    │    │ 第N包    │
    │ 数据[0]  │    │ 数据[1]  │    │ 数据[N]  │
    └─────────┘    └─────────┘    └─────────┘
                         │
                    接收端
                    ┌────┴────┐
                    │ 收齐后  │
                    │ 拼接+校验│
                    └─────────┘
```

### 3.3 Android 端协议解析器实现

#### 3.3.1 协议定义

```java
/**
 * 自定义帧协议定义
 */
public final class FrameProtocol {

    // 帧头
    public static final byte FRAME_HEADER_HIGH = (byte) 0xAA;
    public static final byte FRAME_HEADER_LOW  = (byte) 0x55;

    // 帧结构中各字段偏移（帧头为0）
    public static final int OFFSET_HEADER    = 0;
    public static final int OFFSET_VERSION   = 2;
    public static final int OFFSET_CMD       = 3;
    public static final int OFFSET_SEQ_HIGH  = 4;
    public static final int OFFSET_SEQ_LOW   = 5;
    public static final int OFFSET_LEN_HIGH  = 6;
    public static final int OFFSET_LEN_LOW   = 7;
    public static final int OFFSET_PAYLOAD   = 8; // 载荷起始位置

    // 最小帧长: 帧头(2) + 版本(1) + 命令(1) + 序号(2) + 长度(2) + 校验(2) = 10
    public static final int MIN_FRAME_LEN = 10;

    // 分包标志：使用序号字段的高字节发送分包信息
    // SEQ = 高字节(当前包号) + 低字节(总包数)
    public static final int MAX_PACKET_COUNT = 255;

    private FrameProtocol() {}
}
```

#### 3.3.2 数据包实体

```java
public class DataFrame {
    public byte version;
    public byte command;
    public int  sequence;  // 完整序号
    public byte[] payload;

    // 从帧中解析
    public static DataFrame fromBytes(byte[] frame) {
        DataFrame pkt = new DataFrame();
        pkt.version  = frame[FrameProtocol.OFFSET_VERSION];
        pkt.command  = frame[FrameProtocol.OFFSET_CMD];
        pkt.sequence = ((frame[FrameProtocol.OFFSET_SEQ_HIGH] & 0xFF) << 8)
                     |  (frame[FrameProtocol.OFFSET_SEQ_LOW]  & 0xFF);
        int dataLen = ((frame[FrameProtocol.OFFSET_LEN_HIGH] & 0xFF) << 8)
                    |  (frame[FrameProtocol.OFFSET_LEN_LOW]  & 0xFF);
        pkt.payload = new byte[dataLen];
        System.arraycopy(frame, FrameProtocol.OFFSET_PAYLOAD,
                         pkt.payload, 0, dataLen);
        return pkt;
    }

    // 构建帧
    public byte[] toBytes() {
        int payloadLen = (payload != null) ? payload.length : 0;
        byte[] frame = new byte[FrameProtocol.MIN_FRAME_LEN + payloadLen];

        // 帧头
        frame[0] = FrameProtocol.FRAME_HEADER_HIGH;
        frame[1] = FrameProtocol.FRAME_HEADER_LOW;
        // 版本
        frame[2] = version;
        // 命令
        frame[3] = command;
        // 序号(大端)
        frame[4] = (byte) ((sequence >> 8) & 0xFF);
        frame[5] = (byte) (sequence & 0xFF);
        // 长度(大端)
        frame[6] = (byte) ((payloadLen >> 8) & 0xFF);
        frame[7] = (byte) (payloadLen & 0xFF);
        // 载荷
        if (payloadLen > 0) {
            System.arraycopy(payload, 0, frame, 8, payloadLen);
        }
        // CRC16 校验
        int crc = CRC16.calculate(frame, 0, 8 + payloadLen);
        frame[8 + payloadLen] = (byte) ((crc >> 8) & 0xFF);
        frame[9 + payloadLen] = (byte) (crc & 0xFF);

        return frame;
    }
}
```

#### 3.3.3 CRC16 校验实现

```java
/**
 * CRC16-CCITT (0x1021) 实现
 */
public final class CRC16 {

    // 查表法 —— 空间换时间，适合嵌入式/BLE场景
    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i << 8;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc = crc << 1;
                }
            }
            TABLE[i] = crc & 0xFFFF;
        }
    }

    /**
     * 计算 CRC16
     * @param data  数据
     * @param off   起始偏移
     * @param len   长度（不包含校验字段本身）
     */
    public static int calculate(byte[] data, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc = (TABLE[((crc >> 8) ^ (data[i] & 0xFF)) & 0xFF] ^ (crc << 8)) & 0xFFFF;
        }
        return crc;
    }

    /**
     * 校验帧完整性
     */
    public static boolean verify(byte[] frame) {
        int payloadLen = ((frame[FrameProtocol.OFFSET_LEN_HIGH] & 0xFF) << 8)
                       |  (frame[FrameProtocol.OFFSET_LEN_LOW]  & 0xFF);
        int dataLen = 8 + payloadLen; // 不含CRC字段
        int receivedCrc = ((frame[dataLen] & 0xFF) << 8)
                        |  (frame[dataLen + 1] & 0xFF);
        int calculatedCrc = calculate(frame, 0, dataLen);
        return receivedCrc == calculatedCrc;
    }

    private CRC16() {}
}
```

#### 3.3.4 帧解析引擎 —— 处理黏包/拆包

```java
/**
 * 帧解析器 —— 核心：从字节流中提取完整帧
 *
 * 状态机工作原理:
 * 
 *   byte来了
 *      │
 *   ┌──▼──────┐   找到0xAA    ┌─────────┐  找到0x55   ┌──────────┐
 *   │ SEEK_H1 │──────────────▶│ SEEK_H2 │────────────▶│ READ_META │
 *   └─────────┘               └─────────┘             └──────────┘
 *       ▲                                                   │
 *       │                                                   │ 收齐 HEADER+长度+CRC
 *       │        ┌─────────┐        ┌──────────┐           │
 *       └────────┤ WAIT_NEXT│◀───────│ VERIFY   │◀──────────┘
 *   (继续扫描)    └─────────┘ CRC失败 └──────────┘ CRC通过
 *                                                        │
 *                                                   emit(frame)
 */
public class FrameParser {

    private static final int MAX_FRAME_SIZE = 512; // 最大帧长

    public interface FrameCallback {
        void onFrameReceived(DataFrame frame);
    }

    private enum State {
        SEEK_H1,    // 寻找帧头第一个字节 0xAA
        SEEK_H2,    // 寻找帧头第二个字节 0x55
        READ_META,  // 读取元数据(版本+命令+序号+长度)
        READ_DATA,  // 读取载荷+校验
    }

    private State state = State.SEEK_H1;
    private final byte[] buffer = new byte[MAX_FRAME_SIZE];
    private int pos = 0;
    private int payloadLen = 0;
    private int metaBytesRead = 0;
    private int dataBytesRead = 0;

    private final FrameCallback callback;

    public FrameParser(FrameCallback callback) {
        this.callback = callback;
    }

    /**
     * 喂入原始字节 —— 支持黏包/拆包
     * 每次 onCharacteristicChanged 收到数据就调用此方法
     */
    public void feed(byte[] data) {
        for (byte b : data) {
            processByte(b);
        }
    }

    private void processByte(byte b) {
        switch (state) {
            case SEEK_H1:
                if (b == FrameProtocol.FRAME_HEADER_HIGH) {
                    buffer[0] = b;
                    pos = 1;
                    state = State.SEEK_H2;
                }
                break;

            case SEEK_H2:
                if (b == FrameProtocol.FRAME_HEADER_LOW) {
                    buffer[1] = b;
                    pos = 2;
                    metaBytesRead = 0;
                    state = State.READ_META;
                } else if (b == FrameProtocol.FRAME_HEADER_HIGH) {
                    // 0xAA 后紧跟的是另一个 0xAA(而非0x55)
                    // 说明上一个0xAA是干扰，重新开始
                    buffer[0] = b;
                    pos = 1;
                } else {
                    // 错误，重新寻找
                    state = State.SEEK_H1;
                }
                break;

            case READ_META:
                // 读取 版本(1) + 命令(1) + 序号(2) + 长度(2) = 6字节
                buffer[pos++] = b;
                metaBytesRead++;
                if (metaBytesRead == 6) {
                    // 解析长度字段（第6-7字节，相对于buffer）
                    payloadLen = ((buffer[6] & 0xFF) << 8)
                               |  (buffer[7] & 0xFF);
                    if (payloadLen < 0 || payloadLen > (MAX_FRAME_SIZE - FrameProtocol.MIN_FRAME_LEN)) {
                        // 长度异常，重新同步
                        resetToSeek();
                        return;
                    }
                    dataBytesRead = 0;
                    state = State.READ_DATA;
                }
                break;

            case READ_DATA:
                // 读取 载荷(N) + CRC16(2)
                buffer[pos++] = b;
                dataBytesRead++;
                if (dataBytesRead == payloadLen + 2) {
                    // 收齐一帧
                    if (CRC16.verify(buffer)) {
                        DataFrame frame = DataFrame.fromBytes(buffer);
                        callback.onFrameReceived(frame);
                    }
                    // CRC 失败则静默丢弃，继续寻找下一帧头
                    // （可将 buffer 中剩余数据回退处理，此处简化）
                    resetToSeek();
                }
                break;
        }
    }

    private void resetToSeek() {
        state = State.SEEK_H1;
        pos = 0;
    }
}
```

### 3.4 大数据分包传输

```java
/**
 * 分包发送器 —— 将大数据块拆分并通过 BLE 发送
 */
public class PacketSplitter {

    private final BluetoothGattCharacteristic characteristic;
    private final int mtu;

    // 单包最大载荷 = MTU - 3(ATT头) - 10(帧头)
    private final int maxPayloadPerPacket;

    public PacketSplitter(BluetoothGattCharacteristic ch, int mtu) {
        this.characteristic = ch;
        this.mtu = mtu;
        this.maxPayloadPerPacket = mtu - 3 - FrameProtocol.MIN_FRAME_LEN;
    }

    /**
     * 发送大数据（自动分包）
     * @param cmd      命令码
     * @param data     要发送的数据
     * @return 总包数
     */
    public int send(byte cmd, byte[] data) {
        int totalPackets = (data.length + maxPayloadPerPacket - 1) / maxPayloadPerPacket;

        for (int i = 0; i < totalPackets; i++) {
            int offset = i * maxPayloadPerPacket;
            int chunkLen = Math.min(maxPayloadPerPacket, data.length - offset);

            DataFrame frame = new DataFrame();
            frame.version = 0x01;
            frame.command = cmd;
            // sequence = (第i包 << 8) | 总包数
            frame.sequence = (i << 8) | (totalPackets & 0xFF);
            frame.payload = new byte[chunkLen];
            System.arraycopy(data, offset, frame.payload, 0, chunkLen);

            byte[] frameBytes = frame.toBytes();
            characteristic.setValue(frameBytes);
            // BluetoothGatt.writeCharacteristic 在队列满时会阻塞
            // 使用 Write Without Response 快，但有丢包风险 → 需要接收端 ACK
        }
        return totalPackets;
    }
}
```

```java
/**
 * 分包接收缓存 —— 收集分片，拼接完整数据
 */
public class PacketCollector {

    // 按命令码 + 序号(高字节=index,低字节=total) 缓存
    private final byte[][] fragments;
    private final int totalPackets;
    private final boolean[] received;
    private int receivedCount = 0;
    private final byte cmd;

    public PacketCollector(byte cmd, int totalPackets) {
        this.cmd = cmd;
        this.totalPackets = totalPackets;
        this.fragments = new byte[totalPackets][];
        this.received = new boolean[totalPackets];
    }

    /**
     * 接收一个分包
     * @return null = 尚未收齐；非null = 收齐后的完整数据
     */
    public byte[] accept(DataFrame frame) {
        int index = (frame.sequence >> 8) & 0xFF;

        if (index >= totalPackets) return null; // 非法序号
        if (received[index]) {
            // 重复包，忽略
            return null;
        }

        fragments[index] = frame.payload;
        received[index] = true;
        receivedCount++;

        if (receivedCount == totalPackets) {
            return assemble();
        }
        return null;
    }

    private byte[] assemble() {
        int totalSize = 0;
        for (byte[] frag : fragments) {
            totalSize += frag.length;
        }
        byte[] result = new byte[totalSize];
        int pos = 0;
        for (byte[] frag : fragments) {
            System.arraycopy(frag, 0, result, pos, frag.length);
            pos += frag.length;
        }
        return result;
    }
}
```

```java
/**
 * 分包接收管理器 —— 管理多个并发传输的分包收集
 */
public class PacketReassembler {

    // key = command + sequence_low(total)
    private final Map<Integer, PacketCollector> collectors = new HashMap<>();

    /**
     * @return null = 还没收齐；非null = 重组后的完整数据
     */
    public byte[] onFrameReceived(DataFrame frame) {
        int total = frame.sequence & 0xFF;

        if (total == 0) {
            // 单包，不需要重组
            return frame.payload;
        }

        int key = (frame.command << 8) | total;

        PacketCollector collector = collectors.get(key);
        if (collector == null) {
            collector = new PacketCollector(frame.command, total);
            collectors.put(key, collector);
        }

        byte[] fullData = collector.accept(frame);
        if (fullData != null) {
            collectors.remove(key); // 收齐，清理
        }
        return fullData;
    }
}
```

---

## 四、ACK/重传机制（可靠性保证）

```
                    发送端                                接收端
                      │                                     │
                      │  ────  DATA_PKT(seq=0) ────▶        │
                      │                                     │
                      │       (启动超时计时器)                 │
                      │                                     │
                      │  ◀──── ACK(seq=0) ────────           │
                      │                                     │
                      │  ────  DATA_PKT(seq=1) ────▶        │  ← 丢包
                      │                                     │
                      │       ⏰ 超时 200ms                   │
                      │                                     │
                      │  ────  DATA_PKT(seq=1) ────▶  (重传) │
                      │                                     │
                      │  ◀──── ACK(seq=1) ────────           │
                      │                                     │
```

### 4.1 带确认的可靠传输实现

```java
public class ReliableTransfer {

    private static final int ACK_TIMEOUT_MS = 200;
    private static final int MAX_RETRIES = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BluetoothGatt gatt;
    private final BluetoothGattCharacteristic characteristic;
    private final Map<Integer, PendingPacket> pendingPackets = new ConcurrentHashMap<>();
    private int currentSeq = 0;

    private static class PendingPacket {
        byte[] data;
        int retryCount = 0;
        Runnable timeoutTask;

        PendingPacket(byte[] data) {
            this.data = data;
        }
    }

    public ReliableTransfer(BluetoothGatt gatt, BluetoothGattCharacteristic ch) {
        this.gatt = gatt;
        this.characteristic = ch;
    }

    /**
     * 发送并等待 ACK
     */
    public void sendReliable(byte[] data) {
        int seq = currentSeq++;

        DataFrame frame = new DataFrame();
        frame.command = 0x10; // 可靠传输命令
        frame.sequence = seq;
        frame.payload = data;

        PendingPacket pending = new PendingPacket(frame.toBytes());
        pendingPackets.put(seq, pending);

        sendAndScheduleRetry(seq);
    }

    private void sendAndScheduleRetry(int seq) {
        PendingPacket pending = pendingPackets.get(seq);
        if (pending == null) return;

        // 发送
        characteristic.setValue(pending.data);
        gatt.writeCharacteristic(characteristic);

        // 设置超时重传
        Runnable retryTask = new Runnable() {
            @Override
            public void run() {
                PendingPacket p = pendingPackets.get(seq);
                if (p == null) return; // 已收到ACK
                if (p.retryCount >= MAX_RETRIES) {
                    pendingPackets.remove(seq);
                    onSendFailed(seq);
                    return;
                }
                p.retryCount++;
                sendAndScheduleRetry(seq); // 重试
            }
        };
        pending.timeoutTask = retryTask;
        handler.postDelayed(retryTask, ACK_TIMEOUT_MS);
    }

    /**
     * 收到 ACK 时调用
     */
    public void onAckReceived(int seq) {
        PendingPacket pending = pendingPackets.remove(seq);
        if (pending != null && pending.timeoutTask != null) {
            handler.removeCallbacks(pending.timeoutTask);
        }
    }

    private void onSendFailed(int seq) {
        // 通知上层：该包发送失败，需要断开重连或跳过
    }
}
```

---

## 五、GATT 层常用操作的正确姿势

### 5.1 写数据 —— 串行化

BLE 栈不支持并发写，必须串行：

```java
public class GattWriter {

    private final BluetoothGatt gatt;
    private final Queue<byte[]> writeQueue = new LinkedList<>();
    private boolean writing = false;

    public GattWriter(BluetoothGatt gatt) {
        this.gatt = gatt;
    }

    public void write(BluetoothGattCharacteristic ch, byte[] data) {
        writeQueue.offer(data);
        if (!writing) {
            startNextWrite(ch);
        }
    }

    private void startNextWrite(BluetoothGattCharacteristic ch) {
        byte[] data = writeQueue.poll();
        if (data == null) {
            writing = false;
            return;
        }
        writing = true;
        ch.setValue(data);
        gatt.writeCharacteristic(ch);
    }

    public void onWriteCompleted() {
        // 在 onCharacteristicWrite 回调中调用此方法
        // startNextWrite(ch);  // 需要持有 characteristic 引用
    }
}
```

### 5.2 启用 Notify —— 注意 CCCD

```java
/**
 * 启用特征值的 Notify 功能
 * CCCD (Client Characteristic Configuration Descriptor) UUID = 0x2902
 */
public void enableNotify(BluetoothGattCharacteristic characteristic) {
    gatt.setCharacteristicNotification(characteristic, true);

    BluetoothGattDescriptor descriptor =
        characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
    if (descriptor != null) {
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        gatt.writeDescriptor(descriptor);
    }
}
```

### 5.3 MTU 协商

```java
// Android API 21+ 支持
// 建议在 onConnectionStateChange 连接成功后立即协商
gatt.requestMtu(512);

// 回调
@Override
public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
    if (status == BluetoothGatt.GATT_SUCCESS) {
        // mtu 已协商成功，attPayload = mtu - 3
        int attPayload = mtu - 3;
        // 调整帧协议中的单帧载荷大小
    }
}
```

---

## 六、完整收发架构

```
┌───────────────────────────────────────────────────────────────┐
│                       App 业务层                              │
│              "OTA升级文件" / "固件参数同步"                     │
└───────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌───────────────────────────────────────────────────────────────┐
│                     传输管理层                                │
│  PacketReassembler (分包重组)  +  ReliableTransfer (ACK/重传)  │
└───────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌───────────────────────────────────────────────────────────────┐
│                      帧协议层                                 │
│         FrameParser (黏包处理)  +  CRC16 (校验)                │
└───────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌───────────────────────────────────────────────────────────────┐
│                     GATT 适配层                                │
│         GattWriter(串行写)  +  Notify接收(回调)                │
└───────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌───────────────────────────────────────────────────────────────┐
│                   Android BLE 栈                              │
│        BluetoothGatt / BluetoothGattCallback                 │
└───────────────────────────────────────────────────────────────┘
```

---

## 七、面试题

**Q1: BLE 传输大数据（如 OTA 升级文件 100KB）怎么保证数据正确性？**

> 分层解决：
> 1. **帧协议层**：自定义帧格式（帧头+长度+CRC16校验），通过状态机解析处理黏包
> 2. **分包层**：将大数据按 MTU-帧头开销 切分，每包带序号和总包数
> 3. **传输层**：ACK+超时重传保证每包可靠到达
> 4. **完整性校验**：收齐所有分包后，对完整数据再做整体校验（MD5/SHA256）

**Q2: BLE 的 MTU 是什么？为什么要协商？**

> MTU（Maximum Transmission Unit）是 ATT 层单次传输的最大字节数。BLE 4.0 默认 23 字节，ATT 载荷只有 20 字节。4.2+ 可通过 `requestMtu()` 协商到 512 字节，ATT 载荷最大 509 字节。协商 MTU 能显著提升吞吐量——减少分包数、减少帧开销比例、减少 Connection Event 占用。

**Q3: 为什么不用 JSON 直接传数据？**

> JSON 文本开销巨大。一个 int 值 255 用 JSON 要 3 字节（"255"），用二进制只需 1 字节。再加上 JSON key 的开销，在 MTU 只有 20 字节时一包只能传几个数值。自定义二进制协议能把每字节都利用起来。

**Q4: Write Without Response 和 Write With Response 怎么选？**

> - **Write Without Response**：快但不可靠，适合实时数据（传感器采样值），丢几个包问题不大
> - **Write With Response**：慢但可靠，ATT 层自动确认，适合控制指令
> - **实践中**：大量数据用 Write Without Response + 应用层 ACK，兼顾速度和可靠性

**Q5: 什么是连接参数更新？为什么要关注？**

> 连接参数包括 Connection Interval（通信间隔）和 Slave Latency（从机延迟）。默认值通常较保守（30-50ms）。传输大量数据时应请求更新到更快的参数（如 7.5ms interval, 0 latency），吞吐量可提升数倍。

---

## 八、常见坑

| 坑 | 现象 | 解决 |
|----|------|------|
| **133错误(GATT_ERROR)** | `onCharacteristicWrite` 返回 status=133 | 并发写导致，必须串行化写操作 |
| **MTU协商失败** | `onMtuChanged` status != 0 | 不同手机/ROM 支持程度不同，降级到 23 字节兼容模式 |
| **连接间隔过大** | 发送很慢 | 调用 `requestConnectionPriority(CONNECTION_PRIORITY_HIGH)` |
| **Android 5.0 BLE 扫描** | 启动扫描后找不到设备 | 从 6.0 开始需要 `ACCESS_FINE_LOCATION` 权限 |
| **部分 ROM 限制连接数** | 连接第4个设备失败 | 厂商限制，需适配 |
| **多个 Notify 特征值** | 回调混在一起 | 在 `onCharacteristicChanged` 中通过 UUID 区分来源 |
