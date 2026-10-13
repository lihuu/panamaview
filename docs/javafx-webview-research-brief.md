# JavaFX WebView Native Image：调研任务说明

状态：可交给调研 agent 执行；本文件是任务输入，不是兼容性结论。

## 目标与本轮边界

总体目标是判断 JavaFX WebView 能否在保留 Native Image 分发的前提下，降低 panamaview
的跨平台维护成本。约束与阶段验收以
[技术探索方案](javafx-webview-native-image.md)为准。

**本轮调研的完成条件：给出是否值得进入独立 PoC 的有据结论，并提供一条可执行的
验证路线；如果暂时找不到路线，列出具体阻碍和证据缺口。**

本轮不要求决定迁移，不要求建立 Benchmark，也不修改生产实现或根 Maven 构建。
文献调研中的“支持”不等于本项目已通过 PoC；Native Image 的实际验收仍须运行原生程序。

## 项目输入

- 当前根 `pom.xml` 的 Java 编译目标为 JDK 25。候选路线若需要其他 JDK，记录版本差异
  及未来集成成本，不修改现有项目来迁就候选工具链。
- 先调查 macOS arm64，再调查 Windows x64；Linux 暂不优先。
- 现有 macOS 基准是 FFM + AppKit/WKWebView，Windows 基准是 FFM + Win32/COM/WebView2。
- 本地页面、Java → JavaScript、JavaScript → Java、正常关闭、无 JDK/JVM 独立运行是硬门槛。
- 允许分发必要原生库和资源，但须说明完整分发内容、许可及运行前提。
- JVM + jlink 只能作为对照或备选，不能代替 Native Image 目标。

建议先阅读 [README](../README.md)、[已有实现记录](../NOTES.md)、
[页面运行环境](page-environment.md)及 [Windows 验证说明](../windows-probe/README.md)。
这些是仓库记录，不能替代对当前外部工具链支持状态的核查。

## 必须回答的问题

| 问题 | 需要的答案 |
| --- | --- |
| WebView 是否支持 Native Image？ | 针对 `javafx.web` 和真实页面的证据；普通 JavaFX 窗口示例不够 |
| 有哪些构建路线？ | 调查直接使用 OpenJFX + GraalVM，以及 Gluon 等候选工具链；区分标准依赖、定制构建、补丁或替代实现 |
| macOS / Windows 是否可用？ | 分平台、架构和版本回答，不能从一个平台外推另一个 |
| 双向 Bridge 是否完整？ | 查证 Java 调用脚本及脚本调用 Java 的限制，列出反射、JNI、模块开放和对象生命周期等待验证事项 |
| 本地页面如何分发和加载？ | 资源打包与加载方式；基本 HTML PoC 与 ES modules、fetch、Worker 等后续兼容性测试分开说明 |
| 能否无 JVM 独立运行？ | 必要动态库、资源、系统依赖和安装前提，明确是否存在 JVM 回退 |
| 工具链是否可维护？ | 版本兼容矩阵、更新情况、已知阻碍、许可或商业条件，标出定制原生库及补丁维护成本 |
| 下一步如何验证？ | 推荐一组明确版本、可复现命令与验收步骤，列出缺失依赖和失败时的诊断方法 |

这些都是待查问题；候选工具或路线被列出，不表示已确认可用。

## 证据要求

1. 优先使用官方文档、官方源码、发行说明、维护者 issue 和可复现示例。社区文章可提供
   线索，但不能单独支撑关键兼容性结论。
2. 每条关键结论给出来源链接、检索日期及适用版本/平台。历史 issue 的限制须核查是否
   仍存在，不能当作所有版本的结论。
3. 区分“官方声明”“他人可复现案例”“本项目实测”“推测/待验证”。他人的原生测试
   必须确认包含 WebView 和双向 Bridge；证据未覆盖的部分明确标注。
4. 找不到证据记为“未确认”。一条路线构建失败只证明该组合失败，不能直接推导全面不支持。
5. 如获得实测结果，保存完整版本、命令、元数据、原生日志与退出状态。需要 JDK/JVM
   才能运行的结果不满足主要验收条件。

## 交付内容

调研报告建议保存为 `docs/javafx-webview-research-findings.md`，至少包含：

- 结论：建议进入 PoC、建议暂缓，或证据不足；说明决定依据。
- 版本/平台矩阵：JDK、JavaFX、GraalVM、构建工具、OS/架构、WebView、双向 Bridge、
  独立运行；每项标明证据等级与来源。
- 推荐 PoC 路线：固定版本、安装前提、独立项目结构、构建与运行步骤、所需配置。
- 风险和未知项：区分技术阻碍、维护成本与尚未验证的假设。
- 来源列表和后续验证清单。

若任务授权包含实际 PoC，项目建议放在 `experiments/javafx-webview-native-image/`，使用
独立构建，不加入根 Maven reactor。只有实际通过探索方案中的验收门槛，才标记 PoC
通过；否则报告失败或未验证并停止进入 Benchmark。

“资源开销可接受”和“维护成本显著下降”尚未量化。本轮先收集依赖和维护证据；PoC
通过后，在正式 Benchmark 前确定分发体积、启动、内存等预算及可接受的工具链维护投入，
再用于最终选型。调研 agent 不应自行把未约定的预算写成已接受标准。

## 可直接交给 agent 的任务

> 阅读 `docs/javafx-webview-native-image.md` 与本任务说明，调研 JavaFX WebView 在
> GraalVM Native Image 下的真实支持情况。优先 macOS arm64，随后 Windows x64。
> 核查具体版本、构建路线、WebView 原生运行、双向 JavaScript Bridge、本地资源与无
> JVM 独立分发。用官方资料和可复现案例建立证据矩阵，区分声明、案例、实测和未知。
> 将报告写入 `docs/javafx-webview-research-findings.md`，给出是否进入独立 PoC 的建议
> 以及可执行验证路线。不要修改现有 FFM 实现、引入核心 JavaFX 依赖或提前做 Benchmark。
