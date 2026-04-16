# Dart vs Java vs Kotlin 泛型对比

---

## 一、最核心的区别

```
Java：  泛型在编译后被擦除，运行时 List<String> 和 List<int> 是同一个类型
Kotlin：和 Java 一样被擦除（JVM 限制），但语法层面做了很多增强
Dart：  泛型在运行时保留，List<String> 和 List<int> 是不同的类型
```

---

## 二、类型擦除 vs 类型保留

```java
// Java：类型擦除（Type Erasure）
List<String> strings = new ArrayList<>();
List<Integer> ints = new ArrayList<>();

// 运行时类型信息丢了！
System.out.println(strings.getClass() == ints.getClass());
// true ← 运行时都是 ArrayList，不知道泛型参数

// 所以这些在 Java 中做不到：
if (obj instanceof List<String>) { }  // ❌ 编译报错
new T();                               // ❌ 编译报错
T.class                                // ❌ 编译报错
```

```dart
// Dart：类型保留（Reified Generics）
var strings = <String>[];
var ints = <int>[];

// 运行时泛型信息还在！
print(strings.runtimeType);  // List<String>
print(ints.runtimeType);     // List<int>
print(strings.runtimeType == ints.runtimeType);  // false ✅

// 所以这些在 Dart 中可以做到：
if (obj is List<String>) { }  // ✅ 运行时检查泛型
print(T);                      // ✅ 打印类型参数
```

```
为什么 Java 要擦除？

  Java 1.0 没有泛型
  Java 5 加泛型时要兼容旧代码
  → 编译后擦除泛型 → 字节码和旧版一样 → 兼容 ✅ 但丢信息 ❌

  Dart 没有历史包袱，从一开始就保留泛型信息
```

### 实际影响

```dart
// Dart：运行时可以根据泛型类型做不同处理
void process<T>(T value) {
  if (T == String) {
    print('处理字符串: $value');
  } else if (T == int) {
    print('处理数字: $value');
  }
}
process<String>('hello');  // 处理字符串: hello
process<int>(42);          // 处理数字: 42
```

```java
// Java：运行时 T 被擦除为 Object，无法判断
<T> void process(T value) {
  // if (T == String)  ← 做不到！T 在运行时不存在
  // 只能用 instanceof 检查 value，不能检查 T
  if (value instanceof String) { ... }  // 勉强可以
}
```

---

## 三、型变（Variance）

```
问题：List<Dog> 能赋值给 List<Animal> 吗？

  Dog extends Animal

  List<Dog> dogs = [Dog()];
  List<Animal> animals = dogs;  // 能不能？
```

### Dart：默认协变（Covariant）

```dart
List<Dog> dogs = [Dog()];
List<Animal> animals = dogs;  // ✅ 编译通过！

animals.add(Cat());  // 编译通过，但运行时报错！
// TypeError: Cat is not a subtype of Dog
// 因为 animals 实际指向 List<Dog>，你往里塞了 Cat
```

### Java：默认不变（Invariant）

```java
List<Dog> dogs = new ArrayList<>();
List<Animal> animals = dogs;  // ❌ 编译报错！

// 要用通配符才行：
List<? extends Animal> animals = dogs;  // ✅ 只读
animals.add(new Cat());  // ❌ 编译报错（通配符禁止写入）
animals.get(0);          // ✅ 可以读

List<? super Dog> list = animalList;    // ✅ 只写
list.add(new Dog());     // ✅ 可以写
list.get(0);             // 只能当 Object 读
```

### Kotlin：用 out/in 关键字

```kotlin
val dogs: List<Dog> = listOf(Dog())
val animals: List<Animal> = dogs  // ✅ Kotlin 的 List 声明为 List<out T>
                                  //    天然协变，而且 List 不可变所以安全

// 自定义协变/逆变：
class Producer<out T> {    // out = 只产出 T，不消费（协变）
  fun get(): T             // ✅ T 出现在返回值
  // fun set(t: T)         // ❌ 编译报错，不能在参数位置
}

class Consumer<in T> {     // in = 只消费 T，不产出（逆变）
  fun set(t: T)            // ✅ T 出现在参数
  // fun get(): T          // ❌ 编译报错，不能在返回值位置
}
```

### 型变对比

```
             Dart              Java                  Kotlin
──────      ──────            ──────                ──────
默认         协变（不安全）     不变（安全）            不变（安全）
协变         默认就是          ? extends T            out T
逆变         不支持语法级       ? super T              in T
检查时机     运行时报错        编译时报错              编译时报错
通配符       没有              ? extends / ? super    out / in
```

