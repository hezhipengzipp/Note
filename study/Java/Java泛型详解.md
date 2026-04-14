# Java 泛型详解

## 一、泛型方法 vs 泛型类中的方法

### 核心区别一句话

```
泛型类中的方法  → T 由类实例化时确定，方法共享类的类型参数
泛型方法        → T 由方法调用时确定，类型参数声明在方法自身
```

### 语法对比

```java
// 泛型类中的方法：T 来自类声明
public class Box<T> {
    private T value;

    public T get() { return value; }       // 使用类的 T
    public void set(T value) { ... }       // 使用类的 T
}

// 泛型方法：<T> 声明在返回值前
public class Utils {
    public <T> T identity(T value) {       // 方法自己声明 T
        return value;
    }

    public <T, R> R convert(T input, Function<T, R> fn) {  // 多个类型参数
        return fn.apply(input);
    }
}
```

### 区别一：类型参数的作用域

```java
Box<String> box = new Box<>();
// T 在实例化时已固定为 String
// box 的所有方法 T 都是 String
box.set("hello");
box.set(123);    // 编译错误，T 已是 String


Utils utils = new Utils();
// 每次调用时 T 独立推断
utils.identity("hello");  // T = String
utils.identity(123);      // T = Integer
utils.identity(3.14);     // T = Double
// 同一个对象，每次调用 T 都可以不同
```

### 区别二：static 方法（最常考）

```java
public class Box<T> {

    // 合法：实例方法可以用类的 T
    public T get() { return value; }

    // 编译错误：静态方法不能使用类的 T
    // 因为 static 方法属于类本身，不属于某个实例
    // 类的 T 是实例级别的，static 访问不到
    public static T create() { ... }   // ❌

    // 正确：静态泛型方法，自己声明 <T>
    public static <T> Box<T> of(T value) { // ✅
        Box<T> box = new Box<>();
        box.value = value;
        return box;
    }
}

// 使用
Box<String> b = Box.of("hello");   // 调用时推断 T = String
```

### 区别三：类型参数相互独立

```java
public class Container<T> {

    // 方法的 E 与类的 T 完全独立
    public <E> List<E> toList(E element) {
        return Collections.singletonList(element);
    }

    // 方法的 T 会遮蔽类的 T（不推荐，容易混淆）
    public <T> void shadow(T t) {
        // 这里的 T 是方法自己的，不是类的 T
    }
}

Container<String> c = new Container<>();
c.toList(123);      // E = Integer，与类的 T=String 无关
c.toList(true);     // E = Boolean，与类的 T=String 无关
```

### 区别四：类型擦除后的表现

```java
// 泛型类方法：擦除后 T → Object（或上界）
public class Box<T> {
    public T get() { ... }
    // 擦除后 → public Object get()
}

public class NumberBox<T extends Number> {
    public T get() { ... }
    // 擦除后 → public Number get()（擦除到上界）
}

// 泛型方法：同样擦除，但每次调用编译器插入强转
public <T> T identity(T t) { return t; }
// 擦除后 → public Object identity(Object t)
// 调用处：String s = identity("abc")
// 编译器自动插入：String s = (String) identity("abc")
```

### 使用场景选择

```
需要多个方法共享同一类型约束？
└── YES → 泛型类
    例：List<T>，所有方法都操作同一种类型

只有单个方法需要类型参数？
└── YES → 泛型方法
    例：Collections.sort()、Arrays.asList()

需要在 static 工具类中写通用方法？
└── 必须用泛型方法
    例：Collections、Arrays 里全是泛型方法

方法的类型参数和类的类型参数不同？
└── 必须用泛型方法
    例：<E> List<E> toList(E e)，E 与类的 T 无关
```

### 面试高频考题

```java
public class Foo<T> {
    T a;

    public static T staticMethod() { return null; }        // ❌ 类的T不能用于static
    public static <T> T staticMethod2(T t) { return t; }  // ✅ 方法自己的T
    public <E extends T> void bounded(E e) { }            // ✅ E 上界是类的 T
}

// 泛型方法不能重载（擦除后签名相同）
public <T> void print(T t) { }
public <E> void print(E e) { }  // ❌ 擦除后都是 Object，冲突
```

