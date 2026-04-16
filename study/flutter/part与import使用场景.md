# Dart part / part of 使用与场景

---

## 一、一句话说清

`part` 把一个大文件**拆成多个物理文件，但它们在编译器眼里仍然是同一个库**。拆开的文件能互相访问私有成员（`_` 开头的）。

---

## 二、和 import 的本质区别

```
import：两个独立的库，私有成员互不可见
─────────────────────────────────────
  // a.dart
  class _Private {}    // b.dart 看不到

  // b.dart
  import 'a.dart';
  // _Private?  ← 编译报错，访问不到


part：同一个库的不同文件，私有成员共享
─────────────────────────────────────
  // my_lib.dart（主文件）
  part 'src/private_helper.dart';

  // src/private_helper.dart
  part of 'my_lib.dart';
  class _Private {}    // 主文件能直接用 ✅
```

```
类比 Android：

import  ≈  两个不同的 module，各自有 internal 可见性
part    ≈  同一个 module 里拆成多个文件，internal 互相可见
```

---

## 三、基本用法

```dart
// ==========================================
// 主文件：user_model.dart（库的入口）
// ==========================================
// 声明这个库包含哪些 part 文件
part 'user_model.g.dart';        // 代码生成的文件
part 'user_model.freezed.dart';  // freezed 生成的文件

class User {
  final String name;
  final int age;
  
  User(this.name, this.age);
  
  // 可以直接调用 part 文件里的 _私有方法
  String get _formatted => _formatUser(this);
}
```

```dart
// ==========================================
// part 文件：user_model.g.dart
// ==========================================
// 声明自己属于哪个主文件
part of 'user_model.dart';

// 可以访问主文件的所有私有成员
String _formatUser(User user) {
  return '${user.name}(${user.age})';
}

// 这个文件不能有自己的 import！
// 所有 import 必须写在主文件里
```

### 规则

```
主文件                              part 文件
──────                             ──────────
part 'xxx.dart';                   part of 'main.dart';
可以有 import                       不能有 import（用主文件的）
可以有 library 声明                  不能有 library 声明
可以有多个 part                      只能属于一个主文件
```

---

## 四、使用场景

### 场景一：代码生成（最常见，占 90% 的使用场景）

```dart
// user_model.dart
import 'package:json_annotation/json_annotation.dart';

part 'user_model.g.dart';  // json_serializable 生成的文件

@JsonSerializable()
class User {
  final String name;
  final int age;

  User(this.name, this.age);

  // 这两个方法的实现在 user_model.g.dart 里
  // 生成的代码需要访问类的私有成员，所以必须用 part
  factory User.fromJson(Map<String, dynamic> json) => _$UserFromJson(json);
  Map<String, dynamic> toJson() => _$UserToJson(this);
}
```

```dart
// user_model.g.dart（自动生成，不要手动修改）
part of 'user_model.dart';

User _$UserFromJson(Map<String, dynamic> json) => User(
  json['name'] as String,
  json['age'] as int,
);

Map<String, dynamic> _$UserToJson(User instance) => <String, dynamic>{
  'name': instance.name,
  'age': instance.age,
};
```

```
常见的代码生成工具都依赖 part：

  json_serializable  → xxx.g.dart       JSON 序列化
  freezed            → xxx.freezed.dart  不可变数据类
  built_value        → xxx.g.dart        不可变数据类
  retrofit           → xxx.g.dart        网络请求
  hive               → xxx.g.dart        本地数据库
  mockito            → xxx.mocks.dart    测试 Mock
```

**为什么代码生成必须用 part？** 因为生成的代码要访问你的类的私有构造函数或字段，import 做不到。

### 场景二：大文件拆分（较少用）

```dart
// 一个复杂的 Widget 拆成多个文件

// complex_page.dart（主文件）
import 'package:flutter/material.dart';

part 'complex_page_header.dart';
part 'complex_page_body.dart';
part 'complex_page_actions.dart';

class ComplexPage extends StatefulWidget {
  @override
  _ComplexPageState createState() => _ComplexPageState();
}

class _ComplexPageState extends State<ComplexPage> {
  // 私有状态，part 文件都能访问
  int _counter = 0;
  bool _isLoading = false;
  
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: _buildHeader(),      // 在 header part 里
      body: _buildBody(),          // 在 body part 里
      floatingActionButton: _buildActions(), // 在 actions part 里
    );
  }
}
```

```dart
// complex_page_header.dart
part of 'complex_page.dart';

extension _HeaderBuilder on _ComplexPageState {
  Widget _buildHeader() {
    return AppBar(
      title: Text('Counter: $_counter'),  // 能访问 _counter ✅
    );
  }
}
```

---

## 五、什么时候用 part，什么时候用 import

```
用 part 的场景（少）：
  ✅ 代码生成工具要求（json_serializable、freezed 等）
  ✅ 生成的代码需要访问私有成员

用 import 的场景（绝大多数）：
  ✅ 正常的模块拆分
  ✅ 公共工具类、组件库
  ✅ 任何不需要共享私有成员的场景

经验法则：
  除了代码生成，几乎不需要手动写 part
  如果你想拆分大文件，优先考虑 import + 公开 API
  part 会让文件之间耦合太紧（共享私有成员 = 没有边界）
```

```
❌ 不推荐：用 part 拆分业务模块

  // 不好：所有文件共享私有成员，没有封装边界
  part 'user_repository.dart';
  part 'user_service.dart';
  part 'user_controller.dart';

✅ 推荐：用 import 保持模块独立

  // 好：每个文件是独立的库，通过公开 API 交互
  import 'user_repository.dart';
  import 'user_service.dart';
  import 'user_controller.dart';
```

---

## 六、export 和 part 的区别

容易搞混，但它们完全不同：

```dart
// export：重新导出另一个库的公开 API
// 用于构建 package 的统一入口

// my_package.dart（统一入口文件）
export 'src/user.dart';        // 把 user.dart 的公开 API 暴露出去
export 'src/order.dart';       // 把 order.dart 的公开 API 暴露出去
export 'src/product.dart' show Product;  // 只暴露 Product 类

// 使用方只需要一行 import：
// import 'package:my_package/my_package.dart';
```

```
对比：

part    → 把多个文件合并成一个库（共享私有成员）
export  → 把多个库的公开 API 聚合到一个入口（不共享私有）
import  → 引入另一个库来使用

part:    一个库拆成多个文件（内部用）
export:  多个库聚合成一个入口（对外用）
import:  使用别人的库
```

---

## 七、面试回答模板

**Q: Dart 的 part 和 import 有什么区别？什么时候用 part？**

> part 把多个文件合并成同一个库，文件之间可以互相访问私有成员；import 是引入独立的库，私有成员不可见。part 最主要的使用场景是代码生成——json_serializable、freezed、retrofit 等工具生成的 .g.dart 文件需要访问原类的私有成员，所以必须用 part。日常开发中手动拆分代码应该优先用 import，因为 part 会让文件之间耦合太紧，丧失封装边界。简单记：**除了代码生成，几乎不需要手动写 part**。

**Q: part、import、export 三者的区别？**

> part 是把一个库拆成多个文件，编译器视为同一个库，共享私有成员，part 文件不能有自己的 import。import 是引入一个独立的库来使用，两个库之间私有成员不可见。export 是把其他库的公开 API 重新导出，用于构建 package 的统一入口文件，使用者只需一行 import。三者的关系是：part 对内拆分，export 对外聚合，import 使用外部。
