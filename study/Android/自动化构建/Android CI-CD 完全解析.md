# Android CI/CD 完全解析

一句话总结：CI/CD 是**自动化软件开发实践**，CI（持续集成）保证每次代码提交自动构建+测试，CD（持续交付/部署）保证产物自动分发到测试或生产环境。**目标是开发者只负责 git push，后续全自动。**

---

## 一、CI/CD 解决了什么核心问题？

### 传统手动发布痛点

```
开发写完代码
  → 本地手动改版本号（经常忘）
  → 手动选择签名文件（容易签错）
  → 手动打 Release 包（耗时 30min+）
  → 手动上传测试平台（网速慢）
  → 手动群里通知测试（@所有人）
  → "在我电脑上是好的"（环境差异）
```

**CI/CD 解决的四个核心问题**：

| 问题 | CI/CD 方案 |
|------|-----------|
| 人工操作易出错 | 自动化脚本，每次跑同样的流程 |
| 构建环境不一致 | 统一在 CI 机器构建（Ubuntu + 固定 SDK 版本） |
| 测试不充分 | 每次提交自动跑 Lint + UT + 静态分析 |
| 发布周期长 | 一键触发，10-15 分钟全自动完成 |

---

## 二、CI 与 CD 的职责边界

```
git push / PR merge
    │
    ▼
┌──────────────────────────────────────┐
│            CI Pipeline                │
│                                      │
│  ① 代码质量门禁                       │
│    - 静态分析 (Detekt/SpotBugs)      │
│    - Lint 检查                       │
│                                      │
│  ② 单元测试                          │
│    - JUnit / Robolectric            │
│    - 覆盖率门禁 (≥80%)              │
│                                      │
│  ③ 编译验证                          │
│    - assembleDebug 能否通过          │
│    - 产物作为 artifact 保存          │
└──────────────┬───────────────────────┘
               │ CI 全部通过
               ▼
┌──────────────────────────────────────┐
│            CD Pipeline                │
│                                      │
│  ① 版本号自动生成                     │
│    - git tag → versionName          │
│    - commit count → versionCode     │
│                                      │
│  ② 编译 Release                      │
│    - assembleRelease / bundleRelease │
│    - 签名 + 对齐 (zipalign)         │
│                                      │
│  ③ 分发                              │
│    - 内测：Firebase / 蒲公英         │
│    - 正式：Google Play / 应用市场    │
│                                      │
│  ④ 通知                              │
│    - 钉钉 / 飞书 / Slack 机器人      │
└──────────────────────────────────────┘
```

**CI 失败 → 阻断合并，修复才能继续**。CD 一般只在特定分支（main/release）或 tag 触发。

---

## 三、Android CI/CD 完整流程图

```
开发者 git push / PR
       │
       ▼
  GitHub Actions 触发
       │
       ▼
  ① Checkout + JDK + Gradle 缓存
       │
       ▼
  ② Gradle 缓存恢复（~/.gradle/caches）
       │
       ▼
  ③ 并行执行（可配置）:
     ┌─── lintDebug ─── testDebug ─── detekt ─── ═══ PR 注释结果
     │
     └─── assembleDebug ──→ artifact 存档
       │
       ▼
  ④ 判断分支:
     main / release / tag(v*)
       │
       ▼
  ⑤ CD 阶段:
     ├── 自动生成版本号 (git tag → versionName)
     ├── assembleRelease / bundleRelease
     ├── 签名 (keystore from CI secrets)
     ├── 上传 Firebase / 蒲公英 / Google Play
     └── 通知 (钉钉/Slack)
```

---

## 四、核心工具对比

| 工具 | 托管方式 | 优点 | 缺点 | 适合场景 |
|------|---------|------|------|---------|
| **GitHub Actions** | 云托管 | GitHub 深度集成，生态丰富，免费额度够 | 自定义环境受限 | 开源 / GitHub 托管团队 |
| **GitLab CI** | 云/自托管 | 一体化，配置文件直观 | 自托管需维护 | GitLab 用户，私有化部署 |
| **Jenkins** | 自托管 | 完全自定义，插件丰富 | 需维护服务器，配置复杂 | 大团队，复杂流水线 |
| **CircleCI** | 云托管 | 速度快，缓存机制强 | 免费额度少 | 追求速度的团队 |
| **Bitrise** | 云托管 | 移动端专用，可视化配置 | 价格高 | 纯移动团队 |

