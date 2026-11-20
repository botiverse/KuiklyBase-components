# 开始

本项目基于Kotlin Multiplatform技术，构建了一套跨平台原生资源管理解决方案，支持Android、iOS及HarmonyOS三大移动端平台。通过构建时同步生成类型安全的资源访问类（Resource Class），结合Kotlin/Native（klib）/ Kotlin/Jvm（aar）的标准化资源封装机制，实现了多平台资源统一管理与编译期强校验，为开发者提供与Android R类相仿的资源调用体验。

资源管理器到底管理的是什么，或者说给使用方暴露的是什么？

答：**KMM 资源管理器根本上暴露的是指定资源在不同平台的 ID信息（例如 Android 是 R中的 id，iOS 是
bundle+name，鸿蒙是 name），需要使用者在不同平台实现对应的获取方式来获取资源。**

## 版本

|  | 稳定 | 测试 |
| ------ | ------ | ------ |
| com.tencent.kuiklybase.resource.generator | 0.1.0-raft.1 | 0.1.0-raft.1 |
| resource-core | 0.1.0-raft.1 | 0.1.0-raft.1 |
| resource-compose | 标准 `androidx.compose` / JetBrains Compose wrapper；默认不发布 | 标准 `androidx.compose` / JetBrains Compose wrapper；默认不发布 |

## gradle集成

### Maven 仓库

Raft 会把 `resource-core` 和 `com.tencent.kuiklybase.resource.generator` Gradle 插件
发布到 Raft Artifacts 和 GitHub Packages。匿名下载时，在 `pluginManagement.repositories`
和 `dependencyResolutionManagement.repositories` 中各添加
`maven("https://maven.artifacts.botiverse.dev")` 即可。使用 GitHub Packages 时，按下面的示例给两个仓库配置凭据。

```kotlin
pluginManagement {
    repositories {
        maven("https://maven.pkg.github.com/botiverse/KuiklyBase-components") {
            credentials {
                username = providers.gradleProperty("githubPackagesUsername")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orNull
                password = providers.gradleProperty("githubPackagesToken")
                    .orElse(providers.environmentVariable("GITHUB_PACKAGES_TOKEN"))
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orNull
            }
        }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.pkg.github.com/botiverse/KuiklyBase-components") {
            credentials {
                username = providers.gradleProperty("githubPackagesUsername")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orNull
                password = providers.gradleProperty("githubPackagesToken")
                    .orElse(providers.environmentVariable("GITHUB_PACKAGES_TOKEN"))
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orNull
            }
        }
        google()
        mavenCentral()
    }
}
```

### KMM工程

> >root build.gradle

```kotlin
plugins {
    id("com.tencent.kuiklybase.resource.generator").version("0.1.0-raft.1").apply(false)
}
```

> project build.gradle

```kotlin
plugins {
    id("com.tencent.kuiklybase.resource.generator")
}

commonMain.dependencies {
    implementation("com.tencent.kuiklybase:resource-core:0.1.0-raft.1")
}


multiplatformResources {
    multiplatformResourcesPackage = "net.novate.kotlin" // required
    multiplatformResourcesPrefix = "ohshow" // required
    multiplatformResourcesClassName = "MR" // optional, default MR
    iosBaseLocalizationRegion = "en" // optional, default "en"
    multiplatformResourcesSourceSet = "commonMain"  // optional, default "commonMain"
    commonGeneratedDir = "build/generated/tmm-res-common" // optional stable common MR root
    multiplatformResourcesVisibility = MRVisibility.Internal // optional（编译通过后再添加）, default Public
}
```

| 配置                               | 说明                           |
|----------------------------------|------------------------------|
| multiplatformResourcesPackage    | 生成 R 文件的包名                   |
| multiplatformResourcesPrefix     | 资源prefix 不能与其他业务重复    |       
|multiplatformResourcesClassName  | 生成 R 文件的类名称，默认 MR 可以不填       |
| iosBaseLocalizationRegion        | 暂时没有，预留可以不填                  |
|multiplatformResourcesSourceSet  | 获取 kmm 资源的平台名称，默认 commonMain |
| commonGeneratedDir               | 可选的 common MR 稳定生成根目录。设置为 `build/generated/tmm-res-common` 后，插件只把 `build/generated/tmm-res-common/commonMain/src` 注册到 `commonMain`，避免把 volatile 的默认目录 `build/generated/tmm-res/commonMain/src` 暴露给 KSP/AGP snapshot；各平台生成目录仍保持 `build/generated/tmm-res`。 |
| multiplatformResourcesVisibility | MR 可见性，可以不填，需要 Sync 完之后再次添加  |

