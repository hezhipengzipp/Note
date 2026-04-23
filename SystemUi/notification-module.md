# Notification 模块深度分析文档

## 一、模块概述

Notification 模块是 SystemUI 中负责通知管理和显示的核心模块，采用 **Pipeline（管道）** 架构设计，负责：
- 接收和管理系统通知
- 通知的过滤、排序、分组
- 通知列表的 UI 展示
- 通知交互处理（点击、滑动删除、展开等）
- Heads-up（悬浮通知）显示

---

## 二、目录结构

```
statusbar/notification/
├── collection/                           # 核心通知管理 ⭐
│   ├── coalescer/                       # 通知事件合并
│   │   └── GroupCoalescer.java          # 合并相关事件
│   ├── coordinator/                     # 协调器模式实现 ⭐
│   │   ├── Coordinator.java             # 协调器接口
│   │   ├── AppOpsCoordinator.java       # AppOps 处理
│   │   ├── BubbleCoordinator.java       # 气泡通知
│   │   ├── MediaCoordinator.java        # 媒体通知
│   │   ├── PreparationCoordinator.java  # 准备工作
│   │   ├── RankingCoordinator.java      # 排名处理
│   │   └── VisualStabilityCoordinator.java # 视觉稳定性
│   ├── inflation/                       # 视图填充
│   │   └── NotificationRowBinder.java   # 行绑定接口
│   ├── listbuilder/                     # 通知列表构建 ⭐
│   │   ├── ShadeListBuilder.java        # 列表构建器
│   │   └── pluggable/                   # 可插拔组件
│   │       ├── NotifFilter.java         # 过滤器
│   │       ├── NotifComparator.java     # 比较器
│   │       ├── NotifSectioner.java      # 分区器
│   │       └── NotifPromoter.java       # 提升器
│   ├── notifcollection/                 # 通知收集事件
│   │   ├── NotifCollectionListener.java # 收集监听器
│   │   ├── NotifLifetimeExtender.java   # 生命周期扩展
│   │   └── NotifDismissInterceptor.java # 删除拦截器
│   ├── render/                          # 渲染管理
│   │   ├── GroupMembershipManager.java  # 分组成员管理
│   │   └── GroupExpansionManager.java   # 分组展开管理
│   ├── NotifCollection.java             # 通知集合 ⭐
│   ├── NotificationEntry.java           # 通知条目 ⭐
│   ├── GroupEntry.java                  # 分组条目
│   └── ListEntry.java                   # 列表条目基类
│
├── row/                                 # 单个通知行 UI ⭐
│   ├── ExpandableNotificationRow.java   # 可展开通知行 ⭐
│   ├── ExpandableNotificationRowController.java # 行控制器
│   ├── NotificationContentView.java     # 通知内容视图
│   ├── NotificationGuts.java            # 通知菜单
│   ├── NotificationMenuRow.java         # 滑动菜单
│   ├── wrapper/                         # 模板包装器
│   │   ├── NotificationViewWrapper.java # 基类
│   │   ├── NotificationBigPictureTemplateViewWrapper.java
│   │   ├── NotificationBigTextTemplateViewWrapper.java
│   │   ├── NotificationMediaTemplateViewWrapper.java
│   │   └── NotificationMessagingTemplateViewWrapper.java
│   └── ui/                              # UI 相关
│       ├── viewbinder/
│       └── viewmodel/
│
├── stack/                               # 通知栈（垂直列表）⭐
│   ├── NotificationStackScrollLayout.java # 通知栈容器 ⭐
│   ├── NotificationStackScrollLayoutController.java # 栈控制器
│   ├── StackScrollAlgorithm.java        # 滚动算法
│   ├── StackStateAnimator.java          # 状态动画
│   ├── AmbientState.java                # 环境状态
│   ├── NotificationSwipeHelper.java     # 滑动删除
│   └── ui/
│
├── shelf/                               # 通知浮窗架
│   ├── NotificationShelf.java           # 浮窗架视图
│   ├── domain/interactor/
│   └── ui/
│
├── icon/                                # 图标管理
│   ├── IconManager.java
│   └── NotificationIconContainer.java
│
├── interruption/                        # 中断处理
│   └── NotificationInterruptStateProvider.java # Heads-up 判断
│
├── logging/                             # 日志记录
│   └── NotificationLogger.java
│
├── people/                              # 人员相关通知
│
└── dagger/                              # Dagger 注入配置
```

