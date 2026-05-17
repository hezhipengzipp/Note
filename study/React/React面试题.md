# React 面试题

> 前端 + 手机端开发岗位

## 一、核心概念

### 1. React 是什么

一句话：React 是一个用于构建用户界面的 JavaScript 库，核心思想是**声明式 + 组件化 + 单向数据流**。

```
React 核心：
├── 声明式：描述 UI 应该是什么样，不关心怎么变
├── 组件化：UI 拆成独立、可复用的组件
├── 单向数据流：数据自上而下流动，通过 props 传递
└── Virtual DOM：内存中的 DOM 副本，diff 后最小化真实 DOM 操作
```

### 2. JSX 是什么

JSX 是 JavaScript XML，是 `React.createElement()` 的语法糖。

```jsx
// JSX
const el = <h1 className="title">Hello</h1>;

// 编译后
const el = React.createElement('h1', { className: 'title' }, 'Hello');
```

### 3. Virtual DOM 原理

```
状态变化
    ↓
生成新的 Virtual DOM 树
    ↓
Diff 新旧 Virtual DOM
    ↓
计算最小差异
    ↓
批量更新真实 DOM
```

**为什么用 Virtual DOM？**
- 真实 DOM 操作很重（重排重绘）
- Virtual DOM 是 JS 对象，操作快
- 批量更新，减少 DOM 操作次数

---

## 二、组件

### 1. 函数组件 vs 类组件

| | 函数组件 | 类组件 |
|---|---|---|
| 写法 | 函数 | class extends React.Component |
| 状态 | useState | this.state |
| 生命周期 | useEffect | componentDidMount 等 |
| this | 没有 | 有 |
| 推荐度 | 推荐 | 已过时 |

```jsx
// 函数组件（推荐）
function Counter() {
  const [count, setCount] = useState(0);
  return <button onClick={() => setCount(count + 1)}>{count}</button>;
}

// 类组件（了解即可）
class Counter extends React.Component {
  state = { count: 0 };
  render() {
    return (
      <button onClick={() => this.setState({ count: this.state.count + 1 })}>
        {this.state.count}
      </button>
    );
  }
}
```

### 2. 组件通信

```
父 → 子：props
子 → 父：回调函数
跨层级：Context
全局状态：Redux / Zustand / Jotai
```

```jsx
// 父 → 子
function Parent() {
  return <Child name="张三" />;
}
function Child({ name }) {
  return <p>{name}</p>;
}

// 子 → 父
function Parent() {
  const handleData = (data) => console.log(data);
  return <Child onSubmit={handleData} />;
}
function Child({ onSubmit }) {
  return <button onClick={() => onSubmit('hello')}>发送</button>;
}

// Context 跨层级
const ThemeContext = React.createContext('light');
function App() {
  return (
    <ThemeContext.Provider value="dark">
      <Toolbar />
    </ThemeContext.Provider>
  );
}
function Toolbar() {
  const theme = useContext(ThemeContext);  // 'dark'
  return <div>{theme}</div>;
}
```

---

## 三、Hooks

### 1. useState

```jsx
const [count, setCount] = useState(0);

// 函数式更新（依赖上一次值时用）
setCount(prev => prev + 1);
```

### 2. useEffect

```jsx
// 组件挂载 + count 变化时执行
useEffect(() => {
  console.log(count);

  return () => {
    // 清理函数（卸载或依赖变化前）
  };
}, [count]);  // 依赖数组

// 空依赖：只在挂载时执行一次
useEffect(() => {
  fetchData();
}, []);
```

### 3. useMemo / useCallback

```jsx
// useMemo：缓存计算结果
const expensiveResult = useMemo(() => {
  return heavyCompute(data);
}, [data]);

// useCallback：缓存函数引用
const handleClick = useCallback(() => {
  doSomething(id);
}, [id]);
```

### 4. useRef

```jsx
const inputRef = useRef(null);

// 访问 DOM
<input ref={inputRef} />
<button onClick={() => inputRef.current.focus()}>聚焦</button>

// 保存不触发渲染的值
const timerRef = useRef(null);
```

### 5. 自定义 Hook

```jsx
function useFetch(url) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch(url)
      .then(res => res.json())
      .then(data => {
        setData(data);
        setLoading(false);
      });
  }, [url]);

  return { data, loading };
}

// 使用
function App() {
  const { data, loading } = useFetch('/api/users');
}
```

### 6. Hooks 规则

1. 只在函数组件顶层调用，不能在循环/条件/嵌套函数中
2. 只在 React 函数组件或自定义 Hook 中调用

