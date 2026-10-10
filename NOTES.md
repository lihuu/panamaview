# Notes from the PoC

What was actually hard, what the tutorials do not tell you, and where the bodies are
buried. Organised so you can skim the headings and read only the ones that bite you.

Environment: macOS 27.2 (arm64), Apple clang 21, GraalVM CE 25.0.2 (`native-image`
25.0.2), Maven. Six system framework dylibs are involved and on this OS **none of them
exist as files on disk** — `/System/Library/Frameworks/WebKit.framework/WebKit` is
absent; `dlopen` resolves it from the dyld shared cache. So `ls` and `test -e` are
useless for checking whether you can link against a framework here. `dlopen` is the
only honest test, and it succeeds on all six paths used by this project
(`/usr/lib/libobjc.A.dylib`, `Foundation`, `AppKit`, `WebKit`, both framework paths in
their plain and `Versions/…` spellings).

## 1. How FFM calls Objective-C

The whole approach rests on one fact: Objective-C dispatch is an ordinary C function.

```java
MemorySegment msgSend = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", GLOBAL)
        .find("objc_msgSend").orElseThrow();

// One descriptor per call *shape*, bound once and cached.
FunctionDescriptor fd = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS);
MethodHandle handle = LINKER.downcallHandle(msgSend, fd);

handle.invokeWithArguments(receiver, sel, argument);
```

That is the entire bridge. No JNI, no JNA, no `.m` file, no generated code. `receiver`
and `sel` are just addresses, and because FFM is fully dynamic, `objc_msgSend` can be
re-typed for every signature instead of being frozen as `id(id, SEL, ...)`.

Practical consequence: **the descriptor map is your binding's public surface.** This
project keeps them as static constants and caches one `MethodHandle` per descriptor in a
`ConcurrentHashMap`. `objc_msgSend` is also the only symbol needed for method dispatch —
the rest of the libobjc surface (`objc_getClass`, `sel_registerName`, the class-creation
pair, the autorelease-pool pair) is a handful of straightforward functions.

Selectors are interned by the runtime for the life of the process and never freed, which
is why caching `sel_registerName` results is not an optimisation with a lifetime caveat —
it returns the same address the call would have.

## 2. The ABI rules that are not in the tutorials

### 2.1 A struct *return* silently grows a leading `SegmentAllocator`

This one cost real debugging time. `[view frame]` returns an `NSRect` by value:

```java
// WRONG — looks right, compiles, fails at call time
FunctionDescriptor.of(NSRECT, ADDRESS, ADDRESS);
```

The call fails with:

```
WrongMethodTypeException: cannot convert
  MethodHandle(SegmentAllocator,MemorySegment,MemorySegment)MemorySegment
  to (Object,Object)Object
```

FFM must be told where to put a returned aggregate, so it prepends a
`SegmentAllocator` parameter that you never wrote. Correct form:

```java
static final FunctionDescriptor RECT0 = FunctionDescriptor.of(NSRECT, ADDRESS, ADDRESS);
//                                                              ^ extra allocator
call(RECT0, scope.arena(), receiver, sel);
```

Passing the scope's arena is the right move anyway: it ties the returned struct's
lifetime to the scope that read it. Note the asymmetry — this rule applies to struct
**returns only**. A struct passed *as an argument* (`setFrame:`, `setContentSize:`) is
just a `MemorySegment` with no allocator involved.

My first attempt also wrapped the failure in `IllegalStateException("objc_msgSend failed")`
with no detail, which hid the very signature that would have identified the bug in
seconds. The wrapper now includes the `FunctionDescriptor` in the message.

### 2.2 `BOOL` is one byte

```java
static final FunctionDescriptor BOOL1 = FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, JAVA_BYTE);
```

Not `int`. `applicationShouldTerminateAfterLastWindowClosed:` must return a 1-byte
`BOOL`; declaring the return as `int` leaves the upper bytes to chance.

### 2.3 `NSInteger` is not `BOOL`, even when the selector looks boolean

```java
ObjC.sendVoidLong(app, ObjC.selector("setActivationPolicy:"), ACTIVATION_POLICY_REGULAR);
```

`NSApplicationActivationPolicy` is an `NSInteger` — a 64-bit argument. Passing it with a
BOOL or int descriptor is the kind of mistake that "works" until the value is not 0 or 1.
Same class of bug as 2.2 in the opposite direction.

### 2.4 `-XstartOnFirstThread` is mandatory on the JVM

