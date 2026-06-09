# Flutter drift 使用详解

---

## 一、drift 是什么

`drift` 是 Flutter / Dart 生态里一个高层数据库库，底层通常还是用 `sqlite`，但它在上层补了几件很重要的能力：

- 类型安全的表定义和查询结果映射
- 自动代码生成，减少手写 SQL 和样板代码
- `Stream` 化的数据监听，数据变更后 UI 可自动刷新
- 事务、迁移、关联查询、DAO 分层支持更完整

一句话理解：

> `drift` = “SQLite 能力” + “Dart 类型系统” + “响应式数据流”

如果你以前只用过 `sqflite`，可以把它理解为：

- `sqflite` 更像“直接调 SQLite”
- `drift` 更像“给 SQLite 加了一层 ORM + 响应式封装”

---

## 二、适合什么场景

`drift` 适合这些需求：

- 本地有结构化数据要持久化
- 页面需要自动响应数据库变化
- 查询比较多，手写 SQL 容易乱
- 需要事务、分页、过滤、关联查询
- 希望数据层更容易测试和维护

如果只是存几个简单 key-value：

- 用 `shared_preferences` 更轻

如果只是偶尔写几条 SQL，不需要监听刷新：

- 用 `sqflite` 也可以

---

## 三、先记住核心组成

### 3.1 表定义

用 Dart 类声明表结构：

```dart
class Todos extends Table {
  IntColumn get id => integer().autoIncrement()();
  TextColumn get title => text()();
  BoolColumn get completed => boolean().withDefault(const Constant(false))();
  DateTimeColumn get createdAt => dateTime().withDefault(currentDateAndTime)();
}
```

---

### 3.2 数据库类

所有表、DAO、迁移都挂到数据库类上：

```dart
@DriftDatabase(tables: [Todos])
class AppDatabase extends _$AppDatabase {
  AppDatabase() : super(_openConnection());

  @override
  int get schemaVersion => 1;
}
```

---

### 3.3 代码生成

`drift` 不会只靠运行时反射，而是通过构建期生成类型安全代码。

常见命令：

```bash
flutter pub run build_runner build
```

开发时更常见：

```bash
flutter pub run build_runner watch --delete-conflicting-outputs
```

生成后你会得到：

- 表对应的 DataClass
- Companion 类
- 查询辅助代码
- 数据库基类实现

---

### 3.4 Companion

插入和更新时通常不用直接传 DataClass，而是传 Companion：

```dart
await into(todos).insert(
  TodosCompanion.insert(
    title: 'Learn drift',
  ),
);
```

为什么有 Companion：

- 插入时有些字段可省略
- 更新时要区分“传 null”还是“这个字段不更新”

---

## 四、最小接入方式

### 4.1 依赖

常见依赖组合：

```yaml
dependencies:
  drift: ^2.x.x
  sqlite3_flutter_libs: ^0.x.x
  path_provider: ^2.x.x
  path: ^1.x.x

dev_dependencies:
  drift_dev: ^2.x.x
  build_runner: ^2.x.x
```

如果是移动端，很多项目会配 `NativeDatabase`。

---

### 4.2 打开数据库

```dart
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:drift/native.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

LazyDatabase _openConnection() {
  return LazyDatabase(() async {
    final dir = await getApplicationDocumentsDirectory();
    final file = File(p.join(dir.path, 'app.sqlite'));
    return NativeDatabase.createInBackground(file);
  });
}
```

这里的重点不是“把文件打开”，而是：

- 懒加载，真正用到时再初始化
- 数据库存文件落在应用沙盒
- `createInBackground` 可以把一部分工作放到后台线程，减少主线程压力

---

## 五、表定义怎么写

### 5.1 一个完整例子

```dart
class Users extends Table {
  IntColumn get id => integer().autoIncrement()();

  TextColumn get name => text().withLength(min: 1, max: 50)();

  TextColumn get email => text().unique()();

  BoolColumn get isVip => boolean().withDefault(const Constant(false))();

  DateTimeColumn get createdAt => dateTime().withDefault(currentDateAndTime)();
}
```

可以看到它本质是在声明：

- 字段类型
- 约束
- 默认值
- 是否自增 / 唯一

---

### 5.2 常见列类型

- `integer()`
- `text()`
- `boolean()`
- `dateTime()`
- `real()`
- `blob()`

SQLite 本身类型系统没那么强，而 `drift` 在 Dart 侧把类型约束补强了。

---

## 六、增删改查怎么写

### 6.1 插入

```dart
Future<int> addTodo(String title) {
  return into(todos).insert(
    TodosCompanion.insert(title: title),
  );
}
```

返回值通常是插入行的主键 id。

---

### 6.2 查询全部

```dart
Future<List<Todo>> getAllTodos() {
  return select(todos).get();
}
```

