# Webpack 与 Vite

> 前端构建工具面试题

---

## 一、Webpack

### 1. 核心概念

```
Webpack = 模块打包器

入口(entry) → 加载模块 → 依赖分析 → 输出(bundle)
```

```javascript
// webpack.config.js
module.exports = {
  entry: './src/index.js',       // 入口
  output: {
    path: path.resolve(__dirname, 'dist'),
    filename: 'bundle.js',       // 输出
  },
  module: {
    rules: [],                   // loader
  },
  plugins: [],                   // 插件
};
```

### 2. Loader vs Plugin

| | Loader | Plugin |
|---|---|---|
| 作用 | 转换模块内容 | 扩展构建能力 |
| 时机 | 模块加载时 | 编译全生命周期 |
| 配置 | module.rules | plugins |
| 示例 | babel-loader、css-loader | HtmlWebpackPlugin |

```javascript
module.exports = {
  module: {
    rules: [
      // Loader：把 JSX 转成 JS
      {
        test: /\.jsx?$/,
        exclude: /node_modules/,
        use: 'babel-loader',
      },
      // Loader：处理 CSS
      {
        test: /\.css$/,
        use: ['style-loader', 'css-loader'],
      },
      // Loader：处理图片
      {
        test: /\.(png|jpg|gif)$/,
        type: 'asset/resource',
      },
    ],
  },
  plugins: [
    // Plugin：生成 HTML
    new HtmlWebpackPlugin({
      template: './src/index.html',
    }),
    // Plugin：压缩 CSS
    new MiniCssExtractPlugin(),
  ],
};
```

### 3. Loader 执行顺序

```
从右到左，从下到上

module: {
  rules: [{
    test: /\.css$/,
    use: ['style-loader', 'css-loader', 'postcss-loader'],
  }]
}

执行顺序：postcss-loader → css-loader → style-loader
```

### 4. 常用 Loader

| Loader | 作用 |
|---|---|
| babel-loader | ES6+ → ES5 |
| css-loader | 解析 CSS import |
| style-loader | CSS 注入 DOM |
| sass-loader | SCSS → CSS |
| postcss-loader | CSS 后处理（自动前缀） |
| file-loader | 处理文件 |
| url-loader | 小文件转 base64 |
| ts-loader | TypeScript |
| eslint-loader | 代码检查 |
| vue-loader | Vue 单文件组件 |

### 5. 常用 Plugin

| Plugin | 作用 |
|---|---|
| HtmlWebpackPlugin | 生成 HTML 文件 |
| MiniCssExtractPlugin | CSS 提取为单独文件 |
| CleanWebpackPlugin | 清理 dist 目录 |
| DefinePlugin | 定义全局变量 |
| CopyWebpackPlugin | 复制静态文件 |
| HotModuleReplacementPlugin | 热更新 |
| TerserPlugin | 压缩 JS |
| CssMinimizerPlugin | 压缩 CSS |
| BundleAnalyzerPlugin | 打包分析 |

### 6. 构建流程

```
1. 初始化：读取配置，创建 Compiler
2. 编译：从 entry 出发，递归分析依赖
3. 构建模块：每个模块用对应 Loader 转换
4. 输出：所有模块打包成 chunk，输出 bundle

Compiler（全局，启动一次）
  └── Compilation（每次编译创建）
        ├── 模块解析
        ├── 依赖收集
        ├── 优化
        └── 生成 chunk
```

### 7. 热更新（HMR）

```
修改文件
    ↓
Webpack 检测到变化
    ↓
重新编译变更模块（不是全部）
    ↓
通过 WebSocket 推送到浏览器
    ↓
替换变更模块，保持应用状态
```

```javascript
// 开发配置
module.exports = {
  devServer: {
    hot: true,  // 开启 HMR
    port: 3000,
    proxy: {
      '/api': 'http://localhost:8080',  // 代理
    },
  },
};
```

### 8. 代码分割（Code Splitting）