AppKit requires its event loop to own the process' **first** thread. The `java` launcher
hands `main()` to a freshly created thread, so `NSApplicationMain`/`run` misbehave. This
project calls `pthread_main_np` at startup and warns if the assumption is violated — and
that check is also what proves the fix works: the page reports `onMainThread: true` under
both builds.

**The native executable does not need the flag.** A GraalVM native image's main thread
*is* the process' first thread, so `pthread_main_np` returns true with no arguments at
all. That is a genuine advantage of the native path that has nothing to do with speed.

## 3. Objective-C calling Java, with no glue

There is no `.m` file anywhere in this project. Instead the class is defined at runtime
and its methods are FFM upcall stubs:

```java
MemorySegment cls = objc_allocateClassPair(objc_getClass("NSObject"), "PanamaViewDispatch", 0);
class_addMethod(cls, sel_registerName("userContentController:didReceiveScriptMessage:"),
                LINKER.upcallStub(target, descriptor, GLOBAL),
                "v@:@@");   // ← type encoding, NUL-terminated
objc_registerClassPair(cls);
```

An upcall stub is a real native function pointer, which is all the runtime ever wanted.
For a framework to call back into Java you therefore need **no** binding generator.

Four rules that are easy to violate:

1. **Type encodings are required and must be exact.** `v@:@@` for a void method taking
   two objects; `c@:@` for one returning `BOOL`. `@` object, `:` selector, `c` BOOL,
   `v` void. `Arena.allocateFrom(String)` NUL-terminates for you.

2. **No exception may escape an upcall target.** FFM converts an escaping exception into
   a process abort. Every method here catches `Throwable`, reports, and returns a safe
   value.

3. **`MethodHandles.lookup().findStatic` on your own class is a reflective lookup.** The
   static analyser cannot see it, so native-image needs a reflection entry for each upcall
   target (see §5).

4. **Hold the object strongly from Java, always.** `NSApplication`'s delegate is a *weak*
   reference. If Java's only reference is garbage-collected, the runtime holds a dangling
   pointer and you get a crash far from the cause. `ObjCDispatch.instance` is a static
   field precisely for this.

## 4. Two things that look impossible but are not

### 4.1 Autorelease pools

An Objective-C object returned from anything that is not `alloc`/`new`/`copy` is
autoreleased. Java has no way to participate in that, so every Java→Objective-C
operation runs inside `objc_autoreleasePoolPush`/`Pop`:

```java
try (ObjC.Scope scope = ObjC.scope()) {
    MemorySegment s = ObjC.nsString(scope, "hello");   // autoreleased — safe here
    // ...
}   // pool drained, scratch arena freed
```

Those two functions are `void*`/`void` and do not appear in the obvious documentation;
they are why you never see `NSAutoreleasePool` here. Objects that must outlive the pool
get an explicit `retain`, and there is no matching release anywhere in this project —
see §7.

### 4.2 Objective-C blocks, built by hand

`takeSnapshotWithConfiguration:completionHandler:` takes a block, and a block is not a
class you can create with `objc_allocateClassPair`. It is just a struct:

```
struct Block_layout { void *isa; int flags; int reserved; void *invoke; descriptor *descriptor; }
```

So the same upcall stub works; only the envelope is new. Declaring `BLOCK_IS_GLOBAL`
(`1 << 28`) with `isa = _NSConcreteGlobalBlock` is what makes this safe: `Block_copy` and
`Block_release` both short-circuit for global blocks, so the framework will neither move
the block to the heap nor free it, and the storage can live in `Arena.global()`.

This is what lets the app photograph itself — the completion handler is a Java method and
the finished image arrives as an ordinary Java callback, with no delegate object anywhere.
Worth knowing because a large fraction of modern Cocoa APIs are block-based rather than
delegate-based, so without this trick the FFM surface is much smaller than it looks.

Note `_NSConcreteGlobalBlock` is found via `dlopen` + symbol lookup on
`/usr/lib/system/libsystem_blocks.dylib` — again a path that does not exist on disk.

## 5. GraalVM native image

### 5.1 Foreign calls must be registered, and the error tells you almost nothing

First native run died immediately:

```
MissingForeignRegistrationError: Cannot perform downcall with leaf type (long,long,int)long.
To allow this operation, add the following to the 'foreign' section of 'reachability-metadata.json'
```

Two problems with this message: it describes the **`libc` entry point** signature
(`dlopen`, in that case) rather than the call site, and it does not say which descriptors
are missing. It also fails at **run time**, not build time — so a green build proves
nothing about whether the executable works.

Recipe that actually works: let the agent record it, then review by hand.

