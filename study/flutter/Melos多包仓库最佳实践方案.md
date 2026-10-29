# Flutter 多包仓库 Melos 最佳实践方案

> 本文基于 Melos 8 与 Dart Pub Workspaces，面向包含一个或多个 Flutter App、共享 Dart/Flutter package、插件的单仓库。版本信息核对日期：2026-10-29；写入仓库前应结合项目现有 Flutter/Dart 版本确认 SDK 约束。

## 1. 目标与适用范围

Melos 适合把多个相互依赖、需要一起开发或统一执行质量检查的 Dart/Flutter package 放在一个 Git 仓库中。它提供工作区脚本、包筛选、变更检测和版本发布流程；依赖解析与本地包互相引用由 Dart Pub Workspaces 负责。

适合：

- 多个 App 共享 UI、网络、数据或业务 package。
- Flutter 插件与示例 App、内部工具需要在同一仓库协作。
- 希望在 CI 中统一 bootstrap、分析、测试和代码生成。

不必为了使用 Melos 而拆分单 App。单包项目可以用 Melos 的版本管理能力，但对于普通单 App，除非确实需要统一脚本、版本或发布自动化，否则没有必要引入多包工作区。

## 2. 推荐方案摘要

| 项目 | 建议 |
| --- | --- |
| 工作区机制 | Dart Pub Workspaces；Melos 8 用作工作区任务与发布编排 |
| 配置位置 | 根 `pubspec.yaml`：`workspace`、`dev_dependencies.melos`、`melos` |
| 根目录用途 | 只放工作区配置、工具和文档；App 与 package 放在 `apps/`、`packages/` |
| 包发现 | Dart 3.11+ 可用 glob；较早 SDK 显式列出路径 |
| SDK 与工具版本 | 用 FVM 或团队统一的 Flutter SDK；Melos 固定在根 dev dependency 和根 lockfile |
| 依赖解析 | 一个根 `pubspec.lock`，提交到 Git；各成员声明 `resolution: workspace` |
| 包版本 | App 与内部私有包默认不发布；可发布 package 独立版本，按 Conventional Commits 生成变更记录 |
| CI | 固定 SDK，执行 `melos bootstrap`、格式检查、分析、测试；按变更范围优化属于后续项 |

## 3. 推荐目录结构

```text
my_flutter_workspace/
├── apps/
│   ├── customer_app/
│   │   ├── lib/
│   │   └── pubspec.yaml
│   └── admin_app/
│       ├── lib/
│       └── pubspec.yaml
├── packages/
│   ├── design_system/
│   ├── app_core/
│   ├── networking/
│   └── feature_account/
├── tools/
├── pubspec.yaml                 # 工作区根配置，不作为 App package
├── pubspec.lock                 # 唯一共享 lockfile，提交
├── analysis_options.yaml        # 可选：统一静态分析规则
├── melos                         # 可选：团队本地命令封装
├── .fvmrc                       # 可选：固定 Flutter SDK 版本
└── README.md
```

目录按职责划分即可，不需要为了层级漂亮而过度拆包。只有在模块有清晰 API、独立测试边界、复用需求或独立发布需求时才拆成 package。避免把每个页面、模型或很小的工具类都做成一个 package，减少依赖图和版本维护成本。

## 4. 工作区配置

### 4.1 根 `pubspec.yaml`

下面以 Dart 3.11+ 为例。`workspace` glob 支持需要 Dart 3.11；若项目使用 Dart 3.6–3.10，将 glob 换成实际路径清单。Dart 3.6 是 Pub Workspaces 的最低版本。

```yaml
name: my_flutter_workspace
publish_to: none

environment:
  sdk: ^3.9.0

workspace:
  - apps/**
  - packages/**

dev_dependencies:
  melos: ^8.10.0

melos:
  repository: https://github.com/example/my_flutter_workspace

  scripts:
    analyze:
      exec:
        command: flutter analyze
        concurrency: 1
        failFast: true

    test:
      exec:
        command: flutter test
        concurrency: 1
        failFast: true
      packageFilters:
        dirExists: test

    format-check:
      exec:
        command: dart format --output=none --set-exit-if-changed .
        concurrency: 1
        failFast: true

    verify:
      dependsOn:
        - format-check
        - analyze
        - test
```

注意：

- 版本号 `^8.10.0` 是核对日 pub.dev 上的稳定版示例。根 `pubspec.lock` 锁定实际解析版本，升级 Melos 时应在单独改动中检查迁移说明。
- Melos 8 的多包工作区配置放在根 `pubspec.yaml` 的 `melos:` 字段中，`melos.yaml` 不是此方案的主配置文件。
- Melos 8 的跨 package 脚本使用 `exec.command` 表达要运行的命令；旧版写法不应直接照搬。
- `concurrency: 1` 让 Flutter 工具日志更易读、避免多个 Flutter 命令同时争用资源。CI 速度足够稳定后可调大并行度。
- `test` 使用 `dirExists: test` 跳过没有测试目录的成员。按仓库实际情况也可再区分纯 Dart package、Flutter package 和 App。
- 如果仓库有非 Flutter 的 Dart package，需单独配置 `dart analyze` / `dart test` 脚本，不能假设每个成员都能运行 `flutter` 命令。
- `verify` 是一个脚本依赖图；Melos 会先成功运行依赖脚本，再继续后续流程。

