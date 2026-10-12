# react template

A panamaview app with a React frontend: Java owns the window and the work, React owns the
interface, and a JSON bridge runs between them.

```
src/main/java/com/example/Main.java        the Java side
frontend/                                  the React app, built by Vite
  index.html
  vite.config.js                           three settings here are load-bearing — read the comments
  src/App.jsx
  src/main.jsx
  src/style.css
scripts/build-frontend.sh                  builds frontend/ into src/main/resources/web/
dev.sh                                     dev server + hot module replacement
run.sh                                     production build, loaded from disk
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
template instead — it has the same editing experience without needing Node.

That requirement is **development-time only**. `build-native.sh` produces a native executable
plus a directory of static files, and neither has any trace of Node at runtime — the frontend
toolchain is scaffolding you can delete once the app is built.

## Developing

`./dev.sh` starts Vite, waits for it, then launches the app pointed at it. Edit a `.jsx` file and
the window updates with no restart:

```
editing src/App.jsx  →  the window changes
```

The Java process is *not* reloaded, and does not need to be — the page is the only thing that
changed. If you change Java, restart `./dev.sh`; that restart takes about a second, which is why
nobody has asked for Java hot reload here.

## The bridge

```jsx
const answer = await window.java.invoke("greet", { name: "World" });
```

```java
.bind("greet", params -> "Hello " + params.getOrDefault("name", "world"))
```

Java → page goes to a single callback, which `main.jsx` re-broadcasts as a DOM event so that any
component can subscribe without the bridge needing to know about React:

```js
window.onJavaMessage = (payload) => {
  window.dispatchEvent(new CustomEvent("java:message", { detail: payload }));
};
```

```jsx
useEffect(() => {
  const onMessage = (event) => setFromJava(event.detail);
  window.addEventListener("java:message", onMessage);
  return () => window.removeEventListener("java:message", onMessage);
}, []);
```

## Why the Vite config is boring

It is boring on purpose. Earlier versions of this file carried two workarounds:

- **`base: "./"`** — because an absolute `/assets/x.js` resolves to the *filesystem root* from a
  `file://` page rather than to the page.
- **`vite-plugin-singlefile`** — because a module script is fetched under CORS rules and a
  `file://` origin is opaque, so the fetch was refused and nothing ran. Inlining the bundle
  removed the fetch, and with it code splitting.

Both were symptoms of the page being loaded over `file://`. The host now serves the app's own
assets over a private URL scheme, so the page has an origin of its own: absolute paths resolve
normally, the module graph loads, dynamic imports work, `fetch` works, and Workers work.

So the config is one plugin and nothing else. If you ever see a blank window with a clean console
here, check that the page is being served over `panamaview://` and not over `file://` — that
pairing is the entire failure mode, and it is silent.

## Adding assets

`scripts/build-frontend.sh` writes `src/main/resources/web/manifest.txt` from the build output, so
anything Vite emits is picked up automatically. It copies the whole `dist/` tree, subdirectories
included, and the manifest lists them.

Two things it does **not** handle, because they are not part of the bundle:

- **`-H:IncludeResources='web/.*'`** in `build-native.sh` — resources are not inherited from a
  dependency, so each app declares its own. Miss it and the app compiles, links, starts, and dies
  with `IOException: missing resource` the moment it opens a window.
- **`public/`** — files there are copied verbatim into `dist/` by Vite, so they do end up in the
  manifest. That is where the client bridge lives in development, but the built app uses the
  framework's own copy, which is why the rewrite above exists.

## Verifying without a window

```sh
./build-native.sh
./target/myapp -Dpanamaview.check=true
```

It stages the assets and reports what it found, and additionally asserts that the built
`index.html` contains no absolute asset references — the failure mode that works in development
and silently breaks from disk. It never opens a window, so it runs in CI.