---

## 三、核心架构（Pipeline 模式）

### 3.1 数据流全景图

```
┌─────────────────────────────────────────────────────────────┐
│           NotificationManagerService (SystemServer)          │
│                 应用发送通知到系统服务                         │
└──────────────────────────┬──────────────────────────────────┘
                           │ Binder IPC
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              NotificationListenerService                     │
│                  SystemUI 端通知监听器                        │
└──────────────────────────┬──────────────────────────────────┘
                           │ onNotificationPosted/Removed
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    GroupCoalescer                            │
│              合并短时间内的多个通知事件，提高效率               │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    NotifCollection ⭐                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ - 维护所有活动通知的集合                              │   │
│  │ - 管理通知生命周期（添加、更新、移除）                 │   │
│  │ - 管理 LifetimeExtender（延长通知显示）               │   │
│  │ - 管理 DismissInterceptor（拦截删除操作）             │   │
│  │ - 发送事件给所有 NotifCollectionListener              │   │
│  └─────────────────────────────────────────────────────┘   │
└──────────────────────────┬──────────────────────────────────┘
                           │ CollectionReadyForBuildListener
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   ShadeListBuilder ⭐                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ Pipeline 处理阶段：                                   │   │
│  │ 1. Pre-Group Filters  → 预分组过滤                   │   │
│  │ 2. Sorting            → 初步排序                     │   │
│  │ 3. Grouping           → 分组处理                     │   │
│  │ 4. Group Transform    → 分组转换                     │   │
│  │ 5. Sectioning         → 分区（按优先级等）            │   │
│  │ 6. Finalize Filtering → 最终过滤                     │   │
│  │ 7. Sorting            → 最终排序                     │   │
│  └─────────────────────────────────────────────────────┘   │
└──────────────────────────┬──────────────────────────────────┘
                           │ List<ListEntry>
                           ▼
┌─────────────────────────────────────────────────────────────┐
│            NotificationStackScrollLayout ⭐                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ - 显示通知列表                                        │   │
│  │ - 处理滚动、展开/折叠、滑动删除等交互                  │   │
│  │ - 管理 ExpandableNotificationRow 子视图              │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 Pipeline 处理阶段详解

```
输入: NotifCollection 中的所有 NotificationEntry
     ↓
┌─────────────────────────────────────┐
│ Stage 1: Pre-Group Filters          │
│ 在分组前过滤掉不需要显示的通知        │
│ 例: 低优先级、已静音、被阻止等        │
└─────────────────┬───────────────────┘
                  ↓
┌─────────────────────────────────────┐
│ Stage 2: Initial Sorting            │
│ 按时间戳、优先级等初步排序            │
└─────────────────┬───────────────────┘
                  ↓
┌─────────────────────────────────────┐
│ Stage 3: Grouping                   │
│ 将有相同 group key 的通知归入 GroupEntry │
│ 处理 summary 和 children 关系        │
└─────────────────┬───────────────────┘
                  ↓
┌─────────────────────────────────────┐
│ Stage 4: Group Transform            │
│ 处理分组的展开/折叠状态               │
│ 决定显示 summary 还是 children       │
└─────────────────┬───────────────────┘
                  ↓
┌─────────────────────────────────────┐
│ Stage 5: Sectioning                 │
│ 按优先级分区（Alerting/Silent等）    │
│ 每个 section 有自己的 header         │
└─────────────────┬───────────────────┘
                  ↓
┌─────────────────────────────────────┐
│ Stage 6: Finalize Filtering         │
│ 最终过滤，移除不应显示的条目          │
└─────────────────┬───────────────────┘
                  ↓
