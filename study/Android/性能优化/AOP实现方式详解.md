# Android AOP 实现方式详解

## 一、分类总览

```
                   Android AOP
                       │
         ┌─────────────┴─────────────┐
         │                           │
     编译期织入                   运行期织入
    （静态，性能好）             （动态，侵入小）
         │                           │
   ┌─────┼─────┬──────┐         ┌────┼────┐
   │     │     │      │         │    │    │
 AspectJ ASM  APT/KSP Javassist 动态代理 Hook
         │                           │
    ┌────┴────┐                 ┌────┴────┐
  Transform  Lancet          Xposed    Epic
  /AGP 7+    Booster         Dexposed  (ART hook)
```

**选型原则**：编译期优先（无运行时开销），运行期只在宿主无法修改时用（如 Hook 系统 API）。

---

## 二、编译期方案

### 1. AspectJ（最经典）

**原理**：通过 `ajc` 编译器把切面代码织入目标字节码。

```java
@Aspect
public class TrackAspect {

    @Pointcut("execution(* android.app.Activity.onCreate(..))")
    public void onCreatePoint() {}

    @Around("onCreatePoint()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = joinPoint.proceed();  // 执行原方法
        long cost = System.currentTimeMillis() - start;
        Log.d("AOP", joinPoint.getSignature() + " 耗时 " + cost);
        return result;
    }
}
```

**切入点语法**：

| 语法                                | 含义                      |
| ----------------------------------- | ------------------------- |
| `execution(* *.onClick(..))`        | 匹配所有 onClick 方法     |
| `call(* Logger.log(..))`            | 匹配调用处                |
| `@annotation(com.xx.Trace)`         | 匹配带注解的方法          |
| `within(com.example..*)`            | 匹配包下所有类            |

**优点**：语法成熟、切入点表达式强大。
**缺点**：编译慢、对 Kotlin inline 支持差、包体积增加。

---

### 2. ASM（字节码直接操作，性能最优）

**原理**：直接读写 `.class` 字节码，基于访问者模式。

```
      原 .class 文件
           │
           ▼
    ┌─────────────┐
    │ ClassReader │ 读
    └──────┬──────┘
           ▼
    ┌─────────────┐
    │ ClassVisitor│ ← 你的逻辑在这里插桩
    └──────┬──────┘
           ▼
    ┌─────────────┐
    │ ClassWriter │ 写
    └──────┬──────┘
           ▼
      新 .class 文件
```

**示例**：在每个 Activity.onCreate 开头插入一行日志：

```java
class TrackClassVisitor extends ClassVisitor {
    @Override
    public MethodVisitor visitMethod(int access, String name,
                                     String desc, String sig, String[] ex) {
        MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
        if ("onCreate".equals(name)) {
            return new AdviceAdapter(ASM9, mv, access, name, desc) {
                @Override
                protected void onMethodEnter() {
                    mv.visitLdcInsn("onCreate called");
                    mv.visitMethodInsn(INVOKESTATIC, "android/util/Log",
                        "d", "(Ljava/lang/String;)I", false);
                    mv.visitInsn(POP);
                }
            };
        }
        return mv;
    }
}
```

**集成方式**：
- **旧版**：Gradle `Transform API`（AGP 7.0 已废弃）
- **新版**：`AGP 7.0+ Instrumentation API`

```kotlin
// build.gradle.kts 新版 AGP 插桩
androidComponents {
    onVariants { variant ->
        variant.instrumentation.transformClassesWith(
            MyAsmClassVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) { }
    }
}
```

---

### 3. Javassist（比 ASM 友好）

**原理**：用字符串形式操作字节码，语法像 Java。

```java
CtClass cc = pool.get("com.example.MainActivity");
CtMethod method = cc.getDeclaredMethod("onCreate");
method.insertBefore("{ android.util.Log.d(\"AOP\", \"onCreate start\"); }");
cc.writeFile();
```

**优点**：门槛低。
**缺点**：性能比 ASM 差，功能弱。

