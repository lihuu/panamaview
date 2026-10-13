# macOS 可选后端：相同应用、分别构建

这是独立实验，没有加入根 Maven reactor，也没有修改现有 framework 或 demo-app。
生产主力仍然是 FFM + 系统 WKWebView。JavaFX 原生组件沿用
[独立控件 PoC](../javafx-native-ui/README.md)，三条路线统一从 `experiments/backends.sh` 操作。

## 能共享到哪里

`ComparisonApp`、HTML/CSS/JavaScript、资源清单、现有 `WebApp`、Java `Bridge`、JSON 协议、
客户端 `panamaview.js` 都相同。只替换入口中的 `UiHost`。

JavaFX 宿主提供 WK 风格的消息接口，早期消息先排队，加载完成后将一个被强引用的公开
`Transport` 对象交给 `JSObject.setMember`。它异步投递消息，避免同步 Java 回调改变
`bindUi` 的行为；回复、超时、取消和虚拟线程仍使用现有框架逻辑。

宿主适配涉及窗口、事件循环、UI 调度、页面来源、消息接入、导航失效及关闭，不能归结为
只修改启动 WebView 的一段代码。JavaFX 原生控件不实现 WebView 的 JS API，保持单独的
界面实现；将来可以复用领域服务，但现有 HTML/CSS 界面不会自动变成 JavaFX 控件。

## 构建和运行

环境：本机 macOS arm64，GraalVM CE 25.0.2 / JDK 25.0.2，JavaFX 25.0.2，Maven 3.10.0。
需要桌面会话和 Apple 编译工具链。`POC_JDK_HOME` 指向带 Native Image 的 JDK；不设置时使用
`JAVA_HOME` 或 `/usr/libexec/java_home`。依赖缓存位于前一个 WebView 实验的 `target/maven-repository`。

在仓库根目录运行：

```sh
# 同一套业务和页面；JVM 实际运行并采集 metadata
./experiments/backends.sh jvm ffm
./experiments/backends.sh jvm javafx-webview

# 每个命令先编译、验证 JVM，再构建独立 Native Image
./experiments/backends.sh build ffm
POC_PATCH_WEBKIT=1 ./experiments/backends.sh build javafx-webview
./experiments/backends.sh build javafx-ui

# 实际运行三个独立的原生产物；全部通过才返回 0
./experiments/backends.sh native all
```

`jvm`、`build`、`native` 都接受 `ffm`、`javafx-webview`、`javafx-ui`、`all`；默认选择 FFM。
`all` 按顺序操作三个单独的应用，不生成含三个后端的聚合安装包。

FFM 产物：`target/ffm/dist/ffm-comparison`。JavaFX WebView 产物：
`target/javafx/dist/javafx-comparison`。控件产物位于 `../javafx-native-ui/target/dist`。
JavaFX Maven profile 单独加入 JavaFX 依赖、宿主源码和 JNI/启动 metadata；默认 FFM profile
不编译这些源码、不包含这些 metadata、依赖 classpath 没有 JavaFX JAR。

Native Image 都使用 `--no-fallback`，包括页面资源、agent 采集的 metadata，以及框架自带的
FFM downcall/upcall 配置。JavaFX 另有初始线程 CoreFoundation 事件循环适配、Glass JNI 补充配置。
loopback 宿主还显式启用 `--enable-url-protocols=http,https`；未启用时构建成功但原生加载
页面报 `Malformed URL`，补齐后才通过相同检查。
JavaFX WebView 还需显式授权对复制的 `libjfxwebkit.dylib` 执行前一个实验的
[补丁](../javafx-webview-native-image/README.md)：弱化其 `libjvm.dylib` 加载命令并重新临时签名。
原始 Maven JAR、安装的 JDK 和生产框架不改动。没有用 Gluon 构建工具。

FFM 的实验退出调用 `NSApplication.terminate:`，它可能绕过 tracing agent 的最终刷新而遗留
`.lock`。`run-jvm.sh` 每次使用新目录并启用每秒写配置，进程退出后复制 JSON 到
`snapshot-config`；构建只读取这个已停止写入的快照，不删除仍在使用的锁。报告后延迟
1.5 秒退出，给最终测试涉及的动态调用留下刷新时间。原生程序不加载这个 agent。

## 实际验证和边界

