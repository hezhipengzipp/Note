# Kotlin 内联类（Inline Class / Value Class）

## 一、是什么

内联类是 Kotlin 提供的一种**零开销的类型包装**机制：在**编译期**用底层类型替代包装类，运行时不产生额外对象，但在编译期保留类型安全。

- Kotlin 1.3 引入：`inline class`（实验性）
- Kotlin 1.5 稳定：改名为 `@JvmInline value class`

```kotlin
@JvmInline
value class UserId(val value: Long)

@JvmInline
value class Password(val value: String)
```

---

## 二、解决什么问题

### 场景：原始类型的"语义混淆"

```kotlin
// ❌ 编译器无法区分，调用时容易传反
fun login(userId: Long, password: String) { ... }
login(password.toLong(), "abc")  // 参数顺序错误，编译通过但运行出错

// ✅ 用内联类，类型强区分
fun login(userId: UserId, password: Password) { ... }
login(Password("abc"), UserId(1))  // ❌ 编译报错
```

**传统解决方案对比**：

| 方案 | 类型安全 | 运行时开销 |
|------|----------|------------|
| 直接用 Long/String | ❌ 无 | ✅ 无 |
| 普通 data class 包装 | ✅ 有 | ❌ 有（堆分配） |
| **inline/value class** | ✅ 有 | ✅ 无（编译期擦除） |

---

## 三、ASCII 图解：编译前后

```
源码：
┌─────────────────────────────────┐
│ @JvmInline                      │
│ value class UserId(val v: Long) │
│                                 │
│ fun save(id: UserId) { ... }    │
│                                 │
│ save(UserId(100L))              │
└─────────────────────────────────┘
           │ 编译器处理
           ▼
字节码（近似）：
┌─────────────────────────────────┐
│ // UserId 类本身存在，但...     │
│ fun save-impl(id: long) {...}   │  ← 方法签名中 UserId 被展开为 long
│                                 │
│ save-impl(100L)                 │  ← 调用处没有 new UserId(...)
└─────────────────────────────────┘

对比普通类：
 new UserId(100L)  →  堆上分配对象 + GC 压力
 内联类            →  直接用 100L，无对象
```

---

## 四、基本用法

### 1. 声明

```kotlin
@JvmInline
value class Money(val cents: Long) {
    // ✅ 可以有属性（必须 val，且不能有幕后字段 field）
    val yuan: Double get() = cents / 100.0

    // ✅ 可以有方法
    fun add(other: Money) = Money(cents + other.cents)

    // ❌ 不能有 init 块（1.4.30 之后可以）
    // ❌ 不能有 var 属性带 backing field
    // ❌ 不能继承别的类（但可以实现接口）
}
```

### 2. 实现接口

```kotlin
interface Printable { fun print() }

@JvmInline
value class Name(val value: String) : Printable {
    override fun print() = println(value)
}
```

⚠️ **注意**：实现接口后，当作为接口类型使用时会**自动装箱**，失去零开销优势。

---

## 五、装箱的"隐藏陷阱"

内联类并非任何时候都无开销，遇到下列情况会**装箱**（boxing）：

```kotlin
@JvmInline
value class UserId(val value: Long)

fun demo(id: UserId) { ... }                 // ✅ 不装箱（直接用 long）
fun demoNullable(id: UserId?) { ... }        // ❌ 装箱（? 要求对象）
fun demoGeneric(list: List<UserId>) { ... }  // ❌ 装箱（泛型擦除成 Object）
fun demoAny(x: Any) { ... }                  // ❌ 装箱（向上转型）
```

### ASCII 图：何时装箱

```
UserId(1L)
   │
   ├─ 作为普通参数、局部变量    → ✅ 展开为 long
   ├─ UserId?（可空）           → ❌ 装箱为对象
   ├─ List<UserId>(泛型)        → ❌ 装箱为对象
   ├─ as Any / as 接口          → ❌ 装箱为对象
   └─ ==、hashCode、toString    → ❌ 装箱为对象
```

---

## 六、与其他特性对比

| 特性 | inline class（value class） | data class | typealias |
|------|----------------------------|------------|-----------|
| 运行时开销 | 无（多数场景） | 有 | 无 |
| 类型安全 | ✅ 新类型 | ✅ 新类型 | ❌ 只是别名 |
| 可继承 | 不可被继承 | 不可被继承 | —— |
| 构造参数 | **必须 1 个** val | 1 个或多个 | —— |

```kotlin
typealias UserId = Long     // 只是别名，编译器不区分
value class UserId(val v: Long)  // 真正的新类型
```

---

## 七、常见应用场景

1. **强类型 ID**：`UserId`、`OrderId`、`ProductId` 避免混用
2. **单位类型**：`Seconds`、`Meters`、`Celsius`
3. **约束字符串**：`Email`、`PhoneNumber`（配合 init 校验，需 1.4.30+）
4. **Kotlin 官方应用**：`Duration`、`Result<T>` 就是内联类

```kotlin
// Result<T> 简化原理示意
@JvmInline
value class Result<T>(val value: Any?) {
    val isSuccess: Boolean get() = value !is Failure
    // ...
}
```

---

## 八、面试题

**Q1：inline class 和 data class 的区别？**
- data class 是**真对象**，有堆分配开销，但功能全（多属性、copy、componentN）。
- inline class 编译期擦除为底层类型，**零运行时开销**，但只能有 1 个主构造参数，且多数场景下"装箱"会失去优势。

**Q2：内联类和 typealias 的本质区别？**
typealias 只是**别名**，`typealias UserId = Long` 后 `UserId` 和 `Long` 编译器完全不区分，函数重载也算同一个。而 value class 是**新类型**，编译器严格区分，可重载。

**Q3：哪些情况下内联类会发生装箱？**
可空类型（`UserId?`）、泛型参数（`List<UserId>`）、向上转型为 `Any` 或接口类型、反射、`equals/hashCode` 调用等。装箱后失去零开销优势。

**Q4：为什么内联类不能继承其他类？**
内联类的核心机制是编译期"展开"为底层类型，如果允许继承，就需要运行时多态分发，无法擦除，违背设计目的。实现接口被允许但会触发装箱。

**Q5：为什么要加 `@JvmInline` 注解？**
Kotlin 1.5 后，`value class` 是跨平台概念（Kotlin/Native、JS 也有），但在 JVM 上它的"内联展开"行为由 `@JvmInline` 显式声明。未来可能支持多字段 value class（Project Valhalla），届时 JVM 实现会不同。

**Q6：内联类能否用于 Android？有什么注意事项？**
可以。常用于封装 ID、View tag、ContentResolver 的 URI 等。但需注意：
- 不能作为 `Parcelable` 字段直接传（会装箱），需手动处理。
- 与 Java 互操作时，Java 端看到的是底层类型 + 编译器生成的静态方法。