┌─────────────────────────────────────┐
│ Stage 7: Final Sorting              │
│ 在每个 section 内进行最终排序        │
└─────────────────┬───────────────────┘
                  ↓
输出: List<ListEntry> → 用于 UI 渲染
```

---

## 四、核心类详解

### 4.1 数据模型类

#### ListEntry（列表条目基类）

**文件**: `collection/ListEntry.java`

```java
// 抽象基类，代表列表中的一个条目
public abstract class ListEntry {
    String mKey;                    // 唯一标识
    ListAttachState mAttachState;   // 附加状态（parent、section等）

    abstract NotificationEntry getRepresentativeEntry();
}
```

#### NotificationEntry（通知条目）⭐

**文件**: `collection/NotificationEntry.java`

```java
// 代表单个通知的数据容器
public class NotificationEntry extends ListEntry {
    StatusBarNotification mSbn;     // 原始通知数据
    Ranking mRanking;               // 排名信息
    ExpandableNotificationRow mRow; // 对应的 UI 行
    DismissState mDismissState;     // 删除状态

    // 关键字段
    long mCreationTime;             // 创建时间
    boolean mIsHeadsUp;             // 是否为 heads-up
    boolean mIsSummaryWithChildren; // 是否为带子通知的 summary
}
```

#### GroupEntry（分组条目）

**文件**: `collection/GroupEntry.java`

```java
// 代表一组相关通知
public class GroupEntry extends ListEntry {
    NotificationEntry mSummary;           // 分组摘要
    List<NotificationEntry> mChildren;    // 子通知列表

    String mGroupKey;                     // 分组键
}
```

**分组关系示意**:
```
GroupEntry (Gmail)
├── Summary: "3 封新邮件"
├── Child 1: "邮件 A"
├── Child 2: "邮件 B"
└── Child 3: "邮件 C"
```

---

### 4.2 NotifCollection（通知集合）⭐

**文件**: `collection/NotifCollection.java`

**职责**:
- 维护所有活动通知的集合
- 处理通知的添加、更新、移除
- 管理 LifetimeExtender 和 DismissInterceptor
- 分发事件给 NotifCollectionListener

**核心方法**:
```java
// 通知生命周期
void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap);
void onNotificationUpdated(StatusBarNotification sbn, RankingMap rankingMap);
void onNotificationRemoved(StatusBarNotification sbn, int reason);

// 获取通知
NotificationEntry getEntry(String key);
Collection<NotificationEntry> getAllNotifs();

// 扩展点注册
void addLifetimeExtender(NotifLifetimeExtender extender);
void addDismissInterceptor(NotifDismissInterceptor interceptor);
```

**事件流程**:
```
系统发送通知
    ↓
onNotificationPosted()
    ├── 创建 NotificationEntry
    ├── 添加到集合
    ├── 触发 InitEntryEvent
    ├── 触发 BindEntryEvent
    └── 触发 EntryAddedEvent
    ↓
通知更新
    ↓
onNotificationUpdated()
    ├── 更新 Entry 数据
    └── 触发 EntryUpdatedEvent
    ↓
通知移除
    ↓
onNotificationRemoved()
    ├── 检查 LifetimeExtender（是否延长显示）
    ├── 检查 DismissInterceptor（是否拦截删除）
    ├── 从集合移除
    └── 触发 EntryRemovedEvent