---

### 4. Lancet / Booster（框架化封装）

**Lancet**（饿了么 eleme 开源）：基于 ASM，用注解配置，语法类似 AspectJ 但编译更快、产物无运行时。

```kotlin
@TargetClass("android.widget.Toast")
@Insert("show")
fun show() {
    Log.d("AOP", "Toast 被拦截")
    Origin.callVoid()  // 调用原方法
}
```

> ⚠️ **Lancet 已停止维护**（最后版本 1.0.6 发布于 2018-2019 年），基于旧的 `Transform API`，**AGP 8.0+ 完全不兼容**。新项目请用 AGP 7.0+ 的 `Instrumentation API` 自写 ASM ClassVisitor。
>
> 已知限制：
> - 切点能力弱于 AspectJ（不支持按注解/参数匹配）
> - 不能访问超类字段、无法新增字段
> - 修改 Hook 类或用 `Scope.LEAF/ALL` 触发全量编译
> - Kotlin inline / 协程支持不完整

**Booster**：滴滴开源，封装了常用插桩场景（如线程优化、资源检查）。同样基于老 Transform API，面临类似兼容性问题。

---

### 5. APT / KSP（代码生成，非传统 AOP）

**原理**：编译期根据注解生成新代码（不修改已有字节码），ButterKnife、ARouter、Dagger 用的都是这个。

```java
// 注解
@Route(path = "/main")
public class MainActivity extends Activity {}

// APT 生成
public class ARouter$$Route$$app {
    public void load(Map<String, RouteMeta> map) {
        map.put("/main", MainActivity.class);
    }
}
```

KSP 是 Kotlin 官方的替代品，**比 KAPT 快 2-3 倍**。

---

## 三、运行期方案

### 1. 动态代理（JDK Proxy）

**限制**：只能代理接口。

```kotlin
interface UserApi {
    fun getUser(id: Int): User
}

val proxy = Proxy.newProxyInstance(
    classLoader,
    arrayOf(UserApi::class.java)
) { _, method, args ->
    Log.d("AOP", "调用 ${method.name}")
    method.invoke(realApi, *args)
}
```

**用途**：Retrofit 就是这么实现的。

---

### 2. Xposed / Dexposed（Hook 框架）

**原理**：替换 ART 方法入口，执行前后插逻辑。

```
         正常调用：
    Method A ──▶ A 的代码

         Hook 后：
    Method A ──▶ 替换入口 ──▶ beforeHook()
                              │
                              ▼
                          A 的原代码
                              │
                              ▼
                          afterHook()
```

**Xposed 示例**：

```java
XposedHelpers.findAndHookMethod(
    "android.app.Activity",
    loadPackageParam.classLoader,
    "onCreate", Bundle.class,
    new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Log.d("AOP", "Activity.onCreate called");
        }
    }
);
```

**限制**：需要 root 或 Magisk。

**Epic**：阿里开源，无需 root，基于 ART 方法替换，支持 Android 5-11。

---

### 3. Java 动态字节码（Dexmaker）

类似 CGLib 的思路，运行时生成 dex 并加载：

```java
DexMaker dexMaker = new DexMaker();
// ... 构造字节码
```

实际很少用，太复杂。

---

## 四、对比表

| 方案           | 时机  | 性能开销 | 学习成本 | 典型场景                   |
| -------------- | ----- | -------- | -------- | -------------------------- |
| AspectJ        | 编译期 | 低       | 中       | 方法打点、权限检查         |
| ASM            | 编译期 | 最低     | 高       | 大型项目插桩、字节码优化   |
| Javassist      | 编译期 | 低       | 低       | 简单插桩                   |
| APT/KSP        | 编译期 | 0        | 中       | 代码生成（路由、DI）       |
| Lancet/Booster | 编译期 | 低       | 低       | 业务 AOP 需求              |
| 动态代理       | 运行期 | 中       | 低       | 接口代理（Retrofit）       |
| Xposed/Epic    | 运行期 | 中       | 高       | Hook 第三方/系统 API       |

