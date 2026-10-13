# Case study: `licode-gui`, the first real client

Every other integration document in this repository describes a template — a project the
scaffolder generated, which builds and runs because it was written to. This one describes an
application that was **not** written by the framework's author: `licode-gui`, a window onto a
coding agent, whose requirements arrived one at a time and in the order a user had them.

That difference is the whole reason this document exists. A template exercises the framework in
the order the framework expects. A client finds the seams in the order its own work demands, and
what it hits first is rarely what the design notes worried about.

Concretely, it produced four things worth writing down:

- an **integration shape** — what the Java side and the page side each look like when the app is
  real, not a greeting (17 bindings, two hooks, sixteen push types, one handshake);
- a **division of labour** — which parts of a native build a consumer inherits and which it must
  declare itself, and there is an asymmetry there that stays invisible until an executable runs;
- **numbers** for both run modes, and the analysis of why the native binary is the size it is;
- a **worked example of the two-repository loop** for when the framework is genuinely missing
  something.

## Where the client lives

`licode-gui` is **not** in this repository. It is a Maven module of `lihuu/java-pi`, added by
`panamaview create licode-gui --template react` and then wired into that reactor
(`java-pi` commit `56354993`, 2026-10-12). It consumes this framework as a released artifact:

```xml
<properties>
  <panamaview.version>0.3.0</panamaview.version>
</properties>
...
<dependency>
  <groupId>io.github.lihuu</groupId>
  <artifactId>panamaview</artifactId>
  <version>${panamaview.version}</version>
</dependency>
```

`licode-gui/pom.xml:26`, `:36-41`. A module that resolves from Maven Central and a framework
you are editing in a checkout are two different jars; when working on both at once, install the
framework locally (`mvn install -DskipTests`) and say so, or you will spend an afternoon on a
fix that is not in the jar you are running.

Paths below are relative to the `java-pi` repository root, and were read at `origin/main` =
`db35258c` (2026-10-13). At that commit, `licode-gui` is tracked on `java-pi`'s `origin/main` but
is not present in that repository's working tree, so `git show origin/main:licode-gui/<path>`
is how to read it without checking anything out.

Every `file:line` on the framework side was read at `origin/main` = `67a41ce`. Line numbers are
the part of a citation that rots first: if one of them lands on something unrelated, the claim
is usually still true and only the anchor moved — re-grep for the symbol rather than assuming
the behaviour changed.

---

## 1. The channel

Two directions, both JSON text, both one-way calls that get one answer.

### page → Java: request/reply

```
{ type: "invoke", id, method, params }
{ type: "cancel", id, reason }
```

`framework/src/main/resources/panamaview/panamaview.js:13-18`, dispatched at
`framework/src/main/java/panamaview/Bridge.java:154-158`. A message with no `type` is treated as
an `invoke`, so a page written before cancellation existed keeps working
(`Bridge.java:151-153`).

The page-facing surface is three calls, not one:

```js
const answer = await java.invoke("project.list", {});   // → Promise of the handler's return value
const turn   = java.invoke("chat.send", { text });      // → Promise with .cancel()
turn.cancel();                                          // → posts { type: "cancel" }

panamaview.configure({ fuseMs: 120000 });               // page-side fuse, default 35000
panamaview.inFlight();                                  // how many are outstanding
panamaview.cancelAll("going away");
```

`panamaview.js:84-123`, `:135-141`.

### Java → page: two shapes, and they are not interchangeable

```js
window.__pvResolve({ id, ok, result | error })   // the answer to an invoke; the page's own plumbing
window.onJavaMessage(payload)                    // an application message; the page's to interpret
```

`Bridge.java:308-320` for the first, `Bridge.java:243-245` for the second, wrapped by
`WebApp.postToJs` (`WebApp.java:205-210`). `postToJs` is safe to call from any thread — it
marshals through `host.onUiThread` before evaluating (`Bridge.java:322-325`), which is the only
thread where `evaluateJavaScript` is legal (`UiHost.java:25`).

The two shapes exist because they answer different questions. `__pvResolve` belongs to a request
that is already waiting; `onJavaMessage` carries work whose *shape* the framework cannot know,
which is why a real client's page is a switch over `payload.type` and the framework never sees
those types at all.

### The threading contract

This is the part that is forced by the platforms rather than chosen, so it is worth stating in
the terms the code uses:

| | thread | why |
| --- | --- | --- |
| the incoming message callback | **UI thread** | it arrives inside the platform's own callback; AppKit services nothing else while it runs, so returning fast is a requirement, not an optimisation (`Bridge.java:24-26`, `UiHost.java:27-28`) |
| `bind(...)` handler | **a virtual thread**, one per request | the callback returns immediately; a burst costs a virtual thread each (`Bridge.java:27-29`, `:105-106`) |
| `bindUi(...)` handler | **UI thread**, inline | window operations are only legal there — and it gets **no deadline** (`WebApp.java:127-130`, `Bridge.java:125-132`) |
| reply delivery | **hopped back to the UI thread** | `evaluateJavaScript` is only legal there (`Bridge.java:322-325`) |

Two consequences a client feels immediately:

**Replies are not in request order.** The page correlates by `id`, which it had to do anyway,
because a reply crosses a process boundary and can never be synchronous
(`Bridge.java:34-36`, `panamaview.js:20-23`). Any page code that assumes "the answer I am
waiting for is the next message" is wrong on the first burst.

**A UI-thread handler cannot be rescued.** Deadlines are armed by a watchdog but *reported* on
the UI thread — which, for a stalled UI-thread handler, is exactly the thread that cannot answer.
The timer would fire into a queue nobody can drain. So `bindUi` gets no deadline at all, and the
honest statement is that a slow one freezes the app (`Bridge.java:125-130`). `licode-gui` binds
**zero** handlers with `bindUi`.

### Deadlines and cancellation