```

---

### 4.3 ShadeListBuilder（列表构建器）⭐

**文件**: `collection/listbuilder/ShadeListBuilder.java`

**职责**:
- 将 NotifCollection 的通知转换成最终的显示列表
- 应用各种 Pluggable 组件进行处理

**Pluggable 组件**:

| 组件 | 接口 | 功能 |
|------|------|------|
| **NotifFilter** | `shouldFilterOut(entry)` | 过滤不需要显示的通知 |
| **NotifComparator** | `compare(a, b)` | 通知排序 |
| **NotifSectioner** | `getSection(entry)` | 确定通知所属分区 |
| **NotifPromoter** | `shouldPromoteToTopLevel(entry)` | 是否提升为顶级条目 |

**注册 Pluggable**:
```java
// 在 Coordinator 中注册
void attach(NotifPipeline pipeline) {
    pipeline.addPreGroupFilter(myFilter);
    pipeline.addFinalizeFilter(myFinalFilter);
    pipeline.addComparator(myComparator);
}
```

---

### 4.4 Coordinator（协调器）⭐

**文件**: `collection/coordinator/Coordinator.java`

**职责**:
- 向 NotifPipeline 注册监听器和可插拔组件
- 模块化处理特定类型的通知逻辑

**主要协调器**:

| 协调器 | 职责 |
|--------|------|
| **AppOpsCoordinator** | 处理前台服务和 AppOp 交互，标记通知 AppOps |
| **BubbleCoordinator** | 管理气泡通知的过滤和显示 |
| **MediaCoordinator** | 处理媒体通知的特殊逻辑 |
| **PreparationCoordinator** | 通知视图准备工作 |
| **RankingCoordinator** | 处理通知排名和优先级 |
| **VisualStabilityCoordinator** | 防止通知列表抖动，保持视觉稳定 |
| **HeadsUpCoordinator** | 管理 Heads-up 通知 |

**Coordinator 示例**:
```java
@SysUISingleton
public class MyCoordinator implements Coordinator {
    @Override
    public void attach(NotifPipeline pipeline) {
        // 注册监听器
        pipeline.addCollectionListener(mCollectionListener);

        // 注册过滤器
        pipeline.addPreGroupFilter(mFilter);
    }

    private final NotifFilter mFilter = new NotifFilter("MyFilter") {
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            return shouldHide(entry);
        }
    };
}
```

---

### 4.5 NotificationStackScrollLayout（通知栈）⭐

**文件**: `stack/NotificationStackScrollLayout.java`

**职责**:
- 管理可滚动的通知列表视图
- 处理通知的展开/折叠动画
- 实现下拉面板的滚动逻辑
- 处理手势和触摸事件

**继承关系**:
```
ViewGroup
    └── NotificationStackScrollLayout
            ├── 子视图: ExpandableNotificationRow[]
            ├── 子视图: SectionHeaderView[]
            └── 子视图: FooterView
```

**核心属性**:
```java
// 滚动状态
float mOwnScrollY;                    // 当前滚动位置
float mTopPadding;                    // 顶部内边距
float mBottomPadding;                 // 底部内边距

// 动画状态
AmbientState mAmbientState;           // 环境状态
StackStateAnimator mStateAnimator;    // 状态动画器
StackScrollAlgorithm mStackScrollAlgorithm; // 滚动算法
```

**关键方法**:
```java
// 更新通知
void updateNotifications(List<ListEntry> entries);

// 滚动控制
void scrollTo(int scrollY);
void fling(int velocityY);

// 展开控制
void setExpandedAmount(float amount);
void setShadeExpanded(boolean expanded);

// 触摸处理
boolean onTouchEvent(MotionEvent event);
boolean onInterceptTouchEvent(MotionEvent event);
```

---

### 4.6 ExpandableNotificationRow（通知行）⭐

**文件**: `row/ExpandableNotificationRow.java`

**职责**:
- 代表单个通知在列表中的视图
- 管理通知的展开/折叠状态
- 处理用户交互（点击、长按、滑动）

**视图结构**:
```
ExpandableNotificationRow
├── NotificationContentView (通知内容)
│   ├── Contracted View (收起状态)
│   ├── Expanded View (展开状态)
│   └── Heads-up View (悬浮状态)
├── NotificationGuts (通知菜单)
│   ├── 优先级设置
│   ├── 静音设置
│   └── 应用信息
└── NotificationMenuRow (滑动菜单)
    ├── Snooze 按钮
    └── Info 按钮
```

**展开状态**:
```java
public static final int INTRINSIC_HEIGHT = -1;

