# panamaview

[![CI](https://github.com/lihuu/panamaview/actions/workflows/ci.yml/badge.svg)](https://github.com/lihuu/panamaview/actions/workflows/ci.yml)

Drive the operating system's web view from Java over the FFM API. No JNI, no JNA, no shim
library and no bundled browser engine — everything crosses into Objective-C through
`java.lang.foreign`.

> **The name.** `java.lang.foreign`, the API this is built on, came out of OpenJDK's
> [Project Panama](https://openjdk.org/projects/panama/) — named after the canal that joined
> two oceans. Joining Java and native code is roughly the same trick.
>
> **Status.** macOS only. No application outside this repository has been built on it yet,
> which is the one thing a framework cannot prove about itself — see
> [Not done](#not-done-deliberately) for the rest of the honest list.

**The Phase 1 acceptance criterion is met.** Both chains below run end to end, on the JVM
*and* as a GraalVM native executable:

```
Java → FFM → Cocoa → WKWebView → HTML/JS → JS→Java → Java→JS
```

macOS is the only platform implemented. It is the one being used to prove the approach, not
the one the design is bound to — see [Keeping the platform isolated](#keeping-the-platform-isolated).

![The demo app running](docs/screenshot.png)

<sub>A real window, photographed by the app itself through WebKit's snapshot API. The bridge log
is the self-test reporting: every `PASS` there is a check that would fail if the mechanism under
it were removed.</sub>

## Requirements

- macOS on Apple Silicon (built and verified on macOS 27.2, arm64)
- JDK 25 with the FFM API
- GraalVM CE 25.0.2 (or any GraalVM with `native-image`) — only for the native build
- Maven

There are **no runtime dependencies**. FFM is the entire binding layer.

## Run the demo

There is nothing to launch in this directory, and that is the point: a library has no `main()`.
`demo-app/` is a small application that depends on `panamaview` the way anyone else would — one
dependency, its own sources, its own assets — and is also the acceptance test, because every
mechanism in the bridge has a probe there.

```sh
cd demo-app
./run.sh                       # JVM mode; prints [selftest] 13 passed, 0 failed
./build-native.sh              # → target/panamaview-demo, a standalone executable
./target/panamaview-demo       # ~20 MB Mach-O arm64, no JVM involved
```

The Maven equivalent of `run.sh` is `mvn compile exec:exec`.

Three system properties change what happens on start-up:

```sh
cd demo-app
JAVA_OPTS="-Dpanamaview.check=true" ./run.sh               # stage the assets and stop; no window server needed
JAVA_OPTS="-Dpanamaview.snapshot=/tmp/shot.png" ./run.sh   # photograph the window when loaded
JAVA_OPTS="-Dpanamaview.navigateAway=true" ./run.sh        # end the run by navigating away
```

`panamaview.check` is what CI runs, on a machine with no window server: it proves the framework
resolves and the assets are on the classpath, without loading AppKit at all.

`panamaview.snapshot` is handy when you cannot see the desktop, and is an end-to-end test of the
Objective-C block bridge in its own right. `panamaview.navigateAway` exercises the page-unload
path and leaves the window on a blank document, so it is off by default.

## Getting the framework

A generated project depends on `io.github.lihuu:panamaview`, so that artifact has to be resolvable
before anything will build. Until it is published to Maven Central, there are two ways:

```sh
mvn install -DskipTests       # in this repository; makes it resolvable on this machine only
```

```sh
./scripts/deploy-private.sh   # publish to your own repository; resolvable by any machine that
                              # has it configured, which is what makes a generated project
                              # buildable somewhere that has no checkout of this one
```

The script reads the repository URL and its credentials from `~/.m2/settings.xml` rather than
carrying them itself — a private repository URL contains an account identifier, and this repository
is public. It decides between the release and snapshot repository from the version, refuses to
republish a version that is already there, and then **resolves both artifacts back from an empty
local repository and compares hashes**, because "the upload returned 201" and "another machine can
get the file that was just built" are different claims. `--verify-only` runs that check on its own.

It targets a private repository, not Maven Central. Publishing the framework publicly is a later
step, and it waits on something this repository cannot supply by itself: evidence that someone has
built a real application on it.

## Starting a project

A scaffolder generates a working project from any of the templates:

```console
$ ./scaffold/build-native.sh            # → scaffold/target/panamaview
$ ./scaffold/target/panamaview templates
panamaview 0.1.0

  vanilla           no Node       plain HTML, CSS and JS — no build step
  vanilla-tailwind  no Node       plain HTML styled with Tailwind
  react             needs Node    React, built by Vite
  vue               needs Node    Vue 3 with a history-mode router, built by Vite
  angular           needs Node    Angular with a history-mode router

$ ./scaffold/target/panamaview create my-chat-app --template vue
creating /work/my-chat-app
  template  vue               Vue 3 with a history-mode router, built by Vite
  package   com.example.mychatapp
  title     My Chat App
  18 files written
```

The name, package and title all come from the directory, so one option is usually enough. It writes
no build output, and never overwrites a directory that has something in it without `--force`.

Five templates, which you can also copy by hand:

| | frontend | needs Node | routing | editing experience |
| --- | --- | --- | --- | --- |
| `templates/vanilla/` | plain HTML, hand-written CSS | no | — | edits reload the window |
| `templates/vanilla-tailwind/` | plain HTML + Tailwind CSS | **no** | — | edits reload; re-run the CSS build |
| `templates/react/` | React, built by Vite | yes | — | hot module replacement |
| `templates/vue/` | Vue + vue-router, built by Vite | yes | history mode | hot module replacement |
| `templates/angular/` | Angular, built by the Angular CLI | yes | history mode | dev server |

The "needs Node" column is about **editing and building**, not running: a released app is a native
executable plus static files, with no Node anywhere. [Where your page
runs](docs/page-environment.md#when-node-is-needed-and-when-it-is-not) has the measured details.

```sh
cp -R templates/react ../myapp && cd ../myapp
./dev.sh                           # dev server, hot module replacement
./run.sh                           # production build, loaded from disk
./build-native.sh                  # → target/myapp
```

Either way, the project needs `io.github.lihuu:panamaview` to be resolvable first — see
[Getting the framework](#getting-the-framework).

Every template is deliberately small — a window, one binding, and the bridge in both directions — and
neither contains any bridge plumbing: the framework stages `panamaview.js` into the web root at
startup, straight out of the jar. That file is half the protocol, so the copy you get always
matches the panamaview on your classpath.

A few things no template hides from you, each explained in its own README:

- **The two JVM flags** in `pom.xml`. Dropping either misbehaves in a way that never names the
  cause.
- **Your own web assets are not inherited from a dependency.** They need a line in
  `manifest.txt` and a matching `-H:IncludeResources` in the native build. The framework's
  metadata, including the bridge, *is* inherited — nothing in the templates mentions it.
- **Tailwind needs a build step**, but not Node: the `vanilla-tailwind` template uses Tailwind's
  standalone binary, fetched once and checksum-verified. The stylesheet is generated, so run
  `./run.sh` rather than Maven directly.
- **A relative path to the bridge breaks on any route with a segment.** Use
  `<script src="/panamaview.js">`. A relative `panamaview.js` resolves against the page's own
  directory, so it works from `/index.html` and fails from `/chat/42`, with no error worth reading.
- **Client-side routes in history mode need the entry-document fallback**, on by default
  (`WebApp.spaFallback`). It applies only to extension-less paths, so a missing asset is still an
  honest 404 rather than a page of HTML under a `.js` URL.
- **Your assets are served over a private URL scheme, not `file://`.** That is not a detail: an
  opaque `file://` origin refuses ES modules, `fetch` and Workers, which is to say a bundled
  frontend cannot run under it. `panamaview://app/…` gives the page a real origin, so a plain
  Vite / webpack config works unmodified. [Where your page runs](docs/page-environment.md)
  explains what the web view is, what it is not, and why every problem in this area came from
  serving rather than from the engine.

Each template pins a panamaview version. `scripts/verify-templates.sh` builds them the way a user
receives them — framework installed, template copied somewhere unrelated, taken all the way to a
native executable using the template's own scripts — and fails if a pin has drifted. CI runs it
on every push, one job per template.

### Development and production pages

A panamaview app has the same page in two modes, and `PageSource` models exactly that:

```java
String dev = System.getProperty("panamaview.dev");
if (dev != null) app.dev(URI.create(dev));   // a dev server
                                             // otherwise: the staged files
```

One flag, no other change. Nothing is staged in dev mode, because the dev server owns the page —
which is what keeps HMR and live reloading working. For a file-backed page the framework watches
the assets and reloads when they change, so plain HTML gets the same editing experience without
Node being installed at all.

## The self-test

The page runs a self-test on load and sends its verdict back to Java, so the result appears in
stdout and can be checked mechanically rather than by reading a screenshot:

```
[selftest] 13 passed, 0 failed
[selftest]   PASS  hello — Hello World
[selftest]   PASS  json round trip — {"a":1,"b":"two","c":[3,4],"d":{"nested":true},"e":null}
[selftest]   PASS  slow handler does not block — hello answered in 1 ms while slow(600 ms) was in flight
[selftest]   PASS  control: UI-thread handler does block — hello delayed 406 ms behind slowUi(400 ms)
[selftest]   PASS  cancel actually stops the handler — handler interrupted (count 0 → 1)
[selftest]   PASS  java-side deadline fires on its own — rejected after 303 ms with "no answer within 300 ms"
[selftest]   PASS  deadline can be disabled — ran 200 ms with no deadline
[selftest]   PASS  by-value NSRect round trip — 1000×700 window #17326
[selftest]   PASS  platform extra runs on UI thread — onUiThread=true
[selftest]   PASS  web view follows the window — 900.0x620.0 → 1000.0x700.0
[selftest]   PASS  ordinary handler runs off the UI thread — OpenJDK 64-Bit Server VM, imageKind=jvm
[selftest]   PASS  200 concurrent invocations — 200 round trips in 22 ms
[selftest]   PASS  webkit self-snapshot — /tmp/co2.png
```

## Layout

```
src/main/java/panamaview/              portable — JDK types only
  WebApp.java        lifecycle and the entry point
  Bridge.java        request framing, dispatch, deadlines, cancellation, replies
  UiHost.java        the seam a platform implements
  WindowSpec.java    what a host needs to open a window
  PageSource.java    the page is a staged file, or a URL from a dev server
  DevReload.java     reloads a file-backed page when its assets change
  Resources.java     stages classpath web assets on disk for the platform
  Json.java          minimal JSON codec

src/main/java/panamaview/macos/        the only platform implementation
  MacOsHost.java     Cocoa + WKWebView behind UiHost; platform extras
  UiThread.java      libdispatch: hop onto the UI thread
  ObjC.java          objc_msgSend re-typed per call site, autorelease pools, class creation
  Cocoa.java         NSApplication, menu bar, NSWindow
  WebView.java       WKWebView: load pages, evaluate JS, snapshot, geometry
  AssetScheme.java   the app's private URL scheme — what makes the page a normal web page
  AssetSchemeHandler.java  serves assets over it (WKURLSchemeHandler, defined at runtime)
  ObjCDispatch.java  the ObjC class defined at runtime whose methods are Java methods
  NativeBlock.java   builds an Objective-C block by hand

src/main/resources/
  panamaview/panamaview.js   the client bridge — staged into your web root, not copied

demo-app/              an ordinary consumer: the self-test in a real window
  src/main/java/panamaview/demo/App.java   handlers and the self-test handshake
  src/main/java/panamaview/demo/Main.java  composition root
  src/main/resources/web/                  the demo's page: index.html, app.js, style.css, blank.html

templates/vanilla/     a working app to start from — plain HTML, no Node
templates/vanilla-tailwind/  the same, styled with Tailwind CSS and no Node at all
templates/react/       the same, with a React frontend built by Vite
templates/vue/         the same, with a Vue frontend and a history-mode router
templates/angular/     the same, with an Angular frontend and a history-mode router
scaffold/              the generator: embeds the templates, renames them, writes a project
  scripts/embed-templates.sh   stages templates/ into the jar, with a manifest
scripts/verify-templates.sh  builds each template the way a user receives it
scripts/verify-scaffold.sh   generates from each template, then builds the result
scripts/deploy-private.sh    publishes to a private repository, then fetches it back to check
  META-INF/native-image/…/reachability-metadata.json
```

The API is intentionally minimal:

```java
MacOsHost host = new MacOsHost();
new WebApp(host)
        .title("My App")
        .size(1000, 700)
        .load("/web/index.html")            // staged out of your own resources
        .bind("hello", params -> "Hello " + params.get("name"))
        .run();
```

## The bridge

JavaScript calls Java through WebKit's one native primitive, a script message handler:

```js
const result = await java.invoke("hello", { name: "World" });   // → "Hello World"
```

Java → JavaScript is the mirror image:

```java
app.postToJs(Map.of("message", "Hello from Java"));   // → window.onJavaMessage(payload)
```

Both directions are JSON text — the only representation JavaScript, the Objective-C message
body and Java all already agree on. Replies are correlated by id, so they may arrive out of
order; the page never assumes request order.

### Threading

A message from the page arrives on the **UI thread**, inside AppKit's own callback, with the run
loop blocked on it. Returning quickly is the contract, not an optimisation. So handlers are
dispatched off that thread and only reply delivery comes back:

```java
web.bind("hello", params -> ...);      // virtual thread — may take its time
web.bindUi("resize", params -> ...);   // UI thread — window work, keep it quick
```

`bindUi` exists because some operations (`setContentSize:`, snapshotting) are only legal on the
UI thread. The trade is explicit rather than hidden: `bind` cannot freeze the window, `bindUi`
can.

### Deadlines

Every `bind` handler has a deadline, 30 seconds by default:

```java
new WebApp(host).timeout(Duration.ofSeconds(30));          // app-wide
web.bind("hello", fn);                                     // inherits it
web.bind("research", fn, Duration.ofMinutes(10));          // its own
web.bind("agent-run", fn, null);                           // no deadline at all
```

When a deadline passes the request is answered with an error **and the handler is interrupted**,
which genuinely stops sleeping, waiting and blocking `java.nio` work. It cannot stop a handler
that never checks for interruption — a CPU-bound loop, or a native call that has gone into
Apple's libraries and not come back. Cancellation is cooperative: the framework can deliver the
request, not enforce it.

`bindUi` handlers get **no** deadline, deliberately. Deadlines are enforced by a watchdog but
reported on the UI thread — which, for a stalled UI-thread handler, is exactly the thread that
cannot answer. A timer there would look like protection without being any. A slow `bindUi`
handler freezes the app; that is why so few things may be one.

`null` deadlines are the escape hatch for work that is honestly unbounded. Prefer a generous
explicit value over `null` when you can estimate one.

### Cancellation

`invoke` returns a promise, and that promise can be cancelled:

```js
const run = java.invoke("slow", { ms: 60000 });
run.cancel();     // asks Java to stop, and stops the page waiting
```

The page stops waiting immediately; Java is *asked* to stop and interrupts the handler. Both
halves matter — a page that merely stops listening has not freed anything.

The protocol is additive: `{type: "invoke" | "cancel", …}` in, `__pvResolve` back, with a
missing `type` treated as an invocation so an older page still works.

There is also a page-side fuse 5 seconds behind Java's 30 second default, so that if the reply
itself never makes it back the interface still recovers. It sits behind Java's deadline rather
than in front of it, so the more precise error normally wins.

Requests in flight when the page navigates away are abandoned automatically — they belong to a
document that no longer exists.

## Keeping the platform isolated

The application layer knows nothing about Objective-C, FFM or window handles. Everything a
platform must provide is four methods and a callback interface:

```java
public interface UiHost {
    void start(Callbacks callbacks, WindowSpec spec);
    void evaluate(String script);
    void onUiThread(Runnable action);
    boolean isUiThread();
}
```

Inside `panamaview.macos`, only `MacOsHost` is public — the native classes are package-private, so
an application cannot reach them, and the composition root that wires a host to an app lives with
the app, not with the platform. The portable package has been checked to import nothing but the JDK.

Deliberately **not** on the seam: `snapshot`, `geometry`, `resize`. Those are macOS capabilities
today, so they are ordinary bindings registered where the app is composed — `demo-app`'s `Main`
registers them with `bindUi`. Inventing cross-platform signatures before a second implementation
exists would be a guess; each earns a place on `UiHost` when a second platform wants it.

This is package-level isolation, not module-level — one Maven module, all by convention. The day
a second platform is real, moving `panamaview.macos` into its own module turns that convention
into a guarantee the compiler enforces.

## Verified

Measured on this machine, not inferred. Both builds pass the same checks.

| Check | Result |
| --- | --- |
| Self-test, JVM and native | **13 passed, 0 failed** on both |
| Slow handler off the UI thread | unrelated call answered in **1 ms** while a 600 ms handler was in flight |
| Control: same work pinned to the UI thread | that call delayed **406 ms** — the contrast is the evidence |
| Handler placement on a full run | 205 of 209 handlers on virtual threads; the rest on `main` were exactly the `bindUi` ones |
| Cancel stops the handler | Java-side counter moved **0 → 1**: the sleeping handler was interrupted, not merely abandoned by the page |
| Java-side deadline | a 300 ms binding rejected after **303 ms** while the page allowed 10 s — Java's timer fired, not the page's fuse |
| Deadline disabled | a `null`-deadline binding completed normally |
| Page-unload auto-cancel | navigating away logged `cancel 1 request(s): the page navigated away` |
| 200 concurrent invocations | JVM **22 ms**, native **17 ms**, every reply matched its request |
| Structured round trip | `{a:1,b:"two",c:[3,4],d:{nested:true},e:null}` returned byte-identical |
| By-value `NSRect` get/set | `frame` read, written back, re-read → `roundTripStable: true` |
| Resize | content set to 900×620 → web view frame 900×620 (`followed: true`), restored to 1000×700 |
| Bundled frontend, in production | React template builds to `index.html` + `assets/*.js` + `assets/*.css`, served over `panamaview://app/` |
| Code splitting | a lazy route loads its own chunk (`assets/Lazy-*.js`) on demand — impossible over `file://` |
| Asset references | the template's check resolves every `src`/`href` in the built page against the staged files |
| Scaffolder | 78 files across five templates; generated under a hyphenated name and a package outside `com.example`, then checked for any surviving template name |
| Scaffolded projects | each one builds on the JVM and to a native executable, using the scripts it was generated with |
| Scaffolder native build | 13 MB Mach-O arm64, carrying every template file — including the `.gitignore` files, which two Maven plugins drop by default |
| Native executable | 19 MB Mach-O arm64; links only libSystem, libobjc, Foundation, CoreFoundation, libz |
| Native build time | ~18 s, no fallback (`--no-fallback` fails rather than silently emitting a JVM launcher) |
| Startup, process → page loaded | native 631–730 ms, JVM 592–723 ms — **statistically identical** |

That last row is the most interesting negative result: native image buys **no** startup win here.
AppKit and WebKit initialisation dominate, and they cost the same either way. Its value in this
design is distribution — a single file with no JVM requirement — and one real ergonomic win: a
native executable does **not** need `-XstartOnFirstThread`, because its main thread already is
the process' first thread. The JVM does need it. See `NOTES.md`.

## Releasing

```sh
./scripts/release.sh patch        # or minor, major, or an explicit x.y.z
./scripts/release.sh --dry-run patch
```

It verifies first — both `verify-templates.sh` and `verify-scaffold.sh`, so a release cannot carry a
version number that has not been taken all the way to a native executable — then bumps the version,
commits, tags, and pushes. Order matters: a pushed tag is a public claim that the version is real,
and it cannot be quietly taken back, so nothing that can fail is left until after it exists.

**The tag is the release.** Pushing it publishes the source, as GitHub archives it, and nothing
else — `.github/workflows/release.yml` only checks that the tag and the version fields below agree.

v0.1.0 did carry downloads: native scaffolder archives for macOS arm64 and Linux x64, plus the
framework's jar and pom. They were withdrawn the day after publication. The artifacts were fine —
both were unpacked and taken end to end, on real Mach-O arm64 and real ELF x86-64. The claim was
what was wrong: a download page says *this is a tool, take it*, and no application outside this
repository has been built on panamaview yet. When one has been, the archives come back.

### The version lives in eight places, and only seven of them move

| | field | moves with a release? |
| --- | --- | --- |
| `pom.xml` | `<version>` | yes — the framework |
| `scaffold/pom.xml` | `<version>` | yes — the scaffolder |
| `templates/*/pom.xml` (×5) | `<panamaview.version>` | yes — what a generated project depends on |
| `templates/*/pom.xml` | `<version>` | **no** — that is the version of the app the template describes |
| `templates/*/frontend/package.json` | `version` | **no** — same thing, for the frontend package |

The seven that move are one value, because the templates are staged from this working tree: a
template pinning anything other than the framework's version generates projects that cannot
resolve. `verify-templates.sh` fails on exactly that, which is how the invariant is enforced rather
than remembered — and it is why the release script runs verification *before* it bumps anything.

`release.sh` also reports which documents still name the old version. Sample output in these docs
quotes what the tool printed, and NOTES.md quotes what past releases actually published — that is
evidence, so it is listed for a human to look at rather than rewritten.

## Not done (deliberately)

- One window; no multi-window, tabs, menus beyond Quit, clipboard, file dialogs, drag & drop,
  notifications or tray
- No streaming messages; one reply per request
- No retries and no backpressure — a burst is bounded only by virtual threads being cheap
- Cancellation is cooperative, so a handler that ignores interruption keeps running to
  completion (invisible to the page, but still consuming whatever it consumes)
- No error taxonomy — a thrown Java exception, a timeout and a cancellation all arrive as
  strings
- Objects retained for Objective-C are never released; there is no ownership tracking, so a
  long-running app with many transient objects would leak
- No cross-platform abstraction beyond `UiHost`, no JavaFX backend, no plugin system
- No `.app`, code signing, notarisation or `.dmg`

## Notes

`NOTES.md` records what was actually hard: the FFM rules that are not in the tutorials, the ABI
cases, autorelease-pool discipline, the libdispatch details behind the threading fix, what
GraalVM accepts and what it rejects, the measurements, and the two occasions where a test looked
green while proving nothing. If you are evaluating this approach, read that instead of this.