`get()` 返回一次性结果。

---

### 6.3 条件查询

```dart
Future<List<Todo>> getCompletedTodos() {
  return (select(todos)..where((t) => t.completed.equals(true))).get();
}
```

这种 DSL 最终还是会编译成 SQL，只是调用方拿到的是类型安全 API。

---

### 6.4 更新

```dart
Future<bool> toggleTodo(int id, bool value) {
  return (update(todos)..where((t) => t.id.equals(id))).write(
    TodosCompanion(
      completed: Value(value),
    ),
  );
}
```

这里的 `Value(value)` 很关键，它表示：

- 这个字段要参与更新

如果你写的是 `Value.absent()`，表示：

- 这个字段这次不更新

---

### 6.5 删除

```dart
Future<int> deleteTodo(int id) {
  return (delete(todos)..where((t) => t.id.equals(id))).go();
}
```

---

## 七、响应式监听是 drift 最强的一点

### 7.1 watch 和 get 的区别

```dart
Future<List<Todo>> once = select(todos).get();
Stream<List<Todo>> stream = select(todos).watch();
```

- `get()`：查一次就结束
- `watch()`：后续相关数据变化会继续推送

这非常适合 Flutter 页面。

---

### 7.2 页面里直接监听

```dart
Stream<List<Todo>> watchTodos() {
  return select(todos).watch();
}
```

配合 `StreamBuilder`：

```dart
StreamBuilder<List<Todo>>(
  stream: db.watchTodos(),
  builder: (context, snapshot) {
    final todos = snapshot.data ?? [];
    return ListView.builder(
      itemCount: todos.length,
      itemBuilder: (_, index) => Text(todos[index].title),
    );
  },
)
```

这时候数据库不仅是存储层，也承担了一部分“状态源”的角色。

---

### 7.3 为什么它能自动刷新

核心原因不是 Flutter 魔法，而是 `drift` 自己维护了查询依赖。

你可以理解成这几步：

1. `watch()` 注册了一条查询订阅
2. `drift` 知道这条查询依赖哪些表
3. 当插入 / 更新 / 删除发生时，它会标记相关表已变更
4. 依赖这些表的查询会重新执行
5. 新结果通过 `Stream` 推给 UI

所以它本质上是：

> 表变更通知 + 查询重跑 + Stream 推送

注意：

- 它不是只在“当前结果真的变了”时才一定刷新
- 它更偏向“相关表发生变化，就重新计算这条查询”

---

## 八、DAO 分层怎么用

当数据库逻辑多起来，不要都堆在 `AppDatabase` 里。

### 8.1 定义 DAO

```dart
@DriftAccessor(tables: [Todos])
class TodoDao extends DatabaseAccessor<AppDatabase> with _$TodoDaoMixin {
  TodoDao(AppDatabase db) : super(db);

  Stream<List<Todo>> watchAll() => select(todos).watch();

  Future<int> insertTodo(String title) {
    return into(todos).insert(TodosCompanion.insert(title: title));
  }
}
```

然后挂到数据库里：

```dart
@DriftDatabase(
  tables: [Todos],
  daos: [TodoDao],
)
class AppDatabase extends _$AppDatabase {
  AppDatabase() : super(_openConnection());

  @override
  int get schemaVersion => 1;
}
```

---

### 8.2 为什么推荐 DAO

因为它能把职责拆开：

- `Table` 负责结构
- `DAO` 负责查询逻辑
- `Database` 负责统一注册和迁移

这样后期表多了不会失控。

---

## 九、事务怎么写

多个操作要么都成功，要么都失败，就用事务。

```dart
Future<void> completeAndLog(int todoId) async {
  await transaction(() async {
    await (update(todos)..where((t) => t.id.equals(todoId))).write(
      const TodosCompanion(
        completed: Value(true),
      ),
    );

    await into(logs).insert(
      LogsCompanion.insert(message: 'todo completed'),
    );
  });
}
```

适合：

- 主表 + 从表一起写
- 批量导入
- 余额扣减 / 状态切换这类一致性要求高的操作

---

## 十、关联查询怎么理解

### 10.1 两张表

```dart
class Categories extends Table {
  IntColumn get id => integer().autoIncrement()();
  TextColumn get name => text()();
}

class Todos extends Table {
  IntColumn get id => integer().autoIncrement()();
  TextColumn get title => text()();
  IntColumn get categoryId => integer().references(Categories, #id)();
}
```

---

### 10.2 join 查询

```dart
Future<List<(Todo, Category)>> queryTodoWithCategory() async {
  final query = select(todos).join([
    innerJoin(categories, categories.id.equalsExp(todos.categoryId)),
  ]);

  final rows = await query.get();
  return rows.map((row) {
    return (
      row.readTable(todos),
      row.readTable(categories),
    );
  }).toList();
}
```