```jsx
// ❌ 错误
if (condition) {
  useState(0);  // 不能在条件中
}

// ✅ 正确
const [count, setCount] = useState(0);
```

---

## 四、性能优化

### 1. React.memo

```jsx
// 浅比较 props，不变则跳过渲染
const Child = React.memo(({ name }) => {
  console.log('Child render');
  return <p>{name}</p>;
});
```

### 2. useMemo / useCallback

```jsx
// 避免每次 render 创建新对象/函数
const obj = useMemo(() => ({ a: 1 }), []);
const fn = useCallback(() => {}, []);
```

### 3. 虚拟列表

```jsx
// react-window / react-virtualized
import { FixedSizeList } from 'react-window';

<FixedSizeList
  height={400}
  itemCount={10000}
  itemSize={50}
>
  {({ index, style }) => <div style={style}>Item {index}</div>}
</FixedSizeList>
```

### 4. 代码分割

```jsx
// 懒加载组件
const LazyComponent = React.lazy(() => import('./HeavyComponent'));

function App() {
  return (
    <Suspense fallback={<div>Loading...</div>}>
      <LazyComponent />
    </Suspense>
  );
}
```

---

## 五、Redux

### 核心概念

```
View → dispatch(action) → Reducer(state, action) → newState → View
```

```jsx
// 1. 定义 Reducer
function counterReducer(state = { count: 0 }, action) {
  switch (action.type) {
    case 'INCREMENT':
      return { count: state.count + 1 };
    case 'DECREMENT':
      return { count: state.count - 1 };
    default:
      return state;
  }
}

// 2. 创建 Store
const store = createStore(counterReducer);

// 3. 派发 action
store.dispatch({ type: 'INCREMENT' });

// 4. React 中使用（react-redux）
function Counter() {
  const count = useSelector(state => state.count);
  const dispatch = useDispatch();
  return <button onClick={() => dispatch({ type: 'INCREMENT' })}>{count}</button>;
}
```

### Redux Toolkit（推荐）

```jsx
import { createSlice, configureStore } from '@reduxjs/toolkit';

const counterSlice = createSlice({
  name: 'counter',
  initialState: { count: 0 },
  reducers: {
    increment: (state) => { state.count += 1 },  // 可变写法，内部用 Immer
    decrement: (state) => { state.count -= 1 },
  },
});

const store = configureStore({ reducer: counterSlice.reducer });

// 使用
const { increment, decrement } = counterSlice.actions;
```

---

## 六、React Router

```jsx
import { BrowserRouter, Routes, Route, Link, useNavigate } from 'react-router-dom';

function App() {
  return (
    <BrowserRouter>
      <nav>
        <Link to="/">首页</Link>
        <Link to="/about">关于</Link>
      </nav>
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/about" element={<About />} />
        <Route path="/user/:id" element={<User />} />
      </Routes>
    </BrowserRouter>
  );
}

// 编程式导航
function Home() {
  const navigate = useNavigate();
  return <button onClick={() => navigate('/about')}>跳转</button>;
}

// 获取路由参数
function User() {
  const { id } = useParams();
  return <p>User ID: {id}</p>;
}
```

---

## 七、常见面试题

### 1. React 18 新特性

| 特性 | 说明 |
|---|---|
| 自动批处理 | 多次 setState 自动合并为一次渲染 |
| Concurrent Mode | 并发渲染，不阻塞主线程 |
| Suspense 改进 | 支持 SSR 流式渲染 |
| useTransition | 低优先级更新 |
| useDeferredValue | 延迟更新非紧急值 |

```jsx
// useTransition
const [isPending, startTransition] = useTransition();
function handleChange(e) {
  // 紧急更新：输入框
  setInputValue(e.target.value);
  // 低优先级：搜索结果列表
  startTransition(() => {
    setSearchResults(filter(e.target.value));
  });
}
```

### 2. useEffect vs useLayoutEffect

| | useEffect | useLayoutEffect |
|---|---|---|
| 执行时机 | 渲染后（浏览器绘制后） | 渲染后（浏览器绘制前） |
| 阻塞绘制 | 不阻塞 | 阻塞 |
| 使用场景 | 数据获取、订阅 | DOM 测量、同步修改样式 |

### 3. key 的作用

```
key 帮助 React 识别哪些元素变化了（增/删/移动）

没有 key：React 按索引对比，可能导致错误更新
有 key：React 按 key 对比，精准匹配元素

❌ index 作为 key（列表顺序变化时会出 bug）
✅ 唯一 ID 作为 key
```

### 4. 为什么 useState 要用数组解构