**面试建议选择**：GitHub Actions 说得最多，生态最熟，面试最安全。

---

## 五、GitHub Actions 完整配置（面试版）

```yaml
name: Android CI/CD

on:
  push:
    branches: [ main, develop, release/* ]
    tags: [ 'v*' ]                  # tag 触发发版
  pull_request:
    branches: [ main, develop ]

# 权限声明
permissions:
  contents: read
  checks: write                     # 允许 PR 上写 check 结果
  pull-requests: write              # 允许 PR 注释

jobs:
  # ========================================
  # Job 1: 代码质量 + 测试
  # ========================================
  quality:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Set up Gradle cache
        uses: gradle/actions/setup-gradle@v4

      - name: Lint check
        run: ./gradlew lintDebug

      - name: Unit tests
        run: ./gradlew testDebug

      - name: Upload test report
        if: always()                 # 即使失败也上传报告
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: app/build/reports/

  # ========================================
  # Job 2: 编译 Debug（验证能通过）
  # ========================================
  build:
    runs-on: ubuntu-latest
    needs: quality                   # 依赖质量检查通过
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - uses: gradle/actions/setup-gradle@v4

      - name: Build Debug APK
        run: ./gradlew assembleDebug

      - name: Upload APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/*.apk

  # ========================================
  # Job 3: Release 分发（仅 tag 触发）
  # ========================================
  release:
    if: startsWith(github.ref, 'refs/tags/v')
    runs-on: ubuntu-latest
    needs: build
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - uses: gradle/actions/setup-gradle@v4

      # 签名（keystore 存在 CI Secrets 中）
      - name: Decode Keystore
        run: |
          echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > app/keystore.jks

      - name: Build Release AAB
        run: ./gradlew bundleRelease
        env:
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}

      - name: Upload to Google Play
        uses: r0adkll/upload-google-play@v1
        with:
          serviceAccountJson: ${{ secrets.SERVICE_ACCOUNT_JSON }}
          packageName: com.example.app
          releaseFiles: app/build/outputs/bundle/release/*.aab
          track: internal           # 内部测试轨道

      - name: Notify DingTalk
        run: |
          curl -X POST ${{ secrets.DINGTALK_WEBHOOK }} \
            -H 'Content-Type: application/json' \
            -d '{"msgtype": "text", "text": {"content": "新版本已发布: ${{ github.ref_name }}"}}'
```

---

## 六、Gradle 构建优化（面试加分项）

CI 上每次构建时间直接影响开发效率，以下优化是面试高频点：

### 6.1 缓存策略

```
需要缓存的内容：
  ~/.gradle/caches/          ← 依赖 jar + 编译缓存
  ~/.gradle/wrapper/         ← Gradle Wrapper 本身
  .gradle/build-cache/       ← 构建缓存（增量编译）

不要缓存的内容：
  build/                      ← 每次都重新构建
  ~/.gradle/caches/transforms-*  ← Android 插件会变
```

### 6.2 Gradle 配置优化

```kotlin
// gradle.properties
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m -Dorg.gradle.daemon=false
org.gradle.parallel=true                    // 并行构建
org.gradle.caching=true                     // 构建缓存
org.gradle.configuration-cache=true         // 配置缓存（JDK 8+）
android.enableBuildCache=true               // Android 构建缓存
android.nonTransitiveRClass=true            // 非传递 R 类
```

**面试常问：为什么 CI 上要设 `org.gradle.daemon=false`？**

> 因为 CI 环境是短暂的容器，设置 daemon 没意义。daemon 是为了本地开发保持 JVM 热启动，CI 每次都从头来，开 daemon 反而浪费资源。

### 6.3 模块化与增量构建

```
单模块 App                 多模块 App
┌──────────────┐          ┌──────────────┐
│   app        │          │   app        │
│   (所有代码)  │          │   (壳)       │
└──────────────┘          ├──────────────┤
                          │   :core:ui   │
                          ├──────────────┤
                          │ :feature:home│
                          ├──────────────┤
                          │ :feature:login│
                          └──────────────┘

增量构建优势：
  - 只编译修改的模块
  - 未改动的模块从 build cache 恢复
  - 模块可以并行构建
  - CI 上效果更明显（缓存命中的模块直接跳过编译）
```

