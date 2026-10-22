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

**A platform item** carries a *platform action* with a **nil target**, so AppKit walks the
responder chain and the first responder handles it. That is precisely how ⌘C reaches the web
view's text field today (Cocoa.java:43-48). These items have **no binding behind them**, and
they must not have one: a target that is a Java object would *stop* the action travelling
down the responder chain, and the web view would stop receiving ⌘C.

**A binding item** carries a *name* — `"export"` — and clicking it invokes the binding
declared under that name.

The two are different in kind, not in decoration, so the model has to say so:

```java
menu(
    menu("文件",
        item("export", "导出").shortcut("CmdOrCtrl+E"),   // a binding item
        separator(),
        platformItem(Platform.QUIT, "退出")),               // a platform item
    editMenu())                                            // the built-in platform items
```

**A platform item's action is a named platform operation, not a free string.** The first
version of this design wrote `platformItem("quit", "退出")`, and that is not implementable
across two platforms: `"quit"` is not a macOS selector (`terminate:` is), and it is not
anything Windows has. So the model names the *operations* the framework knows, and each
platform maps them to its own vocabulary.

The set is small, because the set of things a menu can do without a binding is small:

| Operation | macOS | Windows |
| :--- | :--- | :--- |
| `Platform.QUIT` | `terminate:` on the application | `WM_CLOSE` on the window, or the application menu's Quit |
| `Platform.UNDO` / `REDO` | `undo:` / `redo:` | the browser's own — the page handles these |
| `Platform.CUT` / `COPY` / `PASTE` / `SELECT_ALL` | `cut:` / `copy:` / `paste:` / `selectAll:` | the browser's own — the page handles these |

Which is the point of the two kinds, and it is a difference the *platforms* draw rather than
the framework: on macOS the editing commands have to be routed to the web view through the
responder chain, because that is the only way AppKit reaches it; on Windows the browser
already performs them (measured — see "A menu's identity"). So the Edit menu is macOS's,
and a `Platform.COPY` item on Windows has nothing to do.

**The built-in menus are supplied, not declared — and the app menu is one of them.**
`Cocoa.installMenuBar` builds the whole main menu: the application menu carrying
"Quit \<app\>", and the Edit menu (Cocoa.java:51-78). The application menu is not an
afterthought — AppKit expects it first, and ⌘Q lives in it.

So the model has to account for three things, and the first version of it accounted for
two:

| | what it is | how it reaches the model |
| :--- | :--- | :--- |
| the app menu | the first item of the bar, carrying Quit | `appMenu()` — supplied by the framework |
| the Edit menu | a platform menu of platform items | `editMenu()` — supplied by the framework |
| the application's own menus | binding items and platform items | `menu(...)` — declared by the application |

**And `.menu(...)` replaces the framework's bar, it does not merge with it.** The
alternative — merging — has no good answer: AppKit expects the application menu first, and
an application's first declared menu would have to go after it, which means the model would
need to say "your menus come after the app menu" and the app menu would have to be
expressible in the same list. Replacing is simpler and it is what the design does.

**Which means omitting `editMenu()` re-breaks issue #19.** That is the honest cost, and it
is why `editMenu()` is in the example rather than left to chance: an application that
declares its own bar and forgets the Edit menu gets a window whose text fields cannot be
copied from.

So the rule is: **an application that declares a bar takes responsibility for the whole
bar.** The framework supplies `appMenu()` and `editMenu()` for it to include, and a
framework that silently merged them would leave the application unable to choose.

**Both halves of the "two kinds" claim are measured.**

- **With a target, the action goes to the target** — not down the responder chain. Checked
  on macOS with a real `NSWindow`, a real `NSMenu` and a runtime-created target class:
  `with a target, the action landed on: the target`.
- **With a nil target, the action walks the chain** — checked when issue #19 was fixed,
  against a real `WKWebView` in a real window: `sendAction:` with a nil target, which is
  what a key equivalent sends, put "BBB-SENTINEL" into a textarea on ⌘V, copied
  "AAA-MARK" back to the pasteboard on ⌘A ⌘C, and moved "CUT-ME" out on ⌘X.

So the two kinds are not a distinction the framework invented. They are two different
places the action can go, and the framework has to say which one it means.

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

**Both halves of the macOS side are measured**, and the good news is that the parser's
output is directly usable there — no translation layer beyond setting two properties:

```
key equivalent reads back: 'e'
a plain character is enough: YES
modifier mask round trip: 100000 (asked for 100000)
the mask survives: YES
```

