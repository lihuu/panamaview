# Notes from building panamaview

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
`framework/src/main/resources/META-INF/native-image/io.github.lihuu/panamaview/`.

(The path was written here as `…/native-image/panamaview/panamaview/` for a long time. That
directory never existed — the resource path follows the Maven group id, which was already
`io.github.lihuu` when this was written — so anyone following the §17 check below got a
`FileNotFoundError` and no hint that the check itself was the wrong thing to run.)

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

## 7. Object lifetime: what this does not solve

Honest accounting, because it is the main thing standing between this and an app that runs
for a long time:

- Objects retained via `retain` are **never released**. Everything lives for the process
  lifetime. Correct for a short-lived process, wrong for a long-running app with many transient
  objects.
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
AppKit's own callback, while the run loop is blocked on it. The first version
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
no other choice. The platform extras (`geometry`, `resize`, `snapshot`) are registered with
`bindUi` from the demo app's composition root, and the self-test asserts they ran with
`onUiThread: true`.

### Getting onto the UI thread (the platform-specific part)

This is where platforms diverge, which is why it lives in the platform package
(`io.github.lihuu.panamaview.macos.UiThread`) rather than in the bridge. On macOS it is libdispatch, and
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

The original brief said not to design a cross-platform abstraction, and it was right:
an interface extracted from one implementation describes that implementation, while an
interface invented for three is a guess. But "do not abstract" is not the same as "let
native types leak into the application", and the first version did leak — `WebApp` imported
`java.lang.foreign.MemorySegment` in three places to expose platform extras.

So the line is drawn at exactly one seam, derived from the working macOS implementation:

```
io.github.lihuu.panamaview        portable — JDK types only, no foreign, no ObjC
  WebApp  App  Bridge  Json  Resources  UiHost  WindowSpec
                                  │
                                  │  UiHost: String / Runnable / primitives / Path
                                  │
io.github.lihuu.panamaview.macos  the only implementation that exists
  MacOsHost  ObjC  Cocoa  WebView  WebView  ObjCDispatch  NativeBlock  UiThread
```

`UiHost` is four methods plus a callback interface. Everything the application needs from a
platform is in it: start, evaluate, hop to the UI thread, ask whether you are on it.

What is deliberately **not** in it: `snapshot`, `geometry`, `resize`. Those are macOS
capabilities today. Putting them on the seam now would mean guessing at signatures for
platforms nobody has written, so they stay as ordinary bindings registered by the application's
own composition root — `demo-app`'s `Main` is the one that exists. When a second platform also
needs to resize, that is when a method earns its place.

### How strongly this is enforced