---

## 五、实际应用场景

| 场景                          | 推荐方案       |
| ----------------------------- | -------------- |
| 方法耗时统计                  | ASM / AspectJ  |
| 埋点（点击、曝光）            | ASM / Lancet   |
| 路由、依赖注入                | APT / KSP      |
| 权限校验                      | AspectJ        |
| Hook 系统 API（如 Toast 拦截）| Lancet / Epic  |
| 替换第三方库的 Log 实现       | ASM            |
| 线程池收敛                    | Booster        |
| 隐私合规（getDeviceId 拦截）  | ASM / Lancet   |

---

## 六、面试题

### Q1：AspectJ 和 ASM 选哪个？

- **业务简单、开发效率优先** → AspectJ，切入点表达式直接写
- **性能敏感、需要细粒度控制** → ASM，直接改字节码，编译更快、产物更小
- 饿了么 Lancet 就是嫌 AspectJ 慢，用 ASM 重写（但已停止维护，AGP 8+ 不兼容，新项目慎用）

### Q2：Transform API 和 Instrumentation API 区别？

- **Transform API**（AGP 3.x~7.0）：读取所有 class → 处理 → 写回，粒度粗，重复读写慢
- **Instrumentation API**（AGP 7.0+）：只对需要插桩的类注入 ClassVisitor，**增量友好、编译快**

AGP 8.0 已彻底移除 Transform API。

### Q3：KAPT 和 KSP 区别？

- **KAPT**：把 Kotlin 转成 Java stub，再跑 APT，**慢**
- **KSP**：直接解析 Kotlin PSI，速度快 2-3 倍，无需生成 stub

Jetpack 库（Room、Hilt）都在迁 KSP。

### Q4：埋点为什么用 AOP 而不手写？

1. **侵入小**：业务代码不用改
2. **统一管理**：埋点规则集中
3. **防漏埋**：新增页面自动插桩
4. **可动态下发**：配合注解 + 插桩实现运行时控制

### Q5：Hook 点击事件埋点怎么做？

两种方式：

**方式 1：AspectJ** 拦截 `onClick`：

```java
@Around("execution(* android.view.View.OnClickListener.onClick(..))")
public void aroundClick(ProceedingJoinPoint jp) throws Throwable {
    trackClick(jp.getArgs()[0]);
    jp.proceed();
}
```

**方式 2：ASM** 直接修改 View.setOnClickListener 的字节码，包一层代理 listener。

字节跳动的 SDK 用的是 ASM，因为 AspectJ 对 lambda 形式的 listener 不好切。

### Q6：Xposed 和 Epic 的区别？

| 维度     | Xposed                    | Epic                |
| -------- | ------------------------- | ------------------- |
| 是否需 root | 需要                       | 不需要（应用内）    |
| 原理     | Zygote fork 时注入        | ART inline hook     |
| 作用范围 | 全局（任何应用）          | 仅自身进程          |
| 场景     | 系统模组                  | 应用自身的 AOP      |

### Q7：ASM 的 Visitor 模式有哪些回调顺序？

```
ClassVisitor.visit()         ← 类头
  → visitAnnotation()        ← 类注解
  → visitField()             ← 字段
  → visitMethod()            ← 方法（返回 MethodVisitor）
       → visitCode()
       → visitXxxInsn()      ← 每条指令
       → visitMaxs()
       → visitEnd()
  → visitEnd()               ← 类结束
```

插入方法逻辑时，常用 `AdviceAdapter`，它封装了 `onMethodEnter` / `onMethodExit`。

### Q8：编译期插桩会影响增量编译吗？

会。早期 Transform API 会让所有 class 全量重处理。AGP 7.0+ 的 Instrumentation API 支持**增量插桩**，只对修改的类重跑 Visitor，编译速度提升明显。写自定义插件时要实现 `InstrumentationParameters` 且保证幂等。