此处对应的配置信息用于配置目录和生成文件的信息

`resource-compose` 不是 MR 生成和 `resource-core` 运行时的必需依赖。已经有自定义 UI wrapper 的工程可以只依赖
`resource-core`。当前 `resource-compose` 是标准 Compose wrapper：源码导入的是 `androidx.compose.*` API；
OHOS 路径里的 `org.jetbrains.compose.runtime/foundation` 仍保留在 `1.6.1-KBA-001`，因为纯 JetBrains
`1.7.3` artifact 没有 `ohos_arm64` native variant。默认发版流程会向 GitHub Packages 和 Raft 同时发布
`resource-core` 和 generator plugin。只有明确要为新版本发布这组标准 Compose wrapper artifact 时，才打开
`KMMRESOURCE_INCLUDE_COMPOSE=true` 和 `-PkmmResourcePublishCompose=true`。

### Kuikly Compose 兼容性

KuiklyBase 的公网产物应该对齐公网 Kuikly framework artifact 线。当前公网 Kuikly `2.4.2` 版本线里，Android
和 OHOS 使用不同 Kotlin suffix：

| 层级 | 版本 / 坐标 |
|------|-------------|
| Kuikly framework base | `2.4.2` |
| Android Maven artifacts | `com.tencent.kuikly-open:*:2.4.2-2.0.21` |
| OHOS Maven/KLIB artifacts | `com.tencent.kuikly-open:*:2.4.2-2.0.21-ohos` |
| OHOS build plugin line | `2.0.21-KBA-010` |

Kuikly Compose 暴露的是 `com.tencent.kuikly.compose.*` 类型，而当前 `resource-compose` 暴露的是
`androidx.compose.*` 类型。因此不能把当前 `resource-compose` 的发布当作 Kuikly 框架对齐。真正 Kuikly 对齐的 wrapper
应该新增独立 source/API surface，或者明确把现有 wrapper 从 `androidx.compose.*` 迁移到
`com.tencent.kuikly.compose.*`，并同时验证 `2.21.0-2.1.21` 和 `2.21.0-2.0.21-ohos` 两条版本线。

### Ohos工程(其他Platform 跳过)

#### project oh-package.json5添加

```
"dependencies": {
    "@kuiklybase/knoi": "0.0.x"
    "@kuiklybase/resource_compose": "0.0.x"
 }
```

#### project 最终build.gradle中添加

```
linkerOpts("-L${projectDir}/libs/", "-lresource_compose")
```

全路径参考

```
kotlin {
    ohosArm64 {
        binaries.sharedLib {
             linkerOpts("-L${projectDir}/libs/", "-lresource_compose")
        }
    }
}
```

#### 拷贝鸿蒙so到kmm中进行链接

在鸿蒙的 oh_modules/@qqlive/resource_compose/libs/arm64-v8a 中有一个 libresource_compose.so 的文件，拷贝到上一步配置的libs目录里 编译的时候进行链接

#### 启动任务手动初始化

```
import { initResourceCompose } from 'resource_compose';

initResourceCompose(this.context)
```

### 任务介绍和集成运行

KMM 工程

```
|---tmm-resource
|---|---generateMRcommonMain //生成 common MR 文件，sync 自动运行
|---|---generateMRohosArm64Main //生成  ohos MR 文件， 需要手动运行（或者生成最终产物时自动运行）
```

### iOS工程(其他Platform 跳过)

#### 配置最终bundle的拷贝位置(project gradle.properties)

```
resource.targetCopyDir=../Assets/
```

../Assets/ 为需要拷贝的bundle包位置,最终kmm的所有bundle会拷贝到 ../Assets/tmm-resources-apple 目录

#### 最终打出的XXXX.framework 的 podspec

```
spec.resource_bundles = { spec.name => ['Assets/tmm-resources-apple/*.{xcassets,bundle}'] }
```

增加当前framework的resource_bundles的指定目录，执行pod install的时候会自动拷贝到iOS工程

#### 启动页面初始化podName

在调用iOS资源之前（最好是启动任务中初始化kmm pod 的 name）(默认是腾讯视频的QLMM 需要你重新初始化，不然比找不到包)

```
com.tencent.tmm.kmmresource.resource.utils.podName = "XXXXX" //"XXXXX" 是你最终的pod的名称

com.tencent.tmm.kmmresource.resource.utils.isDebug = true //将启动全量bundle扫描
```

### 任务介绍和集成运行

