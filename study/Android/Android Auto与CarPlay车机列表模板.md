# Android Auto 与 CarPlay：为什么车机列表要用模板

手机上的 `ListView`、`RecyclerView` 或 Flutter `ListView`，通常不能作为 Android Auto / CarPlay 的应用页面直接显示到车机屏幕。两套平台都为驾驶场景提供了受约束的界面能力：应用提供内容和操作，车机系统或宿主负责呈现适合车内交互的 UI。这些限制服务于驾驶安全和减少分心，具体可用的应用类别、模板与交互还受平台规则约束。不要把“车机支持投屏”理解为“任意手机 UI 都能镜像”。

> 这里讨论的是 **Android Auto、采用 Car App Library 的 Android Automotive OS 应用，以及 CarPlay 模板应用**。Android Automotive OS 还支持一些其他应用形态，例如停车状态下的界面；不能把所有车机应用都概括成仅能使用 `ListTemplate`。

## 一、平台与列表组件对照

| 平台 | 应用运行位置 | 列表模板 | 列表数据组件 | 谁负责渲染 |
| --- | --- | --- | --- | --- |
| Android Auto | 手机，投射到车机 | `ListTemplate` | `ItemList` + `Row`；分组时用 `SectionedItemList` | Android Auto 宿主 |
| Android Automotive OS（模板应用） | 车机本机 | `ListTemplate` | `ItemList` + `Row`；分组时用 `SectionedItemList` | 车机模板宿主 |
| Apple CarPlay | iPhone，连接车机 | `CPListTemplate` | `CPListSection` + `CPListItem` | iOS / CarPlay |

这意味着列表内容和交互由应用提供，但字号、布局、可见数量及部分操作限制由平台和车机环境决定。不能把任意控件塞进一个 `Row` 或 `CPListItem`。

## 二、Android：Car App Library 的 `ListTemplate`

Google 的 `androidx.car.app`（Car App Library）提供车机专用模板。一个不分组的列表使用 `ListTemplate.Builder().setSingleList(ItemList)`；需要分组时使用 `addSectionedList(SectionedItemList)`。**两种模式不能混用**。[`ListTemplate.Builder` API](https://developer.android.com/reference/androidx/car/app/model/ListTemplate.Builder)

下面展示 `Screen.onGetTemplate()` 返回列表模板的核心代码：

```kotlin
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

override fun onGetTemplate(): Template {
    val items = ItemList.Builder()
        .addItem(
            Row.Builder()
                .setTitle("第一项：离线地图包")
                .addText("大小 450 MB")
                .build()
        )
        .addItem(
            Row.Builder()
                .setTitle("第二项：电子眼数据")
                .addText("已是最新版本")
                .build()
        )
        .build()

    return ListTemplate.Builder()
        .setHeader(
            Header.Builder()
                .setTitle("车机上的列表页")
                .setStartHeaderAction(Action.APP_ICON)
                .build()
        )
        .setSingleList(items)
        .build()
}
```

`setHeader` 是较新的写法，需要支持相应的 Car App API level；旧示例常见的 `ListTemplate.Builder.setTitle` 在新版库中已弃用。实际接入还需要 `CarAppService`、`Session`、`Screen`、manifest 声明，以及满足对应类别和发布要求。音频应用也有自己的媒体接口路径，不能假设所有音频应用都应直接改成 `ListTemplate`。[Car App Library 指南](https://developer.android.com/training/cars/apps/library)、[Android for Cars 应用类别](https://developer.android.com/training/cars)

## 三、iOS：CarPlay 的 `CPListTemplate`

CarPlay 的列表由 `CPListTemplate` 组织，一个或多个 `CPListSection` 持有 `CPListItem` 等条目。应用负责提供内容，CarPlay 负责在车机屏幕上渲染。模板是否可用取决于应用类别和 CarPlay entitlement。[Apple CarPlay 设计指南](https://developer.apple.com/design/human-interface-guidelines/carplay)、[`CPListTemplate` API](https://developer.apple.com/documentation/carplay/cplisttemplate)

```swift
import CarPlay

// 这段代码运行在已获得 interfaceController 的 CarPlay 场景中。
let item = CPListItem(
    text: "精选歌单",
    detailText: "播放中",
    image: nil
)
let section = CPListSection(items: [item])
let template = CPListTemplate(
    title: "音乐列表",
    sections: [section]
)

interfaceController.pushTemplate(template, animated: true)
```

完整应用还需要配置 CarPlay scene、相应 entitlement，并根据业务实现列表项的选择行为。Apple 对不同类别允许使用的模板、层级和条目数量有具体限制；例如可通过 `CPListTemplate.maximumItemCount`、`maximumSectionCount` 查询当前限制。不能假设导航、音频、通信、停车等类别都能使用完全相同的界面。[Apple CarPlay Developer Guide](https://developer.apple.com/download/files/CarPlay-Developer-Guide.pdf)

## 四、Flutter 项目怎么接入

Flutter 页面本身不会自动转换成车机模板。常见结构是：

```text
共享业务数据 / 状态
       ├─ 手机端：Flutter Widget 页面
       ├─ Android 车机端：Car App Library Screen + Template
       └─ iOS 车机端：CarPlay Scene + CPTemplate
```

如果业务逻辑在 Dart 层，可在平台允许的生命周期内通过插件或 Platform Channel 协调数据；车机页面仍由对应平台的车机 API 构建。设计数据模型时，先考虑“驾驶时是否需要显示和操作”，再映射到平台允许的模板与应用类别。

## 参考资料

- [Android：Use the Android for Cars App Library](https://developer.android.com/training/cars/apps/library)
- [Android：ListTemplate.Builder API](https://developer.android.com/reference/androidx/car/app/model/ListTemplate.Builder)
- [Apple：CarPlay Human Interface Guidelines](https://developer.apple.com/design/human-interface-guidelines/carplay)
- [Apple：CPListTemplate API](https://developer.apple.com/documentation/carplay/cplisttemplate)
- [Apple：CarPlay Developer Guide](https://developer.apple.com/download/files/CarPlay-Developer-Guide.pdf)