// 展开相关方法
void setUserExpanded(boolean expanded);
boolean isExpanded();
void setHeadsUp(boolean headsUp);
```

---

### 4.7 NotificationInterruptStateProvider（中断状态）

**文件**: `interruption/NotificationInterruptStateProvider.java`

**职责**:
- 确定通知是否应该以 Heads-up 方式显示
- 判断通知是否应该打断用户

**判断逻辑**:
```java
boolean shouldHeadsUp(NotificationEntry entry) {
    // 检查条件:
    // 1. 通知优先级足够高
    // 2. 设备未处于勿扰模式
    // 3. 用户未在使用全屏应用
    // 4. 通知未被用户静音
    // 5. 等等...
}
```

---

## 五、事件监听系统

### 5.1 NotifCollectionListener

**文件**: `collection/notifcollection/NotifCollectionListener.java`

```java
interface NotifCollectionListener {
    // 条目生命周期
    void onEntryInit(NotificationEntry entry);
    void onEntryBind(NotificationEntry entry, StatusBarNotification sbn);
    void onEntryAdded(NotificationEntry entry);
    void onEntryUpdated(NotificationEntry entry);
    void onEntryRemoved(NotificationEntry entry, int reason);
    void onEntryCleanUp(NotificationEntry entry);

    // 排名变化
    void onRankingApplied();
    void onRankingUpdate(RankingMap rankingMap);
}
```

### 5.2 事件类型

| 事件 | 触发时机 | 用途 |
|------|---------|------|
| `InitEntryEvent` | Entry 创建时 | 初始化监听器 |
| `BindEntryEvent` | Entry 绑定 SBN 时 | 更新绑定状态 |
| `EntryAddedEvent` | Entry 添加到集合 | 更新 UI |
| `EntryUpdatedEvent` | Entry 数据更新 | 刷新显示 |
| `EntryRemovedEvent` | Entry 从集合移除 | 移除 UI |
| `RankingAppliedEvent` | 排名应用后 | 重新排序 |

---

## 六、扩展点接口

### 6.1 NotifLifetimeExtender（生命周期扩展）

**用途**: 延长通知在集合中的存活时间

```java
interface NotifLifetimeExtender {
    String getName();

    // 返回 true 表示延长此通知的生命周期
    boolean shouldExtendLifetime(NotificationEntry entry, int reason);

    // 取消延长
    void cancelLifetimeExtension(NotificationEntry entry);
}
```

**使用场景**:
- Heads-up 通知正在显示时
- 通知正在播放移除动画时
- 用户正在与通知交互时

### 6.2 NotifDismissInterceptor（删除拦截）

**用途**: 拦截用户的删除操作

```java
interface NotifDismissInterceptor {
    String getName();

    // 返回 true 表示拦截此删除操作
    boolean shouldInterceptDismiss(NotificationEntry entry);

    // 取消拦截
    void cancelDismissInterception(NotificationEntry entry);
}
```

**使用场景**:
- 通知正在执行某些后台操作
- 需要用户确认的删除操作

### 6.3 NotifFilter（过滤器）

```java
abstract class NotifFilter {
    String mName;

    // 返回 true 表示过滤掉此通知（不显示）
    abstract boolean shouldFilterOut(NotificationEntry entry, long now);
}
```

### 6.4 NotifComparator（比较器）

```java
abstract class NotifComparator {
    String mName;

    // 比较两个通知的排序
    abstract int compare(ListEntry a, ListEntry b);
}
```

---

## 七、通知处理流程

### 7.1 通知添加流程

```
应用调用 NotificationManager.notify()
    ↓
NotificationManagerService 处理
    ↓
NotificationListenerService.onNotificationPosted()
    ↓
GroupCoalescer.onNotificationPosted()
    ├── 合并短时间内的多个事件
    └── 延迟分发（减少 UI 抖动）
    ↓
NotifCollection.onNotificationPosted()
    ├── 创建 NotificationEntry
    ├── 添加到 mNotificationSet
    ├── 触发 onEntryInit()
    ├── 触发 onEntryBind()
    └── 触发 onEntryAdded()
    ↓
各 Coordinator 处理
    ├── 检查是否需要 Heads-up
    ├── 检查是否为气泡通知
    └── 更新排名信息
    ↓