```javascript
// 方式 1：入口分割
module.exports = {
  entry: {
    app: './src/index.js',
    vendor: './src/vendor.js',
  },
};

// 方式 2：动态导入（推荐）
// React.lazy
const Home = React.lazy(() => import('./pages/Home'));

// 方式 3：SplitChunksPlugin（提取公共代码）
module.exports = {
  optimization: {
    splitChunks: {
      chunks: 'all',  // 分割所有 chunk
      cacheGroups: {
        vendor: {
          test: /[\\/]node_modules[\\/]/,
          name: 'vendors',
          chunks: 'all',
        },
      },
    },
  },
};
```

### 9. Tree Shaking

```
移除未使用的代码（死代码消除）

前提：
  1. 必须用 ES Module（import/export）
  2. 生产模式自动开启

// math.js
export function add(a, b) { return a + b; }
export function minus(a, b) { return a - b; }

// index.js
import { add } from './math';  // minus 没用到，会被删除
```

```javascript
// webpack.config.js
module.exports = {
  mode: 'production',  // 自动开启 Tree Shaking
  optimization: {
    usedExports: true,
    minimize: true,
  },
};
```

### 10. 常用优化配置

```javascript
module.exports = {
  mode: 'production',

  // 1. 代码分割
  optimization: {
    splitChunks: {
      chunks: 'all',
      cacheGroups: {
        vendor: {
          test: /[\\/]node_modules[\\/]/,
          name: 'vendors',
          priority: 10,
        },
        common: {
          minChunks: 2,
          name: 'common',
          priority: 5,
        },
      },
    },
  },

  // 2. 缓存（内容变化才重新打包）
  output: {
    filename: '[name].[contenthash].js',
  },

  // 3. 压缩
  optimization: {
    minimize: true,
    minimizer: [
      new TerserPlugin({ parallel: true }),
      new CssMinimizerPlugin(),
    ],
  },

  // 4. 图片压缩
  module: {
    rules: [{
      test: /\.(png|jpg|gif)$/,
      type: 'asset',
      parser: {
        dataUrlCondition: { maxSize: 8 * 1024 }, // 8KB 以下转 base64
      },
    }],
  },

  // 5. resolve 优化
  resolve: {
    extensions: ['.js', '.jsx', '.ts', '.tsx'],
    alias: { '@': path.resolve(__dirname, 'src') },
  },
};
```

---

## 二、Vite

### 1. 核心原理

```
Webpack：打包构建，启动时要分析整个项目
Vite：利用浏览器原生 ESM，按需编译

开发模式：
  浏览器请求 /src/App.jsx
      ↓
  Vite 拦截请求，按需编译这个文件
      ↓
  返回 ESM 模块
      ↓
  浏览器遇到 import 再发请求（按需加载）

生产模式：
  用 Rollup 打包（Tree Shaking 更好）
```

### 2. 为什么 Vite 快

| | Webpack | Vite |
|---|---|---|
| 启动 | 分析所有模块 → 打包 → 启动 | 直接启动，按需编译 |
| 热更新 | 重新编译受影响的模块链 | 只编译变更模块 |
| 底层 | 自研打包 | 开发用 ESM，生产用 Rollup |
| 冷启动 | 慢（项目越大越慢） | 快（几乎秒开） |

```
Webpack 冷启动：
  entry → 递归分析依赖 → 全部打包 → 启动
  项目大时要几十秒

Vite 冷启动：
  启动 dev server → 浏览器请求时按需编译
  几乎秒开
```

### 3. Vite 配置

```javascript
// vite.config.js
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  build: {
    outDir: 'dist',
    rollupOptions: {
      output: {
        manualChunks: {
          vendor: ['react', 'react-dom'],
        },
      },
    },
  },
  resolve: {
    alias: {
      '@': '/src',
    },
  },
});
```

### 4. Vite 插件

```javascript
// 基于 Rollup 插件接口
import react from '@vitejs/plugin-react';
import { viteStaticCopy } from 'vite-plugin-static-copy';

export default defineConfig({
  plugins: [
    react(),           // React 支持
    viteStaticCopy({   // 复制静态文件
      targets: [{ src: 'assets/*', dest: 'assets' }],
    }),
  ],
});
```

### 5. 常用 Vite 插件