```jsx
// 数组解构：可以自定义命名
const [count, setCount] = useState(0);
const [name, setName] = useState('');

// 如果是对象解构，命名必须固定
const { value, setValue } = useState(0);  // 多个 useState 命名冲突
```

### 5. React Fiber 是什么

```
React 16 引入的协调引擎，把渲染任务拆成小单元（Fiber），可以中断和恢复。

之前：递归遍历 Virtual DOM，一气呵成，阻塞主线程
现在：遍历过程可以暂停，让浏览器处理高优先级任务（如用户输入）

Fiber 节点 = { type, key, stateNode, child, sibling, return }
```

### 6. React 事件机制

```
React 事件不是绑定在真实 DOM 上，而是绑定在根节点（事件委托）。

<div id="root">
  <button onClick={handler}>Click</button>
</div>

实际：document.addEventListener('click', handler)
React 17+：root.addEventListener('click', handler)
```

---

## 八、React Native 相关

### 1. RN 架构

```
┌──────────────┐
│  JS Thread   │  React 组件、业务逻辑
├──────────────┤
│  Bridge      │  异步通信（JSON 序列化）
├──────────────┤
│  Native      │  原生 UI、系统 API
└──────────────┘

新架构（Fabric + TurboModules + JSI）：
  Bridge → JSI（同步调用，C++ 直接访问）
```

### 2. RN vs Flutter

| | React Native | Flutter |
|---|---|---|
| 语言 | JavaScript/TypeScript | Dart |
| UI | 原生组件 | 自绘引擎（Skia） |
| 性能 | Bridge 有开销 | 接近原生 |
| 热更新 | 支持 | 不支持（需重新编译） |
| 生态 | npm 生态 | pub 生态 |

### 3. RN 性能优化

```jsx
// 1. 使用 PureComponent / React.memo
const Item = React.memo(({ data }) => <Text>{data.name}</Text>);

// 2. FlatList 优化
<FlatList
  data={items}
  renderItem={({ item }) => <Item data={item} />}
  keyExtractor={item => item.id}
  getItemLayout={(data, index) => ({
    length: ITEM_HEIGHT,
    offset: ITEM_HEIGHT * index,
    index,
  })}  // 固定高度，跳过测量
  windowSize={5}  // 可见区域外保留的屏幕数
  maxToRenderPerBatch={10}
  removeClippedSubviews={true}  // 移除屏幕外视图
/>

// 3. 避免匿名函数
// ❌
<Button onPress={() => doSomething()} />
// ✅
const handlePress = useCallback(() => doSomething(), []);
<Button onPress={handlePress} />
```

---

## 九、TypeScript + React

```tsx
// 组件 Props 类型
interface Props {
  name: string;
  age?: number;  // 可选
  onClick: (id: string) => void;
}

function User({ name, age, onClick }: Props) {
  return <button onClick={() => onClick('1')}>{name}</button>;
}

// children 类型
interface CardProps {
  children: React.ReactNode;
}

function Card({ children }: CardProps) {
  return <div className="card">{children}</div>;
}

// 泛型组件
interface ListProps<T> {
  items: T[];
  renderItem: (item: T) => React.ReactNode;
}

function List<T>({ items, renderItem }: ListProps<T>) {
  return <div>{items.map(renderItem)}</div>;
}
```

---

## 十、浏览器渲染机制

### 1. 从 URL 到页面渲染

```
URL 输入
    ↓
DNS 解析 → IP 地址
    ↓
TCP 连接（HTTPS 还有 TLS 握手）
    ↓
发送 HTTP 请求
    ↓
服务器返回 HTML
    ↓
浏览器解析渲染
```

### 2. 渲染流程

```
HTML 字节流
    ↓
Tokenizer（词法分析）→ Token
    ↓
Parser（语法分析）→ DOM 树
    ↓
合并 CSSOM → 渲染树（Render Tree）
    ↓
Layout（布局）→ 计算每个节点的位置和大小
    ↓
Paint（绘制）→ 生成绘制记录
    ↓
Composite（合成）→ GPU 合成图层，显示到屏幕
```

```
        HTML                CSS
          ↓                  ↓
       DOM 树             CSSOM 树
          ↓                  ↓
          └──────┬───────────┘
                 ↓
            渲染树（Render Tree）
            只包含可见元素
            不包含 display:none 的元素
                 ↓
             Layout（回流）
          计算位置、大小
                 ↓
             Paint（重绘）
          颜色、阴影、文字
                 ↓
           Composite（合成）
          GPU 合成图层
```

### 3. 回流（Reflow）vs 重绘（Repaint）