ShadeListBuilder.buildList()
    ├── 应用 PreGroupFilters
    ├── 排序
    ├── 分组
    ├── 应用 FinalizeFilters
    └── 生成最终列表
    ↓
NotificationStackScrollLayoutController.updateNotifications()
    └── 更新 UI
```

### 7.2 通知点击流程

```
用户点击通知
    ↓
ExpandableNotificationRow.onClick()
    ↓
NotificationClickNotifier.onNotificationClick()
    ├── 记录点击事件
    └── 通知监听器
    ↓
NotificationActivityStarter.startNotificationIntent()
    ├── 关闭 Heads-up
    ├── 折叠通知面板
    ├── 执行 PendingIntent
    └── 启动动画
```

### 7.3 通知滑动删除流程

```
用户滑动通知
    ↓
NotificationSwipeHelper.onTouch()
    ├── 追踪滑动距离
    └── 检查是否超过阈值
    ↓
onDismiss()
    ↓
NotifCollection.dismissNotification()
    ├── 检查 DismissInterceptor
    ├── 调用 NotificationManager.cancel()
    └── 触发 onEntryRemoved()
    ↓
UI 移除动画
```

---

## 八、分组通知处理

### 8.1 分组结构

```
// 分组通知的 key 结构
Group Key = "pkg|user|groupKey"

// 示例
GroupEntry (key: "com.google.android.gm|0|emails")
├── Summary: NotificationEntry (key: "...|0|emails|summary")
├── Child 1: NotificationEntry (key: "...|0|emails|1")
├── Child 2: NotificationEntry (key: "...|0|emails|2")
└── Child 3: NotificationEntry (key: "...|0|emails|3")
```

### 8.2 分组展开逻辑

```java
// GroupExpansionManager 接口
interface GroupExpansionManager {
    // 分组是否展开
    boolean isGroupExpanded(NotificationEntry entry);

    // 切换展开状态
    void toggleGroupExpansion(NotificationEntry entry);

    // 收起所有分组
    void collapseAllGroups();
}
```

**展开状态显示**:
```
收起状态:
┌─────────────────────────┐
│ Gmail - 3 封新邮件       │  ← Summary
└─────────────────────────┘

展开状态:
┌─────────────────────────┐
│ Gmail - 3 封新邮件       │  ← Summary
├─────────────────────────┤
│   邮件 A                 │  ← Child 1
├─────────────────────────┤
│   邮件 B                 │  ← Child 2
├─────────────────────────┤
│   邮件 C                 │  ← Child 3
└─────────────────────────┘
```

---

## 九、Heads-up 通知

### 9.1 触发条件

```java
// NotificationInterruptStateProvider 判断逻辑
boolean shouldHeadsUp(NotificationEntry entry) {
    // 必须满足:
    if (!entry.isHighPriority()) return false;
    if (isDoNotDisturbMode()) return false;
    if (isFullScreenActivity()) return false;
    if (entry.isSnoozed()) return false;

    // 以下情况强制 Heads-up:
    if (entry.isCall()) return true;
    if (entry.isMessaging()) return true;
    if (entry.hasFullScreenIntent()) return true;

    return true;
}
```

### 9.2 显示位置

```
状态栏
┌─────────────────────────────────────┐
│ 12:00              WiFi  LTE  100%  │
└─────────────────────────────────────┘
         ↓ Heads-up 出现在这里
┌─────────────────────────────────────┐
│ ┌─────────────────────────────────┐ │
│ │  [App Icon]  Title              │ │
│ │  Content text here...           │ │
│ └─────────────────────────────────┘ │
└─────────────────────────────────────┘
         ↓ 正常内容区域
```

---

## 十、关键接口速查

### 10.1 NotifPipeline 接口

```java
interface NotifPipeline {
    // 获取通知
    Collection<NotificationEntry> getAllNotifs();

    // 注册监听器
    void addCollectionListener(NotifCollectionListener listener);