So a plain character is enough for the key equivalent — no key *codes* on this side, which
matters because `"CmdOrCtrl+E"` carries the letter E and a letter is not a key code. And
`keyEquivalentModifierMask` is an `NSInteger`, so `sendVoidLong` and `sendLong` carry it,
the same width as the tag.

**And the mask replaces AppKit's default rather than adding to it — which is a trap.**
Measured on a real `NSMenuItem`:

```
a bare key equivalent, no mask: 1048576
an explicit Command mask:      100000
an explicit empty mask:        0
```

`1048576` is `0x100000`, which is `NSEventModifierFlagCommand` — so **AppKit's default for a
bare key equivalent is already Command**, which is why the framework's own Edit menu can
pass a bare `"c"` and get ⌘C. Setting the mask explicitly to Command gives the *same*
value, not Command-and-Command, so the mask is a replacement.

The trap is on the other side of that. A declaration that names **no** modifiers must
**clear** the mask — set it to 0 — or AppKit's default Command would silently turn
`"C"` into ⌘C. And a declaration that names only Control, on macOS, means ⌃C and not ⌘C,
which is the opposite of what the same string means on Windows. That is the whole reason
`CmdOrCtrl` exists as a spelling.

So the macOS translation is: the character goes in the key equivalent, and the modifier set
goes in the mask **always** — including the empty set, which is `0`.

The Windows side is the one that needs a real translation: a letter has to become a
`VK_*` code, and the modifier set has to become `MOD_*` flags.

**The constants the translation needs**, in the naming style `Win32` already uses
(`WM_DESTROY = 0x0002`, `SW_SHOW = 5` — the `VK_` prefix and hex values):

| Key | `VK_*` | | Modifier | `MOD_*` |
| :--- | :--- | :--- | :--- | :--- |
| A–Z | `0x41`–`0x5A` | | Ctrl | `0x0002` |
| 0–9 | `0x30`–`0x39` | | Alt | `0x0001` |
| F1–F12 | `0x70`–`0x7B` | | Shift | `0x0004` |
| Delete | `0x2E` | | Win | `0x0008` |

Letters and digits are contiguous in the virtual-key table, which is why the translation
is arithmetic for them and a table for the rest. `CmdOrCtrl` becomes `MOD_CONTROL` on
Windows and `NSEventModifierFlagCommand` (1 << 20) on macOS.

**The macOS shortcut back end is not "two properties" for the whole promised key set**, and
the first version of this design said it was. The letters and digits are fine; the rest of
the table is not, and the difference is worth measuring rather than assuming.

Measured on a real `NSMenuItem`:

```
a bare 'f5' reads back: 'f5'
  its mask: 0x100000
the delete key equivalent reads back: 0xf728
```

**"f5" stays the two characters "f5"** — AppKit does not interpret it as the F5 key, so a
key equivalent of "f5" matches nothing. The F-keys need AppKit's *function key* modifier
(`NSEventModifierFlagFunction`, 1 << 23) together with the digit, and Delete needs the
special key code `0xF728` rather than any printable character.

So the first slice's key set is **letters and digits**, and the rest is either more macOS
work or out of scope:

| Key | macOS key equivalent | |
| :--- | :--- | :--- |
| A–Z | the letter | as measured |
| 0–9 | the digit | as measured |
| F1–F12 | the digit, with `NSEventModifierFlagFunction` | **not the first slice** |
| Delete | `0xF728` | **not the first slice** |

Windows has no such split — `VK_F1` is `0x70` and `VK_DELETE` is `0x2E`, ordinary virtual
key codes. So the *parser* can accept the whole table, and the *macOS back end* is what
cannot yet deliver the F-keys and Delete. The design should say which is which, because a
parser that accepts "F5" and a macOS translation that renders it as "f5" is a shortcut that
silently does nothing.

**And the `VK_*` and `MOD_*` constants are only needed if the parser translates to them
eagerly.** With Windows keyboard shortcuts out of scope, a parser that produces a
platform-neutral key and modifier set, and a platform layer that maps them, means the
constants are needed on Windows only when the shortcuts arrive — which is a separate piece
of work.


## How the menu reaches the platform

This is the delivery path, and the first version of this design did not have one at all —
it described the model and the two platform implementations without saying how the model
got to them. Without this there is nothing to build.

**It does not go in `WindowSpec`.** `WindowSpec` is a published public record
(WindowSpec.java:20), and adding a component to a record changes its canonical
constructor — which breaks every consumer that constructs one, at a version that has
shipped. `WindowSpec` is also the wrong shape for it: a record of plain values, where the
menu is a tree of objects.

**It does not go on `UiHost` either**, for the same reason: `UiHost` is a published public
interface, and a new abstract method breaks every implementation outside this repository.
A `default` method would not break them, but a default method that does nothing is not a
delivery path.