| | 泛型类中的方法 | 泛型方法 |
|---|---|---|
| T 声明位置 | 类名后 `class Foo<T>` | 返回值前 `<T> void foo()` |
| T 确定时机 | 类实例化时 | 方法调用时 |
| 能否 static | 不能用类的 T | 可以，用自己的 T |
| 适用场景 | 多方法共享类型 | 单方法/工具类/static |

---

## 二、泛型擦除后为何还能知道类型

### 核心答案

```
类型擦除 → 发生在泛型类/方法内部
类型检查 → 编译器在调用处插入强制转换指令

类型信息不是"运行时查的"，是"编译器提前写死在调用处的"
```

### 字节码说明一切

```java
// 你写的代码
List<String> list = new ArrayList<>();
list.add("hello");
String s = list.get(0);   // 你没有写强转

// 编译器实际生成的字节码等价于
List list = new ArrayList();          // T 擦除为 Object
list.add("hello");
String s = (String) list.get(0);     // 编译器自动插入 checkcast 指令
```

```
字节码指令（javap -c 查看）

invokevirtual #4  // 调用 List.get()，返回 Object
checkcast     #5  // ← 编译器插入，强转为 String
astore_2          // 存入变量 s
```

### 类型信息在哪里

```
泛型类内部（已擦除，不知道 T 是什么）
┌──────────────────────────────────┐
│  class ArrayList<T>              │
│      Object[] elementData;       │  ← T 擦为 Object
│      Object get(int index) {     │  ← 返回 Object
│          return elementData[i];  │
│      }                           │
└──────────────────────────────────┘

调用方（编译器写死了类型）
┌──────────────────────────────────┐
│  List<String> list = ...         │
│  String s = list.get(0);         │
│      ↓ 编译后                    │
│  String s = (String) list.get(0);│  ← 类型信息在这里
└──────────────────────────────────┘
```

### ClassCastException 发生在哪

```java
// 利用原始类型绕过编译器检查（堆污染）
List<String> strings = new ArrayList<>();
List raw = strings;            // 原始类型，无警告拦截
raw.add(123);                  // 放入 Integer，编译通过

// 取值时才爆
String s = strings.get(0);
// 编译器在这里插入了 (String) 强转
// 运行时发现是 Integer → ClassCastException
// 报错位置是取值处，不是 add 处，非常迷惑
```

### 但字节码里保留了签名信息

擦除的是**运行时的类型变量**，但 `.class` 文件的 `Signature` 属性里**保留了泛型签名**，供反射使用：

```java
public class Foo {
    List<String> list;
}

Field field = Foo.class.getDeclaredField("list");
Type type = field.getGenericType();
ParameterizedType pt = (ParameterizedType) type;
System.out.println(pt.getActualTypeArguments()[0]); // class java.lang.String
```

### 擦除导致的限制

```java
T t = new T();                          // ❌ 不能用 T 创建对象
T[] arr = new T[10];                    // ❌ 不能用 T 创建数组
if (obj instanceof List<String>) { }    // ❌ 不能 instanceof 泛型类型
void print(List<String> list) { }
void print(List<Integer> list) { }      // ❌ 擦除后签名冲突，不能重载
static T instance;                      // ❌ static 字段不能用类的 T
```

---

## 三、声明侧保留 vs 使用侧擦除

### 核心理解

```
声明侧 → 写进 .class 文件的 Signature 属性，反射可读
使用侧 → 方法体内的字节码操作，T 一律变成 Object，反射不可读
```

### 声明侧：保留在哪

```java
// ① 泛型类声明
public class Box<T extends Number> { }
// Signature: <T:Ljava/lang/Number;>Ljava/lang/Object;

// ② 泛型接口声明
public interface Converter<F, T> { }
// Signature: <F:Ljava/lang/Object;T:Ljava/lang/Object;>Ljava/lang/Object;

// ③ 字段声明
private List<String> names;
// Signature: Ljava/util/List<Ljava/lang/String;>;

// ④ 泛型方法声明（参数+返回值）
public <T> List<T> wrap(T value) { }
// Signature: <T:Ljava/lang/Object;>(TT;)Ljava/util/List<TT;>;

// ⑤ 继承/实现时的具体化
public class StringBox extends Box<String> { }
// Signature: LBox<Ljava/lang/String;>;
```