- Default deadline **30 s** (`Bridge.java:54`), inherited by every `bind` (`WebApp.java:89-92`).
- Per-binding override, or `null` for none (`WebApp.java:105-116`).
- The page-side fuse defaults to **35 s**, deliberately *behind* Java's, so the more precise
  error ("no answer within 300 ms", naming the binding's own limit) normally wins the race
  (`panamaview.js:46-49`).
- A request ends four ways that race — handler returns, handler throws, deadline passes, page
  cancels — and exactly one of them reports; the rest become no-ops
  (`Bridge.java:87-98`, `:38-43`). Without that, a request that timed out and *then* finished
  would report twice, and the second report would be a lie.
- **Cancellation is cooperative.** A deadline or a `cancel` interrupts the worker, which
  genuinely stops sleeping, waiting and blocking `java.nio` work. It cannot stop a handler that
  never checks for interruption — a CPU-bound loop, or a native call that went into Apple's
  libraries and has not come back (`Bridge.java:44-49`). The framework can deliver the request,
  not enforce it.
- **Requests die with their document.** Navigation cancels everything in flight, with no
  replies — there is nothing left to receive them (`WebApp.java:330-335`,
  `Bridge.java:229-240`).

### What can cross

The wire is JSON text and nothing else (`Json.java:8-14`). `Json.write` handles `null`,
`Boolean`, `Number`, `String`, `Map`, `Iterable` and `Object[]` — and **everything else falls
through to `String.valueOf`** (`Json.java:28-71`, the `default` at `:70`).

That last line is the one that bites. Returning a POJO or a record from a handler does not fail;
it silently becomes a string like `com.licode.gui.Workspace$Status@1a2b3c`, and the page renders
`undefined` for every field it wanted. Return `Map`/`List` of those seven types and nothing else.
This is why every handler in `licode-gui` answers with `Map.of(...)` or a `LinkedHashMap`, and
never with a domain object.

---

## 2. Anatomy of a real client

`licode-gui` is a window onto the licode agent runtime. Four Java classes, and none of them
overlap (`Main.java:23-30`):

| class | owns |
| --- | --- |
| `Main` | the window, the bindings, and the argument naming a project to open with |
| `AgentRuntime` | assembling a runtime for one directory — the whole of the coupling to `licode-core` |
| `Workspace` | which project and which session are current, and the runtime that follows |
| `ChatSession` | projecting what the runtime produces onto the page |
| (`Approvals`, `Endpoints`, `FileBrowser`, `ProjectStore`, `ModelSettings`) | the pieces those four delegate to |

### The composition root

```java
WebApp app = new WebApp(host())
        .title("licode")
        .size(1180, 760)
        .load("/web/index.html");

ChatSession chat      = new ChatSession(app, AgentRuntime.agentHome());
Workspace   workspace = new Workspace(app, chat, AgentRuntime.agentHome());
```

`Main.java:46-58`. Both are built *before* anything is bound, because of the one thing they need
that the window has not given them yet: the window itself. Every answer they produce leaves
through `postToJs`.

`host()` picks `WindowsHost` or `MacOsHost` from `os.name` (`Main.java:179-192`). Both are
*referenced* but only one is loaded, so the platform that is not in use contributes nothing at
run time — which matters, because `ObjC`'s `dlopen` of the Objective-C runtime is a static
initialiser and would fail before the method ran.

### The RPC table — 17 bindings

Read off `Main.java:60-108`. Every one of them is `bind`, i.e. on a virtual thread. There is no
`bindUi` in this application.

| binding | what it does | why it is shaped that way |
| --- | --- | --- |
| `chat.send` | starts a turn | answers with an *acknowledgement*, not the answer: the reply streams back over `postToJs`, so a handler that waited for it would be waiting on the whole turn (`Main.java:61-66`) |
| `chat.approval` | the approval dialog's answer | releases a tool call the turn's own thread is parked on — no work for this call to wait out |
| `chat.cancel` | the stop button | signals a token the loop observes; same reasoning |
| `chat.setModel` | writes a model name | applied through the core's own switch transaction |
| `chat.model.get` | reads the `llm` section off disk | the panel shows the file the next prepare will read, not what the runtime happens to hold |
| `endpoint.list` / `.create` / `.activate` / `.remove` / `.models` | the endpoint picker | `.models` is the one call here that leaves the machine |
| `project.open` | opens a project | can outlast the 30 s deadline — a runtime reads skills, discovers subagents and may start MCP servers — so it answers at once and reports the outcome as a push (`Main.java:93-96`) |
| `session.open` | opens one of a project's sessions | same |
| `project.list`, `session.new`, `status.get` | reads of state this process already holds | answer in place |
| `fs.home`, `fs.list` | the project picker's two calls | see §5 |

The rule the table encodes: **a call that can take seconds does not return the seconds.** It
acknowledges and pushes. The one exception is `chat.model.get`, which reads a file and is
therefore fast enough to answer directly.

### The handshake

```java
.onReady(a -> {
    a.postToJs(Map.of("type", "hello"));
    a.postToJs(Map.of("type", "projects", "projects", workspace.projectsPayload()));
    a.postToJs(workspace.statusPayload());
});
```

`Main.java:116-120`. `onReady` fires when the page reports itself loaded, which is the first
moment it is safe to call into it (`WebApp.java:183-187`). Pushing the project list and the
status here rather than letting the page ask means the sidebar and the status bar are filled by
the time the page renders, instead of one round trip later.

### The page side

Two hooks, split by which stream they follow — `useProjects` for which directory and which
session, `useChat` for what was said in it (`App.jsx:12-24`). `App.jsx` itself holds no state and
no protocol.

The push types Java actually sends, and where they are consumed — **sixteen** of them, and the
framework knows none of these names:

| push `type` | sent by | consumed by |
| --- | --- | --- |
| `hello`, `projects`, `status` | `Main.java:117-119` | `useChat.js:47`, `useProjects.js:31`, `useChat.js:82` |
| `project.opening`, `project.opened` | `Workspace.java:268`, `:286` | `useProjects.js:34-44` |
| `session.reset` | `Workspace.java:227`, `:267` | `useChat.js:93-104` |
| `history` | `ChatSession.java:283` | `useChat.js:88-92` |
| `started`, `delta`, `reasoning`, `tool` | `ChatSession.java:490`, `:516`, `:451`, `:538` | `useChat.js:55-69` |
| `approval` | `Approvals.java:202-209` | `useChat.js:85-87` |
| `done` / `cancelled`, `error` | `ChatSession.java:619`, `:626` | `useChat.js:73-81` |
| `notice` | `ChatSession.java:632`, `Workspace.java:123` | `useChat.js:70-71` |
| `projects`, `status` (re-sent) | `Workspace.java:475`, `:480` | `useProjects.js:31`, `useChat.js:82` |

The vocabulary is not declared anywhere, which is the point: it is an agreement between two
files, and the only way to enumerate it is to grep the Java side for `postToJs` and the page side
for `payload.type`. Doing that is worth the two minutes before changing either end — `approval`
is the one this list would have missed, because it is the only push that leaves from neither
`Main` nor `ChatSession` but from `Approvals`, the class that owns the queue of tool calls the
runtime is parked on (`Approvals.java:195-210`). It carries `pending`, `id`, `tool`, `summary`,
`details` and `source`, is *replaced wholesale* on every push, and pushes `pending: 0` rather
than a separate "no dialog" message — which is why the page can hold it as one piece of state
instead of assembling "which request is on screen" from two messages that could arrive out of
order (`useChat.js:36-44`, `:85-87`).

### The one client-side convention that is not obvious

The framework gives the page **one** callback. `licode-gui` puts a small adapter in front of it,
and that adapter is load-bearing (`frontend/src/bridge.js`):

```js
let latest = null;
const listeners = new Set();

window.onJavaMessage = (payload) => {
  latest = payload;
  for (const listener of listeners) listener(payload);
};

export function useJavaMessage() { /* replays `latest` on mount */ }
export function subscribeJava(handler) { listeners.add(handler); return () => listeners.delete(handler); }
```

`bridge.js:16-38`, `:54-57`. The distinction:

- **`useJavaMessage()` replays.** A component that mounts late still sees the most recent push.
  This is what makes the `hello` handshake work at all: Java's natural first push is on page
  ready, which is *before* the app has mounted anything — a module script is deferred and
  `createRoot().render()` schedules its work rather than finishing it. Subscribe with a plain
  `useEffect` and that first message is simply gone, and the window renders "waiting for Java…"
  forever with a clean console (`bridge.js:3-14`).
- **`subscribeJava()` does not replay.** It is a listener list, and a late subscriber sees only
  what comes next. Two places depend on knowing this. `useProjects` pulls `project.list` on mount
  rather than waiting for a `projects` push it may have missed — "an empty list and a list nobody
  sent look identical on screen" (`useProjects.js:62-70`). And the endpoints section pulls its
  list on mount for the same reason (`9b73e400`).

Neither behaviour is wrong; the mistake is using `useJavaMessage` for a stream. Keeping only the
latest payload loses text outright, because React batches the state updates a burst of deltas
produces and every delta in the burst but the last would be overwritten before anything rendered
(`bridge.js:40-52`). The rule: **state is `useJavaMessage`, a stream is `subscribeJava`.**

---

## 3. Who owns what

The most useful table in this repository is the one that came out of building a template the way
a user receives it (`NOTES.md` §13):

| | crosses the jar boundary? |
| --- | --- |
| `reachability-metadata.json` (foreign registrations, reflection, resources) | **yes** |
| JVM launch flags | **no** — they belong in the app's own pom or script |
| `-H:IncludeResources` for the app's own assets | **no** |

**The framework owns:**

- the FFM downcall/upcall registrations and the `panamaview/panamaview.js` resource entry
  (`framework/src/main/resources/META-INF/native-image/io.github.lihuu/panamaview/reachability-metadata.json`),
  which travel transitively with the jar;
- staging the web assets to a temp directory, and writing its own copy of the client bridge
  beside them (`Resources.java:57-73`, `:142-152`);
- serving the page over a private URL scheme rather than `file://`, so ES modules, `fetch` and
  Workers work (`WebApp.java:31-37`);
- reading the consumer's `manifest.txt` (a classpath directory cannot be enumerated portably,
  and not at all in a native image) and refusing the reserved name `panamaview.js`
  (`Resources.java:63-67`, `:154-171`);
