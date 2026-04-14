# View 绘制流程与事件分发详解

## 一、View 绘制流程

### 触发入口

```
┌─────────────────────────────────────────────┐
│           触发绘制的两种方式                   │
│                                              │
│  invalidate()          requestLayout()       │
│      │                      │               │
│      │ 只标记 dirty          │ 标记需要重新    │
│      │ 不走 measure/layout   │ measure+layout │
│      ↓                      ↓               │
│   跳过 measure          measure()           │
│   跳过 layout           layout()            │
│   执行 draw()           draw()              │
└─────────────────────────────────────────────┘
              ↓
    ViewRootImpl.scheduleTraversals()
              ↓
    Choreographer 注册 Vsync 回调
              ↓
    Vsync 信号到达（16.6ms）
              ↓
    performTraversals()
```

---

### measure 流程

```
performMeasure(childWidthMeasureSpec, childHeightMeasureSpec)
         ↓
   View.measure(widthMeasureSpec, heightMeasureSpec)
         ↓
   ┌─────────────────────────────────────────┐
   │           MeasureSpec（32位int）         │
   │  高2位：mode      低30位：size           │
   │                                         │
   │  EXACTLY    → match_parent / 具体dp值   │
   │  AT_MOST    → wrap_content              │
   │  UNSPECIFIED→ ScrollView 对子View        │
   └─────────────────────────────────────────┘
         ↓
   onMeasure(widthMeasureSpec, heightMeasureSpec)
         ↓
   ┌─── 是 ViewGroup？ ───┐
   │ YES                  │ NO
   ↓                      ↓
遍历子 View           setMeasuredDimension()
measureChild()           存储测量结果
   ↓
子 View.measure()
   ↓（递归）
setMeasuredDimension()
```

---

### layout 流程

```
performLayout()
      ↓
View.layout(l, t, r, b)
      ↓
  setFrame(l, t, r, b)    // 保存 left/top/right/bottom
      ↓
  onLayout(changed, l, t, r, b)
      ↓
  ┌─── 是 ViewGroup？ ───┐
  │ YES                  │ NO
  ↓                      ↓
遍历子 View           什么都不做
child.layout()        （叶子节点无子View）
  ↓（递归）
确定每个 View 的绝对位置
```

---

### draw 流程

```
performDraw()
      ↓
View.draw(canvas)
      ↓
  ① drawBackground(canvas)       // 绘制背景
      ↓
  ② onDraw(canvas)               // 绘制自身内容 ← 重写这里
      ↓
  ③ dispatchDraw(canvas)         // 绘制子 View（ViewGroup 实现）
      ↓                               ↓
  ④ onDrawForeground(canvas)    child.draw(canvas)（递归）
      ↓
  ⑤ drawDefaultFocusHighlight()  // 焦点高亮

        硬件加速（默认）              软件绘制
        ─────────────────────────────────────
        构建 DisplayList             Canvas 直接操作
        RenderThread GPU 渲染         CPU 主线程渲染
        只更新脏区域 DisplayList       重绘整个 View
```

---

## 二、事件分发流程

### 传递链路

```
用户手指触摸屏幕
      ↓
InputManagerService（系统进程）
      ↓  socket
ViewRootImpl.WindowInputEventReceiver
      ↓
ViewRootImpl.processPointerEvent()
      ↓
DecorView.dispatchTouchEvent()
      ↓
Activity.dispatchTouchEvent()
      ↓
PhoneWindow.superDispatchTouchEvent()
      ↓
DecorView.superDispatchTouchEvent()
      ↓
ViewGroup.dispatchTouchEvent()   ← 事件分发核心从这里开始
```

---

### ViewGroup 分发核心逻辑

```
ViewGroup.dispatchTouchEvent(event)
         ↓
  ┌──────────────────────────────────┐
  │  onInterceptTouchEvent(event)    │
  │  是否拦截？                       │
  └──────────────────────────────────┘
         ↓
    ┌────┴────┐
   YES        NO
    ↓          ↓
ViewGroup    遍历子 View
自己处理      找到被点击的子 View
    ↓               ↓
onTouchEvent   child.dispatchTouchEvent()
                     ↓
              ┌──────┴──────┐
             YES             NO
         子View消费        回传给
           return true    ViewGroup.onTouchEvent()
```

---

### View 自身处理逻辑