新增资源之后需要link结束之后执行pod install 然后运行ios工程即可

## 使用

### 文件组织方式

```
commonMain //KMM 共享层名称 可在gradle中配置
|---resources
|---|---MR // Multi Resource 共享资源目录名
|---|---|---base //字符串资源存放目录 string plurals资源存放目录
|---|---|---|---strings.xml
|---|---|---|---plurals.xml
|---|---|---colors //颜色资源存放目录
|---|---|---|---colors.xml
|---|---|---images //图片资源存放目录 建议直接存放webp 减小图片体积
|---|---|---|---home_back@1x.webp //具体文件命名方式 见image图片使用规则
|---|---|---|---home_back@2x.webp
|---|---|---fonts //字体资源存放目录
|---|---|---|---qqlive_font.otf
|---|---|---files //raw资源存放目录
|---|---|---|---file_name.txt
|---|---|---assets //assets资源存放目录
|---|---|---|---file_name.txt
```

### 公共的资源

```
implementation(project(":foundation:resource"))
```

还记得这个工程吗，如果你需要共享你的图片或者使用共享图片，你可以在这个工程下添加各种资源。然后使用这个项目的资源包名下的
MR 进行使用即可

### String

标准使用

在 `commonMain/resources/base/string.xml` 里添加string信息

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<resources>
    <string name="hello_world">Hello World</string>
</resources>
```

添加资源后，我们可以调用 gradle 同步或执行 gradle
任务generateMRcommonMain。这将生成一个包含`MR.strings.hello_world`的 MR 类，我们可以在 commonMain 中使用
此处`MR.strings.hello_world`，只是记录三端存放信息的类，具体的值需要在三端自行获取。当然，我们直接提供了可以在
compose 中使用的方法，见下文

```
MR.strings.hello_world
```

### Format字符串

添加需要format的字符串

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<resources>
    <string name="hello_world">Hello World</string>
    <string name="my_string_formatted">My format \'%s\'</string>
</resources>
```

```
MR.strings.my_string_formatted
```

### plural string

第一步是在 commonMain/resources/MR/base 中创建一个文件plurals.xml，其中包含以下内容

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<resources>
    <plural name="my_plural">
        <item quantity="zero">zero</item>
        <item quantity="one">one</item>
        <item quantity="two">two</item>
        <item quantity="few">few</item>
        <item quantity="many">many</item>
        <item quantity="other">other</item>
    </plural>
</resources>
```

```
MR.plurals.my_plural
```

### 图片

将图像放置在 `commonMain/resources/MR/images` 目录中

目前支持

```
png / jpg / webp / svg / gif
```

图片(除svg)名的后缀必须为以下的其中一个

| 后缀     | Android | iOS    | Ohos    |
--------|---------|--------|---------|
| @0.75x | ldpi    |        |
| @1x    | mdpi    | ios 1x | mdpi    
| @1.5x  | hdpi    |        |
| @2x    | xhdpi   | ios 2x | xldpi   
| @3x    | xxhdpi  | ios 3x | xxldpi  
| @4x    | xxxhdpi |        | xxxldpi

例如

- home_black_18@1x.png
- home_black_18@2x.png

#### svg

将svg图像放置在 to commonMain/resources/MR/images:

- car_black.svg

图片会生成对应的 `MR.images.xxxx`
对于的资源，在使用图片库时只需传入腾讯视频封装好的Image 中即可，如果需要自行处理，则需要进行三端的各自的获取方式获取后统一处理

```
MR.images.xxxx
```

### 字体

字体资源目录是 `commonMain/resources/MR/fonts`。
字体名称应采用以下模式：`<fontFamily>-<fontStyle>`，例如

- Raleway-Bold.ttf
- Raleway-Regular.ttf
- Raleway-Italic.ttf

支持 ttf 和 otf 格式的资源

```
MR.fonts.Raleway.italic
```

### Colors

颜色资源目录为 `resources/MR/colors`
你可以使用以下文件的方式来定义不同的Color

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- format: #RRGGBB[AA] or 0xRRGGBB[AA] or RRGGBB[AA] where [AA] - optional -->
    <color name="valueColor">#B02743FF</color>
    <color name="referenceColor">@color/valueColor</color>
</resources>
```

使用

```
MR.colors.valueColor
```

注意： 换肤或者暗黑模式请联系对应同学咨询

### assets access

放置在  `commonMain/resources/MR/assets`

使用

```
MR.assets.test
```

### file