重点不是语法，而是思路：

- `drift` 没有把关系型数据库“藏起来”
- 它仍然尊重 SQL / join / where / orderBy 的模型
- 只是把结果读取做成了类型安全

所以它不是重 ORM，那种“完全看不到 SQL”的风格。

---

## 十一、迁移怎么做

### 11.1 schemaVersion

```dart
@override
int get schemaVersion => 2;
```

每次表结构变更，都要升级版本。

---

### 11.2 migration

```dart
@override
MigrationStrategy get migration => MigrationStrategy(
  onCreate: (m) async {
    await m.createAll();
  },
  onUpgrade: (m, from, to) async {
    if (from < 2) {
      await m.addColumn(users, users.isVip);
    }
  },
);
```

---

### 11.3 迁移时要注意什么

- 不要只改表定义，不写迁移
- 已上线应用必须考虑老版本用户的数据兼容
- 删除列、改字段类型这种操作要更谨慎，很多时候需要新建临时表再搬数据

数据库接入最容易出事故的地方，不是 CRUD，而是迁移。

---

## 十二、drift 和 sqflite 的核心差异

### 12.1 sqflite

特点：

- 更贴近原始 SQLite
- SQL 手写更多
- 自由度高
- 数据监听能力弱，需要自己补状态同步

---

### 12.2 drift

特点：

- 类型安全更强
- 查询封装更清晰
- 有代码生成
- 原生支持响应式监听
- 更适合中大型本地数据库项目

---

### 12.3 怎么选

如果项目满足下面任意几条，优先考虑 `drift`：

- 表结构不会太简单
- 页面依赖数据库实时刷新
- 查询会越来越多
- 希望数据层有清晰边界

如果需求非常轻：

- `sqflite` 足够

---

## 十三、drift 的底层原理要点

### 13.1 本质不是“自己实现数据库”

`drift` 不是重新发明数据库。

它做的事情主要是：

- 底层接 SQLite 执行器
- 上层提供 Dart DSL
- 构建期生成类型安全代码
- 维护查询监听与表变更通知

所以你仍然是在用 SQLite，只是开发体验大幅改善。

---

### 13.2 为什么它适合 Flutter

因为 Flutter 天然适合声明式刷新，而 `drift.watch()` 天然提供：

- `Stream<T>`
- 可订阅数据源
- 数据变化后的自动重算

于是数据层和 UI 层能比较顺地对接。

---

### 13.3 它解决了什么痛点

如果纯手写 SQL，常见问题有：

- 列名写错只有运行时才发现
- 查询结果解析重复劳动很多
- 页面数据刷新要自己通知
- 数据层代码容易散

`drift` 的核心价值就是把这些问题前移：

- 一部分错误前移到编译期
- 一部分样板代码交给生成器
- 一部分状态同步交给响应式查询

---

## 十四、实际开发建议

### 14.1 推荐组织方式

- 一个 `database/` 目录
- `tables/` 放表定义
- `daos/` 放查询逻辑
- `app_database.dart` 做统一注册

---

### 14.2 不要把所有查询都塞进页面

不推荐：

- 页面里直接写复杂 SQL / join / transaction

推荐：

- 页面只关心调用 DAO
- DAO 返回 `Future` 或 `Stream`

---

### 14.3 watch 很方便，但别滥用

虽然 `watch()` 很强，但也别所有地方都订阅。

适合 `watch()`：

- 列表页
- 明细页
- 依赖数据库变化自动刷新的模块

适合 `get()`：

- 一次性读取
- 提交前校验
- 后台任务里的普通查询

---

### 14.4 迁移一定要提前设计

开发初期最容易忽视迁移，但上线后最贵的就是迁移事故。

建议：

- 每次改表结构都同步写升级逻辑
- 对关键版本留测试库做迁移验证

---

## 十五、面试里怎么回答 drift

可以这样概括：

> `drift` 是 Flutter 里基于 SQLite 的响应式本地数据库方案。它通过 Dart DSL 和代码生成提供类型安全的表定义、查询、DAO、迁移能力，同时支持 `watch()` 把查询变成 `Stream`，让数据库变化能直接驱动 UI 刷新。相比 `sqflite`，它更适合中大型、结构化、本地持久化场景。

如果再往下追问原理，再补一句：

> 它的核心不是替代 SQLite，而是在 SQLite 之上做类型安全封装、构建期代码生成，以及基于表变更的查询重算和流式通知。

---

## 十六、总结

记住四句话就够了：

- `drift` 底层通常还是 SQLite
- 它最重要的增强是类型安全 + 代码生成 + 响应式查询
- 小项目可用 `sqflite`，中大型本地数据更适合 `drift`
- 真正常见难点不是增删改查，而是监听设计和迁移策略