**So the menu is a construction-time argument, handed over by the application.** The way
to give it to a host without changing the host's published shape is a separate optional
collaborator — not a method on `UiHost` at all, but a small interface the framework defines
and the host *may* implement:

```java
public interface MenuHost {
    void setMenu(MenuBar bar);
}
```

A host that does not implement it simply has no menu, and the framework's rule about
optional behaviour already has a precedent: `UiHost.Callbacks.onPageUnloading()` is a
default method, added because "a host that cannot detect navigation should still be
usable". `MenuHost` is the same idea, one level out: a host that cannot take a menu is
still usable.

The application writes it once, and the two hosts implement it:

```java
new WebApp(new MacOsHost())
    .menu(bar)          // the app hands the bar to the host, if the host takes one
    .run();
```

`WebApp.menu(...)` stores the bar and, at `run()` time, hands it to the host if the host
implements `MenuHost`. One place for the application to write it, and no change to either
published type's shape.

**The bar is built at run time, not at `.menu(...)` time**, because the ids are assigned
while the menu is assembled — and the same walk that creates the items assigns the ids.
So `WebApp` holds the model, and the platform builds it.

## The two platform implementations


### macOS

**The structure works, measured.** The model is a bar of top-level menus, each a list of
items with separators among them, and AppKit's own shape holds it — the bar is an `NSMenu`
whose items are menu items carrying a submenu, and each submenu holds items and
separators:

```
menu title: 文件
item title: 导出, tag: 7
the bar holds 1 top-level menu(s)
the 文件 menu holds 2 item(s), separator included
```

A separator is a menu item — `+[NSMenuItem separatorItem]`, a class-method singleton
(Cocoa.java:90) — so it needs no special case in the model beyond not having a title.

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

**And the target must be kept alive by Java, because AppKit will not.** `NSMenuItem.target`
is a **weak** reference, so a target that Java's garbage collector collects becomes nil —
and a nil target is exactly the *platform item* behaviour. The failure is silent and it is
the worst kind: a binding item quietly stops being a binding item and starts walking the
responder chain, which is the confusion this whole design exists to prevent.

The framework already knows this rule. NOTES.md §3 records it — "the delegate is a *weak*
reference, and a garbage-collected delegate is a dangling pointer" — and `ObjCDispatch` obeys
it by keeping its instance in a **static field** (ObjCDispatch.java:45). The menu target
needs the same: a static field holding the instance, for the life of the process.

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

The upcall signature for that method is `-action:(id)sender` — receiver, selector, one
object argument — which is **three pointers**, and the encoding is `v@:@`.

**The descriptor is `ObjC.VOID1`, not `VOID2`,** and the first version of this design said
`VOID2` and would have been wrong in a way that would not have shown up as a compile error.
The `ObjC` layer's own numbering counts the receiver and the selector:

| | descriptor | pointers | |
| :--- | :--- | :---: | :--- |
| `ObjC.VOID0` | `ofVoid(ADDRESS×2)` | 2 | receiver, selector |
| `ObjC.VOID1` | `ofVoid(ADDRESS×3)` | 3 | **+ one object** — this one |
| `ObjC.VOID2` | `ofVoid(ADDRESS×4)` | 4 | + two objects |

`ObjCDispatch` uses the same rule from the other end: its `userContentController:didReceiveScriptMessage:`
takes two objects, so its descriptor is four pointers, `v@:@@` (ObjCDispatch.java:37-38,
56). A four-pointer stub called with three pushes reads a garbage argument — and on a
64-bit ABI the garbage is a pointer, so the failure is a wild read rather than an error.

`ObjC.VOID1` is private, so the menu target needs it to become visible — one line, and
not a new `FunctionDescriptor`.

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
hmenu)` afterwards.

**The first is the one to use, and it is already there.** `CreateWindowExW` takes twelve
parameters, and the tenth is `hMenu`:

```c
HWND CreateWindowExW(DWORD dwExStyle, LPCWSTR lpClassName, LPCWSTR lpWindowName,
                     DWORD dwStyle, int X, int Y, int nWidth, int nHeight,
                     HWND hWndParent, HMENU hMenu, HINSTANCE hInstance, LPVOID lpParam);