### 4.2 工作区成员 `pubspec.yaml`

每个参与工作区解析的 App/package 都要声明 `resolution: workspace`，并保持 SDK 约束与根工作区兼容：

```yaml
name: design_system
resolution: workspace

environment:
  sdk: ^3.9.0
  flutter: '>=3.24.0'

dependencies:
  flutter:
    sdk: flutter
```

示例中的 Flutter SDK 下限仅作格式展示，应按实际使用的 Flutter API、插件和 CI SDK 版本确定。可发布 package 的 `environment.sdk` 应表示其真正支持的最低 Dart 版本，不要只为跟随本地 SDK 而随意提高。

### 4.3 内部依赖写法

同一 Pub Workspace 内的 package 使用 package 名称和版本约束依赖，不在成员的 `pubspec.yaml` 中硬编码本地 `path`：

```yaml
dependencies:
  design_system: ^1.2.0
```

这里的版本约束应与本地 package 的版本兼容；它也表达该 package 发布后消费者需要满足的 API 版本范围。避免把 `dependency_overrides` 当作常规依赖方案，它会遮蔽真实约束，且容易让本地成功、发布或 CI 失败。

## 5. 依赖与锁文件约定

1. 根目录维护唯一的 `pubspec.lock`，应用型仓库应提交该文件，保证本地和 CI 使用相同解析结果。
2. 工作区成员不要各自维护 lockfile；切换 Pub Workspace 后按 Pub 的迁移行为清理旧的成员 lockfile 和 `.dart_tool/package_config.json`。
3. 同一个 workspace 的依赖约束尽量兼容。工作区采用单一解析结果，依赖冲突会尽早暴露，但也意味着同一个依赖无法在不同成员解析成多个版本。
4. 只有确实要统一版本的基础依赖才集中管理。共享依赖配置可以减少版本漂移，但会扩大升级影响面；不要把所有三方依赖都强行统一。
5. 生产依赖放 `dependencies`，生成器、测试库和 lint 放 `dev_dependencies`。不要因 package 依赖彼此而将仅供开发的工具提升为运行时依赖。
6. CI 使用 lockfile 校验（例如 `dart pub get --enforce-lockfile`，或当前 Melos bootstrap 对应的 enforce lockfile 配置），依赖升级由明确的变更提交完成。
7. 检查 `.gitignore`，不要忽略根 `pubspec.lock`；继续忽略 `.dart_tool/`、构建目录和平台生成缓存。

## 6. 包边界与依赖方向

建议按稳定程度和复用范围拆分：

- `app_core`：跨业务稳定复用的基础类型、错误处理、日志或配置接口。
- `design_system`：主题、通用组件、图标和视觉规范。
- `networking` / `storage`：基础设施封装，向上提供清晰接口。
- `feature_*`：有独立边界、需要多个 App 复用或需要隔离测试的业务功能。
- `apps/*`：组合功能、路由、环境配置和平台集成，不把 App 入口本身抽象成公共 package。

依赖方向保持单向：基础能力不依赖具体业务，feature 可以依赖基础 package，App 组合 feature。避免 package 间循环依赖；用 `melos list --cycles` 定期检查循环依赖。共享 package 的公开 API 要稳定，内部实现尽量隐藏。

## 7. 常用工作流

首次检出或更新依赖：

```bash
flutter --version
dart --version
melos bootstrap
melos list
```

日常开发：

```bash
melos run format-check
melos run analyze
melos run test
melos run verify
```

只运行一个 package 或 App：

```bash
melos run analyze --scope=design_system
melos run test --scope=customer_app
```

定位依赖关系和循环：

```bash
melos list
melos list --graph
melos list --cycles
```

执行代码生成时限定 package，并按依赖方向排序，避免在所有成员盲目运行生成器：

```bash
melos exec --scope=feature_account -- dart run build_runner build --delete-conflicting-outputs
```

若需要在多个生成器 package 执行，使用声明好的 `exec` 脚本，并酌情配置 `orderDependents`。生成产物是否提交由项目生成策略决定，但团队必须统一并确保 CI 能重现。

## 8. CI 设计

CI 的基础流水线建议固定 Flutter SDK（使用 FVM 配置或 CI 镜像版本），缓存 Pub 下载目录，随后运行：

```bash
flutter --version
dart pub get --enforce-lockfile
melos run format-check
melos run analyze
melos run test
```