```sh
java -agentlib:native-image-agent=config-output-dir=native-config \
     -XstartOnFirstThread --enable-native-access=ALL-UNNAMED \
     -cp target/classes panamaview.Main
```

The generated `reachability-metadata.json` also captures the resources to embed, the
reflection for the upcall targets, and the upcalls themselves. It is checked in at
`src/main/resources/META-INF/native-image/panamaview/panamaview/`.

### 5.2 The agent records only what ran — and that is a trap

The agent produced 19 downcalls: the ones the self-test happened to exercise. Three
descriptors that the code *can* reach (`sendRect`, `sendVoidRect`, `sendLong`) were
missing, because `Main` did not call them at the time. Shipping that file would have left
a landmine: correct until someone calls an existing helper, then
`MissingForeignRegistrationError` in a field build.

So rather than trust the recording, I wired the missing paths into the self-test — which
is how the by-value `NSRect` bug in §2.1 got caught in the first place. The checked-in
file has 25 downcalls and 5 direct upcalls and is a description of what the code *may* do,
not of one lucky run.

### 5.3 The shipped schema advertises a field the parser rejects

`lib/svm/schemas/reachability-metadata-schema.json` lists `reason` on foreign entries.
Adding it aborts the build:

```
Unknown attribute(s) [reason] in foreign call
```

The explanations therefore live in the file's top-level `comment` array instead. Worth
knowing before you spend time trusting that schema.

### 5.4 No fallback, and it does not need one

`--no-fallback` is set, so the build fails loudly rather than quietly emitting a JVM
launcher. It compiles in ~17 s and the result is a 19 MB Mach-O arm64 binary linking only
`libSystem`, `libobjc`, `Foundation`, `CoreFoundation` and `libz`. Nothing in the FFM path
needed a shim, a substitution, or a workaround.

### 5.5 Moving a class breaks the metadata silently

The rewrite that moved the native code into `panamaview.macos` renamed
`panamaview.ObjCDispatch` and friends. Every upcall target in the metadata file names its
class as a string, so those entries went stale — and nothing complains at build time. The
build succeeds and the executable dies at the first callback with
`MissingForeignRegistrationError`, exactly like §5.1.

My first pass at this only rewrote the `reflection` section and left `foreign.directUpcalls`
pointing at the old names. **Whenever a class moves, grep the metadata for the old package
prefix — the compiler cannot help you.** The file now names five classes and they all live
in one package, which is worth checking mechanically rather than by eye.
## 6. Web resources

`WKWebView`'s `loadFileURL:allowingReadAccessToURL:` needs real files, and a classpath is
not a filesystem — in a native image there is no `target/classes` at all. Two
consequences:

1. Assets are **extracted to a temp directory** at startup, which behaves identically in
   both modes.
2. You **cannot enumerate a classpath directory** portably, and not at all in a native
   image. Hence `web/manifest.txt` — an explicit list, checked in, that
   `Resources.extract` trusts. Add a file, add a line. (The manifest itself is what
   `-H:IncludeResources='web/.*'` embeds, along with every asset it names.)

## 7. Object lifetime: what this PoC does not solve

Honest accounting, because it is the main thing standing between this and a framework:

- Objects retained via `retain` are **never released**. Everything lives for the process
  lifetime. Correct for a PoC, wrong for a long-running app with many transient objects.
- There is no ownership tracking, so nothing can tell you what is leaked.
- Java objects referenced from Objective-C (the delegate, the dispatch object) are kept
  alive by a static field — one process-wide reference, not a real GC bridge.
- Autorelease pools are per-operation, which is fine for request/response and would need
  reconsidering for anything that holds objects across callbacks.

## 8. Startup: the negative result

| | process start → page loaded |
| --- | --- |
| native executable | 631 / 699 / 730 ms |
| JVM (GraalVM CE 25) | 592 / 652 / 723 ms |

Indistinguishable. AppKit and WebKit initialisation dominate and cost the same either
way, so **native image buys no startup win for this workload.** Its value here is
distribution: one file, no JVM requirement, no `-XstartOnFirstThread` to remember. If
someone proposes native image for a WebView app *because of latency*, this is the
counter-evidence.

## 9. The resize contract, and why it needed no code

The spec called for correct window/web-view sizing and resize. The web view is set as the
window's `contentView`, and AppKit keeps a content view at the content rect — so resize
requires **zero** code and the framework does it. That is worth verifying rather than
assuming, so `WebApp.resizeTo` sets the content size and re-reads the web view's frame:

```
requested 900×620  → web view frame 900×620   followed=true
requested 1000×700 → web view frame 1000×700  followed=true
```