```
View.dispatchTouchEvent(event)
         ↓
  ┌──────────────────────────────────────┐
  │  有 OnTouchListener 且 enabled？     │
  └──────────────────────────────────────┘
         ↓
    ┌────┴────┐
   YES        NO
    ↓          ↓
listener    onTouchEvent(event)
.onTouch()        ↓
    ↓       ┌─────────────────────────┐
 return     │ MotionEvent.ACTION_UP   │
  true      │   → performClick()     │
    ↓       │   → onClickListener    │
  消费       └─────────────────────────┘
事件不再
向下传递
```

---

### 返回值影响（最关键）

```
返回 true  → 消费事件，事件到此为止，不再向上回传
返回 false → 不消费，交给父 View 的 onTouchEvent 处理

┌──────────────────────────────────────────────────────┐
│                  事件回传路径                          │
│                                                      │
│  ViewGroup.dispatch → child.dispatch                 │
│                            ↓ false                   │
│                       child.onTouch → false          │
│                            ↓                         │
│                  ViewGroup.onTouch                   │
│                            ↓ false                   │
│                   Activity.onTouch                   │
│                            ↓ false                   │
│                        事件丢弃                       │
└──────────────────────────────────────────────────────┘
```

---

### 事件序列规则（重点）

```
一次完整手势：DOWN → MOVE → MOVE → ... → UP

规则1：DOWN 事件决定消费者（路由建立）
─────────────────────────────────────────
  DOWN 被子 View A 消费（return true）
  → mFirstTouchTarget = View A（记录消费者）
  → 后续 MOVE/UP 依然会调用 onInterceptTouchEvent()
  → 若父 ViewGroup 不拦截，事件路由给 View A
  → 若父 ViewGroup 拦截，则触发规则2

  // ViewGroup 源码关键判断
  if (actionMasked == ACTION_DOWN || mFirstTouchTarget != null) {
      if (!disallowIntercept) {
          intercepted = onInterceptTouchEvent(ev); // MOVE 时依然调用！
      }
  }

规则2：规则1 的例外——ViewGroup 在 MOVE 时主动拦截
─────────────────────────────────────────
  DOWN 已由子 View 消费，mFirstTouchTarget 已建立
  MOVE 时 ViewGroup.onInterceptTouchEvent() 返回 true
         ↓
  给子 View 发一个 ACTION_CANCEL（通知子View放弃）
  mFirstTouchTarget 清空
  后续事件由 ViewGroup 自己的 onTouchEvent 处理

  规则1 描述"正常路由"，规则2 描述"ViewGroup 强行打断"
  两者不冲突，规则2 是规则1 的例外情况

规则3：requestDisallowInterceptTouchEvent——锁死规则2
─────────────────────────────────────────
  子 View 调用 parent.requestDisallowInterceptTouchEvent(true)
  → 设置 FLAG_DISALLOW_INTERCEPT 标志位
  → 源码中 disallowIntercept = true，跳过 onInterceptTouchEvent()
  → ViewGroup 无法再触发规则2
  → 子 View 独占后续所有事件
  → 常用于解决滑动冲突
```

---

### 滑动冲突解决方案

```
场景：外层 ViewPager + 内层 RecyclerView

外部拦截法（父 View 处理）          内部拦截法（子 View 处理）
──────────────────────────────────────────────────────────
重写父 ViewGroup                   重写子 View
onInterceptTouchEvent()            dispatchTouchEvent()

ACTION_DOWN  → return false        ACTION_DOWN
ACTION_MOVE  →                     → requestDisallowInterceptTouchEvent(true)
  if 水平滑动 → return true（拦截）
  if 垂直滑动 → return false        ACTION_MOVE
ACTION_UP    → return false         → if 需要父处理
                                      requestDisallowInterceptTouchEvent(false)
```

---

### 完整流程一张图

```
Activity
  dispatchTouchEvent()
        │ 不消费则回传
        ▼
  PhoneWindow / DecorView
        │
        ▼
  ViewGroup.dispatchTouchEvent()
        │
        ├──► onInterceptTouchEvent() ──► true ──► 自己的 onTouchEvent()
        │                                               │
        │         false                                 │ false → 向上回传
        ▼                                               │
  child.dispatchTouchEvent()                            │
        │                                               │
        ├──► OnTouchListener.onTouch() ──► true → 消费  │
        │                                               │
        │         false                                 │
        ▼                                               │
  child.onTouchEvent()                                  │
        │                                               │
        ├── true  → 消费，ACTION_UP 触发 onClick()       │
        │                                               │
        └── false → 回传给父 ViewGroup.onTouchEvent() ───┘
```
