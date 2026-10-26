# angular template

A panamaview app with an Angular frontend: Java owns the window and the work, Angular owns the
interface, and a JSON bridge runs between them. The router runs in history mode, so URLs are real
paths.

```
src/main/java/com/example/Main.java        the Java side
frontend/                                  the Angular app
  angular.json, tsconfig*.json
  src/index.html
  src/main.ts                              bootstrap
  src/styles.css
  src/app/bridge.ts                        the Java → page channel, as a service
  src/app/app.ts, app.html                 shell: nav, router outlet, the two panels
  src/app/home.ts, about.ts                two routes, so the routing path is exercised
  src/app/app.config.ts, app.routes.ts
scripts/build-frontend.sh                  builds frontend/ into src/main/resources/web/
dev.sh                                     dev server + live rebuild
run.sh                                     production build, served from the app's scheme
build-native.sh                            → target/myapp
```

`src/main/resources/web/`, `frontend/dist/` and `frontend/public/panamaview.js` are all
**generated**. None are checked in, because all three are derived and a committed copy of a
derived file goes stale without saying so.

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
| `./package.sh` | yes → `.app` + `.dmg` | — |

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
./dev.sh              # dev server
./run.sh              # production build on the JVM
./build-native.sh     # → target/myapp, a standalone executable
```

Node 24 or newer is required. If you do not want a frontend toolchain at all, use the vanilla
template — it has the same editing experience without needing Node.

That requirement is **development-time only**. `build-native.sh` produces a native executable
plus a directory of static files, and neither has any trace of Node at runtime — the frontend
toolchain is scaffolding you can delete once the app is built.

## Packaging

`./package.sh` turns the native build into `target/dist/My App.app` and a `.dmg` with an
`/Applications` link. Identity comes from the pom; `--title`, `--bundle-id`, `--version` and
`--icon` override it, and the default signature is ad-hoc.

Windows packaging is not available for this template yet: the frontend build is a POSIX script,
and only the vanilla template has a Windows native build today. See
[Packaging an app](https://github.com/lihuu/panamaview/blob/main/docs/packaging.md).

## The bridge

```ts
import { inject } from "@angular/core";
import { Bridge } from "./bridge";

protected readonly bridge = inject(Bridge);

async ask() {
  this.answer.set(await this.bridge.invoke("greet", { name: "World" }));
}
```

```java
.bind("greet", params -> "Hello " + params.getOrDefault("name", "world"))
```

Java → page arrives at one callback, which `bridge.ts` exposes as a signal:

```html
<pre>{{ bridge.fromJava() ? (bridge.fromJava() | json) : "waiting for Java…" }}</pre>
```

`bridge.ts` is not ceremony. The callback fires on *page ready*, which is earlier than it looks:
the module script is deferred, but `bootstrapApplication` is itself asynchronous, and a root
service is created lazily on first injection. Subscribe with a bare `ngOnInit` and Java's first
push is gone, leaving a window that waits forever with a clean console. So the last payload is
kept at module scope, and `App` injects `Bridge` eagerly so it exists during bootstrap. The React
and Vue templates need the same thing, for the same reason.

## Client-side routing

The router is in **history mode**, so `/about` is a real path. Two things make that work:

**The host answers a path that is not a file with the entry document.** Nothing was ever built at
`/about`, so a reload or a deep link asks for a file that does not exist. `WebApp.spaFallback` —
on by default — serves `index.html` instead and lets the router sort it out. That is what every
bundler's dev server does.

It applies **only to paths without a file extension**. `/about` is a route and falls back;
`/assets/app-9f2.js` is an asset that is missing, and answering *that* with HTML would replace a
clear 404 with a puzzling parse error somewhere else. Turn it off with `app.spaFallback(false)`.

If you would rather not depend on the fallback, `provideRouter(routes, withHashLocation())` needs
nothing from the host; the cost is a `#` in the URL.

**The bridge is referenced absolutely.** `<script src="/panamaview.js">`, not `"panamaview.js"`.
A relative path resolves against the page's own directory, so it works from `/index.html` and
breaks from `/chat/42`, where the browser would ask for `/chat/panamaview.js` — a 404, a page with
no bridge, and no error worth reading. Angular's `<base href="/">` comes *after* that tag, so it
does not help; say the absolute path outright.

## Why there is nothing to configure

Angular's builder emits relative references plus `<base href="/">`, which is exactly what a page
served from a real origin wants. No `base` option, no single-file plugin, no `--deploy-url`.

That is worth stating because it is not accidental. An earlier version of the React template
carried two workarounds — `base: "./"` and inlining the whole bundle — both symptoms of the page
being served over `file://`, where an absolute path resolves to the filesystem root and a module
script is refused outright because the origin is opaque. The host serves over its own URL scheme
instead, so none of that is needed here. See the framework's
[docs/page-environment.md](https://github.com/lihuu/panamaview/blob/main/docs/page-environment.md).
(An absolute URL rather than a relative one, because this README is also the README of a
generated project, where `../../docs/` would point outside it.)

## Adding assets

`scripts/build-frontend.sh` writes `src/main/resources/web/manifest.txt` from the build output, so
anything Angular emits is picked up automatically. Note the output path: Angular nests it as
`dist/browser/`, and that browser directory is the web root — `dist/` holds only build metadata.
`angular.json` pins `outputPath` so the path does not depend on the project name.

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