    // 注册可插拔组件
    void addPreGroupFilter(NotifFilter filter);
    void addFinalizeFilter(NotifFilter filter);
    void addPromoter(NotifPromoter promoter);
    void addComparator(NotifComparator comparator);

    // 注册扩展点
    void addLifetimeExtender(NotifLifetimeExtender extender);
    void addDismissInterceptor(NotifDismissInterceptor interceptor);
}
```

### 10.2 GroupMembershipManager 接口

```java
interface GroupMembershipManager {
    // 判断分组状态
    boolean isGroupSummary(NotificationEntry entry);
    boolean isChildInGroup(NotificationEntry entry);

    // 获取分组信息
    NotificationEntry getGroupSummary(NotificationEntry entry);
    List<NotificationEntry> getChildren(ListEntry entry);
}
```

---

## 十一、调试方法

### 11.1 Dump 命令

```bash
# 通知状态
adb shell dumpsys notification

# SystemUI 通知模块
adb shell dumpsys systemui NotifCollection
adb shell dumpsys systemui NotificationStackScrollLayout

# Heads-up 状态
adb shell dumpsys systemui HeadsUp
```

### 11.2 日志标签

```java
TAG = "NotifCollection"
TAG = "ShadeListBuilder"
TAG = "NotificationStackScrollLayout"
TAG = "ExpandableNotificationRow"
TAG = "HeadsUpManager"
TAG = "NotificationInterruptState"
```

### 11.3 开发者选项

```
设置 → 开发者选项 → 显示通知优先级
```

---

## 十二、设计模式应用

| 模式 | 应用 |
|------|------|
| **Pipeline** | ShadeListBuilder 的多阶段处理 |
| **Observer** | NotifCollectionListener、各种 Callback |
| **Strategy** | NotifFilter、NotifComparator、NotifSectioner |
| **Coordinator** | 各种 Coordinator 模块化处理 |
| **Template Method** | NotificationViewWrapper 子类 |
| **Composite** | GroupEntry 包含多个 NotificationEntry |

---

## 十三、常见修改场景

| 需求 | 修改位置 |
|------|---------|
| 添加通知过滤规则 | 创建新的 `NotifFilter` 并在 Coordinator 中注册 |
| 修改通知排序 | 创建新的 `NotifComparator` |
| 自定义通知行样式 | `ExpandableNotificationRow` + 布局文件 |
| 修改 Heads-up 条件 | `NotificationInterruptStateProvider` |
| 添加通知分区 | 创建新的 `NotifSectioner` |
| 处理特定类型通知 | 创建新的 `Coordinator` |
| 修改滑动删除行为 | `NotificationSwipeHelper` |
| 修改分组展开逻辑 | `GroupExpansionManagerImpl` |

---

## 十四、关键文件速查表

| 文件 | 功能 | 优先级 |
|------|------|--------|
| `NotifCollection.java` | 通知集合管理 | ⭐⭐⭐⭐⭐ |
| `NotificationEntry.java` | 通知数据模型 | ⭐⭐⭐⭐⭐ |
| `ShadeListBuilder.java` | 列表构建 Pipeline | ⭐⭐⭐⭐⭐ |
| `NotificationStackScrollLayout.java` | 通知栈容器 | ⭐⭐⭐⭐⭐ |
| `ExpandableNotificationRow.java` | 单条通知视图 | ⭐⭐⭐⭐ |
| `Coordinator.java` | 协调器接口 | ⭐⭐⭐⭐ |
| `NotifCollectionListener.java` | 事件监听接口 | ⭐⭐⭐⭐ |
| `NotifFilter.java` | 过滤器接口 | ⭐⭐⭐ |
| `GroupEntry.java` | 分组数据模型 | ⭐⭐⭐ |
| `NotificationInterruptStateProvider.java` | Heads-up 判断 | ⭐⭐⭐ |

---

## 十五、相关文档

- `project-guide.md` - 项目整体指南
- `statusbar-module.md` - StatusBar 模块分析
- `volume-module.md` - Volume 模块分析

---

*文档更新日期: 2026-01-28*