### 使用侧：方法体内完全擦除

```java
public void process() {
    List<String>         list = new ArrayList<>();  // 运行时只有 List
    Map<String, Integer> map  = new HashMap<>();    // 运行时只有 Map
}
```

```
javap -verbose 查看字节码

局部变量表（LocalVariableTable）：
  Slot  Name   Descriptor
     1   list   Ljava/util/List;      ← 擦除，只有 List
     2   map    Ljava/util/Map;       ← 擦除，只有 Map

局部变量类型表（LocalVariableTypeTable，需 -g 编译）：
  Slot  Name   Signature
     1   list   Ljava/util/List<Ljava/lang/String;>;        ← 有泛型
     2   map    Ljava/util/Map<Ljava/lang/String;Ljava/lang/Integer;>;
     // 仅 debug 模式生成，标准反射 API 无法访问
```

### 用反射验证两者差异

```java
public class Demo {
    private List<String> field;

    public Map<String, Integer> method(List<String> param) {
        List<String> local = new ArrayList<>();   // 使用侧，反射看不到泛型
        return null;
    }
}

Field f = Demo.class.getDeclaredField("field");
System.out.println(f.getGenericType());
// 输出：java.util.List<java.lang.String>    ✅ 声明侧，有泛型信息

Method m = Demo.class.getDeclaredMethod("method", List.class);
System.out.println(m.getGenericReturnType());
// 输出：java.util.Map<java.lang.String, java.lang.Integer>  ✅

System.out.println(m.getGenericParameterTypes()[0]);
// 输出：java.util.List<java.lang.String>    ✅

// 局部变量 local → 无法通过反射获取泛型信息  ❌
```

### 为什么这样设计

```
声明侧必须保留
─────────────────────────────────────────────
原因：其他类编译时需要读取这些信息
      A 类的 List<String> 字段
      B 类引用 A 时，编译器要知道是 List<String>
      才能做类型检查，才能在调用处插入正确的 checkcast

使用侧无需保留
─────────────────────────────────────────────
原因：方法体是私有实现
      类型检查已由编译器在调用处完成（checkcast）
      运行时 JVM 执行字节码不需要泛型信息
      保留只会增加字节码体积，没有收益
```

### 经典应用：SuperTypeToken 模式

正因为声明侧保留了泛型，Gson / Jackson 利用这一点解决运行时获取泛型类型：

```java
// 直接传 Class 丢失泛型
gson.fromJson(json, List.class);                              // ❌ 不知道 List<什么>

// SuperTypeToken：利用匿名子类的声明侧保留
gson.fromJson(json, new TypeToken<List<String>>(){}.getType());
//                   ↑ 匿名类继承 TypeToken<List<String>>
//                   声明侧 Signature 保留了 List<String>
//                   getType() 通过 getGenericSuperclass() 读取

// 内部原理
class TypeToken<T> {
    public Type getType() {
        return ((ParameterizedType)
            getClass().getGenericSuperclass())
            .getActualTypeArguments()[0];
        // 返回 List<String> 的完整类型信息
    }
}
```

### 总结

```
声明侧（Signature 属性）          使用侧（方法体字节码）
──────────────────────────────────────────────────────────
泛型类/接口定义        ✅ 保留    局部变量             ❌ 擦除
字段类型               ✅ 保留    new 表达式           ❌ 擦除
方法参数类型           ✅ 保留    强转/instanceof      ❌ 擦除
方法返回值类型         ✅ 保留    方法调用             ❌ 擦除
父类/接口具体化        ✅ 保留

反射可读               ✅         反射不可读           ❌
编译器跨类检查依赖它   ✅         运行时 JVM 不需要它  ❌
```

**本质**：声明侧的泛型信息是给**编译器和反射**用的元数据；使用侧擦除是因为**运行时 JVM 执行字节码不需要它**，类型安全已在编译期通过插入 `checkcast` 保证。