第一步是创建一个资源文件 test.txt 例如，在 `commonMain/resources/MR/files` gradle 同步后我们可以通过
id 获取文件 `MR.files.test`
使用

```
MR.files.test
```

> assets 资源的使用，需要结合使用场景和三端的不同实现，自行实现处理后 compose 或者 common
> 端可以使用的数据结构，然后在 common 层使用

# compose 套件

## String

在commonMain的Compose下获取

```
val string: String = stringResource(MR.strings.hello_world)
```

在commonMain的Compose下获取需要格式化的字符串

```
val string: String = stringResource(MR.strings.my_string_formatted,"format_value")
```

在commonMain的Compose下获取plural

```
val string: String = stringResource(MR.plurals.my_plural, quantity)
```

## 图片

图片需要结合Compose 图片库的腾讯视频 Image 使用，只需在 commonMain的Compose中传入对于的资源引用即可

```
Image(MR.images.home_black_18)
```

## 字体

使用

```
val fontFamily: FontFamily = fontFamilyResource(MR.fonts.Raleway.italic)

Text(
        fontFamily = fontFamilyResource(MR.fonts.cormorant.italic),
)
```

## Color

在 compose 中使用

```
val color: Color = colorResource(MR.colors.valueColor)
```

## MR文件生成

集成`id("tmm-resource-generator")`插件之后，Android Stuido Gradle 的 Task 下回增加一个`tmm-resources`
目录的 task，其中包含两个任务

- generateMRcommonMain (用于生成 common 层的 MR 文件，sync 会自动执行)
- generateMRxxxxMain (用于生成 xxxx 平台层的 MR 文件，sync
  不会自动执行，打包鸿蒙时会自动运行，如果需要在开发中使用，可以手动执行)

# 实现思路（介绍模块的具体实现）

## 类

### MR

此类最终会使用Gradle插件动态生成，**根据文件组织方式映射成具体的MR expect和actual实现，业务层无需关注实现逻辑
**，更新资源后调用sync即可动态生成

#### common层

```kotlin
public expect object MR {
    public object strings : ResourceContainer<StringResource> {
        public val hello_world: StringResource
    }
}
```

#### Android层

```kotlin
public actual object MR {
    public actual object strings : ResourceContainer<StringResource> {
        public actual val hello_world: StringResource = StringResource(R.string.hello_world)
    }
}
```

#### 鸿蒙层

```kotlin
public actual object MR {
    public actual object strings : ResourceContainer<StringResource> {
        public actual val hello_world: StringResource = StringResource("模块名_hello_world")
    }
}
```

## ResourceContainer

此类用于持有和本资源相关的平台资源，如果没有则空实现。例如iOS平台会持有含改资源的bundle信息，用于最终的资源获取

### XXXResource

| common层         |     Android     | 鸿蒙       | iOS                                    |
|-----------------|-----------|:---------|:---------------------------------------|
| StringResource     | id        | resName  | bundle+resourceId                      |
| ColorResource   |     id       |     resName | bundle+name                            |
| PluralsResource |     id       |     resName | bundle+resourceId                      |
| ImageResource   | id        | resName  | bundle+assetImageName                  |
| FontResource    |     id       |     path    | bundle+fontName                        |
| AssetResource   |     path     |     path    | bundle+fileName+extension+originalPath |
| FileResource    |     id       |     path    | bundle+fileName+extension              |

这些Resource会在各个平台中，不同实现，记录各个平台对于访问资源的唯一id
方法

### stringResource

```kotlin
@Composable
expect fun stringResource(resource: StringResource): String

@Composable
expect fun stringResource(resource: StringResource, vararg args: Any): String

@Composable
expect fun stringResource(resource: PluralsResource, quantity: Int): String
```

此方法会根据传入的Resource记录和对应的参数，结合多语言local返回当前正确的string取值

### painterResource(暂定，需要根据 Image 库提供的方法使用)

```kotlin
@Composable
expect fun painterResource(imageResource: ImageResource): Painter
```

此方法根据传入的图片Resource，获取对应的资源后包装成Compose的Painter对象，这块需要Image组件同事的协助

### fontFamilyResource

```kotlin
@Composable
fun fontFamilyResource(fontResource: FontResource): FontFamily {
    return fontResource.asFont()
        ?.let { FontFamily(it) }
        ?: FontFamily.Default
}

@Composable
expect fun FontResource.asFont(
    weight: FontWeight = FontWeight.Normal,
    style: FontStyle = FontStyle.Normal,
): Font?
```

此方法根据传入的字体Resource，获取对应的资源后包装成Compose的Font对象，已经测试通过在`0.0.3-SNAPSHOT`
中引入