---

## 七、版本号自动化管理

```kotlin
// app/build.gradle.kts
fun getVersionCode(): Int {
    // Git commit count（每次提交自动递增）
    val process = Runtime.getRuntime().exec("git rev-list --count HEAD")
    return process.inputStream.bufferedReader().readLine()?.toIntOrNull() ?: 1
}

fun getVersionName(): String {
    // 获取最新 git tag，没有则默认 1.0.0
    val process = Runtime.getRuntime().exec("git describe --tags --abbrev=0")
    return process.inputStream.bufferedReader().readLine() ?: "1.0.0"
}

android {
    defaultConfig {
        versionCode = getVersionCode()
        versionName = getVersionName()
    }
}
```

```
版本号策略示例：
  git tag v1.2.3  →  versionName = "1.2.3"
  100 次 commit   →  versionCode = 100

  Google Play 要求 versionCode 递增，git commit count 天然满足。
```

---

## 八、签名方案

### 8.1 安全存储

```yaml
# CI 中 keystore 的推荐做法
1. 本地开发：keystore 存在项目根目录（gitignored）
2. CI 环境：keystore base64 编码，存在 GitHub Secrets
3. 解密：CI 运行时 decode 回文件
```

### 8.2 Gradle 签名配置

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }
}
```

---

## 九、多 flavor / 多环境构建

### 9.1 Build Variants 矩阵

```
flavorDimensions("environment", "apiLevel")

productFlavors {
    // 环境维度
    dev {
        dimension = "environment"
        applicationIdSuffix = ".dev"
        versionNameSuffix = "-dev"
    }
    staging {
        dimension = "environment"
        applicationIdSuffix = ".staging"
    }
    production {
        dimension = "environment"
    }

    // API 版本维度
    minApi21 { dimension = "apiLevel" }
    minApi26 { dimension = "apiLevel" }
}

// 产物变体（2 × 3 = 6 个）：
// DevMinApi21Debug / DevMinApi21Release
// DevMinApi26Debug / DevMinApi26Release
// StagingMinApi21Debug / ...
// ...
```

### 9.2 CI 上选择性构建

```yaml
- name: Build specific variant
  run: ./gradlew assembleDevMinApi21Debug

# 或者并行构建多个变体
- name: Build all release variants
  run: ./gradlew assembleRelease
```

---

## 十、代码质量门禁（Quality Gate）

```
Pipeline 中的质量门禁：

 ① Lint 通过                  → 无 Error（Warning 可配置阈值）
 ② 单元测试通过 + 覆盖率 ≥80%  → JaCoCo 插件统计
 ③ Detekt / SpotBugs 通过     → 无 Critical 级别 issue
 ④ PR 必须有至少 1 个 Approve  → GitHub Branch Protection
 ⑤ 所有 check 通过才可 merge   → Required status checks
```

**JaCoCo 覆盖率配置**：

```kotlin
android {
    buildTypes {
        debug {
            enableAndroidTestCoverage = true
            enableUnitTestCoverage = true
        }
    }
}