Package-private, not modules. Inside `io.github.lihuu.panamaview.macos` only `MacOsHost` is public; `ObjC`,
`Cocoa`, `WebView`, `ObjCDispatch`, `NativeBlock` and `UiThread` are all `final class` with no
modifier, so they are unreachable from the application — by convention rather than by the compiler,
since everything is still one Maven module. The composition root that pairs a host with an app is
an application-side class now (`demo-app`'s `Main`), not a platform one: wiring the two together is
the one thing that has to know both, and that is the app's job, not the platform's.

That is enough for now because the portable half has been checked to be genuinely free of
native types: the core package's imports are JDK-only, and no `MemorySegment`, `Arena`,
`Linker` or `ObjC.` reference appears in it as code. When a second platform is real, moving
`io.github.lihuu.panamaview.macos` into its own Maven module turns the convention into a build-time
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

### 🚨 Live reload did nothing on Windows, and the README said it worked

The watcher was registered only when `host.isUiThread()` was true:

```java
if (liveReload && host.isUiThread()) { … }
```

The reasoning was that reloading a window is a UI-thread operation. It is not — reloading is a
*request*, and the request is delivered later, on whichever thread the platform calls the UI
thread. What the condition actually asked was "is the **caller** on the UI thread right now",
which is a different question with a different answer.

On macOS the two questions happen to have the same answer, because AppKit's UI thread is the
process' first thread from the first instruction: with `-XstartOnFirstThread` the JVM hands
`main()` to it, so `isUiThread()` is true at every point where the portable layer runs. The
condition was therefore *always* true on macOS, and the macOS path was never at risk.

On Windows it was always false at the moment it mattered. The host's event loop runs on a thread
it creates inside `start()`, and the watcher was registered before `start()` — so `isUiThread()`
was false, the watcher was never registered, and live reload silently did nothing. No error, no
message, no failed build. The vanilla template's README said the feature worked, and it did not.

The fix is to stop asking the question that was being answered. The watcher is registered
unconditionally, and the reload is delivered through `host.onUiThread` — which is the platform
seam's answer to "run this later, on the UI thread", and which every platform already promises
to accept from any thread.

### 🚨 The fix was not enough, because the queue was broken underneath it

Removing the condition would have made the watcher register on Windows — and the reload would
still have been lost, one layer down. `WindowsHost.onUiThread` did this:

```java
uiQueue.add(action);
Win32.postRunnable(hwnd, 0);      // hwnd is null until start() creates the window
```

`PostMessageW` with a null HWND does not fail loudly. It returns 0, and the message goes to the
*calling* thread's queue — the main thread, which has no message loop and never drains it. The
action sat in `uiQueue` forever. The pump thread's own `WM_APP_RUNNABLE` handler, which is what
actually drains the queue, was never woken, because the wake-up message went somewhere else.

So the queue is now the source of truth and the message is only the nudge: when the window does
not exist yet, nothing is posted, and the pump drains the queue itself once it is up. The
window's existence is the only thing that decides whether there is a bell to ring.

The general shape of this is worth stating, because it is the second time this repository has
been bitten by it: **a platform's "am I on the right thread" predicate is a statement about
_now_, and a question about _later_ needs a different answer.** `isUiThread()` is still the
right thing to ask when you want to know where you are standing — the demo's self-test uses it
to prove a handler is off the UI thread, and `bindUi` handlers report it. It was the wrong thing
to ask when what you needed was a way to say "do this later, over there".

### The test that would have caught it

This repository had no test source at all: everything was verified by running the demo
application and reading its self-test output. That is a real check of the bridge and no check at
all of the framework's own logic, which is why a disagreement between the portable layer and a
platform's event loop could sit there for a year with nothing to fail — and no Windows CI job
to fail in either.

`LiveReloadTest` now pins the *shape* of the failure rather than the platform: a host that
reports it is not on the UI thread, exactly as Windows does before its event loop is up, must
still get a working watcher. The next platform to arrive inherits the fix instead of the bug.

The test was checked against the old code before the fix was kept: it fails there, with
`expected: <true> but was: <false>`, and passes after. A test that has never failed is not
evidence of anything.

### The same bug, measured on a real Windows box

The A/B that the unit test stands in for, run on a Windows 11 machine — the same probe, the
same page, the same two edits, only a different framework jar:

| | pre-fix jar (115194 B) | fixed jar (115825 B) |
| --- | --- | --- |
| `live reload watching …` | absent | present |
| `change detected, reloading` | never | twice |
| page loads | **1** | **3** |

The pre-fix run has no watcher line at all and ignores both edits. That is the whole bug, in
one log.

### Two traps the run on real hardware found, that no reading of the code had

- **`onReady` fires on every page load**, not only the first. A live reload *is* a page load,
  so the callback runs again — and a callback that starts a thread dies with
  `IllegalThreadStateException` on the first reload. The javadoc said "called once the page
  has loaded", which is how the probe was written, and it was wrong in the way that matters:
  not about the count but about what the count implies for a callback that does setup work.
- **`$ErrorActionPreference = "Stop"` turns a native command's stderr into a terminating
  error.** `java -version` writes to stderr, so a diagnostic line in the runner script killed
  the script before the probe ever started. Worth knowing for any script that captures a
  program's output on Windows.

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

## 15. Generating a project from a template

This was `scaffold/`: a separate Maven project that depended on nothing — not even on panamaview.
It read the templates out of its own resources, renamed three things, and wrote files. Its native
build therefore needed no reachability metadata and no JVM flags, and came out at 13 MB rather than the
framework's 19 MB.

That simplicity was the reason to keep it separate: the moment the scaffolder wants a type from the
framework, its native build inherits the framework's problems.

It is `npm/cli.js` now. The job turned out to be "copy a directory tree and rename three strings", and
13 MB of per-platform binary is a strange way to do that when Node is already installed on every
machine that can run `npm` at all. What follows is kept because of which of the four failures it is
about: the two that were about Maven and jars are gone with them, and the two that are about the job
itself are not.

### Four things that had to be got right, and each was a real failure

**"Which files are a template" is a version-control question.** The first version used
`find templates -type f` and promptly tried to copy `node_modules/` — which is not part of any
template, and is not something a scaffolder should ship. The obvious repair, an exclusion list, does
not work either: `vanilla-tailwind` generates `tailwind.css` and `manifest.txt` *inside its own
source tree*, so pruning by directory name misses them, and a file-level list goes stale the moment
a template generates something new. `git ls-files --cached --others --exclude-standard` is the same
set `git status` would offer to commit, so it is right by construction.

**A jar carries no file modes.** `run.sh` would come back out `0644` and `./run.sh` would fail with
"permission denied" — in a project that otherwise looks complete. The staging step recorded the
executable bit in the manifest as a `*` suffix, the same convention tar and `ls -F` use. (Git's
index would be the natural source, but it prints no mode for an untracked file, and the manifest is
built before anything is committed.)

Gone with the jar: npm carries the mode through the tarball. That was measured rather than assumed —
a tarball was packed with an executable in it and the modes read back — because the JS version's whole
reasoning about modes is "the file's own bit is the truth, so there is nothing to restore".

**`addDefaultExcludes` defaults to true, and it has to be turned off twice.** Maven's default
excludes include `.gitignore` — along with `.gitattributes` and `.gitmodules` — and the resources
plugin applies them on the way into `target/classes` *and* the jar plugin applies them again on the
way into the jar. Avoiding the same trap in the staging script is not enough; the files are dropped
a second time. The symptom is a generated project with no `.gitignore`, which commits `target/` and
`node_modules/`, and nothing in the build says so.

**And the same failure came back under a different packer, which is the point worth keeping.** npm
never packs a file named `.gitignore`, in any directory: it is in npm-packlist's list of names that
are always ignored, and `files` cannot override it — measured, a tarball whose `files` names the path
outright still comes back without it. Every other dotfile travels, so it is that one name and not
"dotfiles". The package stages each template's as `.gitignore.packed` and the scaffolder writes it out
under the template's own name, and `build-package.sh` refuses to stage one under the name that would
be dropped. The earlier lesson read as a Maven quirk to be worked around once; it was a property of "a
packer decides which files travel", and every packer has its own version of it.

**A resource lookup without a leading slash resolves against the caller's package.**
`Catalog.class.getResourceAsStream("templates-manifest.txt")` looked for
`panamaview/scaffold/templates-manifest.txt` and found nothing. The manifest had been in the jar the
whole time.

Gone with the jar as well, and for the reason the port exists: the templates are directories in the
package, so "where is the template" is `fs.readdirSync` and "it is not there" is an ENOENT naming the
path it looked in. The manifest was a substitute for a directory tree, and it needed a check of its own
to notice when it was wrong.

### Renaming is one pass, not three replaces

Substitution runs a single `Matcher` pass over `com\.example|com/example|myapp|My App`. Three
sequential `replace` calls would be wrong, not merely slower: with `--package com.example.myapp`,
the `myapp` rule would rewrite text the `com.example` rule had just inserted, silently producing
`com.example.<app>`. One pass cannot see its own output. `verify-scaffold.sh` generates a project
with exactly that package for that reason.

Both spellings of a package are handled, in both file contents and file paths. The slash form
appears inside a *text* file: the templates' READMEs name `src/main/java/com/example/Main.java`.
Handling only the dot form in text left that one behind — found by scanning a generated project for
the original tokens, not by reading the code.

Text versus binary is decided from the bytes. The only binary file in the templates is a
`favicon.ico`, and an extension list has to be maintained; a UTF-8 decode that rejects malformed
input does not. Nothing depends on the guess being right, because a file wrongly treated as binary
keeps its original tokens and the verification fails on a single surviving one.

### The check and the build fail for different reasons

`verify-scaffold.sh` generates each template into an unrelated directory and takes the result to a
native executable using the generated project's own scripts. Two separate checks:

- **A leftover token** means a rename was missed. The project may still build, and it is wrong.
- **A build failure** means a coupling was missed that no rename could have fixed — a path derived
  from the app name, or a name shape Maven or Angular will not accept. The residual check passes
  cleanly on a project like that, because every token *was* substituted.

It generates under a name with a hyphen and a package **outside** `com.example`. That detail matters:
searching a project whose package is `com.example.x` for `com.example` matches the correct answer, so
the first version of the check passed while being unable to fail.

The `.gitignore` check has already earned its place. The vanilla template had none — it was the only
one of the five — and it went unnoticed until a generated project turned out to have one file fewer
than its template. Nothing else would have caught it, because a project without a `.gitignore`
builds perfectly.

Two couplings were removed rather than taught to the generator:

- **Angular's output path.** `build-frontend.sh` derived it from `angular.json`'s project name, which
  comes from the app — a two-file agreement that nothing enforced. `angular.json` now pins
  `outputPath: "dist"`, so the path is `dist/browser/` regardless of what the app is called.
- **The frontend package name.** `package.json` and `package-lock.json` carried the app's name, which
  put it in a machine-generated file the generator then had to rewrite. It is now the constant
  `frontend`, which is what that package is.

### What is not covered

No window opens. CI has no window server, so the headless check is what is left: it stages the assets
and resolves every `src`/`href` in the built page against them. That catches the failure that matters
most — page loads, script does not, window is blank — but it is not the same as seeing it.

## 16. Publishing to a private repository

Before Maven Central, and before anyone else has tried to build on this. A generated project depends
on `io.github.lihuu:panamaview`; `mvn install` satisfies that on one machine, and a private
repository satisfies it on every machine the owner configures. `scripts/deploy-private.sh` does it.

The deploy target is read out of `settings.xml`, at run time, every run. A private repository URL
carries an account identifier and this repository is public; committing it would publish a
stranger's namespace for no benefit, since the URL is useless without credentials and no reader
needs it. Keeping it in `settings.xml` — where the credentials already are — means the script
contains nothing specific to one account, and works against anyone's repository.

The poms deliberately have no `<distributionManagement>`. Plain `mvn deploy` then fails with
"no distributionManagement" instead of pushing a library into whichever private repository happens
to be configured on the machine.

### Two things the repository does that Maven does not expect

**A 404 with a JSON content type breaks deploy, and only on the first version of an artifact.**
Maven Resolver 2.0.24's default Apache transport reads the `Content-Type` of a failed response and,
on seeing `application/json`, decodes the body as an RFC 9457 problem document. Aliyun answers a
missing `maven-metadata.xml` with exactly that: 404, an empty body, `Content-Type: application/json`.
The decode fails, so instead of the "resource does not exist" that the deploy plugin handles, it
raises a malformed-error exception and aborts.

The failure is worth describing because of where it lands: the pom and jar are uploaded **first**,
and the metadata merge comes after. So the deploy reports failure having already published the
artifact. Measured:

```
uploaded panamaview-0.1.0.pom     2015 B      → HTTP 200 when fetched back
uploaded panamaview-0.1.0.jar    84652 B      → HTTP 200 when fetched back
maven-metadata.xml               → PUT succeeds (201); GET is what fails
```

`-Dmaven.resolver.transport=wagon` fixes it. The legacy wagon transport does not inspect the content
type of a 404, and the metadata step completes. It is applied to the deploy command only: the
verification that follows uses the **default** transport on purpose, because that is what a
consuming project uses, and proving resolution works under a different transport would prove the
wrong thing.

**A release repository refuses to overwrite a published version, with a bare `409`.** Maven reports
that as "Failed to deploy artifacts", which reads like a credentials or network problem and is
neither. Retrying cannot help. The script checks whether the version is already published before
uploading, and says which two things would work: bump the version, or publish a `-SNAPSHOT` while
iterating — snapshots exist to be replaced, and the script chooses the snapshot repository from the
version string on its own.

### What the check actually establishes

"Uploaded" and "another machine can fetch it" are different claims. After deploying, the script
resolves both artifacts into an **empty** local repository and compares SHA-256 against the jars it
just built. An empty repository is the only version of this check that means anything: any other
one can be satisfied by the copy `mvn install` left on this machine.

End to end, with that empty repository, and with no framework checkout on the path:

```
$ java -jar panamaview-scaffold-0.1.0.jar create demo-app --template vanilla --package dev.acme.demo
  10 files written

$ MAVEN_OPTS="-Dmaven.repo.local=/tmp/fresh" JAVA_OPTS=-Dpanamaview.check=true ./run.sh
  [check] staged index.html (1178 bytes)
  [check] staged panamaview.js (5473 bytes)
  [check] ok

$ cat /tmp/fresh/io/github/lihuu/panamaview/0.1.0/_remote.repositories
  panamaview-0.1.0.jar>2375652-release-Yr1Eef-52f3300f...=
```

`_remote.repositories` is Maven's own record of where each artifact came from. It names the private
repository, and the jar's hash matches the build in this checkout — so the generated project built
against the artifact that was published, not against a local copy of something similar.

## 17. Reproducing any of this

```sh
mvn -pl framework test                        # the framework's own tests — no window, no demo

cd demo-app
./run.sh                                       # JVM, 13 self-test checks
JAVA_OPTS=-Dpanamaview.snapshot=/tmp/s.png ./run.sh   # 14 — the WebKit snapshot check too
JAVA_OPTS=-Dpanamaview.navigateAway=true ./run.sh          # also exercise the unload path
./build-native.sh && ./target/panamaview-demo -Dpanamaview.snapshot=/tmp/s.png
otool -L target/panamaview-demo                # what the binary actually links

cd ..
./scripts/verify-templates.sh                  # every template, all the way to a native binary
./scripts/verify-templates.sh react            # or just one

./npm/scripts/build-package.sh                # → npm/templates/ and npm/lib/, the package as packed
node npm/cli.js --selftest                     # every file the package ships is opened and read
node npm/cli.js templates

./scripts/verify-scaffold.sh                   # generate from every template, then build each one
./scripts/verify-scaffold.sh vue               # or just one

./scripts/deploy-private.sh                    # publish to the repository in ~/.m2/settings.xml
./scripts/deploy-private.sh --verify-only      # just check it still serves these versions

# Every class and method named in the native-image metadata must still exist in the sources,
# and every shape the sources build must be registered. Worth running before any native build:
# nothing else catches a class that moved. The second script proves the first can fail.
python3 scripts/verify-native-metadata.py
python3 scripts/verify-native-metadata-failures.py
```

The self-test runs on page load and reports to stdout and on screen, so a single run covers
JS→Java, Java→JS, structured round trips, 200 concurrent invocations, cancellation, deadlines,
by-value struct get/set, resize, and a WebKit self-snapshot. The `-Dpanamaview.snapshot` property
exists because WebKit's own snapshot API is the most reliable way to see the result without a
desktop session — and using it is itself an end-to-end test of the block bridge.

## 18. Application menus

An application can declare its own menu bar, and the framework builds it on both platforms.
The declaration is portable; the menus that come out of it are the platform's.

```java
new WebApp(host)
    .menu(Menus.menu(
            Menus.appMenu(),
            Menus.menu("文件",
                    Menus.item("export", "导出").shortcut("CmdOrCtrl+E"),
                    Menus.separator(),
                    Menus.platformItem(Platform.QUIT, "退出")),
            Menus.editMenu()))
    .bind("export", params -> { … })
    .run();
```

A **binding item** names a Java method: choosing it calls that binding — the same one a
page's `java.invoke` would reach, though not quite the same call. A menu binding's return
value is discarded, there being no page to hand it to, and a menu binding that exceeds its
deadline is reported on the framework's own stderr rather than to a page. A **platform item**
names an operation the platform already knows — `Platform.QUIT`, `Platform.COPY` — and has no
binding behind it. The two are different in kind, not in decoration.

### Why there are two kinds, and it is not a matter of taste

On macOS a platform item's target is **nil**, so AppKit walks the responder chain and the
first responder handles the action. That is the only way ⌘C reaches the web view's text
field, and it is what issue #19 was about. A target that stopped the action there — a Java
object, say — would break it. So a platform item must have *no* target and a binding item
must have one, and the framework cannot offer one kind and dress it up as the other.

**A binding item's target has to be kept alive by Java.** `NSMenuItem.target` is a **weak**
reference, so a target Java's collector reclaims becomes nil — which is exactly what a
platform item does. A binding item whose target was collected would quietly stop being one
and start walking the responder chain, which is the failure the whole distinction exists to
prevent. The target's instance therefore lives in a static field.

### The ids, and the two values that are not ids

The framework numbers the items when the bar is built, and the number is what the platform
reports when an item is chosen. The numbering starts at 1, and a **separator takes an id of
its own**, so that the ids after it are not shifted — a separator is an item as far as the
numbering is concerned.

Two values are **not** ids, and both come back from the platform looking like one:

| value | what it means |
| --- | --- |
| `0` | a separator was chosen — the macOS separator is a shared singleton with no tag, and a Win32 separator's command id is 0 |
| `0xFFFFFFFF` | a popup was chosen: a menu that opens another menu rather than a command |

`0xFFFFFFFF & 0xFFFF` is 65535, not 0, so a guard written on the masked value would look up
the binding numbered 65535. The guard is on the whole value.

### The shortcut string, and why the framework parses it itself

`CmdOrCtrl` is the spelling that means "the platform's own key" — ⌘ on macOS, Ctrl on
Windows — and it is the reason the framework parses the shortcut string rather than handing
it to each platform: one declaration, one meaning, whichever platform it lands on. macOS
honours that today; the Windows builder registers no accelerator yet, so a shortcut there does
nothing — see the Windows shortcuts below.

The parser is strict, because a shortcut that silently does nothing is worse than one that
is not offered. `"Ctrl+"` used to parse as the key `Ctrl` with no modifiers, and
`"Ctrl+Shift"` as the key `Shift`. Both throw now, and so does a modifier on its own.

### What AppKit actually does with a key equivalent

These are measurements, and two of them contradict what the shipped code believed.

- **The modifier mask *replaces* AppKit's default rather than adding to it, and AppKit's
  default for a bare key equivalent is ⌘.** So a declaration of "C" with no modifier means
  the plain `c`, not ⌘C: the platform sets the mask always, and the model has to say which
  it means.
- **An uppercase key equivalent does not imply Shift.** The shipped Edit menu passed "Z" for
  Redo and set no mask, so the mask that applied was AppKit's default — Command; the uppercase
  letter changed only the *displayed* key, never the mask. The comment in the source that said
  otherwise was a belief, not a measurement, and it is gone.
- **Among items with different actions, the second to declare the same key and the same
  mask loses its key equivalent entirely** — the key comes back empty, so the item is
  reachable by no chord at all. The rule reaches across the whole main menu, not only within
  one of its menus.
- **Items that share an action both keep theirs.** That is how two binding items may declare
  the same shortcut, and why the framework's own Undo and Redo — two platform items, with
  different actions — have to differ in the case of the letter ("z" and "Z") to keep both.

So the rule for an application is: **two platform items anywhere in the bar must not declare
the same key and the same mask.** Two binding items may; the second one simply shadows the
first when the chord is pressed.

**What is not measured: which chord AppKit gives an uppercase key equivalent.** A synthesized
`NSEvent` and `-[NSMenu performKeyEquivalent:]` were tried and matched nothing — an event
built from a `CGEvent` does not carry what AppKit's matcher needs, and a detached menu
answers "no match" for everything — so the experiment measures the harness rather than the
rule. The mask is measured (Command); the matching is not. No chord claim goes into the
source or into this file.

### The two platforms do not offer the same menus, and the difference is measured

**The built-in Edit menu is macOS's.** On Windows the WebView2 control performs the
clipboard operations by itself: `document.execCommand('copy')` inside a real user gesture
put the text on the clipboard with no framework involved, and that call is the code path
Chromium's own Ctrl+C runs. So on Windows the browser already does the editing commands,
and the Windows bar is for *application* items. The framework installs no Edit menu of its own
there, and a declared `editMenu()` is built with every editing item **disabled** — the user
sees that it exists and cannot have it, which is the same trade the framework makes
everywhere else: an item that looks live and does nothing is worse than one that says it
cannot be had. The framework says so once on stderr, naming the operations it could not
perform, because a silent difference between the platforms costs an afternoon.

**Windows keyboard shortcuts are a separate piece of work.** The first slice delivers a
clickable menu bar on both platforms; macOS additionally gets shortcuts, because its menu
item already carries them. The Windows accelerator route is not in this one, and the reason
is measured: a Windows shortcut handled by the framework would have to take the key away
from the page.

### A declared bar replaces the framework's bar, and that is a responsibility

`.menu(...)` replaces the framework's bar; it does not merge with it. Merging has no good
answer: the framework finds the application menu by the placeholder title the model carries —
the model has no application name to put there — and an application menu declared anywhere but
first leaves an untitled item in the middle of the bar. Merging would have to make the
application menu expressible in the same list as the application's own, and say where in it.
Replacing is simpler, and it is what the design does.

The cost is that **an application that declares a bar takes responsibility for the whole
bar**: `appMenu()` and `editMenu()` included. On macOS, leaving the Edit menu out brings back
issue #19 — ⌘C stops reaching the web view's text field — and the symptom is "the text fields
stopped working", which mentions nothing about menus. The built-in menus are supplied for the
application to include; a framework that merged them would leave the application unable to
choose.

### A menu item can be chosen without a human, and that is how it is checked

A check that cannot fail is not a check, and a menu check has to be able to *choose* an item
without a human choosing it. Both platforms grew a seam for it, in the same family as
`windowRectangle()` and `readClipboard()`:

```java
host.clickMenuItem(id);        // the id the framework assigned, readable from the model
```

On **macOS** it is `-[NSMenu performActionForItemAtIndex:]` — a method of `NSMenu`, taking
the item's *position*. `NSMenuItem` has no `performClick` at all; that is `NSButton`'s
method, and a menu item is not a button. The call works on a submenu, the position counts
the separators, and the target receives the item as the sender and reads the id off its
`tag` — the same path a real click takes. Activating a separator does nothing, which is
right. The position is the item's index in the model's own item list, which is the order the
builder appends them in, so the id is found by walking the model and the index comes from
where the walk found it.

On **Windows** it is a message: `PostMessage(WM_COMMAND, id, 0)` to the window, which is
what the menu bar does when an item is chosen. The message still has to reach a window —
`PostMessageW` to a null handle goes nowhere, quietly — so the call refuses when there is no
window to post to.

The wait belongs in the binding, not in the page. The activation crosses three hops — the
menu item's action, the target's upcall, and the binding on a virtual thread — so the effect
is not there the instant the call returns. Waiting inside the binding makes the answer
arrive with the result, in one round trip, with no polling in the page and no race in the
check. Two seconds is generous for an activation that has already happened.

### The built menu is read back, and a mismatch refuses to start

A menu with the wrong ids dispatches the wrong binding, silently: the item is there, the
click works, and the wrong handler runs. So the Windows builder reads the `HMENU` it just
built and compares it with the plan — the number of items in each submenu, and each item's
command id and title. **A mismatch is a hard failure, not a warning**, and the message names
the menu, the position and both ids, because the person reading it is looking at a menu and
not at a stack trace.

**The built menu reads back exactly what went into it, and that is measured.** A non-ASCII
title survives character for character: 导出 comes back as U+5BFC U+51FA and 文件 as
U+6587 U+4EF6. That is the mojibake question — a menu item whose title is not ASCII must not
be mojibake in the menu — answered on the real Win32, and on macOS the same round trip
through `NSMenu` brings 导出 and 保存 back exactly. And a mnemonic marker survives verbatim —
"&File" comes back with its `&` — which is what lets the read-back compare the *titles* as
well as the ids: a platform that stripped the marker would report a mismatch for every title
that had one, and the ids alone would have left the titles unchecked.

The macOS side paid for the same lesson the other way round: a key equivalent that looked
right in the model came back from AppKit with the shortcut gone, and nothing but reading the
built menu back would have said so. The macOS builder has no read-back yet, so that failure
is still caught by measurement rather than by the build.

### What the demo's run showed

The demo declares a bar, and its own check proves the wiring. The red was seen before the
green, and the red was the failure this feature is exposed to: with the bar declared but not
handed to the host, the id named a *different* item — the framework's default bar was
installed instead — and the binding's count stayed at 0.

```
[selftest] 13 passed, 1 failed
[selftest]   FAIL  menu item reaches its binding — the menu item ran the binding 0 time(s), and the one after the separator 0
```

and after the bar was handed over:

```
[selftest] 14 passed, 0 failed
[selftest]   PASS  menu item reaches its binding — the menu item ran the binding 1 time(s), and the one after the separator 1
```

Both blocks are the **snapshot** run — `JAVA_OPTS=-Dpanamaview.snapshot=… ./run.sh` — because
that is the run with the WebKit check in it. A plain `./run.sh` reports one fewer, the
snapshot check being the one that only runs when the property is set.

Two items are chosen, not one, and the second one is the item *after* the separator. An item's
position in a menu is not the number of the items before it, so a click that counted only the
items would land on the separator and reach nothing — which is the failure the check would
then report, and which choosing only the first item would not have caught.

The menu bar is AppKit's own chrome, above the window, so the demo's own snapshot does not
show it: `docs/screenshot-macos-menu.png` is a region capture of the top of the screen, and
it shows the application's own menu (文件) beside the framework's Edit menu.

One detail the picture makes plain: **the application menu is titled with the process
name**, because an unbundled JVM has no application name of its own. The Quit item inside it
is titled with the *window's* title — "Quit panamaview" — because that is the name the
framework has, and the platform composes it (`Cocoa.itemTitle`); the app menu is closed in the
picture, so that one is from the code rather than from the capture. A bundled `.app` shows
its own name in the same place.