Both builds, both directions. If the web view ever stops being the content view, this
probe fails instead of silently producing a broken layout.

## 10. The threading model

- **One primitive.** WebKit gives the page exactly one thing: `postMessage` into a named
  script message handler. `app.js` builds request/reply correlation on top of it with an
  id-keyed promise map, because the reply comes back via `evaluateJavaScript`.
- **JSON text in both directions.** The reply is injected as an object literal
  (`window.__pvResolve({...})`), which needs no escaping dance because a JSON document is
  already a valid JavaScript expression.
- **Replies are correlated by id, never by order.** This stopped being a detail and became
  load-bearing once handlers moved off the UI thread: the first request answered is now
  routinely not the first one sent.

### The problem

`userContentController:didReceiveScriptMessage:` is delivered on the UI thread, inside
AppKit's own callback, while the run loop is blocked on it. The first version of this PoC
therefore ran the whole handler there — parse, execute, build the reply, call
`evaluateJavaScript` — so a handler that took a second froze the window for a second.
Returning quickly is not an optimisation; on these platforms it is the contract.

### The fix, and the evidence it works

The callback now does three things and returns: parse the JSON, hand the work to a virtual
thread, and get out of the way. The reply goes back through the UI thread, because that is
where `evaluateJavaScript` is legal.

Two measurements, both from the self-test, and the second exists to keep the first honest:

| | |
| --- | --- |
| `slow` (600 ms, on a virtual thread) | an unrelated `hello` answered in **1 ms** while it was in flight |
| `slowUi` (400 ms, pinned to the UI thread) | the same `hello` was delayed **403 ms** |

Same work, same duration, different thread. The 400 ms control is the point: a test that
only ever passes proves nothing, and if the bridge ever stops scheduling off-thread the
control is what will notice. Handler counts are also visible in the log — on a full run,
**205 of 209 handlers ran on virtual threads** and the 4 that did not were exactly the
`bindUi` ones.

Replies now arrive out of order, which is visible in the log and harmless by design. A
burst costs a virtual thread each: 200 concurrent invocations measured 21 ms (JVM) / 13 ms
(native).

### Which handlers may block

Some operations are only legal on the UI thread — `setContentSize:`, `takeSnapshot…` — so
those stay there, via `bindUi`. The trade is explicit rather than hidden: `bind` is
off-thread and cannot freeze the window; `bindUi` can, and is for the operations that have
no other choice. Platform extras registered by `panamaview.macos.Main` all use `bindUi`,
and the self-test asserts they ran with `onUiThread: true`.

### Getting onto the UI thread (the platform-specific part)

This is where platforms diverge, which is why it lives in the platform package
(`panamaview.macos.UiThread`) rather than in the bridge. On macOS it is libdispatch, and
two details cost real time to establish — both were checked with throwaway C programs
rather than assumed:

- **`NSApp`'s run loop drains the main dispatch queue.** Verified directly: work submitted
  from background threads with both `dispatch_async` and `dispatch_async_f` ran, on the
  main thread, while `[NSApp run]` was in effect.
- **`dispatch_get_main_queue()` is a header inline, not an exported symbol** — it cannot be
  resolved, so FFM cannot call it. The queue object itself *is* exported, as
  `_dispatch_main_q`, and its symbol address **is** the queue (checked against
  `dispatch_get_main_queue()`'s return value, because the alternative would be a pointer to
  a pointer and would fail at run time).
- **`dispatch_async_f` takes a function pointer plus an opaque context**, not a block. That
  is why this hop needs no hand-built block struct: an FFM upcall stub already is a C
  function pointer. (`NativeBlock` exists because some APIs really do require blocks.)

The context pointer carries a small integer token rather than an address, so there is
nothing to free; the token indexes a map of pending work.

Windows and Linux will each need their own version of this — `PostMessage` or a
`DispatcherQueue`, and `g_idle_add` respectively. That is the point of keeping it behind
the seam.

### Still true

`postToJs` before the page finishes loading is silently lost, which is why Java's first push
is gated on the navigation-finished callback.
## 11. Deadlines and cancellation

Both exist for the same reason: a request has to be able to end even when nothing goes well. A
request that never gets an answer leaves the page waiting forever with no error and no way out,
and that failure mode is the one users blame the tool for.

### Exactly one outcome, decided by a race

A request can end four ways and they race: the handler returns, the handler throws, the deadline
passes, or the page cancels. Four paths, one report. The guard is a compare-and-set on a flag:

