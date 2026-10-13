# JavaFX WebView + GraalVM Native Image 调研结论

**后续实测更新：** 本文是先前资料调研的历史结论。随后已在 macOS arm64 上用标准
GraalVM/JDK 25.0.2 与 JavaFX 25.0.2 实际构建并运行 WebView 和双向 Bridge，正常关闭与
隔离启动通过；同时发现必须处理首线程启动、JNI metadata 和 WebKit 的库加载依赖。
因此本文的“JDK25 未验证”和 Gluon 候选路线不代表当前全部运行证据，详见
[macOS 原生 PoC](../experiments/javafx-webview-native-image/README.md)。

调研日期：2026-10-13（Asia/Shanghai）  
范围：`javafx.web` 的真实 WebKit WebView；macOS arm64 优先，其次 Windows x64。  
状态：文献与源码调研；没有建立或运行 PoC，也没有做 Benchmark。

## 结论

建议进入一个**仅限 macOS 的独立 Native Image PoC**，前提是 PoC 可以使用独立的 JDK 17 工具链。Gluon 官方 Native Image 功能矩阵把 macOS 的 `web` 标为支持、Windows 的 `web` 标为不支持；Gluon Substrate 的 macOS 链接代码也会在项目启用 Web 时加入静态 `jfxwebkit` 和 WebKit 依赖。这比普通 JavaFX 控件示例更直接地证明了路线目标是 `javafx.web`。官方文档另列 macOS AArch64 为 Native Image 架构，源码也含 arm64 目标分支；但平台能力表没有逐项展开“macOS AArch64 × web”的交叉组合，因此 arm64 仍须由 PoC 实际运行确认。[Gluon 平台矩阵](https://docs.gluonhq.com/#_platforms)、[Substrate macOS 链接源码](https://github.com/gluonhq/substrate/blob/0.0.69/src/main/java/com/gluonhq/substrate/target/MacOSTargetConfiguration.java)

这不构成迁移建议：PoC 的价值是确认 macOS 路线能否满足页面、本地资源、双向 Bridge、关闭和无 JVM 分发要求，并评估它带来的旧工具链成本。当前项目以 JDK 25 为基线，而 Gluon 1.0.29 实际依赖的 Substrate 0.0.69 默认仍使用 JavaFX static SDK `21-ea+11.3`、Java static SDK `18-ea+prep18-9`；Gluon 面向 Apple Silicon 的安装说明仍给出 Gluon GraalVM 22.1.0.1 / JDK 17。issue #543 报告了一个 Windows x64 + 插件 1.0.27 + Oracle GraalVM 25 的失败案例，错误发生于 JavaFX Glass 初始化，issue 中另有一条非维护者身份的社区回复列出其理解的兼容组合；这不是维护者确认，也不能外推到 macOS、1.0.29 或 JavaFX WebView。当前官方 macOS 指南仍指定 JDK17，因此本轮没有依据宣称 JDK 25 可用。[GluonFX 1.0.29 的 Substrate 依赖](https://repo.maven.apache.org/maven2/com/gluonhq/gluonfx-maven-plugin/1.0.29/gluonfx-maven-plugin-1.0.29.pom)、[Substrate 0.0.69 常量](https://raw.githubusercontent.com/gluonhq/substrate/0.0.69/src/main/java/com/gluonhq/substrate/Constants.java)、[Gluon macOS AArch64 要求](https://docs.gluonhq.com/#_macos)、[issue #543](https://github.com/gluonhq/gluonfx-maven-plugin/issues/543)

Windows x64 暂不进入 JavaFX WebView Native Image PoC：当前 Gluon 官方矩阵明确不支持 Native Image `web`。这是该工具链的声明，不是对所有 JavaFX、GraalVM 版本及定制补丁的普遍否定。标准 OpenJFX + Oracle GraalVM 没找到官方的 `javafx.web` Native Image 构建配方或包含双向 Bridge 的可复现官方样例；该路线记录为“未确认”，不能从 JavaFX 控件可运行推断 WebView 可运行。

**证据等级说明：**“官方声明”指官方文档或维护者答复；“官方源码”表示源代码中存在相应路径，但不证明发布二进制已通过运行；“外部案例”表示第三方案例且只覆盖其明示范围；“本项目实测”仅用于本项目亲自运行的结果；“未知”表示本轮无足够证据。下表的 PoC 验收项全部“未验证”。

## 版本与平台矩阵

| 路线 / 平台 | JDK、JavaFX、Native Image 构建工具 | `javafx.web` 与 WebKit | 双向 Bridge / 资源 / 独立运行 | 证据与判断 |
| --- | --- | --- | --- | --- |
| OpenJFX + GraalVM，macOS arm64 | OpenJFX 26 API 文档发布于 2026-07；Oracle GraalVM 25 的 Native Image 文档可用。没有找到 OpenJFX 官方 Native Image 配方。 | OpenJFX 将 WebView 定义为 `javafx.web` / WebKit 功能；标准 JavaFX macOS arm64 JVM 分发有 WebKit，但这并不证明可 Native Image。 | WebEngine API 声明支持本地 URL、`loadContent`、`executeScript` 和 Java 对象桥接；Native Image 对动态反射、JNI、classpath 资源要求元数据。哪些 JavaFX/WebKit 动态路径需要何种元数据未确认。 | WebView API：官方声明；Native Image 支持：未确认；原生运行：未验证。[JavaFX 26 WebView](https://docs.oracle.com/en/java/java-components/javafx/26/docs/javafx.web/javafx/scene/web/WebView.html)、[Native Image metadata](https://www.graalvm.org/jdk25/reference-manual/native-image/metadata/) |
| OpenJFX + GraalVM，Windows x64 | 同上。 | JVM 下的 JavaFX `javafx.web` 可用于 Windows；无官方 Native Image WebView 方案或原生运行样例。 | API 能力同左；本地资源打包、Bridge metadata、无 JVM 分发均未确认。 | 未确认；没有因一个失败组合判定所有组合不支持。 |
| GluonFX + Substrate，macOS arm64 | 当前 GluonFX Maven Plugin **1.0.29**；它声明支持 JavaFX 11+，但官方文档中平台样例与推荐的 macOS AArch64 GraalVM 是 Gluon 定制 GraalVM **22.1.0.1 / JDK 17**。已发布的插件 1.0.29 依赖 Substrate 0.0.69；其源码默认 static SDK 为 JavaFX `21-ea+11.3`、Java static SDK `18-ea+prep18-9`。与项目 JDK 25 不同。 | Gluon 官方平台矩阵：macOS Native Image 的 `web` 为支持；Substrate 0.0.69 macOS 链接源码在 `hasWeb()` 时链接静态 `libjfxwebkit.a` 与 WebKit 组件。矩阵没有把架构和 feature 交叉展开；arm64 架构列、`MIN_MACOS_AARCH64_VERSION="12"` 和链接源码提供了 arm64 路径证据，仍需原生 PoC 核验发布资产。 | Gluon 文档有 WebKit resource bundles 默认项、额外资源配置、JavaFX 默认 reflection/JNI 列表；应用自己的 HTML/JS 要核实包含规则。没有找到由维护者维护且演示 WebView + 双向 Bridge + Native Image 的官方样例。独立 Native Image 目标无 JVM fallback；发布包/系统依赖仍须实测清点。 | Web 支持：官方声明；arm64 静态链接：官方源码；WebView/Bridge 实际 Native Image 运行：未验证。[平台矩阵](https://docs.gluonhq.com/#_platforms)、[macOS 链接代码](https://github.com/gluonhq/substrate/blob/0.0.69/src/main/java/com/gluonhq/substrate/target/MacOSTargetConfiguration.java)、[Substrate static SDK 常量](https://raw.githubusercontent.com/gluonhq/substrate/0.0.69/src/main/java/com/gluonhq/substrate/Constants.java) |
| GluonFX + Substrate，Windows x64 | GluonFX 1.0.29 + GraalVM 的通用 Native Image Windows x64 目标存在；实际 Web 支持为否。官方文档列出的 Maven 约束为 Maven 3.8.8 或更低，但 release notes 对后续 Maven 兼容性有不一致表述，须以固定依赖实跑为准。 | Gluon 官方 Native Image feature matrix：Windows `web` 为不支持。JavaFX 控件支持不改变此结论。 | 不建议将 Native Image HTML/Bridge/分发验收当作已有路线。另行构建定制 WebKit、修改 Substrate 或以其他 WebView 实现替换，均属于维护者未提供的定制路线。 | 官方声明：不支持。该结论限于 Gluon Native Image 路线。[Gluon 平台矩阵](https://docs.gluonhq.com/#_platforms) |

### 版本状态与兼容边界

- Gluon 官方文档在检索日显示 Maven 插件 **1.0.29** 为 latest stable；其发布日期为 **2026-06-18**。本报告检索日期是 **2026-10-13**，没有把之后的版本或未来状态纳入结论。[Gluon release notes](https://docs.gluonhq.com/)、[Maven Central 1.0.29 元数据](https://repo.maven.apache.org/maven2/com/gluonhq/gluonfx-maven-plugin/1.0.29/gluonfx-maven-plugin-1.0.29.pom)
- 1.0.29 的发布 POM 固定依赖 Substrate 0.0.69；release 对应源码默认 static SDK 仍是 JavaFX `21-ea+11.3` / Java `18-ea+prep18-9`。Gluon 文档示例中还出现过 `21-ea+11.1` 与较早 Java static SDK 值，说明文档/示例并非都与当前发布常量一致；PoC 必须按实际解析到的 1.0.29 / 0.0.69 产物记录版本，不能照抄旧示例作为已验证组合。[1.0.29 POM](https://repo.maven.apache.org/maven2/com/gluonhq/gluonfx-maven-plugin/1.0.29/gluonfx-maven-plugin-1.0.29.pom)、[0.0.69 常量](https://raw.githubusercontent.com/gluonhq/substrate/0.0.69/src/main/java/com/gluonhq/substrate/Constants.java)
- Gluon 官方 macOS 指南仍指定 Gluon 自有 GraalVM CE **22.1.0.1、JDK 17**，并提供 Apple Silicon 构建。Gluon issue #543 于 **2025-09-20** 报告 plugin 1.0.27 + Oracle GraalVM 25 的 Windows 案例在启动 Glass 时失败；issue 中次日有一条社区回复称其理解的插件/自有 GraalVM 兼容组合，但发言者并非维护者。该 issue 截止本次调研仍 open；失败组合仅是 Windows + 控件启动案例，不证明 macOS WebKit、插件 1.0.29 或其他版本全面失败。[官方 macOS 要求](https://docs.gluonhq.com/#_macos)、[issue #543](https://github.com/gluonhq/gluonfx-maven-plugin/issues/543)
- Gluon 平台矩阵的 macOS `web` 标记是重要的官方支持声明，但不报告对应 JavaFX static SDK、GraalVM、Native Image、操作系统补丁版本的完整交叉矩阵，也没有覆盖 JDK 25。它不能替代 PoC。

## WebView、Bridge 与本地页面要求

JavaFX API 层面，WebEngine 能按 URL 加载页面，也能以 `loadContent` 加载字符串；`executeScript` 可由 Java 执行 JavaScript 并取得基本类型或 `JSObject` 返回值。JavaScript → Java 可用 `JSObject.setMember("app", bridge)` 暴露 Java 对象。文档明确对象绑定使用**弱引用**，应用必须持有 bridge 的强引用；对象应是 JavaFX `javafx.web` 可反射访问的公开类/方法。如果应用使用具名模块，须对 `javafx.web` 开放相应包。`WebEngine`、`WebView` 和 DOM/脚本交互须在 FX Application Thread 上处理。[JavaFX 21 WebEngine API](https://docs.oracle.com/en/java/java-components/javafx/21/docs/javafx.web/javafx/scene/web/WebEngine.html)、[JavaFX 26 WebView API](https://docs.oracle.com/en/java/java-components/javafx/26/docs/javafx.web/javafx/scene/web/WebView.html)

以上是**JavaFX API 声明**，不是 Native Image 通过证据。Native Image 官方文档指出反射、JNI 与运行时 classpath 资源可能需要 reachability metadata；Gluon 插件提供默认 JavaFX reflection/JNI 列表以及应用追加列表。PoC 应明确公开桥接类和方法的 reflection metadata，并检查 bridge 被脚本调用的真实路径；是否还需要 JavaFX/WebKit 以外的 JNI 配置，须通过 native 构建日志和执行确定。JS 与 Java 的线程边界、导航期间旧 JSObject 的有效期，以及正常退出时 WebKit 线程/资源关闭也都要实际验证。[Gluon resource/reflection/JNI 配置](https://docs.gluonhq.com/#_configuration)、[Native Image metadata 分类](https://www.graalvm.org/jdk25/reference-manual/native-image/metadata/)

本地页面建议用 classpath URL 加载随应用构建的简短页面；`loadContent` 字符串可作为最低限度烟雾页，但它无法替代对相对链接与本地 CSS/JS 文件的验证。Gluon 文档的 JavaFX 默认资源 bundles 包含 `com.sun.webkit.*` 的 WebKit 文本资源，默认普通资源后缀包括 CSS/JSON/图像等；文档列出的默认后缀没有 HTML 或 JS，应为应用的 HTML/JS 显式配置 resource pattern，随后检查生成配置与原生程序实际加载结果。[Gluon resource 配置](https://docs.gluonhq.com/#_resources)

首轮只要求单个 HTML 和 inline JavaScript。ES modules、`fetch`、Worker、history、文件协议 origin/CORS 和目录访问应作为后续页面兼容验证；本轮没有证据确认 JavaFX WebKit 对这些能力达到项目当前自定义 scheme 的行为。本地文件加载成功本身也不能证明模块化前端正常。

## 可复现 PoC 路线与验收

在仓库外或独立目录建立最小实验项目，不加入根 Maven reactor。**首选 macOS-only、JDK 17 对照路线**：GluonFX Maven Plugin 1.0.29（其发布依赖 Substrate 0.0.69）+ Gluon GraalVM CE 22.1.0.1 的 macOS AArch64 / JDK17 发行包，按 0.0.69 发布源码核对 static SDK 实际版本；Maven 使用 Gluon 文档允许的 3.8.8 或更低版本，并满足 Apple 编译工具链要求。应用本身显式依赖 `javafx.controls` 和 `javafx.web`，API 依赖须与 JDK17及候选静态 SDK 匹配，不能直接沿用 JavaFX25/26；具体依赖版本在创建 PoC 时核对并锁定。JavaFX WebView 创建窗口、读取打包本地 HTML、Java 执行一段脚本、脚本回调一个持有强引用的公开 Java 对象，然后窗口关闭并返回 0。此候选是**文档支持边界内的验证组合**，不是报告认定已经兼容的矩阵；JDK 17 相对项目 JDK 25 是明确成本。[Gluon macOS/AArch64 要求与命令流程](https://docs.gluonhq.com/#_macos)、[1.0.29 POM](https://repo.maven.apache.org/maven2/com/gluonhq/gluonfx-maven-plugin/1.0.29/gluonfx-maven-plugin-1.0.29.pom)

按官方插件流程先用 `mvn gluonfx:run` 确认 JVM WebView API/页面逻辑，再用 `mvn gluonfx:build` 生成 Native Image，并用 `mvn gluonfx:nativerun` 执行原生程序。后两个目标和命令来自插件官方指南；在实验开始时应先将实际解析的 JavaFX SDK、static SDK、GraalVM、Maven、macOS 和架构写入日志，不能将上述候选默认值静默继承为兼容结论。[GluonFX goals](https://docs.gluonhq.com/#_native_applications)

PoC 必须验证并记录：

1. 窗口和 WebView 原生启动，页面 DOM 出现唯一标记。
2. 本地 HTML 由实际分发资源加载；HTML、脚本和样式均纳入资源清单。
3. Java → JavaScript 执行后读回页面状态；JavaScript → Java 回调触发 Java 侧可观测结果。
4. Java Bridge 对象被强引用；具名模块场景验证 `opens ... to javafx.web`，Native Image 反射 metadata 是否完整。
5. 用户关闭窗口后 Native Image 进程按时退出、退出码为 0；重复启动/关闭无挂起。
6. 在没有 JDK/JVM 的干净 Apple Silicon macOS 环境运行同一原生包；记录 App 包/二进制、随包资源、动态依赖和最低 macOS 要求。Substrate 0.0.69 源码写明 AArch64 macOS deployment target 12，且链接 AppKit/Foundation 等系统框架和静态 WebKit 组件；实际打包内容仍须检查。[Substrate macOS 链接源码](https://github.com/gluonhq/substrate/blob/0.0.69/src/main/java/com/gluonhq/substrate/target/MacOSTargetConfiguration.java)

项目 JDK 25 兼容性是独立的后续门槛：只有 Gluon 发布了明确对应的 JDK 25 Native Image 工具链/JavaFX static SDK，或某组合经受控 PoC 证明可用后，才把 JDK25 作为尝试路线。不要拿 Oracle GraalVM 25 + Gluon static libraries 直接作为基线；issue #543 仅提供一个 Windows x64 + plugin 1.0.27 组合的 JNI 失败案例，不支持其他版本判断。Windows x64 暂不拟定“可执行”的 WebView 原生路线，因为官方 feature matrix 当前标为不支持；若未来出现新的官方支持声明，再单独重新开矩阵与验证。

## 无 JVM 分发、更新与许可证成本

- Gluon `gluonfx:build` / `gluonfx:nativerun` 是 Native Image 编译和原生运行目标，符合“无已安装 JVM”的目标类型；它不同于 OpenJFX 的 JVM 启动或 `jlink`。macOS WebKit 通过静态库参与链接，因此预计不需额外分发 `jfxwebkit.dylib`，但这是源码层链接路径，不能代替对最终 Mach-O、App 包和运行时依赖的检查。系统框架、最低 macOS 版本和应用页面资源仍是完整交付的一部分。[Gluon goals](https://docs.gluonhq.com/#_native_applications)、[Substrate macOS 链接源码](https://github.com/gluonhq/substrate/blob/0.0.69/src/main/java/com/gluonhq/substrate/target/MacOSTargetConfiguration.java)
- 本轮未发现 Gluon Native Image 自动切回 JVM 的机制；若原生程序失败，JVM 版本应当作为独立构建/发行变体，而非把它标成无 JVM 通过。
- 更新责任比单一 Maven 依赖更重：需同步 GluonFX 插件、Substrate、Gluon 定制 GraalVM、JavaFX static SDK 与其 WebKit 静态库，并验证 macOS SDK/Xcode 变更。Gluon 自定义 Native Image 对 GraalVM 发行版有约束；该约束叠加旧 JavaFX static SDK 构成维护成本。当前插件文档还要求 Maven 3.8.8 或更低，而 1.0.25 release notes 声称修复 Maven 3.9+；此文档差异需纳入固定工具链验证。
- GluonFX Maven plugin 在 Maven Central 声明 BSD-3-Clause；Substrate 0.0.69 的 LICENSE 文件为 GPL v2，而所检查的 Java 源文件头写 GPL v3-or-later，二者存在差异，需按实际构建和分发的文件核对；OpenJFX 使用 GPL with Classpath Exception；JavaFX WebKit 使用多种许可证，整体包含 LGPL-2.1、MPL-2、Apache-2.0、Unicode、BSD-style 等。若产品是闭源商业分发，需按实际下载的 GraalVM、static SDK、WebKit 及各自 notice 做许可证审阅；本报告不对项目法律适用性作结论。[Plugin license metadata](https://repo.maven.apache.org/maven2/com/gluonhq/gluonfx-maven-plugin/1.0.29/gluonfx-maven-plugin-1.0.29.pom)、[Substrate 0.0.69 LICENSE](https://github.com/gluonhq/substrate/blob/0.0.69/LICENSE)、[OpenJFX license](https://github.com/openjdk/jfx)、[JavaFX WebKit notice](https://docs.oracle.com/en/java/java-components/javafx/26/javafx-license-guide/webkit-open-source-project.html)

## 风险与未知项

1. **JDK25 无确认兼容配对。** 官方指南的 macOS AArch64 Native Image 要求仍落在 JDK17 Gluon GraalVM；将独立 PoC 降到 JDK17 可以验证产品技术能力，但不能证明它未来能进入当前 JDK25 构建。
2. **macOS arm64 的 feature/arch 交叉验证缺失。** 文档分别声明 AArch64 架构和 macOS Web 功能；源码为 Web-enabled macOS 编译链添加 arm64 目标静态 WebKit，但本项目仍需对实际发布的 static SDK 跑窗口程序。
3. **Windows WebView 原生目标被官方矩阵挡住。** 若 Windows x64 native 也是硬性产品目标，JavaFX Native Image 路线目前不能成为统一替换方案；维护 Substrate/JavaFX WebKit 的定制 Windows 静态构建需要另行研究成本和支持责任。
4. **Bridge reflection/JNI 元数据未证实。** JavaFX API 允许桥接；Native Image 要求动态路径 metadata；Gluon 默认 metadata 覆盖 JavaFX 类，但没有找到覆盖应用 bridge 类型的 WebView 原生案例。
5. **WebKit 真实运行、功能与退出生命周期未实测。** 官方矩阵不说明页面兼容性、硬件加速、崩溃或退出清理。本轮也无官方可复现 WebView + Native Image demo。
6. **成本预算未定义。** 未测分发体积、构建时间、冷启动、空闲/加载内存、WebKit 辅助进程等指标，不据此评价性能是否可接受。

## 调研来源与方法

本次按 agent-reach 技能阅读了 `search.md`、`dev.md`、`web.md` 并先尝试规定的工具路由。当前环境中 `agent-reach`、`mcporter` CLI 不可用。Luna 子任务初次在受限网络环境中调用 `gh` 时报告认证失败，改用联网网页搜索/打开工具查官方文档与 GitHub 源码；父 agent 随后在允许联网的环境中确认 `gh` 认证正常，并通过 GitHub API 和固定发布 POM 复核平台源码、版本及 issue 作者身份。认证失败不是已确认的 token 失效。另经授权把 Gluon 官方文档 HTML 暂存于 `/private/tmp`，直接检查渲染后 HTML table 的勾选符号，确认 Web feature matrix 的 macOS/Windows 标记。未下载第三方二进制，未构建或运行任何实验，也未检查更新 agent-reach CLI 版本（CLI 缺失）。

关键来源（均于 2026-10-13 检索，页面记录的发布日期保留在文内）：

| 来源 | 类型 | 本报告使用的证据 |
| --- | --- | --- |
| [GluonFX 文档 §5 Platforms](https://docs.gluonhq.com/#_platforms) | 官方声明 | Native Image 平台与 feature 矩阵：macOS web 支持、Windows web 不支持；架构列支持 macOS AArch64。 |
| [GluonFX 文档 §5.2 macOS](https://docs.gluonhq.com/#_macos) | 官方构建指南 | Gluon GraalVM JDK17，Apple Silicon 下载项和 GluonFX 原生构建、package 流程。 |
| [GluonFX Maven 插件 release notes](https://docs.gluonhq.com/) | 官方发行说明，1.0.29 发布日期 2026-06-18 | 当前插件版本/文档所述构建先决条件；说明版本记录存在滞后。 |
| [GluonFX Maven Plugin 1.0.29 POM](https://repo.maven.apache.org/maven2/com/gluonhq/gluonfx-maven-plugin/1.0.29/gluonfx-maven-plugin-1.0.29.pom) | 已发布工件元数据 | 插件版本、Substrate 0.0.69 依赖、BSD-3 license。 |
| [Substrate 0.0.69 Constants.java](https://raw.githubusercontent.com/gluonhq/substrate/0.0.69/src/main/java/com/gluonhq/substrate/Constants.java) | 固定发布 tag 的官方源码 | 发布版本实际 Java / JavaFX static SDK 默认值。 |
| [Substrate 0.0.69 MacOSTargetConfiguration.java](https://github.com/gluonhq/substrate/blob/0.0.69/src/main/java/com/gluonhq/substrate/target/MacOSTargetConfiguration.java) | 固定发布 tag 的官方源码 | macOS AArch64 最低系统版本；`hasWeb()` 时加入 `jfxwebkit` 和 WebKit static libraries。 |
| [GluonFX issue #543](https://github.com/gluonhq/gluonfx-maven-plugin/issues/543) | 维护者项目中的 issue，2025-09-20 起；兼容建议来自一位非维护者身份的社区回复 | 报告者记录 Oracle GraalVM 25 + 插件 1.0.27 + Windows x64 的 JNI 错误。只支持这一失败组合的他人案例；不能外推到 macOS、1.0.29 或 WebView。 |
| [JavaFX 21 WebEngine API](https://docs.oracle.com/en/java/java-components/javafx/21/docs/javafx.web/javafx/scene/web/WebEngine.html) | 官方 API 声明 | URL/内存加载、JavaScript 执行、Java bridge、弱引用、公开成员和具名模块可访问性要求。 |
| [JavaFX 26 WebView API](https://docs.oracle.com/en/java/java-components/javafx/26/docs/javafx.web/javafx/scene/web/WebView.html) | 官方 API 声明 | WebView/FX thread 要求。JavaFX 26 用户指南发布日期为 2026 年 7 月。 |
| [GraalVM Native Image metadata (JDK 25)](https://www.graalvm.org/jdk25/reference-manual/native-image/metadata/) | 官方 Native Image 文档 | Reflection、JNI、resources、resource bundles metadata 类别及必要性。 |
| [JavaFX WebKit license notice](https://docs.oracle.com/en/java/java-components/javafx/26/javafx-license-guide/webkit-open-source-project.html) | Oracle 第三方许可证清单 | WebKit LGPL/MPL/Apache/Unicode/BSD 等许可证范围。 |

**结果边界：** 没有本项目实测结论；没有声称 Gluon 官方支持矩阵等于真实 WebView PoC 通过。若 macOS PoC 完成并通过所有硬门槛，之后再分别决定 Windows 是否可接受现有 WebView2 实现，最后才建立 Benchmark。
