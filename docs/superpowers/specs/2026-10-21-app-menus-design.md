# Application menus: design

**Date:** 2026-10-21
**Status:** draft, for approval

## Intent

panamaview is a framework. A framework that cannot let an application add a menu item is
not offering the capability an application needs — today, an application that wants a
「文件 > 导出」 has no way to declare one without editing the framework's own source.

This is not about fixing a broken shortcut. The Edit menu exists already and works
(issue #19). It is about the framework exposing the ability at all.

## The model

The same shape Tauri v2 uses, because it is the shape that answers the question properly:

```rust
// Tauri
let menu = MenuBuilder::new(app).text("open", "Open").build()?;
app.set_menu(menu)?;
app.on_menu_event(|_app, event| match event.id().0.as_str() { "open" => { … } });
```

A menu item carries **an identity and a label — no behaviour**. The framework says which
item was chosen; the application decides what that means. Tauri identifies the item by
string id, and the application matches on it.

panamaview already has the other half of this: `bind` declares a named Java method, and
the page can call it. So a **binding item** is a *second surface of the same binding*:

```java
// panamaview
.bind("export", params -> { … do the export … })
.menu(menu(
    menu("文件",
        item("export", "导出").shortcut("CmdOrCtrl+E"))))
```

Clicking 「导出」 calls the `export` binding. The behaviour is still the application's
Java method — the framework contributes only the ability to *put a menu item on screen and
report that it was chosen*.

This is deliberately not a new dispatch mechanism. A menu item and a bridge call are the
same thing seen from two sides.

There is a second kind of item, for the cases the binding model cannot express, and the
reason it exists is the whole point of the next section.

## Goals

- An application can declare a menu bar: top-level menus, their items, separators, and
  shortcuts.
- A **binding item** points at a name declared with `bind`. Clicking it invokes that
  binding.
- The same declaration produces a correct menu on macOS and on Windows.
- The framework's existing Quit and Edit menus keep working, and keep working by default —
  an application that forgets the Edit menu must not reproduce issue #19. **The Edit menu
  stays macOS-only**, and the measurement behind that is in "A menu's identity" below.

## Two kinds of menu item

This is the distinction the design turns on, and getting it wrong re-breaks issue #19.

**A platform item** carries a *native action* — `copy:`, `paste:`, `undo:` — with a **nil
target**, so AppKit walks the responder chain and the first responder handles it. That is
precisely how ⌘C reaches the web view's text field today (Cocoa.java:43-48). These items
have **no binding behind them**, and they must not have one: a target that is a Java object
would *stop* the action travelling down the responder chain, and the web view would stop
receiving ⌘C.

**A binding item** carries a *name* — `"export"` — and clicking it invokes the binding
declared under that name.

The two are different in kind, not in decoration, so the model has to say so:

```java
menu(
    menu("文件",
        item("export", "导出").shortcut("CmdOrCtrl+E"),   // a binding item
        separator(),
        platformItem("quit", "退出")),                     // a platform item
    editMenu())                                            // the built-in platform items
```

The built-in Edit menu is a *platform* menu, and it is not expressible in terms of bindings.
It is therefore **not part of the binding model at all** — it is something the framework
supplies, and an application may ask for it, keep it, or leave it out. It is not
"replaceable by an application-declared menu of the same name", because an application
declaring 复制/剪切/全选 as binding items would silently stop ⌘C from reaching the web view.

**Consequence for the API:** the model needs two item kinds, and the built-in Edit menu
stays outside them. A design with one kind of item cannot express both.

## Non-goals

- **Icons, checkable items, radio items, sub-submenus.** Tauri has all of these; they are
  not needed for the capability being asked for, and each one is a platform difference
  (macOS `NSMenuItemState` vs Windows `MFS_CHECKED`). Later, if wanted.
- **A tray icon.** That is a separate roadmap item and a separate piece of platform work.
- **Context menus.** A menu bar is the ask. A right-click menu is a different surface on
  both platforms.

## Anti-goals

- **A shortcut syntax that is neither platform's.** The point of the shortcut string is
  that the *same declaration* works on both platforms. If the string has to be written per
  platform, the abstraction is leaking.
- **The framework deciding what an item does.** A binding item pointing at a binding is the
  whole contract. A platform item points at a platform action, and the platform decides.
- **Breaking the existing menus.** Quit and Edit stay, and stay on by default.
- **A binding item standing in for a platform item.** That is how ⌘C would stop working.

## The shortcut string

The same shape Tauri's accelerators use: modifiers joined by `+`, then a key.

```
"CmdOrCtrl+C"        "Ctrl+Shift+E"        "F5"        "Alt+Delete"
```

`CmdOrCtrl` is the one that carries weight. On macOS it means ⌘; on Windows it means Ctrl.
A declaration written once then means *the same user-visible action* on both platforms —
copy on macOS is ⌘C, and on Windows it is Ctrl+C. Writing `"Ctrl+C"` and meaning ⌘C on
macOS would be a trap, and the framework is in a position to avoid it.

**This is a decision, and it is mine to flag:** the string is parsed by the framework, not
handed to the platform. macOS wants a key *character* plus a modifier mask; Windows wants a
*virtual key code* plus a modifier mask. Neither accepts a string, so a small parser is
needed on both sides, and it is the same parser with two back ends.

## The two platform implementations

### macOS

The existing Edit menu is already correct AppKit: items carry an *action selector*
(`copy:`, `paste:`) with a **nil target**, so AppKit walks the responder chain and the
first responder handles it. That is what makes ⌘C reach the web view's text field.

A **binding item** is different: it must reach *Java*, not the responder chain. The
mechanism already exists — `ObjC.defineClass` creates an Objective-C class at runtime whose
methods are FFM upcall stubs into Java, and `ObjCDispatch` is exactly that pattern for
script messages.

Two ways to give an item a Java target, and one of them is much better:

- **One class per item**, with one method per item, named after the item. `ObjC` binds no
  `sel_getName` (ObjC.java:91), so a generic handler cannot read the selector's name back
  to work out which item fired — each item would need its own distinct method, and a
  runtime class per item.
- **One shared target object, and the item's `tag` carries the identity.** `NSMenuItem` has
  a `tag` property, an `NSInteger`, and the `ObjC` layer can read and write it
  (`sendLong`, `sendVoidLong` — ObjC.java:339, 369). The target has a single action method;
  the item that fired identifies itself through the tag. One class, one method, any number
  of items.

The second is what to build. It is less code, and it does not need `sel_getName`.

**The whole mechanism, measured.** Not the tag alone — the whole path: a runtime-created
Objective-C class as the item's target, its action method receiving the clicked item, and
the tag coming back out of that item:

```
the action method saw tag: 4242
the tag survived the action: YES
```

So the design's macOS approach is not plausible, it is **working**. The pieces it needs
are all already in the framework: `ObjC.defineClass` for the class, `ObjC.upcall` for the
method, `VOID2` for the signature, and `sendLong`/`sendVoidLong` for the tag.

One thing the measurement corrected: **`NSMenuItem` has no `performClick`** — that is
`NSButton`'s method, and sending it to a menu item raises `NSInvalidArgumentException`
and takes the process down. The action is invoked on the *target*, with the item as the
argument, which is what AppKit does when the item is chosen.

**The ABI works out, and it is measured.** `NSMenuItem.tag` is an `NSInteger`, which is
64-bit — and the `ObjC` layer already has exactly the two accessors that needs: `sendLong`
(no arguments, returns an `NSInteger`) and `sendVoidLong` (one `NSInteger` argument).
NOTES.md §2.3 records the trap this is the other side of: `NSInteger` is not `BOOL`, and
passing it with the wrong width is a mistake that "works" until the value is not 0 or 1.
The existing helpers use `JAVA_LONG`, which is the right width.

Round-tripped on macOS 14, through the real `NSMenuItem`:

```
wrote 102030405060708
read  102030405060708
round trip: EXACT
small value round trip: EXACT
```

A full-width value and a small one both survive, which is the case a wrong width would
break.

**The action's argument.** AppKit sends the clicked item as the argument to the action
method, so the handler reads the tag off the item it was handed rather than off `self` —
which is also what makes one shared target sufficient.

The upcall signature for that method already exists: an AppKit action is
`-action:(id)sender`, and `ObjC` has `VOID2` — receiver, selector, one object argument.
So the menu target needs no new `FunctionDescriptor`, only a new class and its method.

`NSMenuItem` also needs `keyEquivalentModifierMask` set, because AppKit reads the
modifiers from the mask rather than from the key character. The mask is an `NSInteger`,
which the `ObjC` layer already knows how to pass (`sendVoidLong`). The existing Edit menu
passes only a bare key character, which is why it gets ⌘ by default; a configurable menu
sets the mask explicitly.

### Windows

`HMENU`, created with `CreateMenu` / `AppendMenuW`. Each item carries an **integer command
id**. The click arrives as `WM_COMMAND` with that id in the low word of `wParam`, and the
framework maps id → binding name.

**How the menu is attached.** `WNDCLASSEX.lpszMenuName` is a *string* field, not an `HMENU`
slot (Win32.java:71, 300), so it cannot carry a programmatically built menu. An `HMENU`
reaches a window two ways: the `hMenu` parameter of `CreateWindowExW`, or `SetMenu(hwnd,
hmenu)` afterwards. `SetMenu` is the one to use, and it has a constraint: `Win32.createWindow`
calls `ShowWindow` internally (Win32.java:322), so a menu attached after creation lands on
an already-visible window. The fix is to attach the menu *before* the window is shown, which
means `createWindow` has to grow a parameter — it is a package-private method, so this is not
a public API change.

**What the Windows side does not have yet, and this needs:**

- **`WM_COMMAND` handling.** `WindowsHost.onWindowMessage` has no case for it, and a message
  it does not answer falls through to `DefWindowProcW` — which would swallow the click.
- **`CreateMenu` / `AppendMenuW` / `SetMenu` / `DestroyMenu`.** None are bound.
- **`VK_*` and `MOD_*` constants.** None exist.

### Keyboard shortcuts on Windows are a different problem from macOS

This is the least symmetric part of the design, and it is worth being exact about it.

**On macOS**, a shortcut is a property of the menu item: the item has a key equivalent and
a modifier mask, and AppKit sends the action down the responder chain. The web view
receives it because the web view *is* the first responder.

**On Windows**, a menu's `&` mnemonic gives a *bare* key, not a modifier combination. The
usual mechanism for Ctrl+C is an accelerator table, and the route first written down here
is not implementable as described: `CreateAcceleratorTable` takes a **module resource id**,
and this repository has no resource toolchain — a jar and a native image, no `.rc` and no
`windres`. So an accelerator table built from a resource is out.

That leaves `WM_KEYDOWN` plus `GetKeyState` on the window, which is a *different* mechanism
from macOS's, and it has a consequence that matters more than the binding list:

> A window-level `WM_KEYDOWN` handler sees Ctrl+C **before** the focused WebView2 child
> window does. So a Windows shortcut handled this way is **stolen from the page's text
> field** — the opposite of macOS, where the nil-target Edit menu routes the action to
> whoever is first responder, which is the web view.

And the measurement sharpens it: the WebView2 control already performs clipboard commands
itself (see "A menu's identity"), so a Windows framework shortcut for Ctrl+C would be a
*second* implementation of something the browser does, and it would take the key away from
the page while doing it. That is strictly worse than the browser's own.

So on Windows the shortcut story cannot be "the framework handles the key". It has to be
"the menu item handles the key, and the page still gets it first" — which on Windows means
the accelerator route, which means the resource problem above.

**This is the strongest argument for splitting the work:** the *first slice* delivers a
clickable menu bar on both platforms, and macOS gets keyboard shortcuts because its menu
item already carries them. Windows keyboard shortcuts are a separate piece of work with
their own design question — how to get a Ctrl+E to a menu item without taking it from the
page — and shipping the clickable menu without them is honest rather than broken.

## What the application writes

```java
new WebApp(host)
    .title("My App")
    .bind("export", params -> { … })
    .bind("save", params -> { … })
    .menu(menu(
        menu("文件",
            item("export", "导出").shortcut("CmdOrCtrl+E"),
            item("save", "保存").shortcut("CmdOrCtrl+S"),
            separator(),
            platformItem("quit", "退出")),
        editMenu()))
    .run();
```

`editMenu()` is the framework's built-in Edit menu — the platform items that make ⌘C, ⌘V,
⌘X, ⌘A and ⌘Z reach the web view. It is supplied, not declared, because an application
declaring those as binding items would stop them reaching the web view at all.

**Where the builders live.** `WebApp` is a fluent builder of instance methods — `title`,
`size`, `bind`, `onReady` — and the repository's static factories live in their own
classes: `Resources.stage`, `Json.write`, `PageSource.FromAssets`. So the menu builders
follow that: a `Menus` class in the framework's root package, with `Menus.menu(...)`,
`Menus.item(...)`, `Menus.separator()`, `Menus.platformItem(...)` and `Menus.editMenu()`.
An application writes `Menus.menu(…)`, or imports them statically.

**The shape of the model.** A menu bar is a list of top-level menus, a top-level menu is a
list of items, and an item is either a binding item or a platform item. So:

```java
public final class Menus {
    public static MenuBar  menu(Menu… menus);
    public static Menu     menu(String title, Item… items);
    public static Item     item(String binding, String title);      // a binding item
    public static Item     platformItem(String action, String title); // a platform item
    public static Item     separator();
    public static Menu     editMenu();                              // the built-in one
}
```

`WebApp.menu(MenuBar)` takes the bar, and the bar is built from the same `menu(...)`
overloads — which is why the examples nest the way they do. `Item.shortcut(String)` is
the one method on an item, and it returns the item so it chains.

## How a click reaches a binding

This is the seam, and it does not exist yet. `Bridge` is package-private (Bridge.java:51)
and `UiHost.Callbacks` (UiHost.java:56-84) has no menu event, so the platform layer has no
door into a binding.

Two ways to open one:

- **A new method on `UiHost.Callbacks`** — `onMenuItem(String name)`. A public API change
  on an interface that has shipped at 1.0.0, but a small and honest one: the platform says
  "this item was chosen", the portable layer invokes the binding.
- **Synthesise a bridge frame** and push it through the existing `callbacks.onMessage(json)`.
  This reuses the protocol, but it is a fake request: the bridge correlates replies by id
  (Bridge.java:172-174) and every reply is delivered by evaluating `window.__pvResolve` in
  the page — so a menu click would allocate a request id, produce a reply the page will
  never be waiting for, and evaluate JavaScript for nothing. It would also make a menu
  click look like a page request, which is a lie.

**The first is right.** A menu click is not a page request, and the bridge is built for
requests that the page made. The framework's rule that the platform layer knows nothing
about the bridge holds either way; what changes is that `UiHost.Callbacks` grows one method,
and the portable layer maps the name to a binding.

**The new method should be a `default` one**, and there is precedent for exactly that:
`onPageUnloading()` is a default method, added because "a host that cannot detect navigation
should still be usable". A menu event is the same shape of thing — a host that has no menu
has nothing to report, and it should not have to implement the method to be usable. There is
also only one implementor of `UiHost.Callbacks` in the whole repository (`WebApp.java:350`),
so the cost is nil either way; the default method is the polite form.

**The binding is invoked the way a binding is invoked** — on a virtual thread, with the
standard deadline, and cancellable. A menu item that names a binding nobody declared is a
mistake the framework should report, not a silent no-op: the click arrives and the name
does not resolve, so the framework says so.

## A menu's identity

"Replace the built-in one" needs an identity that is not the display title: the built-in
Edit menu is titled `"Edit"` in English (Cocoa.java:62) while an application writes
`"编辑"`, so matching by title cannot work.

The built-in menus are identified by a **reserved name**, not by their title: `edit` and
`quit` are keys the framework recognises, and the title is whatever the platform's
convention is. An application that wants the built-in Edit menu asks for it by that key;
an application that wants its own menu of its own choosing uses any other key.

**Are the built-in menus macOS-only?** They exist on macOS today. On Windows there is no
menu at all (Win32.java:300-315, `lpszMenuName` is `NULL`).

**Measured on Windows 11, 2026-10-21:** the WebView2 control performs clipboard operations
itself. The test asked the page to select its text and run `document.execCommand('copy')`
inside a real user gesture, then read the clipboard back through the framework's own
`readClipboard()`:

```
[probe] execCommand('copy') returned: true
[probe] mouse events: [down@96,48 trusted=true]
[probe] selection: SENTINEL-TEXT-42
[probe] clipboard after the page copied: SENTINEL-TEXT-42
```

The page put the text on the clipboard by itself, with no framework involved. And
`document.execCommand('copy')` **is** the code path Chromium's own Ctrl+C runs — so the
control's editing commands are the browser's, and the page is already the thing that
performs them.

That is the opposite of macOS, where the Edit menu exists *because* AppKit routes the
keyboard through the responder chain, and without it ⌘C never reaches the web view
(issue #19). On Windows the browser handles it.

**So the built-in Edit menu is a macOS thing, and should stay one.** Adding it to Windows
would be a second path to a capability the page already has — and, on the shortcut side, a
mechanism that would take the key away from the page. The Windows menu bar exists for
*application* items, not for the editing commands.

**The shortcut consequence, which is the useful part.** On Windows the keyboard shortcut
for an application item has to reach the menu without taking the key away from the page.
Since the browser's own editing commands work, the framework's Windows shortcuts must not
be handled ahead of the page — which is the opposite of the `WM_KEYDOWN` route, and the
reason that route is wrong rather than merely unimplemented.

**This is measured, not inferred.** The measurement needed the browser's own copy path
rather than a keystroke, because a keystroke injected from outside never reached the page
at all — see the note in "How this is verified" below.

## How this is verified without a human

CI runs ubuntu and macos-latest and never opens a window. There is no Windows runner, so
the Windows side is the manual `run-selftest.cmd` path that now exists.

A menu test has to *click* an item, which on each platform means synthesising the click:
macOS `performKeyEquivalent:` on the item, Windows `PostMessage(WM_COMMAND, id, 0)`. That
needs a way to reach the menu from a test — either a test-only hook on the host, or the
test uses the same public API an application would.

This is a real question, not a detail: without it, a menu can only be verified by a person
looking at the screen, which is the gap that let this whole area go untested.

### Driving a real WebView2 from outside — measured

The Windows side needed this for the clipboard measurement, and the details are worth
recording because each one cost a run:

- **A keystroke injected from outside does not reach the page.** `SendInput` into the
  foreground window reaches the *window* and not the browser. The page's `keydown` list
  comes back empty, and an empty clipboard afterwards says nothing about the browser.
- **The focus is the problem.** `AttachThreadInput(myThread, targetThread, true)` and then
  `SetFocus(Chrome_WidgetWin_1)` moves it into the browser's child window. The catch:
  `GetCurrentThreadId` lives in **kernel32**, not user32 — a user32 declaration returns 0,
  `AttachThreadInput` silently fails, and the keys still go nowhere. This was the actual
  bug, and the symptom (keys arriving as `key: "Unidentified"`) looked like a browser
  problem.
- **Chromium reads the scan code.** `SendInput` with only `wVk` set arrives as
  `key: "Unidentified"`. The scan code has to be sent too, with `KEYEVENTF_SCANCODE`
  (0x08) and `KEYEVENTF_KEYUP` (0x02) — and the scan code is *not* the virtual key: X=0x58
  is scan 0x2E, A=0x41 is 0x1E, C=0x43 is 0x2E, Ctrl=0x11 is 0x1D.
- **The display runs at 200% scaling**, so page CSS pixels are half the physical ones. A
  window asked for 700×400 CSS is 350×200 physical, and the ratio of the render host's
  physical width to the page's CSS width is the conversion.
- **A click is delivered to `Chrome_RenderWidgetHostHWND`**, with `lParam` carrying the
  pixel coordinates (`x` in the low word, `y` in the high word). A `WM_MOUSEMOVE` first, so
  Chromium has a position before the button goes down. The page's own `mousedown` listener
  reports `isTrusted`, which is the check that the gesture was real — and a real gesture is
  what Chromium requires for `document.execCommand('copy')`.

**A menu test on Windows does not need any of this.** `WM_COMMAND` is a message to the
window, and posting it is exactly as simple as the click above. The hard part is the
*keyboard*, not the click.

## Decisions this document makes

These are the builder's calls, not the partner's, and each is open to being overturned:

1. **The shortcut string is parsed by the framework**, with `CmdOrCtrl` as the
   cross-platform spelling. Tauri parses in Rust; there is no platform service to delegate
   to here, so the framework owns it.
2. **A binding item references a `bind` name.** Not a callback, not a JavaScript function.
3. **There are two kinds of item** — a binding item and a platform item — and the built-in
   Edit menu is made of platform items and stays outside the binding model.
4. **`separator()` is explicit** rather than a null item, so the model has no null case.
5. **No icons, no checkable items, no radio items** in the first slice. Each is a
   platform difference with no cross-platform spelling yet.
6. **The click reaches a binding through a new `UiHost.Callbacks` method**, not by
   synthesising a bridge frame.

## Open for the partner

- **Should the first slice include Windows keyboard shortcuts?** The analysis above argues
  it should not: the mechanism that would deliver them on Windows takes the key away from
  the page's text field, which is the opposite of what macOS does — and the browser already
  handles the editing commands itself, so a framework shortcut would be a second
  implementation of something that works. A clickable menu bar is a complete, useful
  capability on its own.
- **Should the built-in Edit menu appear on Windows?** **Answered, and no**: the WebView2
  control performs clipboard operations itself, measured. The built-in Edit menu exists on
  macOS because AppKit routes the keyboard through the responder chain; on Windows the
  browser does the work. Adding it would be a second path to a capability the page has.

## What is left to the builder

- A portable menu model in the framework's public package: `Menu`, `MenuItem`, with the
  builder shape above, and the two kinds of item.
- A shortcut parser: string → a platform-neutral key + modifier set.
- macOS: build the `NSMenu` from the model; one shared runtime target class whose method
  reads the clicked item's `tag`; set `keyEquivalentModifierMask`.
- Windows: bind `CreateMenu`/`AppendMenuW`/`SetMenu`/`DestroyMenu`; answer `WM_COMMAND`;
  attach the menu before `ShowWindow`, which means `createWindow` grows a parameter (it is
  package-private, so not a public API change).
- The seam: one new method on `UiHost.Callbacks`, and the portable layer mapping the name
  to a binding.
- The built-in Edit menu, which stays macOS-only — measured, see "A menu's identity".
- **The native-image reachability metadata.** The macOS menu target is a runtime-created
  Objective-C class whose method is an FFM upcall stub, exactly like `ObjCDispatch` — so it
  needs an entry in `directUpcalls` and a matching `reflection` entry, or a native build
  fails at run time with `MissingForeignRegistrationError` rather than at build time. The
  file is hand-curated, and the schema rejects a `reason` field on an entry, so the
  explanation goes in the `comment` array at the top. Nothing in CI checks it: the check is
  a snippet in NOTES.md §17. **Adding a CI step for it would be a good thing to do alongside
  this work**, since the failure it prevents is a native build that compiles and then dies.
- Tests: a menu test that synthesises a click, on macOS in CI, and on Windows through the
  self-test that now exists.
- Documentation: the README's roadmap line, and the template READMEs.