```

The binding already has all twelve (Win32.java:117-120), and the call already passes
`MemorySegment.NULL` in the `hMenu` slot (Win32.java:310-315). So the menu goes in
there, and nothing has to be reordered around `ShowWindow`.

That matters because the alternative is worse: `SetMenu` afterwards would land on an
already-visible window, because `Win32.createWindow` calls `ShowWindow` internally
(Win32.java:322). The window would flash up without its menu and then acquire one, which
is a visible artifact for something the user sees on every launch. Passing the menu at
creation time is both less code and the only version that does not flicker.

**`createWindow` grows a parameter** for the `HMENU` — it is a package-private method, so
this is not a public API change. A null `HMENU` means "no menu", which is the current
behaviour, so the callers that do not want a menu pass null and nothing changes for them.

**How a top-level menu joins the bar** — and this is where the two platforms differ in
shape, though not in what the model says.

**On macOS**, the bar is an `NSMenu` and its items are menu items; a top-level menu is
attached through a **menu item that holds it as its submenu**. Measured:

```
menu title: 文件
item title: 导出, tag: 7
the bar holds 1 top-level menu(s)
the 文件 menu holds 2 item(s), separator included
```

The bar's item is the one that carries the title the user sees; the submenu it holds is
where the items live. The existing `Cocoa.installMenuBar` does exactly this (Cocoa.java:54-63).

**On Windows**, the bar is an `HMENU` and a top-level menu is an item with `MF_POPUP`,
whose `uIDNewItem` is the submenu's handle rather than a command id. Measured:

```
AppendMenuW with MF_POPUP: True
the bar holds 1 item(s)
GetSubMenu returns the menu we passed: True
the submenu's item id: 5
the bar item's own id (a popup has none): 4294967295
```

So the same model — a bar of menus — needs a wrapper item on macOS and a flag on Windows.
That is a platform difference in the *implementation*, and the model says nothing about it:
`MenuBar` is a list of `Menu`s on both sides.

**Where the click arrives.** The window procedure already receives
`(hwnd, message, wparam, lparam)` through its upcall stub, and `wparam` is a `long` — which
is where `WM_COMMAND` carries the command id, in its low word. So the click needs **no new
binding**: the case reads the id out of `wparam` and looks it up.

`WM_COMMAND` is `0x0111`, and the documented breakdown of `wParam` by source is:

| Source | `wParam` high word | `wParam` low word | `lParam` |
| :--- | :--- | :--- | :--- |
| Menu | 0 | the menu identifier | 0 |
| Accelerator | 1 | the accelerator identifier | 0 |
| Control | the notification code | the control identifier | the control's handle |

So for a menu the id is the low word and the high word is zero — the framework can read
`wparam & 0xFFFF` and be done.

**But the guard belongs on the full `wParam`, not on the masked value.** Two reasons, and
the first is a bug this design would otherwise have shipped:

- A popup's `wParam` is `0xFFFFFFFF`, and `0xFFFFFFFF & 0xFFFF` is **65535** — not 0. So a
  handler that masked first and then checked for 0 would not catch it; it would look up the
  binding numbered 65535. The check has to be against the whole value.
- **The high word says where the message came from**, and only 0 means a menu. An
  accelerator is 1, and a control notification is whatever the control defines. Ignoring
  the high word lets a control whose identifier collides with a menu item's fire that
  item's binding — and the WebView2 child is full of controls.

So the handler is: take the whole `wParam`; if the high word is not 0, it is not a menu
click and is not ours; if the low word is 0 or 0xFFFF, it is a separator or a popup and
there is nothing to invoke; otherwise the low word is the id, looked up in the
framework's table.

**One trap in that table.** A separator sends a `WM_COMMAND` with the low word set to **zero**
— the system does that when the user selects a separator. So an id of 0 must not be looked
up as a binding; it means "a separator was clicked", and there is nothing to invoke.

And the separator's id *is* zero, confirmed against the real Win32 on a Windows 11 box — a
menu built with `AppendMenuW`, read back with `GetMenuItemID`:

```
append results: True True True
item count: 3
  position 0: id=1
  position 1: id=0
  position 2: id=2