| | 回流 | 重绘 |
|---|---|---|
| 触发条件 | 元素尺寸/位置变化 | 样式变化（颜色、背景） |
| 影响范围 | 可能影响其他元素 | 只影响自身 |
| 性能开销 | 大 | 小 |
| 关系 | 一定触发重绘 | 不一定触发回流 |

```javascript
// 触发回流（性能差）
element.style.width = '100px';   // 尺寸变了
element.style.height = '100px';
element.style.display = 'none'; // 布局变了

// 只触发重绘（性能较好）
element.style.color = 'red';    // 颜色变了，位置没变
element.style.backgroundColor = 'blue';
```

### 4. 减少回流

```javascript
// ❌ 多次触发回流
el.style.left = '10px';
el.style.top = '10px';
el.style.width = '100px';

// ✅ 合并为一次
el.style.cssText = 'left:10px; top:10px; width:100px;';

// ✅ 用 class 切换
el.classList.add('new-style');

// ✅ 批量读写分离
// 先读
const width = el.offsetWidth;
const height = el.offsetHeight;
// 再写
el.style.width = width + 10 + 'px';
el.style.height = height + 10 + 'px';

// ✅ 脱离文档流操作
const fragment = document.createDocumentFragment();
for (let i = 0; i < 1000; i++) {
  fragment.appendChild(document.createElement('div'));
}
document.body.appendChild(fragment);  // 一次回流

// ✅ 用 transform 代替 top/left（只触发合成，不回流）
el.style.transform = 'translate(10px, 10px)';  // 不回流
el.style.left = '10px';  // 触发回流
```

### 5. CSS 加载阻塞

```
CSS 不阻塞 DOM 解析
CSS 阻塞 DOM 渲染（渲染树需要 DOM + CSSOM）
CSS 阻塞后面 JS 的执行

JS 阻塞 DOM 解析（JS 可能操作 DOM）

最佳实践：
  <link rel="stylesheet"> 放 <head>（尽早下载）
  <script> 放 </body> 前（不阻塞 DOM 解析）
  <script async> 异步下载，下载完立即执行
  <script defer> 异步下载，DOM 解析完再执行
```

```html
<!-- 推荐 -->
<head>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <!-- DOM 内容 -->
  <script src="app.js"></script>
</body>

<!-- 或者用 async/defer -->
<script async src="analytics.js"></script>
<script defer src="app.js"></script>
```

### 6. 关键渲染路径（CRP）

```
目标：尽快渲染首屏内容

优化策略：
1. 最小化关键资源数量（内联首屏 CSS）
2. 最小化关键资源大小（压缩 CSS/JS）
3. 最小化关键路径长度（减少往返次数）

指标：
FCP（First Contentful Paint）：首次内容绘制
LCP（Largest Contentful Paint）：最大内容绘制
TTI（Time to Interactive）：可交互时间
```

---

## 十一、事件循环（Event Loop）

### 1. 核心概念

```
┌───────────────────────────┐
│        调用栈（Call Stack） │  同步代码执行
└─────────────┬─────────────┘
              ↓ 栈空时
┌─────────────▼─────────────┐
│  微任务队列（Microtask）     │  Promise.then、MutationObserver
└─────────────┬─────────────┘
              ↓ 清空微任务
┌─────────────▼─────────────┐
│  宏任务队列（Macrotask）     │  setTimeout、setInterval、IO
└───────────────────────────┘
```

### 2. 执行顺序

```
一轮事件循环：
1. 执行同步代码（调用栈）
2. 调用栈清空后，清空所有微任务
3. 执行一个宏任务
4. 清空所有微任务
5. 渲染（如果需要）
6. 回到第 3 步
```

```javascript
console.log('1');                // 同步

setTimeout(() => {
  console.log('2');              // 宏任务
}, 0);

Promise.resolve().then(() => {
  console.log('3');              // 微任务
});

console.log('4');                // 同步

// 输出：1 → 4 → 3 → 2
```

### 3. 宏任务 vs 微任务

| 宏任务 | 微任务 |
|---|---|
| setTimeout / setInterval | Promise.then / catch / finally |
| setImmediate（Node） | MutationObserver |
| I/O | queueMicrotask() |
| UI 渲染 | process.nextTick（Node） |
| requestAnimationFrame | |

### 4. 经典面试题

```javascript
async function async1() {
  console.log('async1 start');
  await async2();
  console.log('async1 end');
}

async function async2() {
  console.log('async2');
}

console.log('script start');

setTimeout(() => {
  console.log('setTimeout');
}, 0);

async1();

new Promise((resolve) => {
  console.log('promise1');
  resolve();
}).then(() => {
  console.log('promise2');
});

console.log('script end');

// 输出：
// script start
// async1 start
// async2
// promise1
// script end
// async1 end
// promise2
// setTimeout
```

