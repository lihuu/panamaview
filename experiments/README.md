# JavaFX / Native Image PoC：本阶段结束

本阶段代码、结论、配置和验证证据集中保留于远程分支 **`codex/poc-javafx-native-image`**。
该分支从全部实验的最终提交延续历史，没有将实验合并到主分支。

## 当前决定

- FFM + 系统 WebView 继续作为当前主线，优先完善。
- JavaFX + WebView 保留为后续集成项。
- JavaFX 原生控件保留为后续集成项。
- 本阶段结束，后续有需求时再继续集成和完整 Benchmark。

生产框架继续使用原有依赖。JavaFX 实验位于独立项目中，没有加入根 Maven reactor。

## 已保留的内容

| 内容 | 入口与结论 |
|---|---|
| 最初探索目标、验收门槛、Benchmark 设计 | [技术探索方案](../docs/javafx-webview-native-image.md) |
| 前期资料与 Gluon 等候选工具链调研 | [历史调研报告](../docs/javafx-webview-research-findings.md)，实际 PoC 结果以以下报告为准 |
| 标准 JavaFX WebView Native Image 实验 | [WebView PoC](javafx-webview-native-image/README.md) |
| JavaFX 原生控件 Native Image 实验 | [控件 PoC](javafx-native-ui/README.md) |
| FFM / JavaFX WebView 共用业务、前端、Bridge | [可选后端实验](backend-comparison/README.md) |
| 产物大小、hash、依赖与运行证据 | [manifest](backend-comparison/evidence/manifest.json)、[体积核查](backend-comparison/evidence/size-audit.json) |
| 三路线统一构建、运行选择器 | [backends.sh](backends.sh) |

macOS arm64 / GraalVM CE 与 JDK 25.0.2 / JavaFX 25.0.2 下，两个 WebView 的相同页面及
业务逻辑在 JVM 和 Native Image 均通过 12 项检查。JavaFX 控件也已编译为 Native Image，
通过窗口、按钮事件、控件状态、截图、关闭及隔离运行验证；控件不是相同 HTML 工作负载。

实测未压缩可执行文件为 FFM 18.79 MiB、JavaFX WebView 157.32 MiB、JavaFX 控件 38.63 MiB。
同一文件 gzip level 6 分别为 6.51、55.34、13.50 MiB。JavaFX WebView 内嵌 WebKit 库
本身约 101.91 MiB；FFM 复用 macOS 系统 WKWebView。这个分发体积优势不等同于性能、
稳定性和维护成本的综合结论，也不是正式安装包大小或不可优化的下限。

JavaFX WebView 的原生运行仍依赖实验性的 macOS 启动适配、JNI 配置及复制库补丁。
Windows、物理无 JDK 的干净机器、完整生产生命周期、长期稳定性和完整 Benchmark 尚未验证。

## PR 收口与后续使用

此前的递进 PR 为 #10、#11、#14、#15、#16。它们作为历史审阅记录保留；本阶段结束后
关闭这些 PR，代码集中保存在上述专用分支，各阶段原有分支也不删除。
关闭 PR 不表示实验被放弃，也不影响后续从专用分支继续工作。

后续集成时，先基于这份 PoC 与当时主线的差异选择需要的实现，再在新分支验证并提交
面向主线的集成 PR；不要把所有实验和历史报告直接当作生产接入完成。

分支保存源码、脚本、metadata、日志、截图和 hash；`target/` 中的可执行文件、依赖缓存及
临时目录仍按 `.gitignore` 排除，不上传生成的大文件。构建和复验命令见各实验 README。