仓库若确实依赖 Melos bootstrap 行为，可在 CI 使用 `melos bootstrap --enforce-lockfile`；采用 Pub Workspaces 时按当前 Melos 版本核对该参数和配置。缓存 key 应包含 Flutter/Dart 版本、根 `pubspec.lock` 哈希和操作系统，防止 SDK 或依赖变化后复用错误缓存。

CI 约定：

- Pull Request 至少运行格式检查、静态分析和自动化测试。
- 先保证全量 CI 正确，再考虑基于变更 package 和依赖图缩小范围。过滤规则错误会漏掉受影响的下游 package。
- 代码生成类检查应能检测生成代码过期；平台插件还应按支持平台补充构建验证。
- 发布凭据只放 CI Secret；发布 job 与普通验证 job 分开，避免普通 PR 自动发布。
- CI 的 Melos 版本来自根 lockfile，不在每个 job 临时安装未固定的全局最新版本。

## 9. 版本与发布策略

先按 package 的发布方式分类：

- App：`publish_to: none`，按应用版本规则管理；不发布到 pub.dev。
- 内部共享 package：通常 `publish_to: none`，版本可按仓库约定维护，或通过 Git tag/私有制品源分发。
- 对外 package：设置可发布元数据和 LICENSE，维护独立 `CHANGELOG.md`，按 SemVer 版本发布。

对外 package 推荐独立版本（Melos 默认 `independent`），因为不同 package 的用户和 API 变化频率通常不同。只有所有 package 必须同版本发布、兼容性总是一起变化时，才选择 `fixed` lockstep 版本。

发布流程建议：

1. 使用 Conventional Commits（例如 `feat(design_system): add ...`、`fix(networking): ...`、`feat(api)!: ...`）。
2. 检查变更范围和 breaking change 标记，确认受影响 package 与依赖约束。
3. 在发布分支运行 `melos version`，审阅版本、CHANGELOG、依赖约束和 Git tag 变更。
4. 先运行 `melos publish` 默认 dry-run，检查发布内容与权限。
5. 由受控发布 job 或有权限的维护者显式运行 `melos publish --no-dry-run`。

不对纯私有 App 仓库机械启用发布命令。Melos 支持自动版本和 changelog，但自动化应建立在提交信息、包元数据和权限流程均已规范的前提上。

## 10. 迁移落地步骤

从现有多仓库或普通 Flutter 仓库迁移时，按小步执行：

1. 盘点现有 App、package、插件、依赖关系、SDK 下限、测试和代码生成方式。
2. 确认全仓库可共同使用的 Dart/Flutter SDK 版本，先解决不兼容依赖。
3. 建立根 `pubspec.yaml`，显式列出工作区成员；使用 Dart 3.11+ 后再考虑 globs。
4. 给每个成员添加 `resolution: workspace`，将内部 `path` 引用调整成 package 名称和兼容版本约束。
5. 合并 lockfile，执行一次 Pub 获取依赖，检查旧的成员 lockfile、`.dart_tool` 和 overrides。
6. 将 Melos 固定为根 dev dependency，提交根 lockfile，配置脚本并从根目录逐条验证。
7. 为 CI 固定 SDK 并运行全量 analyze/test；确认开发者 IDE 从仓库根目录打开后只有预期的分析上下文。
8. 稳定后再接入变更范围执行、版本自动化或发布，不要在迁移时同时改造所有流程。

## 11. 常见问题

| 问题 | 处理建议 |
| --- | --- |
| 每个 package 都有自己的 lockfile | 迁到 Pub Workspace 后只保留根 lockfile，并重新执行依赖解析 |
| 新 package 没被工具发现 | 检查根 workspace 路径/Glob、成员的 `resolution: workspace` 和 `pubspec.yaml` |
| package 版本约束冲突 | 统一兼容的约束，评估升级或拆分工作区；不要长期依赖 overrides 掩盖冲突 |
| CI 与本地解析版本不同 | 固定 SDK、提交根 lockfile，并启用 lockfile enforcement |
| `melos exec` 脚本照旧版语法不工作 | Melos 8 使用 `exec: { command: ..., concurrency: ... }` |
| 测试或分析在 App 与 package 间命令不兼容 | 按目录类型拆分脚本并配置 package filters |
| 每个小模块都做成 package，维护负担上升 | 回到独立 API、复用、测试隔离和版本边界这些拆分标准 |

## 12. 参考资料

- [Melos Getting Started](https://melos.invertase.dev/getting-started)
- [Melos Configuration Overview](https://melos.invertase.dev/configuration/overview)
- [Melos Workspace Scripts](https://melos.invertase.dev/configuration/scripts)
- [Melos Automated Releases](https://melos.invertase.dev/guides/automated-releases)
- [Dart Pub Workspaces](https://dart.dev/tools/pub/workspaces)
- [Melos on pub.dev](https://pub.dev/packages/melos)