```java
boolean claim(String why) {
    if (!pending.compareAndSet(true, false)) return false;   // somebody already reported this
    ...
}
```

Without it, a request that timed out and *then* finished would report twice, and the second
report would be a lie about a request that is already over. The log shows the rule working — a
cancelled handler waking from its sleep writes an `abandoned` line instead of a second reply:

```
[panamaview] cancel    cancelled by the page  (id 7.0)
[panamaview] abandoned slow → cancelled by the page
```

### The runaway handler gets interrupted

Answering the page fixes the symptom. `worker.interrupt()` fixes the cause: it genuinely stops
`Thread.sleep`, `wait`, `join` and blocking `java.nio`. It does **not** stop a CPU-bound loop or
a native call that has gone into Apple's libraries, because cancellation is cooperative —
the same contract as Go's `context.Context`, .NET's `CancellationToken` and JavaScript's
`AbortSignal`. The honest summary: the framework guarantees the *request* is delivered, not that
the work stops.

JDK 25 helps here in a concrete way: since JEP 491 (JDK 24), a virtual thread blocking inside
`synchronized` no longer pins its carrier, so ordinary blocking handler code does not have to be
audited for scheduling damage.

### Two decisions that are really one decision

`bindUi` handlers get **no** deadline, and that is not laziness. Deadlines are enforced by a
watchdog thread but *reported* on the UI thread — which, for a stalled UI-thread handler, is
exactly the thread that cannot answer. The timer would fire into a queue nobody can drain: it
would look like protection without being any. The honest statement is that a slow `bindUi`
handler freezes the app. That is the price of running on the UI thread, and the reason the set
of things allowed to run there should stay tiny.

`null` deadlines are the other half: some work is honestly unbounded, and a fixed number there
would be a guess that eventually fails a real request. Generous explicit values beat `null` when
an estimate is possible.

### 🚨 A test that looked green and proved nothing

My first cancellation test did this:

```js
const started = performance.now();
doomed.cancel();
await doomed.catch(() => {});
check(elapsed < 300);          // PASSED — and measured the wrong thing entirely
```

It reported **"settled in 0 ms"**. `cancel()` rejects the promise locally, so those 0 ms were
how long JavaScript took to call `reject` — the request had not even reached Java, and the check
would have passed just as happily if the cancel message went nowhere at all.

What the check has to show is that the *handler on the other side stopped*. So the handler now
counts its own interruptions and the page reads that counter back:

```
PASS  cancel actually stops the handler — handler interrupted (count 0 → 1)
```

The same trap catches deadline tests: a page-side fuse firing proves nothing about the Java-side
timer, which is why the deadline probe is a 300 ms binding invoked with a 10 s page allowance. A
~303 ms rejection can only be the Java timer.

This is the second time in this project a test passed while measuring nothing (see §5.2, where
the native-image agent faithfully recorded a lucky code path). The general rule worth keeping:
**a passing test is only evidence if it would fail when the mechanism is removed.**

### The page-side fuse sits *behind* Java's deadline