- the whole bridge: framing, dispatch, deadlines, cancellation, UI-thread hop;
- live reload for a file-backed page, and SPA history-mode fallback.

**The client owns:**

- **its own assets in the native build.** `build-native.sh:47` carries
  `-H:IncludeResources='web/.*'`. Miss it and the app compiles, links, starts, and dies with
  `IOException: missing resource` the moment it opens a window — which is why the check mode
  below exists;
- **the manifest.** `scripts/build-frontend.sh:63-70` generates `web/manifest.txt` from Vite's
  output, because Vite's names are hashed and change on every build;
- **the frontend build order.** `scripts/build-frontend.sh` writes `src/main/resources/web/`,
  which the Java build then embeds — so it runs *before* `mvn compile`, from `run.sh:15`,
  `dev.sh:16` and `build-native.sh:28`;
- **the reserved-name dance.** Vite copies `frontend/public/` verbatim into `dist/`, so the
  dev-server copy of the client bridge arrives in the build output and must be deleted again
  (`scripts/build-frontend.sh:56-61`). Leaving it makes the app refuse to start — loudly, which
  is the intent of the reservation;
- **`window.onJavaMessage`.** A page that never defines it fails deep inside the framework with
  a `TypeError` naming an internal function; the bridge warns at `DOMContentLoaded` instead
  (`panamaview.js:143-152`);
