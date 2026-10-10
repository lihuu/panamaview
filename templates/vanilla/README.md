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

## Run it

```sh
./run.sh                       # on the JVM
./build-native.sh              # → target/myapp, a standalone executable
./target/myapp
```

`mvn compile exec:exec` is the equivalent of `./run.sh`.

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
