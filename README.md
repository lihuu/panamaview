# panamaview

[![Maven Central](https://img.shields.io/maven-central/v/io.github.lihuu/panamaview.svg)](https://repo1.maven.org/maven2/io/github/lihuu/panamaview/)
[![npm](https://img.shields.io/npm/v/create-panamaview.svg)](https://www.npmjs.com/package/create-panamaview)
[![CI](https://github.com/lihuu/panamaview/actions/workflows/ci.yml/badge.svg)](https://github.com/lihuu/panamaview/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> Build ultra-lightweight, high-performance desktop applications with modern web technologies and Java.  
> Powered by OpenJDK **Project Panama (FFM)** and operating system WebViews.

No Electron bloat. No bundled Chromium. Zero JNI, JNA, or C/C++ shims.

---

## ✨ Features

- 🪶 **Ultra-lightweight footprint**: ~20 MB standalone native binary via GraalVM Native Image (~30 MB memory at idle, compared to Electron's 150+ MB).
- ⚡ **Pure Java 25 FFM**: Zero C/C++ shims. Direct native interop with Cocoa/WebKit (macOS) and Win32/WebView2 (Windows) via `java.lang.foreign`.
- 🌐 **Modern Frontend Toolchains**: Built-in private URL scheme (`panamaview://app/` on macOS, `https://app.panamaview/` on Windows) supporting ES modules, CORS, `fetch()`, Web Workers, and SPA History-mode routers.
- 🧵 **High-Concurrency Virtual Thread IPC**: IPC handlers run on Java Virtual Threads off the UI thread without freezing the window; built-in request cancellation and watchdog deadlines.
- 🍽️ **Native Application Menus**: Declare a menu bar in Java — items, separators, and shortcuts — and get a native one on macOS and Windows. Only macOS binds the keyboard shortcuts today, and the framework installs no Edit menu of its own on Windows — there the browser already owns the editing commands.
- 📂 **Native File Dialogs**: Open-file, open-multiple, open-directory and save dialogs (`NSOpenPanel`/`NSSavePanel` on macOS, `IFileOpenDialog`/`IFileSaveDialog` on Windows) declared once as a portable `FileDialog` spec — with file-type filters, a start directory and a save name — and bound as an ordinary `bindUi` handler whose cancellation is an answer, not an error.
- 🛠️ **Out-of-the-Box Scaffolder & Templates**: First-class templates for React, Vue 3, Angular, Tailwind CSS, and Vanilla JS.

---

## 🚀 Quick Start & Scaffolding Tool

`panamaview` provides an official project generator published to npm as [`create-panamaview`](https://www.npmjs.com/package/create-panamaview) (CLI command `panamaview`).

### 1. Instant Generation (via `npx`)

No installation required — `npx` automatically runs the latest version:

```sh
# Interactive prompt (selects template if omitted)
npx create-panamaview create my-app

# Or specify options directly
npx create-panamaview create my-app --template react --package com.example.myapp --title "My App"

# Run the app
cd my-app
./run.sh            # JVM mode
./build-native.sh   # Standalone native executable via GraalVM Native Image
```

### 2. Global CLI Installation (Optional)

If you prefer having the `panamaview` command available directly on your `$PATH`:

```sh
npm install -g create-panamaview

# After installing globally:
panamaview create my-app --template vue
panamaview templates    # List all available templates and node requirements
```

### CLI Command & Options

```
panamaview create <dir> [options]
panamaview templates
panamaview version | help
```

| Option | Description | Default |
| :--- | :--- | :--- |
| `<dir>` | Target directory to create the project in (`.` for current directory) | Required |
| `-t, --template <name>` | Template: `vanilla`, `vanilla-tailwind`, `react`, `vue`, `angular` | Interactive selection |
| `-p, --package <pkg>` | Root Java package name | `com.example.<dir>` |
| `--title <text>` | Window and document title | Derived from directory name |
| `-f, --force` | Allow generating into a non-empty directory | `false` |
| `templates` | Lists all available templates with Node requirements | — |

### Official Templates

| Template | Frontend Stack | Node Required? | Routing Support | Dev Experience |
| :--- | :--- | :---: | :---: | :--- |
| `vanilla` | Plain HTML5, CSS, JS | ❌ No | — | Instant file-watcher reload |
| `vanilla-tailwind` | Plain HTML + Tailwind CSS | ❌ No | — | Standalone Tailwind CLI |
| `react` | React 19 + Vite | ✅ Build-only | — | Hot Module Replacement (HMR) |
| `vue` | Vue 3 + Vite | ✅ Build-only | History mode | Hot Module Replacement (HMR) |
| `angular` | Angular 19 | ✅ Build-only | History mode | Angular DevServer |

> **Note on Node.js:** Node is used only for frontend bundling during development. Released applications run as pure native executables with zero Node dependencies.

### 💡 Self-Contained Offline First-Build

A standout design of `create-panamaview`: the npm package **bundles a local copy of the framework runtime** directly inside `<project>/.panamaview/lib` as a pre-packaged Maven repository.

When your project is generated:
- **No network dependency on day 1**: You can run `./run.sh` immediately without waiting for downloads from Maven Central.
- **Zero version drift**: The project is guaranteed to build against the exact matched framework version.

---

## 💻 Minimal Example

### 1. Java Backend

```java
package com.example;

import io.github.lihuu.panamaview.WebApp;
import io.github.lihuu.panamaview.macos.MacOsHost; // or io.github.lihuu.panamaview.windows.WindowsHost

public final class Main {
    public static void main(String[] args) throws Exception {
        var host = new MacOsHost();

        new WebApp(host)
            .title("My App")
            .size(900, 600)
            .load("/web/index.html")
            // Runs on a virtual thread; cannot freeze the UI window
            .bind("greet", params -> "Hello " + params.getOrDefault("name", "World"))
            .run();
    }
}
```

### 2. Frontend (`index.html`)

The framework automatically stages the client bridge (`/panamaview.js`) into your web root at startup:

```html
<!DOCTYPE html>
<html>
  <head>
    <script src="/panamaview.js"></script>
  </head>
  <body>
    <h1>Hello from panamaview</h1>
    <button id="greetBtn">Call Java</button>

    <script>
      document.getElementById('greetBtn').onclick = async () => {
        // Asynchronous IPC call returning a Promise with cancellation support
        const message = await java.invoke('greet', { name: 'Developer' });
        alert(message);
      };

      // Receive push messages from Java (app.postToJs)
      window.onJavaMessage = (payload) => {
        console.log('Received push from Java:', payload);
      };
    </script>
  </body>
</html>
```

---

## 🏗️ Architecture & Core Concepts

### Threading & Concurrency Contract

Native toolkits (AppKit, Win32) strictly require window operations to execute on the main UI thread. `panamaview` cleanly separates concerns:

- **`app.bind("method", fn)`**: Executed on a **Virtual Thread**. Safe for blocking I/O, heavy computation, or slow tasks without stuttering the window.
- **`app.bindUi("method", fn)`**: Executed inline on the native **UI thread**. Dedicated to window geometry and UI manipulations.

```java
app.bind("fetchData", params -> service.queryDb());    // Virtual thread (non-blocking)
app.bindUi("resizeWindow", params -> host.resize(...)); // UI thread
```

### Deadlines & Cooperative Cancellation

Every standard `bind` handler inherits a 30-second watchdog deadline by default:

```java
app.bind("fastTask", fn);                              // Inherits 30s deadline
app.bind("longTask", fn, Duration.ofMinutes(5));       // Custom deadline
app.bind("unboundedTask", fn, null);                   // Unbounded (e.g. LLM/Agent tasks)
```

If a deadline expires or the frontend calls `promise.cancel()`, the worker thread receives a cooperative Java `Thread.interrupt()` and the frontend promise rejects immediately.

### Custom Protocol vs. `file://`

Loading local frontend bundles over `file://` assigns an opaque origin, which WebKit and Chromium restrict by blocking ES module scripts, CORS `fetch()`, and Web Workers.

`panamaview` serves assets over a custom native URL scheme (`panamaview://app/` on macOS, `https://app.panamaview/` on Windows) with valid HTTP response headers, MIME types, and automated SPA History-mode routing fallback. See [Where your page runs](docs/page-environment.md) for details.

### Development vs. Production

Switch from built static assets to a local Vite/webpack development server with a single system property:

```java
String devServer = System.getProperty("panamaview.dev");
if (devServer != null) {
    app.dev(URI.create(devServer)); // Loads dev server directly, preserving HMR
}
```

---

## 📦 System Requirements

- **Java Runtime**: JDK 25 with Foreign Function & Memory (FFM) API support.
- **Operating Systems**:
  - **macOS**: macOS 14+ on Apple Silicon (arm64), utilizing system WebKit.
  - **Windows**: Windows 10/11 x64, utilizing Microsoft Edge WebView2.
  - **Linux**: not yet — there is no Linux host, and the WebKitGTK one is still on the roadmap. The framework's own tests do pass on Linux (measured in WSL2, Ubuntu 26.04), but nothing can *run* there yet, and a CI job that keeps them passing there is still missing.
- **Native Image**: GraalVM CE 25+ (optional, required only for native binary builds).

---

## 📁 Repository Layout

The repository is organized as a unified Maven reactor:

```
pom.xml                The reactor parent POM
framework/             io.github.lihuu:panamaview (core framework library)
demo-app/              Self-test and showcase application
windows-probe/         Windows verification probe
templates/             Starter application templates (Vanilla, Tailwind, React, Vue, Angular)
npm/                   create-panamaview CLI scaffolder package
scripts/               Verification, release, and deployment scripts
docs/                  Deep-dive architecture and maintenance documentation
```

---

## 🗺️ Roadmap & Current Status

`panamaview` is transitioning from foundational runtime validation to an application framework.

**Shipped**

- [x] macOS native host via FFM & Cocoa WebKit
- [x] Windows native host via FFM, Win32 & WebView2, exercised by a self-test of its own (`windows-probe`)
- [x] Virtual thread-backed JSON RPC & cooperative cancellation
- [x] Private asset URL schemes with SPA routing fallback
- [x] GraalVM Native Image reachability metadata & standalone builds — macOS today; the Windows shapes are tracked in [#34](https://github.com/lihuu/panamaview/issues/34)
- [x] Maven Central publishing & npm scaffolder (`create-panamaview`)
- [x] Application menu bar (macOS & Windows)
- [x] System notifications via `UNUserNotificationCenter` (macOS)
- [x] Native clipboard bindings on both hosts — read, write, and a change counter
- [x] Window position memory: the window reopens where the user last put it (macOS)
- [x] Live reload on Windows
- [x] A reachability-metadata check in CI, so the sources and the metadata cannot drift apart again ([#31](https://github.com/lihuu/panamaview/issues/31))
- [x] Native file dialogs (`NSOpenPanel`/`NSSavePanel`, `IFileOpenDialog`/`IFileSaveDialog`) — a portable `FileDialog` spec behind the optional `DialogHost` seam

**Next up — the features the next phase is shaped around**

- [ ] System tray integration
- [ ] Multi-window management
- [ ] Distribution packaging (`.app` bundle, `.dmg`, Windows `.msi` installers)
- [ ] Linux host via WebKitGTK

**Ready to start — no design question stands between here and the first commit**

- [ ] Register the Windows package's FFM shapes, and give a Windows native build the guarantee a macOS one already has ([#34](https://github.com/lihuu/panamaview/issues/34))
- [ ] A Linux CI job for the framework's own tests — they already pass there, measured in WSL2; only the job is missing

---

## 📖 Deep Dives & Further Reading

- [Migrating to 1.0 (package rename)](docs/migrating-to-1.0.md)
- [Where your page runs (Asset Serving & WebKit Internals)](docs/page-environment.md)
- [Verification & Benchmarks](docs/verification.md)
- [Publishing to Maven Central & Private Repositories](docs/publishing.md)
- [Release Workflow & Version Management](docs/releasing.md)
- [Low-Level FFM & Runtime Notes](NOTES.md)

---

## License

[MIT](LICENSE) © [lihuu](https://github.com/lihuu)