- **the client conventions of §2** — pull-on-mount where a push can be missed, ack-then-push for
  slow work, JSON-only return values.

Two notes on the asymmetry. First, a jar **can** hand you the hard part: the foreign-function
registrations — the thing that otherwise fails *at run time* with a message naming a `libc` entry
point rather than your call site — travel with the dependency, and `licode-gui` writes nothing
for them. Second, the framework's own metadata `resources` entry must list **only** the
framework's asset. It once listed this repository's demo pages, and because the file applies
transitively, every consumer's native build was embedding them (see the `comment` block in
`reachability-metadata.json`, and `NOTES.md` §13).

---

## 4. Two ways to run

### The JVM loop

```sh
./dev.sh            # Vite dev server + hot module replacement
./run.sh            # production build, loaded from disk
```

`run.sh:15-21` builds the frontend, compiles, and materialises the dependency classpath —
**not just `target/classes`**, because panamaview's own jar carries the FFM registrations and the
client bridge, so leaving it out fails at start-up with `NoClassDefFoundError` rather than at
compile time.

The launch line is an argument list, not a command line (`run.sh:31-38`):

```sh
set -- \
    ${START_ON_FIRST_THREAD:+-XstartOnFirstThread} \
    --enable-native-access=ALL-UNNAMED \
    ${JAVA_OPTS:-} \
    -cp "target/classes:$(cat target/classpath.txt)" \
    com.licode.gui.Main "$@"
```

Both flags are compulsory on macOS and neither is optional:

- **`-XstartOnFirstThread`** — AppKit requires the event loop to own the process' first thread,
  and the `java` launcher hands `main()` a freshly created one (`NOTES.md` §2.4). Without it the
  window misbehaves in ways that never name the cause.
- **`--enable-native-access=ALL-UNNAMED`** — the calls into Cocoa and WebKit are native access.

`-XstartOnFirstThread` is macOS's alone and is **worse than useless** elsewhere: the JVM treats
an unknown `-X` option as a fatal start-up error ("Unrecognized option", "Could not create the
Java Virtual Machine", measured on Windows 11 with JDK 25), so putting it in the pom
unconditionally does not warn, it does not run. `licode-gui` gives it a `mac`-activated Maven
profile and the shell scripts a `uname -s` test (`pom.xml:114-156`, `run.sh:9-13`).

`dev.sh` adds one more thing worth copying: the address. Vite binds to `localhost`, which on
macOS resolves to IPv6 `::1` — so `http://127.0.0.1:$PORT` does **not** reach it and produces a
window that waits forever. The hostname is the one that works (`dev.sh:20-23`). The dev server
also gets a trap, and the app is deliberately *not* `exec`'d, so the dev server cannot outlive
the app (`dev.sh:66-67`).

### The native binary

```sh
./build-native.sh   # → target/licode-gui
```

```sh
exec "$NATIVE_IMAGE" \
    --no-fallback \
    -H:IncludeResources='web/.*' \
    -H:+ReportExceptionStackTraces \
    -J-Xmx4g \
    ${NATIVE_IMAGE_THREADS:+-J-XX:ActiveProcessorCount=$NATIVE_IMAGE_THREADS} \
    --enable-native-access=ALL-UNNAMED \
    -cp "target/classes:$(cat target/classpath.txt)" \
    -o target/licode-gui \
    com.licode.gui.Main
```

`build-native.sh:45-54`. `--no-fallback` means the build fails loudly rather than quietly
emitting a JVM launcher. `NATIVE_IMAGE_THREADS` caps `ActiveProcessorCount`: unset, native-image
sizes itself for the whole machine, which is right for one build and wrong for several at once —
measured on this project, five concurrent builds on a fourteen-core box spent their time waiting
on each other (load average 175) and finished no faster than two at a time (`build-native.sh:36-44`).

**What you give up:** the client measured its JVM loop at roughly **15 seconds** end to end —
build the frontend, compile, run. The native build is minutes, and the binary is large. That
trade is only worth making deliberately.

### Why the binary is the size it is

| | native compile | executable |
| --- | --- | --- |
| the framework's own demo app | **~17 s** | **19 MB** Mach-O arm64, linking only `libSystem`, `libobjc`, `Foundation`, `CoreFoundation` and `libz` (`NOTES.md` §5.4) |
| `licode-gui` | **~2 m 40 s** (`Finished generating in …`) | **~130 MB** Mach-O arm64 |
| `java-pi`'s own TUI binary, which links the same `licode-core` and SDK tree | ~1 m 8 s | 129.7 MB, and 133.14 MB in a later acceptance run |

The framework's figures are reproducible from this repository (`NOTES.md` §5.4,
`docs/verification.md`). The `licode-gui` and TUI figures were recorded during those projects'
own integration and acceptance runs and are quoted here as reported: neither tree is present in
this repository, and neither build was re-run to write this document.