### colorResource

```kotlin
@Composable
expect fun colorResource(resource: ColorResource): Color
```

此方法根据传入的颜色Resource，获取对应的资源后包装成Compose的Color对象

### FileResource

fileResource会记录文件的rawResId（Android，path（iOS），extension（iOS）和bundle（iOS）的信息，可以吧文件作为文本或者特定的buff等读取，需要自己实现对应的读取实现（三端）

### AssetResource

类似FileResource，Android会根据path存放在asset目录下，iOS可以根据bundle.pathForResource等方法获取具体的文件

# 鸿蒙端注入接口和 KMM 工程获取鸿蒙端资源的类

## 鸿蒙端注入获取资源信息的方法

```js
import { OhosResourceService } from './KmmResource';

export class OhosResourceServiceImpl implements OhosResourceService {
  getString(resName: string): string;

  getString(resName: string, args: Array<string | number>): string;

  getString(resName: string, args?: Array<string | number>): string {
    if (args != null && args.length > 0) {
      return getContext().resourceManager.getStringByNameSync(resName, ...args)
    } else {
      return getContext().resourceManager.getStringByNameSync(resName)
    }
  }


  getPlural(resName: string, args: number): string {
    return getContext().resourceManager.getPluralStringByNameSync(resName, args)
  }

  getImage(resName: string): Uint8Array {
    return getContext().resourceManager.getMediaByNameSync(resName)
  }

  getColor(resName: string): number {
    return getContext().resourceManager.getColorByNameSync(resName)
  }

  getImageBase64(resName: string): string {
    return getContext().resourceManager.getMediaBase64ByNameSync(resName)
  }

  getFile(resName: string): Uint8Array {
    return getContext().resourceManager.getRawFileContentSync(resName)
  }
}
```

以上是需要鸿蒙端native 调用 arkts注入的方法及实现代码，在鸿蒙工程启动时注入。

## KMM 工程获取鸿蒙资源可以调用的 Knoi Service 类

因为 Kotlin Mulit 提供了 iOS 和 Android 的调用方式，所以根据提供的资源信息，可以直接使用对应的方法获取到资源，但是
Kotlin 并未提供直接调用鸿蒙的方法，所以我们提供了注入的接口来获取对应的资源。不同于 iOS 和
Android，目前只支持指定基础类型的获取方式，并不能和 Android 和 iOS 一样直接转。所以需要在使用鸿蒙资源的时候通过
ByteArray 获取到信息之后再转换成自己需要的数据才可以使用。

```
不建议直接使用
```

```kotlin
@ServiceConsumer
interface OhosResourceService {
  fun getString(resName: String): String?

  fun getString(resName: String, vararg args: Any): String?

  fun getPlural(resName: String, args: Number): String?

  fun getImage(resName: String): ArrayBuffer?

  fun getColor(resName: String): Int?

  fun getImageBase64(resName: String): String?

  fun getFile(resName: String): ArrayBuffer?

}
```

调用方式参考（建议使用），其他的同理类推即可

```kotlin
object OhosKmmResourceManager {


  fun getString(resName: String): String? {
    val resResult = getOhosResourceServiceApi().getString(resName)
    return resResult
  }

  fun getString(resName: String, vararg args: Any): String? {
    val resResult = getOhosResourceServiceApi().getString(resName, args)
    return resResult
  }


  fun getPlural(resName: String, args: Number): String? {
    val resResult = getOhosResourceServiceApi().getPlural(resName, args)
    return resResult
  }

  fun getImage(resName: String): ArrayBuffer? {
    val resResult = getOhosResourceServiceApi().getImage(resName)
    return resResult
  }

  fun getColor(resName: String): Int? {
    val resResult = getOhosResourceServiceApi().getColor(resName)
    return resResult
  }

  fun getImageBase64(resName: String): String? {
    val resResult = getOhosResourceServiceApi().getImageBase64(resName)
    return resResult
  }


  fun getFile(resName: String): ArrayBuffer? {
    val resResult = getOhosResourceServiceApi().getFile(resName)
    return resResult
  }


}
```

# QA

### sync 报错

```
Caused by: java.lang.RuntimeException: Please configure tmm-resources multiplatformResourcesPackage
```

需要在使用插件的工程 build.gradle 配置,必配置选项

```
multiplatformResources {
    multiplatformResourcesPackage = "com.tencent.tmm.ohshow" // required
    multiplatformResourcesPrefix = "ohshow" // required
}
```