tasks.withType<JacocoReport> {
    reports {
        xml.required = true
        html.required = true
    }
}
```

---

## 十一、常见 CI 失败场景与排查

| 失败场景 | 原因 | 解决 |
|---------|------|------|
| **Lint 报错** | 废弃 API、格式违规 | 本地跑 `./gradlew lint` 先检查 |
| **测试失败** | 本地依赖环境差异 | CI 与本地用同一 Gradle 版本 |
| **编译 OOM** | CI 机器内存不足 | 限制 `org.gradle.jvmargs=-Xmx2048m` |
| **缓存失效** | Gradle 版本或 AGP 升级 | 清缓存重跑 |
| **签名失败** | Secrets 未配置 | 检查 GitHub Secrets 是否存在 |
| **Google Play 上传失败** | Service Account 权限不足 | 检查 Google Play Console 权限 |
| **网络超时** | 下载依赖慢 | 配置国内镜像源 |

---

## 十二、面试高频题

### Q1：你们项目的 CI/CD 流程是怎样的？

> 我们使用 GitHub Actions，main 分支 push 和 PR 触发。PR 触发 CI（Lint + UT + assembleDebug），跑完后结果评论到 PR。合入 main 后触发 CD（assembleRelease + 签名 + 上传蒲公英 + 钉钉通知）。发布时打 tag 触发 Google Play 上传。

### Q2：CI 上构建速度太慢怎么优化？

> 1. **Gradle 缓存**：缓存 `~/.gradle/caches/`，避免每次下载依赖
> 2. **并行构建**：`org.gradle.parallel=true` + 多模块增量构建
> 3. **按需构建**：只 compile 改动的 module（缓存命中）
> 4. **配置缓存**：`org.gradle.configuration-cache=true`
> 5. **选择快的 runner**：macOS 比 Ubuntu 慢且贵，用 ubuntu-latest
> 6. **合理切分 Job**：Lint 和 assemble 并行跑

### Q3：如何保证 CI 和本地开发环境一致？

> 1. **Gradle Wrapper**：项目内 `gradle-wrapper.properties` 锁定版本
> 2. **CI 镜像固定 JDK/SDK 版本**
> 3. **Docker 化**：用自定义 Docker 镜像包含固定工具版本
> 4. **Lock file**：Gradle 7.6+ 的 dependency locking

### Q4：Android 项目 CI 中 unit test 和 instrumented test 怎么处理？

> - **Unit test**：JVM 上跑（Robolectric），在 CI 上直接执行 `./gradlew test`
> - **Instrumented test**：需要真机或模拟器，CI 上跑比较困难。方案有：
>   - Firebase Test Lab（云真机，按量付费）
>   - macOS runner 可开模拟器（但免费额度少）
>   - 只跑 PR 级别的 smoke test，完整回归跑 nightly

### Q5：多模块项目的 CI 怎么实现增量构建？

> 利用 **Gradle Build Cache** + **模块化**：
> 1. 每个模块独立缓存
> 2. 这次只构建变更的模块及其依赖
> 3. 未变更模块直接从 cache 恢复
> 4. CI 上用 `gradle/actions/setup-gradle` 的 cache-read-only 模式避免并发写入冲突

### Q6：版本号怎么管理和递增？

> versionCode 用 `git rev-list --count HEAD`（提交次数），天然递增。
> versionName 用 `git describe --tags`（最近 tag），符合语义化版本。
> 这样开发者不用手动改版本号，每次构建自动生成。

### Q7：多 flavor 项目怎么优化 CI 构建时间？

> 不在 CI 上构建所有 flavor，只构建**改动的 flavor**。
> 或者用矩阵策略并行构建：
>
> ```yaml
> strategy:
>   matrix:
>     flavor: [dev, staging, production]
> steps:
>   - run: ./gradlew assemble${{ matrix.flavor }}Release
> ```

### Q8：GitHub Actions 和 Jenkins 你会选哪个？

> 小团队用 GitHub Actions，零维护成本。大团队用 Jenkins，可自建集群、权限细粒度、不依赖第三方。如果公司代码在 GitHub，优先 GitHub Actions——少维护一台服务器。

---

## 十三、总结速记

```
┌──────────────────────────────────────────────────────────────┐
│                   Android CI/CD 核心要点                      │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  CI（持续集成）                                              │
│    每次 git push → 自动 Lint + 自动测试 + 自动编译          │
│    价值：问题早发现，避免"在我电脑上是好的"                   │
│                                                              │
│  CD（持续交付/部署）                                        │
│    CI 通过 → 自动签名 → 自动分发到测试/生产环境             │
│    价值：一键发布，测试人员自动获取新包                       │
│                                                              │
│  典型工具链：                                                │
│    GitHub Actions / GitLab CI + Fastlane + Gradle            │
│                                                              │
│  关键配置点：                                                │
│    • 缓存策略是提速核心（~/.gradle/caches）                  │
│    • 签名密钥放 Secrets 而非代码库                          │
│    • 版本号用 git tag + commit count 自动生成                │
│    • 质量门禁：Lint + UT + 覆盖率 ≥80%                     │
│    • 多 Job 并行 + 增量构建 = 10 分钟完成                   │
│                                                              │
│  面试口诀：                                                  │
│    git push 触发流水线，能并行就并行                          │
│    缓存加速是第一，签名密钥不提交                             │
│    tag 自动发版，PR 自动检测，出了问题看日志                  │
└──────────────────────────────────────────────────────────────┘
```