The two orders of magnitude between the demo and the client are not a framework regression. The
rule of thumb is blunt: **the native binary is roughly the size of everything on the classpath,
unpacked.** Bytecode becomes machine code at about 1:1, so a 19 MB demo means a 19 MB dependency
set and a 130 MB client means a 130 MB one. `licode-gui` depends on `licode-core`, which depends
on the OpenAI Java SDK and the MCP SDK — the same tree that puts `java-pi`'s own binary at
~130 MB.

And then there is the part that defeats the usual "the linker throws away what you do not use"
intuition. From `java-pi`'s metadata inventory (`docs/NATIVE_IMAGE_METADATA_MERGE_SEMANTICS.md:54`):

| contributor | reflection entries |
| --- | --- |
| `openai-java-core-4.45.0.jar`, its own `META-INF/native-image/reflect-config.json` | **12,580** |
| `reactor-core-3.7.0.jar` | 2 |
| hand-written, project-owned | 184 |
| picocli annotation processor | 33 |

A single dependency ships twelve and a half thousand reflection registrations. Every one of them
is a reachability root, and each root drags in the code it names — so the SDK's own metadata
**cancels the pruning** that would otherwise have shrunk the binary. This is worth knowing before
you spend an afternoon hunting for a flag that will bring 130 MB down: the lever is the
dependency, not the build.

One more thing the size analysis is good for. Assets staged at startup go to a temp directory —
`Files.createTempDirectory("panamaview-web-")` (`Resources.java:69`) — logged as
`[panamaview] web root: /var/folders/…/panamaview-web-…` (`WebApp.java:254-259`). Seeing that
path from a native binary is the proof that the web assets really were embedded and unpacked,
and not silently missing.

### What a successful run proves, and what it does not

The framework prints `[panamaview] page loaded: url=… title=…` when the page finishes loading
(`WebApp.java:316-323`). **That line proves the document loaded. It does not prove the
application rendered.** If the bundle threw on import, or a component threw while drawing — a
value React cannot render takes the whole tree with it — you get a blank window, and on macOS
the log has nothing more to say, because a page error is not a Java exception.

`licode-gui` answers this with a headless check mode that never opens a window, which is also
the only thing that can run on CI, where there is no window server:

```sh
./target/licode-gui -Dpanamaview.check=true     # the native binary
JAVA_OPTS=-Dpanamaview.check=true ./run.sh      # the JVM path; JAVA_OPTS reaches the JVM (run.sh:35)
```

`Main.java:41-44`, `:195-243`. It stages the assets and then asserts two things: that
`index.html` and `panamaview.js` are both present and non-empty, and that **every `src=` and
`<link href=` reference in `index.html` resolves to a file that was actually staged**. That
second one is the check that earns its keep in a native build: the config that embeds the assets
is a command-line flag nothing type-checks, and the failure it prevents — HTML loaded, script
did not — looks exactly like rendering.

The general form of the lesson, which this project learned twice (`NOTES.md` §11):
**a passing check is only evidence if it would fail when the mechanism is removed.** "The page
loaded" is the check that passes whether or not anything works.

---

## 5. When the framework falls short

