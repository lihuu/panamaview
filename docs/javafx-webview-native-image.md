# 技术探索：JavaFX WebView 与 GraalVM Native Image

状态：后续研究任务，尚未启动 PoC；不代表 JavaFX WebView 的 Native Image 兼容性已得到验证。

下一步调研的问题、证据要求与交付格式见
[调研任务说明](javafx-webview-research-brief.md)，可直接交给调研 agent。

## 1. 背景

本项目以 Java 为核心构建轻量级跨平台桌面应用框架，采用 Java 驱动 WebView 的路线：

- Java 负责应用逻辑及生命周期管理。
- JDK FFM API 调用操作系统原生接口。
- macOS 使用 AppKit + WKWebView，已验证 JVM 与 GraalVM Native Image 两种运行模式。
- 实现仍处于早期阶段，长期稳定性及跨平台兼容性需要进一步验证。

仓库中已存在 Windows 的 Win32/COM + WebView2 实现及手工运行验证记录，详见
[项目说明](../README.md)与 [Windows probe](../windows-probe/README.md)。本次 JavaFX
探索仍优先验证 macOS，再验证 Windows；Linux 暂不作为优先支持目标。

当前路线需要自行处理操作系统 API、窗口管理、事件循环、对象生命周期及 JavaScript
Bridge。随着平台数量增加，适配与维护成本可能进一步上升，因此将 JavaFX WebView
列为后续替代方案研究任务。

## 2. 探索目标

评估 **JavaFX + WebView + GraalVM Native Image** 能否成为现有 FFM + Native WebView
架构的更优替代方案。研究对象是 JavaFX 作为跨平台窗口与 WebView 宿主的能力，不以
JavaFX 原生 UI 组件为重点。

拟验证的架构：

```text
Java Application
       │
       ▼
JavaFX Runtime
       │
       ▼
JavaFX Stage / Scene
       │
       ▼
JavaFX WebView
       │
       ▼
WebKit
       │
       ▼
HTML / CSS / JavaScript
```

最终仍以 GraalVM Native Image 作为主要分发方式。希望通过复用 JavaFX 的窗口、事件及
JavaScript 交互能力减少平台适配工作，是否能够实现这一目标须由实验确认。

## 3. 核心技术约束

### 3.1 Native Image 优先

GraalVM Native Image 是核心技术要求。JavaFX 基础组件支持 Native Image，不能作为
WebView 同样兼容的证明；必须验证真实 WebView 的构建和运行。

- JVM 运行成功不能作为 Native Image 兼容性的证明。
- 仅编译成功、但运行时存在关键功能缺失，不能通过验收。
- 若 WebView 无法满足要求，记录原因并保留现有架构，不强制迁移。
- JVM + jlink 精简 Runtime 可作为对照实验或备选分发方案，不替代主要验收目标。

### 3.2 保持现有架构稳定

采用独立实验方式：不直接修改 FFM + WKWebView 实现，不提前引入 JavaFX 核心依赖，
不为适配 JavaFX 大规模重构现有代码。未来 PoC 应使用独立构建项目，不加入当前 Maven
reactor；验证完成前不决定最终技术选型。

现有实现作为后续 Benchmark 基准。已有测量记录可用于设计实验，不能代替统一条件下的
重新测量。

### 3.3 跨平台能力

先 macOS，后 Windows。验证 JavaFX 是否能够在无需大量平台专用代码的情况下提供
一致的 WebView 能力。macOS 通过验收不能推定 Windows 也通过。

## 4. 第一阶段：技术可行性验证（PoC）

建立最小化 JavaFX WebView 独立项目，验证以下能力：

1. 创建并显示 JavaFX 窗口。
2. WebView 加载随应用分发的本地 HTML 页面。
3. Java 调用 JavaScript，并验证返回值或页面状态变化。
4. JavaScript 调用 Java，并验证 Java 实际处理了请求。
5. 正常关闭窗口，进程正常退出，完成应用所持资源的清理。
6. 使用 GraalVM Native Image 编译。
7. 原生可执行文件及其完整分发包可在不依赖已安装 JDK/JVM 的环境中独立运行。

构建后的原生程序必须实际执行前五项检查，不能仅用 JVM 运行结果代替。独立运行允许
分发必要的原生库和资源，但必须列出这些文件及系统前提，避免把单个可执行文件大小
当成完整分发成本。

每个平台记录以下信息，并保存可复现的构建命令、运行命令和日志：

| 项目 | 记录内容 |
| --- | --- |
| 环境 | 操作系统、硬件、CPU 架构 |
| JDK | 发行版与完整版本 |
| JavaFX | 完整版本、模块及原生库来源 |
| GraalVM | 发行版、完整版本与 `native-image` 版本 |
| 构建 | 构建工具、插件版本、完整命令及配置 |
| 元数据 | 额外反射、资源、JNI 配置及生成方式 |
| 分发 | 可执行文件、原生库、资源清单及外部运行前提 |
| 问题 | 构建或运行失败日志、复现步骤、修复或绕过方式 |
| 验收 | 每项检查结果、退出状态及双向 Bridge 的实际输出 |