### 5. Node.js 事件循环（了解）

```
Node.js 事件循环有 6 个阶段，按顺序执行：
┌───────────────────────────┐
│   timers                  │  setTimeout、setInterval
├───────────────────────────┤
│   pending callbacks       │  系统级回调
├───────────────────────────┤
│   idle, prepare           │  内部使用
├───────────────────────────┤
│   poll                    │  I/O 操作
├───────────────────────────┤
│   check                   │  setImmediate
├───────────────────────────┤
│   close callbacks         │  关闭回调
└───────────────────────────┘
每个阶段之间会清空微任务队列
```

---

## 十二、异步编程模型

### 1. 回调函数（Callback）

```javascript
// 回调地狱
getData(function(a) {
  getMoreData(a, function(b) {
    getMoreData(b, function(c) {
      getMoreData(c, function(d) {
        console.log(d);  // 嵌套太深
      });
    });
  });
});
```

### 2. Promise

```javascript
// 创建
const promise = new Promise((resolve, reject) => {
  if (success) {
    resolve(data);
  } else {
    reject(error);
  }
});

// 使用
promise
  .then(data => data * 2)
  .then(data => console.log(data))
  .catch(err => console.error(err))
  .finally(() => console.log('done'));
```

**Promise 链式调用原理：**
```javascript
// then 返回新的 Promise，所以可以链式调用
promise
  .then(a => {
    return a + 1;  // 返回值自动包装为 Promise
  })
  .then(b => {
    return b + 1;  // 继续链式
  });
```

**Promise 静态方法：**
```javascript
// 并行执行，全部完成才返回
Promise.all([p1, p2, p3]).then(([r1, r2, r3]) => {});

// 并行执行，任意一个完成就返回
Promise.race([p1, p2, p3]).then(first => {});

// 并行执行，全部完成（不管成功失败）
Promise.allSettled([p1, p2, p3]).then(results => {
  // results: [{status: 'fulfilled', value}, {status: 'rejected', reason}]
});

// 并行执行，任意一个成功就返回
Promise.any([p1, p2, p3]).then(first => {});
```

### 3. async/await

```javascript
// async 函数返回 Promise
async function fetchData() {
  const res = await fetch('/api/data');
  const data = await res.json();
  return data;
}

// 等价于
function fetchData() {
  return fetch('/api/data')
    .then(res => res.json())
    .then(data => data);
}
```

**错误处理：**
```javascript
// try/catch
async function getData() {
  try {
    const data = await fetch('/api');
    return data;
  } catch (err) {
    console.error(err);
  }
}

// 或者用 .catch
async function getData() {
  const data = await fetch('/api').catch(err => console.error(err));
  return data;
}
```

**并发执行：**
```javascript
// ❌ 串行（等一个完再执行下一个）
const a = await fetch('/a');
const b = await fetch('/b');

// ✅ 并行
const [a, b] = await Promise.all([
  fetch('/a'),
  fetch('/b'),
]);
```

### 4. Generator（了解）

```javascript
function* generator() {
  const a = yield fetch('/a');
  const b = yield fetch('/b');
  return { a, b };
}

const gen = generator();
gen.next();        // 执行到第一个 yield
gen.next(result1); // 把结果传回去，执行到第二个 yield
gen.next(result2); // 执行完
```

### 5. 三种模型对比

| | 回调 | Promise | async/await |
|---|---|---|---|
| 可读性 | 差（嵌套） | 中（链式） | 奼（同步写法） |
| 错误处理 | 困难 | .catch | try/catch |
| 链式调用 | 不支持 | 支持 | 支持 |
| 调试 | 困难 | 一般 | 方便 |

### 6. 面试追问

**Q：Promise 的 then 是同步还是异步？**
```javascript
const p = new Promise((resolve) => {
  console.log(1);        // 同步执行
  resolve();
});
p.then(() => console.log(2));  // 回调是异步的（微任务）
console.log(3);

// 输出：1 → 3 → 2
```

**Q：async/await 和 Promise 的关系？**
```
async 函数 = 返回 Promise 的函数
await = 语法糖，等价于 .then()，暂停执行直到 Promise 完成
```

**Q：如何中断 Promise？**
```javascript
// 原生 Promise 无法中断，但可以用 AbortController
const controller = new AbortController();
fetch('/api', { signal: controller.signal });
controller.abort();  // 取消请求

// 或者用竞速
function timeout(ms) {
  return new Promise((_, reject) => setTimeout(() => reject('timeout'), ms));
}
Promise.race([fetch('/api'), timeout(5000)]);
```
