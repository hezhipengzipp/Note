# Kotlin 泛型型变（out 与 in）

---

## 一、为什么需要型变

```kotlin
open class Animal
class Dog : Animal()
class Cat : Animal()

// Dog 是 Animal 的子类 ✅
val animal: Animal = Dog()

// 那 List<Dog> 是 List<Animal> 的子类吗？
val dogs: List<Dog> = listOf(Dog())
val animals: List<Animal> = dogs  // 能不能？
```

```
直觉上应该可以，但如果允许可变集合这么做会出危险：

val dogs: MutableList<Dog> = mutableListOf(Dog())
val animals: MutableList<Animal> = dogs  // 假设允许
animals.add(Cat())  // 往里塞了一只猫！
val dog: Dog = dogs[1]  // 💀 取出来是 Cat，类型不安全！

所以默认不允许（不变），需要你用 out/in 告诉编译器"安全"才放行
```

---

## 二、三种型变

```
不变（Invariant）：默认
  MutableList<Dog> 和 MutableList<Animal> 没有任何关系

协变（Covariant）：out
  List<Dog> 是 List<Animal> 的子类
  只能读（产出），不能写（消费）

逆变（Contravariant）：in
  Washer<Animal> 是 Washer<Dog> 的子类
  只能写（消费），不能读（产出）
```

---

## 三、out（协变）—— 只产出，不消费

```kotlin
interface Producer<out T> {
    fun produce(): T        // ✅ T 在返回值（出去）
    // fun consume(t: T)    // ❌ 编译报错！T 不能在参数位置
}
```

```kotlin
// Kotlin 的 List 就是 out 声明的
public interface List<out E> {
    fun get(index: Int): E   // ✅ E 出去
    // 没有 add！因为 out 不允许消费
}

val dogs: List<Dog> = listOf(Dog())
val animals: List<Animal> = dogs  // ✅ 协变

// 安全的原因：
// 只能读 → 读出来的 Dog 一定是 Animal → 没问题
// 不能写 → 不可能塞一只 Cat 进去 → 安全
```

```
类比：自动贩卖机（只出货）

  ┌─────────────┐
  │  贴着"动物"   │
  │             │ → 取出来的一定是 Animal 或子类
  │  里面是 Dog  │   所以安全
  │             │
  │  只有出口    │ ← 没有投入口，没法往里塞 Cat
  └─────────────┘
```

---

## 四、in（逆变）—— 只消费，不产出

### 从一个真实场景理解

```kotlin
// 你有一个给 Dog 洗澡的函数
fun washDog(dog: Dog, washer: Washer<Dog>) {
    washer.wash(dog)
}

// 你手上有一个"动物清洗器"，能洗任何动物
val animalWasher: Washer<Animal> = ...

// animalWasher 能洗所有动物（猫、狗、鸟都行）
// Dog 是动物 → 当然能洗 Dog ✅
washDog(myDog, animalWasher)  // ✅

// 这就是逆变：
// Washer<Animal> 可以当 Washer<Dog> 用
```

### 为什么叫"只消费"

```kotlin
interface Washer<in T> {
    fun wash(target: T)    // T 在参数位置 = 被传进来处理 = 被消费
}

// wash(target: T) 是在"消费" T：
animalWasher.wash(dog)
//                 ↑
//             Dog 被传进去了
//             Washer 把 Dog "吃掉"处理了
//
// 能吃 Animal 的嘴 → 当然能吃 Dog
// 所以 Washer<Animal> 可以当 Washer<Dog> 用 ✅
```

### 反过来不行

```kotlin
val dogWasher: Washer<Dog> = ...

fun washAnimal(animal: Animal, washer: Washer<Animal>) {
    washer.wash(animal)  // 可能传进来一只 Cat
}

washAnimal(myCat, dogWasher)  // ❌ 危险！
// dogWasher 只会洗狗，你给它一只猫，它不会洗！
```

### 方向是反的

```
  Animal（大）← 父类
    ↑
   Dog （小）← 子类

  Washer<Animal>（能力大，啥都能洗）← 反而可以赋值给更"小"的类型
    ↑ 可以赋值给
  Washer<Dog>  （能力小，只洗狗）

  方向反了！所以叫"逆变"
```

### 再看一个你一定用过的：Comparator

```kotlin
val animalComparator: Comparator<Animal> = Comparator { a, b ->
    a.weight - b.weight  // 按体重比较，任何动物都行
}

val dogComparator: Comparator<Dog> = animalComparator  // ✅ 逆变

// 为什么安全？
// animalComparator.compare(dog1, dog2)
//                          ↑      ↑
//                      Dog 传进去被消费
//                      能比较 Animal 的 → 当然能比较 Dog

// 反过来不行：
val dogOnlyComparator: Comparator<Dog> = Comparator { a, b ->
    a.tricks - b.tricks  // 按技能数比较，只有 Dog 有 tricks
}
val animalComp: Comparator<Animal> = dogOnlyComparator  // ❌
// 传进来一只 Cat → Cat 没有 tricks → 崩溃
```

