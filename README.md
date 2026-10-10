# panamaview

Drive the operating system's web view from Java over the FFM API. No JNI, no JNA, no shim
library and no bundled browser engine — everything crosses into Objective-C through
`java.lang.foreign`.

> **The name.** `java.lang.foreign`, the API this is built on, came out of OpenJDK's
> [Project Panama](https://openjdk.org/projects/panama/) — named after the canal that joined
> two oceans. Joining Java and native code is roughly the same trick.
>
> This is a **macOS proof of concept**, not a finished framework. See
> [Not done](#not-done-deliberately) for the honest list.

**The Phase 1 acceptance criterion is met.** Both chains below run end to end, on the JVM
*and* as a GraalVM native executable:

```
Java → FFM → Cocoa → WKWebView → HTML/JS → JS→Java → Java→JS
```

macOS is the only platform implemented. It is the one being used to prove the approach, not
the one the design is bound to — see [Keeping the platform isolated](#keeping-the-platform-isolated).

![The PoC running](docs/screenshot.png)

<sub>A real window, photographed by the app itself through WebKit's snapshot API. The bridge log
is the self-test reporting: every `PASS` there is a check that would fail if the mechanism under
it were removed.</sub>

## Requirements

- macOS on Apple Silicon (built and verified on macOS 27.2, arm64)
- JDK 25 with the FFM API
- GraalVM CE 25.0.2 (or any GraalVM with `native-image`) — only for the native build
- Maven

There are **no runtime dependencies**. FFM is the entire binding layer.

## Run it

```sh
./run.sh                       # JVM mode
./build-native.sh              # → target/panamaview, a standalone executable
./target/panamaview            # ~20 MB Mach-O arm64, no JVM involved
```

The Maven equivalent of `run.sh` is `mvn compile exec:exec`.

Two system properties change what happens on start-up:

```sh
JAVA_OPTS="-Dpanamaview.snapshot=/tmp/shot.png" ./run.sh   # photograph the window when loaded
JAVA_OPTS="-Dpanamaview.navigateAway=true" ./run.sh        # end the run by navigating away
```

`panamaview.snapshot` is handy when you cannot see the desktop, and is an end-to-end test of the
Objective-C block bridge in its own right. `panamaview.navigateAway` exercises the page-unload
path and leaves the window on a blank document, so it is off by default.

## Starting a project

`templates/vanilla/` is a working app to copy:

```sh
mvn install                      # this repository, until panamaview is on Maven Central
cp -R templates/vanilla ../myapp && cd ../myapp
./run.sh
```

It is deliberately small — a window, one binding, and the bridge in both directions. Its
`app.js` contains no bridge plumbing, because the framework stages `panamaview.js` into the
web root at startup, straight out of the jar. That file is part of the protocol, so the copy
you get always matches the panamaview on your classpath.

Two things the template cannot hide from you, both explained in its README: the two JVM flags
in its `pom.xml` (dropping either misbehaves in a way that never names the cause), and the fact
that **your own web assets are not inherited from a dependency** — they need a line in
`manifest.txt` and a matching `-H:IncludeResources` in the native build. The framework's
metadata, including the bridge, *is* inherited; nothing in the template mentions it.

The template pins a panamaview version. `scripts/verify-template.sh` builds it the way a user
receives it — framework installed, template copied somewhere unrelated, taken all the way to a
native executable — and fails if that pin has drifted. CI runs it on every push.

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
  WebApp.java        lifecycle and the exploratory entry point
  App.java           the PoC application: handlers and the self-test handshake
  Bridge.java        request framing, dispatch, deadlines, cancellation, replies
  UiHost.java        the seam a platform implements
  WindowSpec.java    what a host needs to open a window
  Resources.java     stages classpath web assets on disk for the platform
  Json.java          minimal JSON codec

src/main/java/panamaview/macos/        the only platform implementation
  MacOsHost.java     Cocoa + WKWebView behind UiHost; platform extras
  Main.java          composition root
  UiThread.java      libdispatch: hop onto the UI thread
  ObjC.java          objc_msgSend re-typed per call site, autorelease pools, class creation
  Cocoa.java         NSApplication, menu bar, NSWindow
  WebView.java       WKWebView: load files, evaluate JS, snapshot, geometry
  ObjCDispatch.java  the ObjC class defined at runtime whose methods are Java methods
  NativeBlock.java   builds an Objective-C block by hand

src/main/resources/
  demo/              the demo app's page: index.html, app.js, style.css, blank.html
  panamaview/panamaview.js   the client bridge — staged into your web root, not copied

templates/vanilla/     a working app to start from
scripts/verify-template.sh   builds the template the way a user receives it
  META-INF/native-image/…/reachability-metadata.json
```

The API is intentionally minimal and exploratory:

```java
MacOsHost host = new MacOsHost();
new App(host).install().web().run();
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

Inside `panamaview.macos`, only `MacOsHost` and `Main` are public — the native classes are
package-private, so the application cannot reach them. The portable package has been checked to
import nothing but the JDK.

Deliberately **not** on the seam: `snapshot`, `geometry`, `resize`. Those are macOS capabilities
today, so they are ordinary bindings registered by `panamaview.macos.Main`. Inventing
cross-platform signatures before a second implementation exists would be a guess; each earns a
place on `UiHost` when a second platform wants it.

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
| Native executable | 19 MB Mach-O arm64; links only libSystem, libobjc, Foundation, CoreFoundation, libz |
| Native build time | ~18 s, no fallback (`--no-fallback` fails rather than silently emitting a JVM launcher) |
| Startup, process → page loaded | native 631–730 ms, JVM 592–723 ms — **statistically identical** |

That last row is the most interesting negative result: native image buys **no** startup win here.
AppKit and WebKit initialisation dominate, and they cost the same either way. Its value in this
design is distribution — a single file with no JVM requirement — and one real ergonomic win: a
native executable does **not** need `-XstartOnFirstThread`, because its main thread already is
the process' first thread. The JVM does need it. See `NOTES.md`.

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
- Vanilla HTML/CSS/JS only

## Notes

`NOTES.md` records what was actually hard: the FFM rules that are not in the tutorials, the ABI
cases, autorelease-pool discipline, the libdispatch details behind the threading fix, what
GraalVM accepts and what it rejects, the measurements, and the two occasions where a test looked
green while proving nothing. If you are evaluating this approach, read that instead of this.