```

Position 1 is the separator, and its id is 0. So the rule is not merely a precaution: a
separator really does arrive as id 0, and a framework that started its ids at 0 would find
its first binding unreachable by a click.

**Which fixes the id allocation.** The framework hands out the command ids, and it starts
them at 1 — because 0 is what a separator reports. And it must treat `0xFFFFFFFF` as "not a
command id" too, since that is what a popup item reports. In practice neither arrives at the
`WM_COMMAND` handler as a click on an item that has a binding: a separator is not
clickable in the sense that matters, and a popup opens a menu rather than firing an
action. But the handler should not look either up, and the guard is worth writing down
rather than leaving to the reader.

**Where the id-to-name mapping lives, and why it differs by platform.** macOS identifies an
item by its **name** — the binding's name is the item's identity, and the `tag` carries it
(the framework's own items are identified by their *action selector*, `copy:`, `paste:`). So
on macOS the click arrives as a name and the lookup is a map from name to binding.

Windows identifies an item by an **integer**, so the framework has to keep the map the
other way round: from the id it handed out to the name it stands for. That map is the
framework's, and it is built while the menu is assembled — the same walk that creates the
menu items assigns the ids.

So the portable layer owns a **name → id** table, and each platform reads it in its own
direction: macOS puts the name in the item's `tag`, Windows puts the id in `uIDNewItem`.
The table is the only thing that has to agree across the two.


And the case is genuinely missing today, which is the whole problem: `WindowsHost.onWindowMessage`
switches on the message, and `WM_COMMAND` falls into the `default` branch, which forwards to
`DefWindowProcW` (WindowsHost.java:181). A window with no menu has nothing for
`DefWindowProcW` to do with a command id, so the click would be **silently dropped** — the
same shape of failure as the live-reload bug, and the reason the test for it has to assert
on the effect rather than on the absence of an exception.

**What the Windows side does not have yet, and this needs:**

- **`WM_COMMAND` handling.** `WindowsHost.onWindowMessage` has no case for it, and a message
  it does not answer falls through to `DefWindowProcW` — which would swallow the click.
- **`CreateMenu` / `AppendMenuW` / `DestroyMenu`.** None are bound. `SetMenu` is not needed
  at all — the menu goes in `CreateWindowExW`'s `hMenu` parameter, which the binding
  already has.

**Their signatures**, so the implementation does not have to look them up:

```c
HMENU  CreateMenu(void);
BOOL   AppendMenuW(HMENU hMenu, UINT uFlags, UINT_PTR uIDNewItem, LPCWSTR lpNewItem);
BOOL   DestroyMenu(HMENU hMenu);
```

**All three arities checked against the real Win32**, because a wrong arity is a wrong
binding and the extra argument is read off the stack rather than refused. `CreateMenu` takes
none, `AppendMenuW` takes four, `DestroyMenu` takes one — called on a Windows 11 box, and
the item landed with the id it was given.

`AppendMenuW` is the awkward one: four parameters, of which the *string* is UTF-16 and
`uIDNewItem` is a `UINT_PTR` — a pointer-sized integer, so `ADDRESS` in FFM terms, not
`JAVA_INT`. That one parameter carries two different things: the **command id** for an
ordinary item, and the **submenu handle** when `MF_POPUP` is set. The `MF_STRING` flag
makes the item's text come from `lpNewItem`; `MF_SEPARATOR` makes it a separator and
ignores the text entirely.

The submenu case, checked on a Windows 11 box:

```
AppendMenuW with MF_POPUP: True
the bar holds 1 item(s)
GetSubMenu returns the menu we passed: True
the submenu's item id: 5
the bar item's own id (a popup has none): 4294967295
```

That last line is worth having. **A popup item has no command id of its own** — the system
reports `0xFFFFFFFF`, which is `-1` read as a signed int. So the `WM_COMMAND` handler must
not look a popup's id up as a binding, and must not read it as a small number either:
`0xFFFFFFFF` and `0` are two different "not a command id" answers, and a framework that
only guarded against zero would eventually look up 65535.

The flag values, from the documented API:

| Flag | Value | |
| :--- | :--- | :--- |
| `MF_STRING` | `0x00000000` | the text is a string — **and it is the absence of a flag, not a bit** |
| `MF_GRAYED` | `0x00000001` | |
| `MF_DISABLED` | `0x00000002` | |
| `MF_CHECKED` | `0x00000008` | |
| `MF_POPUP` | `0x00000010` | `uIDNewItem` is then an `HMENU`, not a command id |
| `MF_SEPARATOR` | `0x00000800` | the text and the id are both ignored |

`MF_STRING` being zero is the one that catches people: it is not a bit to set, it is the
absence of `MF_BITMAP` and `MF_OWNERDRAW`. Passing it changes nothing, and passing
`MF_SEPARATOR` alone is enough for a separator.

Strings follow the pattern `Win32` already uses for window titles: `allocateFrom(text,
StandardCharsets.UTF_16LE)`, with a null terminator — `allocateFrom` adds one. Measured,
because a menu label that comes out as mojibake is not a thing to find out about in a
Windows installer:

```
code units: 导出
round trip exact: true
byte size: 6 for 2 chars + terminator
```
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

**What that costs, stated plainly.** On Windows, in the first slice, a menu item is
**clickable but not reachable by keyboard**: the menu bar opens on click, the item works on
click, and there is no Ctrl+E. A Windows user gets the menu structure and the actions; they
do not get the shortcut. On macOS the same declaration gives both, because the key
equivalent rides on the item itself.

The reason this is worth shipping in that order rather than waiting is that the clickable
menu is the whole capability, and the shortcut is an extra. But the cost is real, and an
application that needs a keyboard shortcut on Windows has to wait for the second piece.

## What the application writes

```java
new WebApp(host)
    .title("My App")
    .bind("export", params -> { … })
    .bind("save", params -> { … })
    .menu(menu(
        appMenu(),                                         // the built-in app menu: Quit
        menu("文件",
            item("export", "导出").shortcut("CmdOrCtrl+E"),
            item("save", "保存").shortcut("CmdOrCtrl+S"),
            separator()),
        editMenu()))                                        // the built-in Edit menu
    .run();