两种 WebView 运行同一套 12 项检查：Java 主动发消息启动测试、JavaScript 调用 hello/sum、
结构化 JSON、虚拟线程、慢后台调用、UI 阻塞对照、取消中断、Java 超时、100 个关联回复、
ES module 动态导入、本地 fetch、Web Worker。页面逐项显示 PASS/FAIL，Java 打印汇总；
外部 verifier 同时要求完整成功报告和进程退出 0，并设置超时。

测试启动脚本使用普通 script；ES module 是其中一项异步能力检查。首次采用顶层 module
启动测试时，页面完成回调可能早于应用回调安装，导致整套测试未启动。不能将宿主的
“页面已加载”直接等同于任意前端业务已准备好；正式应用还需自己的 ready 握手。

JavaFX 宿主当前仅支持 `PageSource.FromAssets`，通过 127.0.0.1 随机端口提供文件和 SPA fallback。
它与 FFM 的 `panamaview://app` 来源不同，必须继续验证 CSP、跨域、权限和导航行为。
没有实现 FromUrl、HMR、live reload、多窗口、持久存储策略、完整 MIME 表及生产发布流程。
HTML 内注入脚本目前假定页面允许 inline script，不能直接用于严格 CSP 页面。

现有 `UiHost` 文档要求 `start` 的调用线程拥有 UI 事件循环。JavaFX 会另行管理 FX Application
Thread，因此本实验按 JavaFX 的线程规则实现其余回调和操作；`WebApp` 的这些路径实际通过，
但 JavaFX 宿主并不满足该条字面约定。正式接入前须明确这个契约，不能把实验当成生产后端。

`cleanup` 在 Application.stop 和 launcher 返回时释放 HTTP server、执行器和 Bridge 引用；
这证明单窗口进程退出路径，没有证明长期反复打开 WebView 后所有原生内存都已回收。

结果及构建/运行证据见 [evidence](evidence)。控件路线的独立运行、窗口、按钮事件、控件状态、
截图及关闭证据见其 [报告](../javafx-native-ui/README.md)。控件应用不是同一 HTML 工作负载，
不能将它与两种 WebView 直接做运行性能排名。

本次本机实测（完整字节数、SHA256、依赖列表见 [manifest](evidence/manifest.json)）：

| 路线 | JVM | Native Image 隔离运行 | 未压缩可执行文件 |
|---|---|---|---|
| FFM + 系统 WKWebView | 共用页面 12/12 | 12/12，退出 0，无 JavaFX/JVM/JDK 库 | 18.79 MiB |
| JavaFX WebView | 共用页面 12/12 | 12/12，退出 0，含 JavaFX WebKit，无 JVM/JDK 库 | 157.32 MiB |
| JavaFX 控件 | 前一个 PoC 已通过 | 复验窗口、按钮、状态、截图、关闭，无 WebKit/JVM/JDK 库 | 38.63 MiB |

两个 WebView 原生程序都复制到新的隔离目录，工作目录只放该可执行文件，环境变量仅有
系统 PATH 与临时目录；JavaFX 的动态库从内嵌资源解压，未使用原有缓存。
这台机器仍安装有 JDK，不能代替物理无 JDK 的干净机器验收。原始 `vmmap` 记录已压缩保存。
`config` 目录保存本次观察到的 metadata 快照，构建命令仍要求重新运行真实 JVM 测试采集。

## 分发与下一步

保留多个源码后端不要求把它们放入一个发行包。本实验在构建时选定依赖和入口，分别产出。
如果未来改为一个安装包内运行时任意切换，就要携带候选后端需要的代码、原生库和资源，
体积通常会上升；共享的 JavaFX 基础部分意味着增长不能简单按三个文件大小相加。

macOS WKWebView 使用操作系统提供的 WebKit，无需随应用分发渲染引擎；JavaFX WebView 的
WebKit 动态库是应用依赖，并已包含在原生可执行文件的资源中，运行时会解压。
JavaFX 控件没有 WebKit，但仍有 JavaFX 窗口、渲染、字体等原生库。

记录的可执行文件字节数仅用于观察这个 PoC 的未压缩产物，不代表正式 `.app`/安装包，
也不是框架净增体积或完整 Benchmark。正式对照还需统一业务、重复采样启动/交互/Bridge、
统计应用及 WebView 子进程的 CPU/内存、缓存解压空间、长期操作和无 JDK 干净机器验证。