| 插件 | 作用 |
|---|---|
| @vitejs/plugin-react | React 支持（Babel/Fast Refresh） |
| @vitejs/plugin-vue | Vue 支持 |
| vite-plugin-svg-icons | SVG 图标 |
| vite-plugin-compression | gzip 压缩 |
| vite-plugin-mock | Mock 数据 |
| unplugin-auto-import | 自动导入 |

### 6. 依赖预构建（Pre-bundling）

```
Vite 开发模式的一个优化：

问题：node_modules 里有大量 CommonJS 模块，浏览器不支持
解决：Vite 用 esbuild 预构建这些依赖

node_modules/react（CJS）
    ↓ esbuild 预构建
node_modules/.vite/react.js（ESM）

好处：
1. 转换 CJS 为 ESM
2. 减少请求数（合并小模块）
3. 缓存（依赖不变就不重新构建）
```

---

## 三、Webpack vs Vite 对比

| | Webpack | Vite |
|---|---|---|
| 开发模式 | 先打包再启动 | 按需编译，秒开 |
| 热更新 | 慢（模块链重编译） | 快（只编译变更模块） |
| 生产打包 | 自己打包 | Rollup |
| 配置复杂度 | 高 | 低 |
| 生态 | 非常成熟 | 快速成长 |
| 适用场景 | 大型老项目 | 新项目首选 |
| Tree Shaking | 支持 | 更好（Rollup） |
| 兼容性 | 更好（可转 ES5） | 需额外配置 |

---

## 四、面试常问

### 1. Webpack 构建流程

```
1. 初始化：读取 webpack.config.js，创建 Compiler 对象
2. 编译：从 entry 出发，调用 Loader 转换模块，递归分析依赖
3. 构建模块：每个文件是一个模块，用对应的 Loader 处理
4. 输出：根据 entry 和 chunk 关系，生成 bundle 文件
```

### 2. Loader 和 Plugin 区别

```
Loader：模块转换器，把非 JS 文件转成 Webpack 能处理的模块
  - 配置在 module.rules 里
  - 本质是一个函数，接收源码，返回转换后的代码

Plugin：扩展插件，介入构建流程的各个阶段
  - 配置在 plugins 里
  - 本质是一个类，实现 apply 方法，通过钩子介入
```

### 3. Tree Shaking 原理

```
1. ES Module 静态分析（import/export 在编译时确定）
2. 标记未使用的导出
3. 压缩时移除死代码

注意：
  - 必须用 import/export，不能用 require
  - 副作用问题：package.json 里配置 sideEffects
```

```json
// package.json
{
  "sideEffects": false  // 告诉 Webpack 这个包没有副作用，可以安全删除未使用代码
}
```

### 4. Webpack 优化手段

```
1. 代码分割：SplitChunksPlugin、动态 import
2. 缓存：contenthash、babel cacheDirectory
3. 并行：thread-loader、TerserPlugin parallel
4. 缩小范围：resolve.extensions、include/exclude
5. Tree Shaking：production 模式自动开启
6. 图片优化：asset 模块，小图转 base64
7. 按需加载：React.lazy、动态 import
```

### 5. 为什么 Vite 开发模式不用打包

```
浏览器原生支持 ESM（import/export）

Vite 只需要：
1. 启动一个 dev server
2. 浏览器请求哪个文件，就编译哪个文件返回
3. 遇到 import，浏览器自动发新请求

不需要提前打包整个项目
```

### 6. esbuild 在 Vite 中的作用

```
esbuild = 极快的 JS/TS 编译器（Go 语言编写）

Vite 用 esbuild 做：
1. 依赖预构建（node_modules 转 ESM）
2. TS/JSX 转译（比 Babel 快 10-100 倍）

生产打包还是用 Rollup（兼容性更好）
```

### 7. 生产环境为什么不用 Vite 的开发模式

```
开发模式用 ESM 按需加载：
  - 每个 import 都是一个 HTTP 请求
  - 请求多了加载慢
  - 不兼容旧浏览器

生产模式用 Rollup 打包：
  - 合并模块，减少请求数
  - Tree Shaking 更彻底
  - 代码压缩、分包
```