```
Dart 为什么选择默认协变？

  因为实际开发中 90% 的场景是协变的（只读）
  Dart 选择了"方便优先"——默认允许，运行时兜底
  Java/Kotlin 选择了"安全优先"——默认禁止，编译时强制
```

### Dart 的 covariant 关键字

```dart
class Animal {
  void chase(Animal target) { }
}

class Dog extends Animal {
  @override
  void chase(covariant Dog target) { }  // 收窄参数类型
  // 没有 covariant 会编译警告
  // 加了 covariant 表示"我知道风险，我保证只传 Dog"
}
```

---

## 四、泛型约束（上界）

```dart
// Dart：extends（只支持单个约束）
class Cache<T extends Comparable<T>> {
  T max(T a, T b) => a.compareTo(b) > 0 ? a : b;
}
Cache<int>().max(1, 2);       // ✅
Cache<String>().max('a','b'); // ✅
// Cache<Dog>();                // ❌ Dog 没有实现 Comparable
```

```java
// Java：extends（支持多重约束）
<T extends Comparable<T> & Serializable>  // ✅ 多重约束
```

```kotlin
// Kotlin：冒号 + where（支持多重约束）
fun <T> sort(list: List<T>) where T : Comparable<T>, T : Serializable {
  // T 必须同时实现 Comparable 和 Serializable
}
```

```
Dart 不支持多重约束！

// Java
<T extends Comparable<T> & Serializable>  // ✅

// Kotlin
where T : Comparable<T>, T : Serializable  // ✅

// Dart
// <T extends Comparable<T> & Serializable>  ❌ 语法不支持
// 只能约束一个上界
```

---

## 五、泛型函数

```dart
// Dart
T first<T>(List<T> items) => items[0];
var s = first<String>(['a', 'b']);  // 显式指定
var i = first([1, 2, 3]);          // 自动推断为 int
```

```java
// Java
<T> T first(List<T> items) { return items.get(0); }
String s = <String>first(list);    // 显式（少见）
String s = first(stringList);      // 自动推断
```

```kotlin
// Kotlin
fun <T> first(items: List<T>): T = items[0]
val s = first<String>(list)  // 显式
val s = first(stringList)    // 自动推断

// Kotlin 独有：reified（内联泛型，运行时保留）
inline fun <reified T> isType(obj: Any): Boolean {
  return obj is T   // ✅ 因为 reified，运行时知道 T 是什么
}
// 普通泛型在 JVM 上被擦除，reified + inline 绕过了擦除
// 原理：inline 把函数体复制到调用处，T 被替换为实际类型
```

### Kotlin reified vs Dart 泛型保留

```
Kotlin reified：
  只能在 inline 函数中使用（编译器把泛型内联替换）
  是个 trick，不是真正保留

Dart 泛型保留：
  所有场景都保留，不需要特殊关键字
  是语言层面的设计
```

---

## 六、总结对比表

| 特性 | Dart | Java | Kotlin |
|------|------|------|--------|
| 运行时类型 | **保留** | 擦除 | 擦除（reified 除外） |
| `is List<String>` | **可以** | 不可以 | reified 才可以 |
| 默认型变 | 协变（运行时检查） | 不变 | 不变 |
| 协变语法 | 默认 / covariant | `? extends T` | `out T` |
| 逆变语法 | 不支持 | `? super T` | `in T` |
| 通配符 | **没有** | `?` | `*` |
| 泛型约束 | `extends`（单个） | `extends`（多个 `&`） | `:`（多个 `where`） |
| 泛型方法 | 支持 | 支持 | 支持 + reified |

---

## 七、面试回答模板

**Q: Dart 泛型和 Java/Kotlin 有什么区别？**

> 最核心的区别是 Dart 的泛型在运行时保留（Reified），而 Java/Kotlin 的泛型在编译后被擦除。所以 Dart 中可以在运行时检查 `obj is List<String>`，Java 做不到。型变方面，Dart 默认协变，把安全检查推迟到运行时，写起来方便但可能运行时报错；Java 用 `? extends/? super` 通配符、Kotlin 用 `out/in` 关键字在编译期保证安全。另外 Dart 不支持多重泛型约束和逆变语法，这是比 Java/Kotlin 弱的地方。Kotlin 的 reified 关键字可以在 inline 函数中保留泛型信息，但这是编译器内联的 trick，不像 Dart 是语言层面的设计。

**Q: 为什么 Java 泛型要类型擦除？**

> 历史兼容性。Java 1.0 没有泛型，Java 5 加入泛型时为了让新代码编译出的字节码能和旧版 JVM 兼容，选择了在编译后擦除泛型信息。代价是运行时无法获取泛型参数，不能做 `instanceof List<String>` 这样的检查。Dart 没有历史包袱，从设计之初就保留了运行时泛型信息。
