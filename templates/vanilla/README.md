# vanilla template

A panamaview app: a Java process in charge of the work, and a web page in a window on top of
it, talking over a JSON bridge.

```
src/main/java/com/example/Main.java       the Java side: handlers, lifecycle
src/main/resources/web/index.html         the page
src/main/resources/web/app.js             application code — no bridge plumbing
src/main/resources/web/manifest.txt       the assets to stage (explicit, see below)
src/main/resources/web/style.css
```

## Before it will build

`io.github.lihuu:panamaview` has to be resolvable first — from Maven Central, where it is published
from `0.3.0` on, from a private repository the machine is configured for, or from a framework
checkout with `mvn install -DskipTests`. Until one of those is true the build stops at
`could not resolve io.github.lihuu:panamaview`, which names the artifact but not the fix. [Getting
the framework](https://github.com/lihuu/panamaview#getting-the-framework).

## Which platform it runs on

macOS and Windows. The window implementation is chosen at run time from the platform you are on, so
this project — the one you are reading — is not a macOS project or a Windows project: it is the same
source either way.

| | macOS | Windows |
|---|---|---|
| `./run.sh` | yes | a POSIX shell script; on Windows run the Maven equivalent below |
| `mvn compile exec:exec` | yes | **yes** |
| `./build-native.sh` | yes | not — it is a POSIX script |
| `.\build-native.ps1` | — | yes |
| `./package.sh` | yes → `.app` + `.dmg` | — |
| `.\package.ps1` | — | yes → zip + `.msi` |

The two places a platform actually shows through, and what happens to them:

- **The JVM flag.** `-XstartOnFirstThread` is macOS's alone, and the JVM refuses to start with an
  unknown `-X` option rather than ignoring it. The pom adds it from a `mac`-activated profile and
  the shell scripts from `uname -s`, so neither platform ever sees it.
- **The window backend.** `Main.host()` picks `WindowsHost` or `MacOsHost` from `os.name`. Both are
  referenced but only one is loaded, which is what keeps the other platform's runtime out of the
  process entirely.

On Windows, `WebView2Loader.dll` has to sit beside the executable (or in `loader/` under the
working directory) before
the window will open — it is the loader for the WebView2 runtime the system already has, and it is
not installed with it. `fetch-loader.ps1`, in this directory, downloads it from the NuGet package
Microsoft publishes it in and checks its sha256 before using it:

```powershell
powershell -ExecutionPolicy Bypass -File fetch-loader.ps1
```

Run that once; `loader/` is ignored by git. Without it the app stops with a message naming the file.
It is a Windows-only step — macOS uses the system WebKit.

## Run it

```sh
./run.sh                       # on the JVM
./build-native.sh              # → target/myapp, a standalone executable
./target/myapp
```

`mvn compile exec:exec` is the equivalent of `./run.sh`.

## Packaging

`./package.sh` turns the native build into an installable app: `target/dist/My App.app` and
`target/dist/myapp-0.1.0.dmg` with an `/Applications` link. The name, bundle identifier and
version come from the pom; `--title`, `--bundle-id`, `--version`, `--icon` and `--sign` override
them. The default signature is ad-hoc — the app runs on this machine; distributing it needs a
Developer ID signature and notarization.

On Windows, `.\package.ps1` writes a portable zip and a per-user MSI, installed under
`%LOCALAPPDATA%` with no administrator prompt. The MSI needs WiX v4/v5; `-NoMsi` produces only
the zip.

Both are described in [Packaging an app](https://github.com/lihuu/panamaview/blob/main/docs/packaging.md).

### Editing without restarting

While running from `./run.sh`, the framework watches these assets and reloads the window when they
change — so editing `app.js` or `style.css` is enough to see the result. No build step, no Node, no
restart:

```
[panamaview] live reload watching .../target/classes/web
[panamaview] change detected, reloading
```

Two things to know:

- **It watches the compiled copy**, which is `target/classes/web`, so the edit has to reach it.
  In an IDE that happens on save; from a terminal, `mvn compile` does it. There is nothing to
  configure either way.
- **Nothing reloads in a native executable**, because there are no files to watch. That build is
  for shipping.

This works the same on macOS and Windows. The two platforms run their event loops on different
threads — AppKit on the process' first thread, WebView2 on a thread of its own — but the reload
is delivered through the same queue either way, so the watcher does not care which it is on.

Turn it off with `app.liveReload(false)` if you would rather not have it. If you want hot module
replacement rather than a full reload, use the react template — that is what a dev server buys.

## The bridge

```js
const greeting = await java.invoke("greet", { name: "World" });   // → "Hello World"
```

```java
.bind("greet", params -> "Hello " + params.getOrDefault("name", "world"))
```

The other direction:

```java
.onReady(app -> app.postToJs(Map.of("message", "ready")))
```

```js
window.onJavaMessage = (payload) => { … };
```

**Do not copy `panamaview.js` into this project.** The framework stages it into the web root
at startup, straight out of the panamaview jar, so it always matches the version you are
running. It is a reserved name there.

## The menu bar

An application can declare its own menu bar, and the framework builds it on both platforms.

```java
import io.github.lihuu.panamaview.Menus;
import io.github.lihuu.panamaview.Platform;

new WebApp(host)
    .title("My App")
    .size(900, 620)
    .load("/web/index.html")
    .menu(Menus.menu(
            Menus.appMenu(),
            Menus.menu("File",
                    Menus.item("export", "Export").shortcut("CmdOrCtrl+E"),
                    Menus.separator(),
                    Menus.platformItem(Platform.QUIT, "Quit")),
            Menus.editMenu()))
    .bind("export", params -> { … do the export … })
    .run();
```

Two kinds of item, and the difference is not decoration:

- a **binding item** names a method bound with `bind` — choosing it calls that method, the
  same one a page's `java.invoke` would reach;
- a **platform item** names an operation the platform already knows (`Platform.QUIT`,
  `Platform.UNDO`, …) and has no Java behind it.

**One title is composed for you, and it is the one in the application menu.** The Quit item
inside `appMenu()` reads "Quit \<app name\>" — the platform is the side that knows the
application's name, because the model has no way to know it. A `platformItem(Platform.QUIT,
"退出")` in your **own** menu keeps the title you gave it, on both platforms: the composition
belongs to the application menu alone.

`CmdOrCtrl` means ⌘ on macOS and Ctrl on Windows, so one declaration means the same
user-visible action on both platforms — but only macOS honours it today: the Windows builder
registers no accelerator yet, so a shortcut there does nothing until that is done.

**A declared bar replaces the framework's bar — it does not merge with it — so the
application takes responsibility for the whole bar, `appMenu()` and `editMenu()` included.**
The built-in menus are supplied for the application to include; the framework does not add
them behind your back, because an application that cannot choose them cannot be helped by
one that chooses for it.

The two platforms do not offer the same built-in menus, so this is not a formality:

- **On macOS the Edit menu is the framework's and it is not optional.** AppKit routes the
  keyboard through menu items, so ⌘C reaches the web view's text field only because the Edit
  menu carries it. Leave `editMenu()` out and ⌘C stops working, with a symptom that mentions
  nothing about menus.
- **On Windows the framework installs no Edit menu of its own.** The WebView2 control performs
  the clipboard operations itself — `document.execCommand('copy')` inside a real user gesture
  puts the text on the clipboard with no framework involved, and that is the code path
  Chromium's own Ctrl+C runs. The browser already does the editing commands there, so the
  Windows bar is for *application* items. A declared `editMenu()` is still built — with every
  editing item **disabled**, so the user sees that it exists and cannot have it, and the
  framework says so once on stderr.

**Windows keyboard shortcuts are not part of this.** The menu bar is clickable on both
platforms, and macOS additionally gets shortcuts, because its menu item already carries them.
A Windows shortcut handled by the framework would have to take the key away from the page,
which is the opposite of what the browser is doing with it.

## The two flags you must not drop

Both are already in `pom.xml` and `run.sh`, and both bite in ways that do not name the cause:

- **`-XstartOnFirstThread`** — AppKit requires the event loop to own the process' first
  thread; the `java` launcher gives `main()` a fresh one. Drop it and the window misbehaves
  without saying why. (A native executable does not need it — its main thread already is the
  first thread.)
- **`--enable-native-access=ALL-UNNAMED`** — the calls into Cocoa and WebKit are native access.

## Handler threading

`bind` runs the handler on a **virtual thread**, so slow work does not freeze the window.
A 30-second deadline applies by default; `bind(name, fn, Duration.ofMinutes(10))` gives a
handler its own, and `null` means none.

`bindUi` runs the handler *on the UI thread*, for the operations that are only legal there
(resizing, snapshotting). Those are the ones that **can** freeze the interface, so keep them
quick — and note they get no deadline, because the thread that would report the stall is the
one that is busy.

## Adding assets

Two places, or the file will work on the JVM and go missing from the executable:

1. `web/manifest.txt` — a classpath directory cannot be enumerated portably, and not at all
   inside a native image, so the framework is told explicitly.
2. `build-native.sh` — `-H:IncludeResources='web/.*'`. Resources are **not** inherited from a
   dependency, so each app declares its own. (`panamaview`'s own metadata *is* inherited,
   which is why nothing here mentions the bridge.)

## Verifying without a window

CI has no window server. This stages the assets and reports what it found:

```sh
mvn -q compile exec:exec -Dexec.args="" -Dpanamaview.check=true   # or simply:
./target/myapp -Dpanamaview.check=true
```

It exists because "an asset is missing" is a **runtime** failure: the compile succeeds, and
the app dies at start-up with `IOException: missing resource: /web/…`.