This is the part of a first-client integration that no amount of framework polish removes, so it
is worth having a procedure for it rather than a philosophy. The procedure `licode-gui` settled
on is a **two-repository loop**:

1. the client hits something the framework does not do;
2. the client writes the smallest honest workaround it can, and says in a comment what it is
   standing in for;
3. an issue goes into this repository, describing the gap and not the workaround;
4. both sides get built: the framework gets the capability, the client later deletes its
   workaround.

Two real instances, and they are at opposite ends of the spectrum.

### A missing framework capability: panamaview#13

The client needed to know where its window opened, and the framework had no opinion beyond "the
primary display". Filed as [issue #13](https://github.com/lihuu/panamaview/issues/13), which is
worth reading as a template for this kind of report, because it does three things:

- **it splits the gap into two problems of different size** — no persistence (the window does not
  remember where the user last put it) is general, and no explicit placement (`.position(x, y)` /
  `.onDisplay(index)`) is narrower. It then argues the general one is the one that hurts: a
  panamaview app tends to be a long-lived tool window kept open beside the user's main work, so
  landing on the primary display interrupts something on *every* launch, and the fix has to live
  in the framework because a user drag only lasts until the next launch;
- **it names the mechanism that already exists** — AppKit's `setFrameAutosaveName:` restores
  position *and* size in one call;
- **it names the file and the line** that would change
  (`framework/src/main/java/panamaview/macos/WebView.java:122`), the platforms in scope, and an
  acceptance criterion that includes the negative case: *with nothing specified, behaviour is
  exactly as it is today*.

An issue written that way can be implemented without a conversation. That is the standard to
hold client reports to.

### A capability the framework should probably not grow: the project picker

The window needs a directory chooser. `WebApp`'s entire public surface is `title, size, load,
bind, bindUi, timeout, dev, liveReload, spaFallback, onReady, quiet, isUiThread, inFlight,
postToJs, eval, reload, cancelInFlight, run` — and **no method on it opens a file panel**
(`FileBrowser.java:14-36`).

The obvious substitutes do not work either, and it is worth knowing why before proposing them:

- an `NSOpenPanel` would mean reaching into AppKit from a class the framework does not expose —
  i.e. the framework would have to expose exactly enough of the native world for a consumer to
  bypass it, which is the thing its design deliberately avoids;
- `<input type="file">` in the page yields a *file*, never the absolute path of a *directory* —
  which is the one thing a project picker exists to produce.

So `licode-gui` drew the picker in the page, over two framework calls: `fs.home` and `fs.list`,
one directory level per click. The class is explicitly labelled a stand-in, and — this is the
design point — it returns **the shape a directory listing has**, so replacing it later with a
real chooser replaces the call and not the picker built on top of it.

Not every gap deserves a framework change. The judgement to make is whether the missing thing is
a *window* capability (the framework's problem) or an *application* capability the consumer
happens to need (the consumer's problem, served by bindings). `fs.list` is a `DirectoryStream`
loop over one level, and it belongs where it is.

---

## 6. What to take from this

- **Two channels, two purposes.** `invoke` is a request with one answer; `postToJs` is an
  application message. Do not try to make one of them do the other's job — a call that waits for
  a whole agent turn is a call that hits the 30 s deadline.
- **Acknowledge and push.** Any handler whose work is measured in seconds should answer
  immediately and report the outcome as a push. This is the single most load-bearing pattern in
  the client.
- **Return JSON, literally.** `Map`, `List`, `String`, `Number`, `Boolean`, `null`. Anything else
  is stringified in silence.
- **State gets `useJavaMessage`; a stream gets `subscribeJava`.** And a component that subscribes
  late must pull, because a push it missed is not replayed.
- **Declare what you inherit and what you must not forget**: JVM flags and your own
  `-H:IncludeResources` are yours; the FFM registrations and the client bridge come with the jar.
- **`page loaded` is not "it works".** Have a headless check that asserts the things a compile
  cannot, and ask of every check whether it would fail if the mechanism were removed.
- **The native binary costs what your dependencies cost.** If it is 130 MB, that is the
  dependency set plus the reflection metadata that defeated the pruning, and no build flag is
  going to fix it.
- **When the framework falls short, file the gap and keep the workaround honest.** A stand-in
  that returns the shape the real thing would return is a stand-in you can delete.