### 为什么不能产出（返回）

```kotlin
interface Washer<in T> {
    fun wash(target: T)   // ✅ T 进来（消费）
    // fun getBest(): T   // ❌ 编译器禁止！T 不能出去（产出）
}

// 假设允许：
val animalWasher: Washer<Animal> = ...
val dogWasher: Washer<Dog> = animalWasher  // 逆变赋值

val dog: Dog = dogWasher.getBest()  // 你以为拿到 Dog
// 但实际执行的是 animalWasher.getBest()
// 可能返回 Cat！
// Cat 赋值给 Dog 变量 → 类型不安全 💀

// 所以编译器禁止 in T 出现在返回值位置
```

---

## 五、一张图对比

```
out（协变）= 只能出去                in（逆变）= 只能进来

  ┌──────────┐                    ┌──────────┐
  │          │ ──→ 产出 T         │          │
  │ Producer │     返回值          │ Consumer │
  │          │                    │          │ ←── 消费 T
  └──────────┘                    └──────────┘       参数
  
  取东西：                         塞东西：
  取出来的 Dog 一定是 Animal        能吃 Animal 的嘴一定能吃 Dog
  所以 List<Dog> → List<Animal>   所以 Washer<Animal> → Washer<Dog>
  方向一致 = 协变                   方向相反 = 逆变

生活类比：

  out 协变 = 自动贩卖机（只出货）
    卖 Dog 的机器，当它是卖 Animal 的 → 安全（出来的 Dog 就是 Animal）

  in 逆变 = 垃圾处理器（只吃货）
    能处理 Animal 的处理器，当它是处理 Dog 的 → 安全（Dog 就是 Animal，它吃得了）
```

---

## 六、声明处型变 vs 使用处型变

```kotlin
// ① 声明处型变（Kotlin 独有，定义类时声明）
interface List<out E>           // 定义时就说好：E 永远协变
interface Comparable<in T>      // 定义时就说好：T 永远逆变

// 所有使用方自动生效
val animals: List<Animal> = listOf<Dog>()  // ✅ 自动


// ② 使用处型变（某些类本身不变，但某个方法里只读）
fun copy(from: MutableList<out Animal>, to: MutableList<Animal>) {
    //                      ↑ 只在这里声明协变
    for (item in from) {
        to.add(item)
    }
    // from.add(Dog())  // ❌ out 不允许写入 from
}

val dogs: MutableList<Dog> = mutableListOf(Dog())
val animals: MutableList<Animal> = mutableListOf()
copy(dogs, animals)  // ✅
```

---

## 七、和 Java 通配符对比

| | Kotlin | Java |
|---|---|---|
| 协变 | `out T` | `? extends T` |
| 逆变 | `in T` | `? super T` |
| 声明处型变 | **支持** | 不支持 |
| 使用处型变 | 支持 | 支持 |
| 星投影 | `List<*>` | `List<?>` |
| 可读性 | **out/in 直观** | extends/super 绕 |

```
Kotlin 比 Java 好在哪：

  Java：每次用都要写 List<? extends Animal>（烦且不直观）
  Kotlin：定义时 List<out E>，所有使用方自动协变（省心）
```

---

## 八、实际应用场景

```kotlin
// ① 协变：返回数据的接口
interface Repository<out T> {
    fun getAll(): List<T>
    fun getById(id: Int): T
}
val repo: Repository<Animal> = DogRepository()  // ✅


// ② 逆变：处理数据的接口
interface EventHandler<in T> {
    fun handle(event: T)
}
val handler: EventHandler<Dog> = AnimalHandler()  // ✅
// 能处理 Animal 的 → 当然能处理 Dog


// ③ 不变：既读又写
interface MutableRepository<T> {
    fun getAll(): List<T>   // T 出去
    fun save(item: T)       // T 进来
    // 既读又写 → 不能 in 也不能 out → 不变
}
```

---

## 九、面试回答模板

**Q: Kotlin 的 out 和 in 是什么意思？**

> out 是协变，表示泛型参数只出现在返回值位置（只产出），这样 `List<Dog>` 就能赋值给 `List<Animal>`，因为读出来的 Dog 一定是 Animal。in 是逆变，表示泛型参数只出现在参数位置（只消费），这样 `Comparator<Animal>` 就能赋值给 `Comparator<Dog>`，因为能处理 Animal 的一定能处理 Dog。方向和继承相反所以叫逆变。编译器通过限制 T 的位置保证安全：out 不能在参数位置（防止写入错误类型），in 不能在返回值位置（防止读出错误类型）。Kotlin 比 Java 好的地方是支持声明处型变，定义类时写好 `<out T>` 所有使用方自动生效，不用每次写通配符。