**验收门槛：Native Image 构建成功，且原生程序中的 WebView、本地页面与双向
JavaScript Bridge 均正常工作，关闭和独立运行检查通过。**

结果分别标记为“通过”“失败”或“未验证”。构建成功但未运行属于未验证。如果无法
通过验收，记录失败环节与原因，暂时终止后续性能评估；不能据此宣称所有 JavaFX 版本
或构建路线均不支持 Native Image。

## 5. 第二阶段：Benchmark 体系

仅在 PoC 通过后建立统一、尽可能自动化的 Benchmark 机制。macOS 对比：

```text
方案 A：Java → GraalVM Native Image → FFM → macOS WKWebView
方案 B：Java → GraalVM Native Image → JavaFX → JavaFX WebView
```

Windows 在其 PoC 单独通过后，使用 FFM + WebView2 作为平台基准。

### 5.1 测试指标

| 类别 | 测试指标 |
| --- | --- |
| 构建 | Native Image 构建耗时 |
| 分发 | 完整应用分发包体积 |
| 启动 | 冷启动时间 |
| 启动 | 首次页面可交互时间 |
| 内存 | 空闲状态内存占用 |
| 内存 | 页面加载后内存占用 |
| 内存 | 多次打开、关闭页面后的内存变化 |
| CPU | 空闲状态 CPU 使用率 |
| CPU | 页面渲染及交互时 CPU 使用率 |
| Bridge | Java ↔ JavaScript 调用延迟与吞吐量 |
| 稳定性 | 长时间运行及重复操作后的稳定性 |
| 兼容性 | HTML、CSS、JavaScript 功能支持情况 |

### 5.2 测试原则

- 使用相同硬件、操作系统及 HTML/CSS/JavaScript 测试页面，尽量保持 Java 业务逻辑一致。
- 区分首次启动与后续启动，说明冷启动的定义及缓存条件。
- 统一时间边界：从启动请求到窗口显示，以及到页面完成初始化、双向 Bridge 可用。
- 每项测试重复执行，记录样本数、原始数据、中位数及波动范围。
- 分别记录 Native Image 构建耗时与运行时性能，注明构建缓存和构建参数。
- 分发体积包含运行所需的原生库及资源，分别记录压缩包大小与展开后大小。
- 内存统计覆盖应用主进程及相关渲染、网络等进程，说明指标、进程归属和采样方式。
  不仅依赖父子进程树识别：由系统服务启动的 WebView 进程也需要归属确认；无法归属时
  明确记录统计局限，避免漏算或重复计算共享资源。
- CPU 测量覆盖同一进程范围、工作负载和采样时长。
- Bridge 对比使用一致的消息负载、往返语义、并发量和预热条件，分别报告延迟与吞吐量。
- 稳定性测试固定持续时间、打开/关闭次数及操作序列，记录内存趋势、崩溃与异常。
- 兼容性逐项测试，记录页面服务方式与来源，避免将页面加载策略造成的限制误判为
  渲染引擎能力差异。已有问题背景见 [页面运行环境](page-environment.md)。
- 尽可能区分宿主框架开销与渲染引擎开销；无法分离时明确测量的是整体应用。

Benchmark 脚本、测试页面、环境信息和原始结果一并保存，使测试可以重复执行。

## 6. 第三阶段：工程维护成本评估

性能不是唯一标准，还需要评估：

- 跨平台适配工作量与框架 API 复杂度。
- 操作系统升级带来的兼容性风险。
- JavaFX 与 WebKit 的版本更新、修复及发布机制。
- Native Image 构建、元数据和工具链的维护成本。
- 问题排查难度及长期维护成本。
- 第三方依赖、原生库、许可要求及分发复杂度。

核心判断是：**JavaFX 所节省的平台适配成本，是否足以抵消其额外运行时、依赖和性能
开销。** 结合实际 PoC 与平台验证记录评估，不仅比较 API 数量。

## 7. 技术选型原则

不预设 JavaFX 优于现有方案，根据 PoC、Benchmark 和维护成本综合判断：

| 结果 | 条件与后续方向 |
| --- | --- |
| A：JavaFX 更合适 | Native Image 完整可用，资源开销可接受，显著降低跨平台开发成本；考虑作为主要实现 |
| B：现有方案更合适 | JavaFX 存在关键 Native Image 问题，或运行、分发成本明显更高；继续使用 FFM + Native WebView |
| C：各有优势 | 需求明确且维护成本合理时考虑保留不同实现；不提前设计复杂多后端抽象 |

## 8. 当前决策

1. 继续完善现有 FFM + WKWebView 实现。
2. 暂不引入 JavaFX 依赖，不改变当前技术路线。
3. 后续建立独立 JavaFX WebView Native Image PoC。
4. PoC 成功后再建立统一 Benchmark，并评估工程维护成本。
5. 根据实测结果决定是否调整技术路线。

**以 Native Image 为优先约束，以跨平台维护成本为重要考量，以可重复的 Benchmark
数据作为技术选型依据。**
