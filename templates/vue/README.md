# vue template

A panamaview app with a Vue frontend: Java owns the window and the work, Vue owns the interface,
and a JSON bridge runs between them. The router runs in history mode, so URLs are real paths.

```
src/main/java/com/example/Main.java        the Java side
frontend/                                  the Vue app, built by Vite
  index.html
  vite.config.js                           deliberately boring — the comments say why
  src/main.js                              app + router setup
  src/bridge.js                            the Java → page channel, as a composable
  src/App.vue
  src/Home.vue, src/About.vue              two routes, so the routing path is exercised
  src/style.css
scripts/build-frontend.sh                  builds frontend/ into src/main/resources/web/
dev.sh                                     dev server + hot module replacement
run.sh                                     production build, served from the app's scheme
build-native.sh                            → target/myapp
```

`src/main/resources/web/`, `frontend/dist/` and `frontend/public/panamaview.js` are all
**generated**. None are checked in, because all three are derived and a committed copy of a
derived file goes stale without saying so.

## Before it will build

`io.github.lihuu:panamaview` has to be resolvable first — from Maven Central once it is published
there, from a private repository the machine is configured for, or from a framework checkout with
`mvn install -DskipTests`. Until one of those is true the build stops at
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
| `./build-native.sh` | yes | not yet — it is a POSIX script and needs GraalVM, which is not on Windows here |

The two places a platform actually shows through, and what happens to them:

- **The JVM flag.** `-XstartOnFirstThread` is macOS's alone, and the JVM refuses to start with an
  unknown `-X` option rather than ignoring it. The pom adds it from a `mac`-activated profile and
  the shell scripts from `uname -s`, so neither platform ever sees it.
- **The window backend.** `Main.host()` picks `WindowsHost` or `MacOsHost` from `os.name`. Both are
  referenced but only one is loaded, which is what keeps the other platform's runtime out of the
  process entirely.

On Windows, `WebView2Loader.dll` has to sit beside the working directory (or in `loader/`) before
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
./dev.sh              # dev server, hot module replacement
./run.sh              # production build on the JVM
./build-native.sh     # → target/myapp, a standalone executable
```

Node 24 or newer is required. If you do not want a frontend toolchain at all, use the vanilla
template — it has the same editing experience without needing Node.

That requirement is **development-time only**. `build-native.sh` produces a native executable
plus a directory of static files, and neither has any trace of Node at runtime — the frontend
toolchain is scaffolding you can delete once the app is built.

## Developing

`./dev.sh` starts Vite, waits for it, then launches the app pointed at it. Edit a `.vue` file and
the window updates with no restart. The Java process is *not* reloaded and does not need to be —
the page is the only thing that changed. If you change Java, restart `./dev.sh`.

## The bridge

```vue
<script setup>
const answer = ref(null);
async function ask() {
  answer.value = await window.java.invoke("greet", { name: "World" });
}
</script>
```

```java
.bind("greet", params -> "Hello " + params.getOrDefault("name", "world"))
```

Java → page goes to a single callback, which `bridge.js` exposes as a composable:

```js
const fromJava = useJavaMessage();
```

`bridge.js` is not ceremony. The callback fires on *page ready*, which is before any component has
mounted — a module script is deferred, and mounting happens after. Subscribe with a bare
`onMounted` and Java's first push is gone, leaving a window that waits forever with a clean
console. `bridge.js` keeps the last payload so a late subscriber still receives it.

## Client-side routing

The router is in **history mode**, so `/about` is a real path. Two things make that work:

**The host answers a path that is not a file with the entry document.** Nothing was ever built at
`/about`, so a reload or a deep link asks for a file that does not exist. `WebApp.spaFallback` —
on by default — serves `index.html` instead and lets the router sort it out. That is what every
bundler's dev server does.

It applies **only to paths without a file extension**. `/about` is a route and falls back;
`/assets/app-9f2.js` is an asset that is missing, and answering *that* with HTML would replace a
clear 404 with a puzzling parse error somewhere else. Turn it off with `app.spaFallback(false)`
if your app's paths are always files.

**The bridge is referenced absolutely.** `src="/panamaview.js"`, not `"panamaview.js"`. A
relative path resolves against the page's own directory, so it works from `/index.html` and
breaks from `/chat/42`, where the browser would ask for `/chat/panamaview.js` — a 404, a page with
no bridge, and no error worth reading. This is easy to get wrong and hard to diagnose, which is
why it is written down in two places.

If you would rather not depend on the fallback, `createWebHashHistory()` needs nothing from the
host; the cost is a `#` in the URL.

## Why the Vite config is boring

It is boring on purpose. It would need `base: "./"` if an absolute `/assets/x.js` resolved to the
filesystem root — which it does from a `file://` page. It would need a single-file plugin if a
module script were fetched over `file://`, where the origin is opaque, the fetch is refused, and
**nothing runs at all**: a blank window, a clean console, and a page that reports it loaded fine.

The host serves these assets over its own URL scheme instead, so the page has a real origin.
Absolute paths resolve, the module graph loads, dynamic imports and code splitting work, `fetch`
works, and Workers work. See the framework's `NOTES.md` for the full comparison.

## Adding assets

`scripts/build-frontend.sh` writes `src/main/resources/web/manifest.txt` from the build output, so
anything Vite emits is picked up automatically.

`-H:IncludeResources='web/.*'` in `build-native.sh` is the part it cannot do for you: resources
are not inherited from a dependency, so each app declares its own. Miss it and the app compiles,
links, starts, and dies with `IOException: missing resource` the moment it opens a window.

## Verifying without a window

```sh
./build-native.sh
./target/myapp -Dpanamaview.check=true
```

It stages the assets, and resolves every `src`/`href` in the built page against the staged files —
the failure mode that matters, because the page loading and the script not loading looks like a
blank window with nothing to go on. It never opens a window, so it runs in CI.