```

**`appMenu()` and `editMenu()` are supplied, not declared**, and both have to be here.

- `appMenu()` is the application menu — AppKit expects it first, and it is where ⌘Q lives.
  Leaving it out loses ⌘Q.
- `editMenu()` is the platform items that make ⌘C, ⌘V, ⌘X, ⌘A and ⌘Z reach the web view.
  Leaving it out re-breaks issue #19, and the cost is invisible: the window opens, and every
  text field in it silently becomes type-only.

**And `.menu(...)` replaces the framework's bar rather than merging with it.** So an
application that declares a bar takes responsibility for the whole bar, and the framework
does not quietly add the built-in menus back — because an application that wants the Edit
menu in a different place, or not at all, has to be able to say so.

That is the deal: two calls to write, and the framework's defaults are one line away.

**Where the builders live.** `WebApp` is a fluent builder of instance methods — `title`,
`size`, `bind`, `onReady` — and the repository's static factories live in their own
classes: `Resources.stage`, `Json.write`, `PageSource.FromAssets`. So the menu builders
follow that: a `Menus` class in the framework's root package, with `Menus.menu(...)`,
`Menus.item(...)`, `Menus.separator()`, `Menus.platformItem(...)`, `Menus.appMenu()` and
`Menus.editMenu()`. An application writes `Menus.menu(…)`, or imports them statically.

**The shape of the model.** A menu bar is a list of top-level menus, a top-level menu is a
list of items, and an item is either a binding item or a platform item. So:

```java
public final class Menus {
    public static MenuBar  menu(Menu… menus);
    public static Menu     menu(String title, Item… items);
    public static Item     item(String binding, String title);          // a binding item
    public static Item     platformItem(Platform action, String title);  // a platform item
    public static Item     separator();
    public static Menu     appMenu();                                    // the built-in app menu
    public static Menu     editMenu();                                   // the built-in Edit menu
}

/** The platform operations a menu item can carry without a binding behind it. */
public enum Platform { QUIT, UNDO, REDO, CUT, COPY, PASTE, SELECT_ALL }
```

`WebApp.menu(MenuBar)` takes the bar, and the bar is built from the same `menu(...)`
overloads — which is why the examples nest the way they do. `Item.shortcut(String)` is
the one method on an item, and it returns the item so it chains.

**The public names are `MenuBar`, `Menu` and `Item`** — one word each, and the same on
both platforms. `NSMenu` and `HMENU` are the platforms' names for the same thing, and
`NSMenuItem` is one of them; a portable model that borrowed either platform's vocabulary
would be tied to that platform's shape. The first version of this design used both
`MenuBar`/`Menu`/`Item` and `Menu`/`MenuItem` in different places, which is the kind of
drift that makes a public API confusing.

**The two `menu` overloads are compiled, not just written down.** They differ only in
their first argument — a list of menus against a title and a list of items — which is
exactly the pair that turns into an ambiguity error, or into a call that binds to the
wrong overload. The revised example, nested and with the built-in menus in it, compiles
and runs:

```
the bar holds 3 menu(s)
  MyApp: 1 item(s)
  文件: 3 item(s)
  Edit: 8 item(s)