30 s deadline in Java, 35 s fuse in JavaScript. Deliberate ordering: Java's error is more
precise ("no answer within 300 ms", naming the binding's own limit), so it should normally win
the race. The fuse exists only for the case where the reply itself never makes it back.

### Requests die with their document

`webView:didStartProvisionalNavigation:` now cancels everything in flight. Requests belong to
the page that made them; when it navigates away they are meaningless, and replying to a
departing document is worse than useless. Verified by navigating away mid-request
(`-Dpanamaview.navigateAway=true`):

```
[panamaview] cancel   1 request(s): the page navigated away
[panamaview] cancel   the page navigated away  (id 218.0)
```

Note that the hook fires on the *initial* load too, which is why an empty sweep is silent.

## 12. Keeping the platform isolated

The brief for this PoC said not to design a cross-platform abstraction, and it was right:
an interface extracted from one implementation describes that implementation, while an
interface invented for three is a guess. But "do not abstract" is not the same as "let
native types leak into the application", and the first version did leak — `WebApp` imported
`java.lang.foreign.MemorySegment` in three places to expose platform extras.

So the line is drawn at exactly one seam, derived from the working macOS implementation:

```
panamaview            portable — JDK types only, no foreign, no ObjC
  WebApp  App  Bridge  Json  Resources  UiHost  WindowSpec
                    │
                    │  UiHost: String / Runnable / primitives / Path
                    │
panamaview.macos      the only implementation that exists
  MacOsHost  Main  ObjC  Cocoa  WebView  WebView  ObjCDispatch  NativeBlock  UiThread
```

`UiHost` is four methods plus a callback interface. Everything the application needs from a
platform is in it: start, evaluate, hop to the UI thread, ask whether you are on it.

What is deliberately **not** in it: `snapshot`, `geometry`, `resize`. Those are macOS
capabilities today. Putting them on the seam now would mean guessing at signatures for
platforms nobody has written, so they stay as ordinary bindings registered by
`panamaview.macos.Main`. When a second platform also needs to resize, that is when a
method earns its place.

### How strongly this is enforced

Package-private, not modules. Inside `panamaview.macos` only `MacOsHost` and `Main` are
public; `ObjC`, `Cocoa`, `WebView`, `ObjCDispatch`, `NativeBlock` and `UiThread` are all
`final class` with no modifier, so they are unreachable from the application — by
convention rather than by the compiler, since everything is still one Maven module.

That is enough for now because the portable half has been checked to be genuinely free of
native types: the core package's imports are JDK-only, and no `MemorySegment`, `Arena`,
`Linker` or `ObjC.` reference appears in it as code. When a second platform is real, moving
`panamaview.macos` into its own Maven module turns the convention into a build-time
guarantee — the application's classpath would simply not contain the native code.

## 13. What a consumer inherits, and what they must declare

Building the vanilla template the way a user receives it — framework installed, template copied
somewhere unrelated to this checkout — turned out to be the most informative test in the
project. Resource handling in GraalVM native image is **asymmetric**, and the asymmetry stays
invisible until an executable runs.

| | crosses the jar boundary? |
| --- | --- |
| `reachability-metadata.json` (foreign registrations, reflection, resources) | **yes** |
| JVM launch flags | **no** — they belong in the app's own pom |
| `-H:IncludeResources` for the app's own assets | **no** |

Two consequences worth stating plainly.

**A jar can hand you the hard part.** The foreign-function registrations — the thing that
otherwise fails *at run time* with a message naming a `libc` entry point rather than your call
site (§5.1) — travel with the dependency. A consumer writes nothing for them. That is a
genuinely good property of the metadata design and it is not obvious from the documentation.

**Resources are not inherited, and the failure lands at start-up.** An application that lists
its assets in `manifest.txt` but forgets `-H:IncludeResources` compiles, links, and dies with
`IOException: missing resource: /web/index.html`. Compilation proves nothing about it. That is
why the template ships a headless check mode, and why CI uses it: a macOS runner has no window
server, so the only runnable verification is "stage the assets and say what you found".

### Three things the template found that review did not

1. **The framework's `resources` entry listed its own demo pages.** Because that file applies
   transitively, every consumer's native build was embedding this repository's demo into its
   binary — and those demo pages then sat on the classpath where `manifest.txt` could shadow a
   consumer's own. Fixed by moving the demo to `demo/` and listing only the framework's asset.
2. **`blank.html` was not listed at all.** The framework's own native build hid this behind a
   command-line flag that a consumer has no equivalent of.
3. **The client bridge was never published.** It lived inside the demo's `app.js`, so every
   consumer was expected to copy it — precisely the arrangement that rots silently. It is now
   `panamaview/panamaview.js` in the jar, staged into the web root at startup, with
   `panamaview.js` reserved there.

The verification script found all three within a minute, then found two bugs in itself: XML
comments cannot contain a double dash, and a classpath is more than `target/classes`. That is
the argument for writing the script rather than for being careful.

## 14. One page, two sources

A panamaview app serves the same page two ways, and the difference is not cosmetic — it decides
how the page is built and what the platform is handed.

```
dev    Vite dev server at http://localhost:5173     live reload, hot module replacement
prod   staged files over file://                    one inlined HTML document
```

`PageSource` models that as a sealed pair rather than a nullable field:

```java
sealed interface PageSource {
    record FromAssets(String scheme, Path root, String entry) implements PageSource {}
    record FromUrl(URI url) implements PageSource {}
}
```

The reason to separate them is that they are not interchangeable. One carries an asset root and
the scheme to serve it under; the other has no use for either, because a dev server is already
serving everything. A nullable field would have let callers hand the wrong shape to the wrong case
and find out at run time.

`FromAssets` started life as `FromFile`, loading over `file://` with a read-access root. That case
is gone — not renamed, replaced — because it cannot run a bundled frontend at all (see below).
Keeping it would have meant maintaining two production paths where one is known to be deficient,
and a caller who picks the deficient one gets a blank window and no explanation.

This is also the first thing to earn a place on the seam since the seam was drawn. The rule stays
what it was — add to it when a second real need appears, not in anticipation — and a second real
need appeared.

### What actually breaks, and why inlining was the wrong fix

> The short version is now its own document — [Where your page
> runs](docs/page-environment.md) — because it answers a question that comes up every time somebody
> touches this area: is a system web view a cut-down browser, and why does serving from disk break
> a bundled frontend. The detail stays here.


Both of the following were found by building a real Vite app and loading it, not by reading
documentation. The first was originally reported here as "ES modules do not load over `file://`;
inlining them removes the fetch" — which was true, and was the wrong conclusion to draw from it.

**A `file://` origin is opaque, and everything that goes through the fetch machinery is refused.**

| capability | `file://` | private scheme |
| --- | --- | --- |
| classic `<script src>` (separate file) | **works** | works |
| ES module `<script type="module">` | **refused** | works |
| static `import` | refused | works |
| dynamic `import()` — code splitting | refused | works |
| `fetch()` | refused | works |
| `XMLHttpRequest` | refused | works |
| `new Worker()` | refused | works |
| `location.origin` | `file://` (opaque) | `panamaview://app` |

Classic scripts survive because they are fetched in no-cors mode; module scripts are *always*
fetched in CORS mode, which an opaque origin can never satisfy. That asymmetry is why a
hand-written page works and a bundled one does not — and it is why the first version of this
project never noticed.

So "you cannot split a frontend into files" was the wrong lesson. **Splitting is fine; ES modules
over `file://` are not.** Inlining the bundle fixed exactly one symptom — an inline module needs
no fetch — while leaving `fetch`, `XHR` and Workers broken and making code splitting impossible.
The page still was not a normal web page, which is what it needs to be.

**The fix is a private URL scheme, which is what Tauri and Electron do.** Neither loads the app's
own content over `file://`. WebKit's `WKURLSchemeHandler` gives the page an origin it owns, and
the whole table above turns green — verified with a standalone AppKit harness before any of it was
wired into the framework, and then end to end: the React template's production build now emits a
separate `assets/Lazy-*.js` chunk that loads on demand.

Cost: `setURLSchemeHandler:forURLScheme:` on the configuration before the web view exists, a
handler class implementing `webView:startURLSchemeTask:` / `stopURLSchemeTask:`, and an
`NSHTTPURLResponse` to answer with. That last one is why `AssetSchemeHandler` exists in the macOS
package — and why `ObjC` needed a four-object message shape, since
`initWithURL:statusCode:HTTPVersion:headerFields:` will not fit in three.

Two details worth keeping, both learned the hard way:

- **`Content-Type` must be right.** A `.js` served as `application/octet-stream` is refused by the
  module loader, and the failure looks like an empty page rather than a MIME error.
- **Answer with real status codes, not task failures.** A missing file should be a 404 — the URL is
  in the log, the Web Inspector understands it, and nothing else has to explain itself.

**Vite binds to `::1`, not `127.0.0.1`.** Unrelated to the above and equally invisible: the dev
server prints `http://localhost:5173/` and listens on the IPv6 loopback only, so a URL written as
`http://127.0.0.1:5173/` connects to nothing and produces a window that waits forever. The
templates use the hostname, and say why.

### Client-side routes, and a latent bug the router exposed

Adding a Vue template turned up two things about serving files that no hand-written page would
ever have hit.

**A history-mode route asks for a file that was never built.** `/about` exists only in the router,
so a reload or a deep link is a request for a missing file, and the honest 404 is the wrong answer.
Every bundler's dev server serves the entry document instead and lets the router sort it out;
`AssetSchemeHandler` now does the same, controlled by `WebApp.spaFallback` (on by default).

The interesting part is the restriction: **the fallback applies only to paths whose last segment
has no file extension.** `/about` and `/chat/42` are routes; `/assets/app-9f2.js` is an asset that
is missing. Answering the second with a page of HTML would turn a clear 404 into an
`Unexpected token '<'` in the module loader, which is a worse bug than the one being fixed — a
fallback that swallows asset typos is worse than no fallback.

**The bridge was referenced relatively, and that only works one level deep.** The framework's own
demo and the vanilla template both used `<script src="panamaview.js">`. From `/index.html` that
resolves to `/panamaview.js` and everything is fine. From `/chat/42` it resolves to
`/chat/panamaview.js`, which does not exist — so the page loads, renders nothing, has no bridge,
and reports no error anybody would look at.

Measured, on the same page and the same handler:

| bridge reference | `/settings` | `/chat/42` | `/nope/with/segments` |
| --- | --- | --- | --- |
| `panamaview.js` | works | **no bridge** | **no bridge** |
| `/panamaview.js` | works | works | works |

`/settings` working is what made this worth chasing: a single-segment route is exactly the case
that hides the bug, so any test using `/settings` passes while `/chat/42` is broken. Both the demo
and the vanilla template now use the absolute form, and the reason is written down at each site —
this is a thing that will be "simplified" back by someone who has only tested the root path.

### Live reload for people who do not want Node

A dev server is the right answer when there is a frontend toolchain. When there is not — plain
HTML, the vanilla template — the framework does it: it watches the classpath resource directory
and re-stages on change. Two details that are easy to get wrong:

- **Watch recursively.** A flat watch misses `assets/…`, which is exactly where generated output
  lives.
- **Debounce.** One save in an editor is routinely three events plus the compiler's own output,
  and three reloads in a row is visibly janky.

The page is pointed at the *staged* directory and the staged copy is refreshed, rather than
pointing the page at the source. That keeps the framework's client bridge available beside the
assets and never writes into the project's source tree.

### 🚨 The verification script had a hole big enough to hide two bugs

The first version of `verify-template.sh` assembled its own `java` invocation to run the headless
check. Sensible-looking, and wrong in a way that took a while to surface: it never ran `./run.sh`,
so it never noticed that **`run.sh` put only `target/classes` on the classpath**. Every template
died with `NoClassDefFoundError: panamaview/UiHost` the first time anyone used the script the
README actually recommends. It was found by running `./dev.sh` for real.

The lesson is the same one as §11 and §5.2, in a new costume: **a test that does not exercise the
documented entry point is not testing the thing users will do.** The script now calls
`JAVA_OPTS=-Dpanamaview.check=true ./run.sh` — the real script, its compile step, its classpath
assembly and its two JVM flags — and the check mode is what makes that possible without a window.

Three more faults came out of the same pass, each cheap to fix and expensive to find later:

- **A reserved-name collision.** Vite copies `public/` into `dist/`, so the dev-server copy of
  `panamaview.js` arrived in the staged web root and tripped the framework's own reservation.
  Caught loudly, by the guard, at build time — which is what the guard is for.
- **`exec` in a launcher script discards its trap.** `dev.sh` ran Java with `exec`, replacing the
  shell and orphaning the dev server on every exit. It also recorded the PID of a subshell rather
  than of Vite, so cleanup killed the wrapper and left the server holding the port — surfacing as
  the *next* run failing to start, a confusing distance from the cause. Both fixed by not using
  `exec` and by having the subshell `exec` instead.
- **`set -u` plus an unset variable.** `JAVA_OPTS` unset is normal, and `${JAVA_OPTS}` in a `set -u`
  script is a hard error — one that only showed up in the template that used `set -eu`, which is
  how the two scripts drifted apart.

## 15. Reproducing any of this

```sh
./run.sh                                       # JVM, 13 self-test checks
JAVA_OPTS=-Dpanamaview.snapshot=/tmp/s.png ./run.sh
JAVA_OPTS=-Dpanamaview.navigateAway=true ./run.sh          # also exercise the unload path
./build-native.sh && ./target/panamaview -Dpanamaview.snapshot=/tmp/s.png
otool -L target/panamaview                     # what the binary actually links

./scripts/verify-templates.sh                  # both templates, all the way to a native binary
./scripts/verify-templates.sh react            # or just one

# Every class and method named in the native-image metadata must still exist in the sources.
# Worth running before any native build: nothing else catches a class that moved.
python3 - <<'EOF'
import json, pathlib, re
m = json.loads(pathlib.Path("src/main/resources/META-INF/native-image/panamaview/"
                            "panamaview/reachability-metadata.json").read_text())
src = pathlib.Path("src/main/java")
for e in m["foreign"]["directUpcalls"]:
    text = (src / (e["class"].replace(".", "/") + ".java")).read_text()
    assert re.search(r"(?m)^\s*(public\s+)?static\s+[\w\[\]<>.?,\s]+\s+"
                     + re.escape(e["method"]) + r"\s*\(", text), e
print("metadata resolves")
EOF
```

The self-test runs on page load and reports to stdout and on screen, so a single run covers
JS→Java, Java→JS, structured round trips, 200 concurrent invocations, cancellation, deadlines,
by-value struct get/set, resize, and a WebKit self-snapshot. The `-Dpanamaview.snapshot` property
exists because WebKit's own snapshot API is the most reliable way to see the result without a
desktop session — and using it is itself an end-to-end test of the block bridge.