```

The Edit menu has **eight** entries because the menu it replaces has eight
(Cocoa.java:67-75 — a separator after Redo *and* one after Paste). The count was an
observation of the model's shape, never a design decision, and reproducing the shipped menu
is what keeps a user's Edit menu looking the same after the change.

So the nesting in the examples is necessary rather than decorative, and it is the same
shape Tauri's `MenuBuilder` and `SubmenuBuilder` have, which is where the model came from.

## How a click reaches a binding

This is the seam, and it does not exist yet. `Bridge` is package-private (Bridge.java:51)
and `UiHost.Callbacks` (UiHost.java:56-84) has no menu event, so the platform layer has no
door into a binding.

Two ways to open one:

- **A new method on `UiHost.Callbacks`** — the platform says "this item was chosen", the
  portable layer invokes the binding.
- **Synthesise a bridge frame** and push it through the existing `callbacks.onMessage(json)`.
  This reuses the protocol, but it is a fake request: the bridge correlates replies by id
  (Bridge.java:172-174) and every reply is delivered by evaluating `window.__pvResolve` in
  the page — so a menu click would allocate a request id, produce a reply the page will
  never be waiting for, and evaluate JavaScript for nothing. It would also make a menu
  click look like a page request, which is a lie.

**The first is right.** A menu click is not a page request, and the bridge is built for
requests that the page made. The framework's rule that the platform layer knows nothing
about the bridge holds either way.

**The seam carries an integer, not a name.** This matters, and the first version of this
design had it backwards. Both platforms identify an item by an integer: macOS by the
`tag` the framework puts on the item, Windows by the command id. The *name* is the
framework's own bookkeeping — the portable layer's `name → id` table.

So the callback is `onMenuItem(int id)`, and the portable layer maps the id back to the
name and then to the binding. The alternative — a `String name` on the seam — would mean
the platform layer had to know the portable names, which is the seam's job done backwards.

**The new method should be a `default` one**, and there is precedent for exactly that:
`onPageUnloading()` is a default method, added because "a host that cannot detect navigation
should still be usable". A menu event is the same shape of thing — a host that has no menu
has nothing to report, and it should not have to implement the method to be usable. There is
also only one implementor of `UiHost.Callbacks` in the whole repository (`WebApp.java:350`),
so the cost is nil either way; the default method is the polite form.

**And the compatibility is checked, not assumed.** An implementation of `UiHost.Callbacks`
written the way an outside consumer would write one — without every method — compiles
today, because `onPageUnloading()` already has a default. Adding another default does not
change that. A consumer's `UiHost` implementation is unaffected either way, since the
change is to the nested interface rather than to `UiHost` itself.

**Where the mapping happens.** `Bridge` holds the bindings in a private map
(Bridge.java:131-133) and is package-private itself (Bridge.java:51), so the platform layer
cannot reach a binding — which is the point of the seam, not an obstacle. `WebApp` owns the
`Bridge` and is the only implementor of `UiHost.Callbacks` (WebApp.java:350), so the
callback lands there, and `WebApp` is in the same package as `Bridge` and can ask it to
invoke the named binding.

**Which is the same path a page call takes**, and that is deliberate: a menu item is a
second surface of a binding, so it should be dispatched the same way — on a virtual thread,
with the standard deadline, cancellable. The only thing that differs is who asked.

**What a menu binding receives.** The parameters are the ones the click carries, and a
click carries none, so a menu binding is called with an **empty map** — the same `params`
a page call would get if it passed nothing. A binding that needs an argument reads it
from the application's own state, which is the point of the binding being the
application's method.

**What a menu binding's return value does: nothing.** A page call's result goes back to
the page, which is waiting for it. A menu click has no page waiting, so a returned value
has nowhere to go and is discarded. That is worth saying, because "the menu item calls my
method and gets an answer" is a reasonable thing to expect and it does not happen.

**A throwing menu binding** is reported the way a throwing page binding is: `Bridge.fail`,
to stderr, with the `[panamaview]` prefix. There is no promise to reject.

**A menu item may not name a `bindUi` binding.** `bindUi` handlers run inline on the UI
thread with no deadline (WebApp.java:144-147), and a menu click arrives on the UI thread
already — so the item would run there, blocking the window, with no watchdog. The
framework should refuse it: a menu item that names a UI-thread binding is a mistake, and
the framework should say so rather than run it.

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

A menu test has to *click* an item, which on each platform means synthesising the click.

**On macOS the method is `-[NSMenu performActionForItemAtIndex:]`** — and that is a method
of `NSMenu`, taking the item's position, not a method of `NSMenuItem`. The first version of
this design said "performKeyEquivalent: on the item", which is wrong twice over:
`performKeyEquivalent:` belongs to `NSMenu` too, and it takes an `NSEvent`, not an item.
`NSMenuItem` has no such method at all. (This is the same class of mistake as
`NSMenuItem`'s missing `performClick`.) The call that works is the one measured above:

```java
ObjC.sendVoidLong(menu, ObjC.selector("performActionForItemAtIndex:"), 0L);
```

**On Windows it is `PostMessage(WM_COMMAND, id, 0)`** to the window — which needs no
window to exist, since it is a message and not a call.

**And the macOS test cannot run in CI**, which is the constraint that shapes the testing
plan: CI has no window server, and AppKit cannot be touched at all without one — creating
an `NSWindow` off the first thread raises `NSInternalInconsistencyException`, and the
framework's own probe needed `-XstartOnFirstThread` to get as far as creating a menu.

So the macOS test in CI covers **the model and the parser** — that a declaration produces
the right model, that the shortcut string parses to the right key and modifier set, that
the ids are allocated from 1 — and **not** the AppKit side, which is verified the way this
document verified everything else: by running it, on a machine with a desktop.

That split is not a compromise; it is the same shape as the existing verification. The
framework's macOS behaviour is checked by the demo application (13 checks in a real
window), and the Windows behaviour by `WindowsSelfTest`. A menu test belongs in both
places, and the CI job covers the half that CI can run.

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
   menus are made of platform items and stay outside the binding model.
4. **A platform item names a `Platform` operation**, not a free string, because the two
   platforms have no common vocabulary for one.
5. **`separator()` is explicit** rather than a null item, so the model has no null case.
6. **No icons, no checkable items, no radio items** in the first slice. Each is a
   platform difference with no cross-platform spelling yet.
7. **The click reaches a binding through a new `UiHost.Callbacks` method**, not by
   synthesising a bridge frame — and that method carries an **int**, because both platforms
   identify an item by an integer and the name is the framework's own bookkeeping.
8. **The menu reaches the host through a separate optional `MenuHost` interface**, not
   through `UiHost` or `WindowSpec`, because both of those are published and adding to
   either breaks consumers.
9. **`.menu(...)` replaces the framework's bar**, so an application that declares a bar
   takes responsibility for the whole bar, built-in menus included.
10. **The first slice's key set is letters and digits.** The F-keys and Delete need macOS
    work AppKit does not do for free, and a shortcut that silently does nothing is worse
    than one that is not offered.

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
- Windows: bind `CreateMenu`/`AppendMenuW`/`DestroyMenu`; answer `WM_COMMAND`; pass the
  `HMENU` through the `hMenu` parameter `CreateWindowExW` already has, which means
  `createWindow` grows a parameter (it is package-private, so not a public API change) and
  nothing has to be reordered around `ShowWindow`.
- The seam: one new method on `UiHost.Callbacks`, and the portable layer mapping the id
  back to a name and then to a binding.
- The built-in Edit menu, which stays macOS-only — measured, see "A menu's identity".
- **The native-image reachability metadata, which is already missing two shapes the shipped
  code uses.** This is not only a menu problem, and it is worth fixing whatever else
  happens.

  The macOS menu target is a runtime-created Objective-C class whose method is an FFM
  upcall stub, exactly like `ObjCDispatch` — so it needs an entry in `directUpcalls` and a
  matching `reflection` entry, or a native build fails at run time with
  `MissingForeignRegistrationError` rather than at build time.

  But diffing the 28 registered downcalls against the descriptors `ObjC` actually uses
  shows two **already missing**:

  | shape | used by |
  | :--- | :--- |
  | `void*(void*,void*)` | `ObjC.send(receiver, selector)` — `ID0` (ObjC.java:264). `+[NSMenuItem separatorItem]` is exactly this (Cocoa.java:91), and so is `alloc` |
  | `jbyte(void*,void*,void*)` | `ObjC.sendBool(receiver, selector, object)` — `BOOL_OBJ` (ObjC.java:274). `setFrameUsingName:` is exactly this (Cocoa.java:158) |

  Both are missing. Both are reachable from the **existing** code, not just the menu.

  They have never been noticed because **every native CI run is headless**:
  `verify-scaffold.sh` runs each template with `-Dpanamaview.check=true`, which stages
  the assets and exits without opening a window. So the native build never reaches the
  code that uses them, and the missing registrations are invisible.

  That is exactly the shape of failure the menu work would hit: a native build that opens
  a window — the natural way to validate a menu — dies with
  `MissingForeignRegistrationError` before reaching any menu code. **Fixing the metadata
  is a prerequisite for validating the menu in a native build, not a menu feature.**

  The file is hand-curated, and the schema rejects a `reason` field on an entry, so the
  explanation goes in the `comment` array at the top. Nothing in CI checks it: the check
  is a snippet in NOTES.md §17, and it checks `directUpcalls` only — never `reflection`,
  never the downcall shapes. **Adding a CI step for it is worth doing alongside this
  work**, since the gap is what let the file drift in the first place.
- Tests: a menu test that synthesises a click, on macOS in CI, and on Windows through the
  self-test that now exists.
- Documentation: the README's roadmap line, and the template READMEs.
