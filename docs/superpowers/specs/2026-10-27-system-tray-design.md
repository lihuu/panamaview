# System tray: design

**Date:** 2026-10-27
**Status:** approved direction; the portable slice and both host slices are implemented (macOS landed 2026-10-31, Windows 2026-11-01). One correction the macOS slice recorded: the default icon is built with the class method `imageWithSystemSymbolName:accessibilityDescription:` — the instance init this section never named does not exist on the runtime, and the selector is the whole difference between a tray icon and an abort. The Windows slice's one open item is its own: the interactive check a session-0 runner cannot perform — a person seeing the notification-area icon and choosing from its popup — is what finishes the feature and moves the README line.

## Intent

An application that lives in the tray has no other way to be reached: no window when it starts,
one icon when it is put away, and a menu behind that icon. Today panamaview can open a window and
run a web view, and an application that closes its window is gone. The tray is the capability that
turns "a window that happens to be native" into "an application that stays".

It is also the smaller half of two roadmap items that share a question. Tray and multi-window both
need the framework to stop assuming that a process is its one window; the tray asks that question
in its simplest form, so it goes first.

## What "system tray" means on each platform

The name hides three different surfaces, and the first thing this document has to do is separate
them, because panamaview already ships one of them:

- **macOS, the menu bar** — `NSMainMenu`, the strip at the top of the screen. This is the
  application menu bar, and it is already implemented (`Cocoa.installMenuBar`).
- **macOS, the status area** — an `NSStatusItem`, a small item at the right end of that same
  strip. This is what "tray" means on macOS. Despite sharing a strip with the application menu, it
  is a different object, a different API, and appears even for an application with no window.
- **Windows, the window menu** — the `HMENU` attached to a window. Already implemented
  (`WinMenu`), and it disappears with the window.
- **Windows, the notification area** — a `Shell_NotifyIcon` entry at the right end of the taskbar.
  This is what "tray" means on Windows.

So the tray is a **third surface**, not a second spelling of the menu bar. It can still reuse the
menu model, and that reuse is most of the design.

**Linux is deliberately out of scope.** There is no Linux host yet, and the tray is a separate
roadmap item from it. Because `TrayHost` is an optional interface, a host that cannot show a tray
— or does not exist yet — still runs the application: the same rule that lets `MenuHost` be
optional.

## What the framework already has, and will reuse

- **The menu model.** `Menu`/`Item`/`MenuBar` already express titles, bindings, platform
  operations and separators. A tray menu is a list of items; it needs no new item kinds.
- **The id → binding table.** `MenuBar` numbers its items and records which id names which
  binding. The same walk can number a tray's items.
- **Click dispatch.** On macOS every binding item is handled by one runtime-created target,
  `MenuTarget`, which reads the framework id from `NSMenuItem.tag`. An `NSStatusItem`'s menu is
  an ordinary `NSMenu`, so the target works unchanged and needs no new upcall.
- **The Windows plan/build split.** `WinMenu.plan` is pure and is tested on Linux; `build` is the
  only part that touches `user32`. A tray popup can follow the same split.
- **The optional-interface pattern.** `MenuHost`, `DialogHost` and the default methods on
  `UiHost.Callbacks` are the established way to add capability without breaking implementations
  outside this repository. `TrayHost` is one more.

## Goals

- An application can declare one tray icon: a tooltip, an optional icon, and a flat menu of items.
- A tray menu item is a **binding item** — the same `bind` mechanism the page and the menu bar
  already use — or a platform item.
- The same declaration works on macOS and Windows.
- Declaring a tray changes the lifecycle default: closing the last window no longer ends the
  process, because that would take the icon with it.
- A host that cannot show a tray still runs the application.

## Non-goals

- **Linux.** A separate roadmap item, and one that should decide its own tray story
  (`StatusNotifierItem` or a fallback) when it has a host at all.
- **Multiple tray icons.** One is the capability being asked for. The API should not make a second
  one impossible, but it does not model a list yet.
- **Animated icons, badges, attention states.** Each is a platform-specific surface with its own
  rules; none is needed for the first capability.
- **Balloon or notification-area messages.** Notifications are a separate feature, already
  implemented on macOS.
- **Submenus, checkable items, radio items, icons inside menu items.** The same non-goals the
  application-menu design records, for the same reason.
- **A tray without a window.** The first slice is "window + tray, window can be put away". A
  process with no window at all is a different lifecycle question and is left with multi-window.
- **Context menus on the window itself.** A right-click menu over the page is a different surface
  on both platforms.

## The model

```java
// The host side: optional, like MenuHost, and called once before start().
public interface TrayHost {
    void setTray(TraySpec tray);
}

// What the application declares.
public final class TraySpec {
    public TraySpec(String tooltip, List<Item> items);
    public TraySpec(String tooltip, List<Item> items, Path icon);
    public String tooltip();
    public List<Item> items();
    public Path icon();          // null: the framework's own icon
}
```

Three decisions are in that shape.

**A `TraySpec` is not a `MenuBar`.** A tray popup has no title, and a `Menu` requires one; making
every application write a placeholder is a wart. It is a flat list of items, because a tray menu
has no top-level menus.

**The tray has its own id namespace.** `MenuBar` numbers its items from 1, and a tray menu
numbered the same way would collide with the bar's ids. The two surfaces therefore have two
tables, and a click arrives on a second callback:

```java
// UiHost.Callbacks, a default method like onMenuItem
default void onTrayMenuItem(int id) {}
```

The alternative — one shared namespace, with the host translating tray ids into bar ids — was
rejected: it would force every tray item to exist in the bar as well, which is exactly backwards
for the common case ("Quit" belongs in the tray, not necessarily in the window menu).

**An `Item` belongs to exactly one surface.** `Item` carries its id, so the second surface to
number a shared item would overwrite the first surface's id and the first surface's table would
then disagree with the item it holds. This is the same constraint `MenuBar` documents for an item
shared between two bars, and it is documented here rather than enforced: the check would have to
be a global registry of every item ever numbered, and the existing model chose a written rule over
that cost.

## Lifecycle: a window is not the process

Two places currently hard-code the assumption that it is:

- `WebApp.run()` answers `shouldQuitWhenLastWindowCloses()` with `true`.
- The Windows host turns `WM_DESTROY` into `PostQuitMessage`.

The portable half is fixed in this slice:

- Declaring a tray and having the host accept it flips the default to `false`.
- `WebApp.quitWhenLastWindowCloses(boolean)` overrides it in either direction.
- A tray that the host **did not accept** does not flip the default. An application with no window
  and no icon is a background process nobody can reach; keeping "quit on close" is the honest
  behaviour when the icon is not there.

The platform half — closing the window *hides* it instead of destroying it — lands with each
host. Until then, "do not quit" means the process survives with the window closed, which is
already useful and already correct on both platforms: macOS keeps a closed window object
(`setReleasedWhenClosed:false`) and can order it back, and Windows keeps pumping messages.

Two platform operations are needed for a tray menu to be useful — show the window, hide the
window — and they are **not** added to `Platform` in this slice. `Platform` is a published enum:
adding a constant breaks every consumer's exhaustive switch, so a constant should arrive with a
host that can honour it. Shipping `SHOW_WINDOW` before either host can route it would put a
dead item in the model and a compile break in every application for no capability.

## macOS

- `[NSStatusBar systemStatusBar] statusItemWithLength:NSVariableStatusItemLength]`.
- The item must be retained and held in a static field, for the same reason `MenuTarget` is:
  an unreferenced status item is released and its icon disappears.
- `button.image` with a **template** image, so the menu bar tints it for light and dark
  appearances; `button.toolTip`; `statusItem.menu` set to the built `NSMenu`, which makes a left
  click open it with no action target.
- The default icon is an SF Symbol (`imageWithSystemSymbolName:accessibilityDescription:`), so an
  application without its own artwork still gets a legible icon. A `Path` overrides it.
- A menu-bar application normally runs with `NSApplicationActivationPolicyAccessory` (no Dock
  icon). That is a visible change to an existing application, so it is a decision, not a default
  this slice can make — see "Open".
- New downcall shapes must be registered in `reachability-metadata.json`; a native build that
  omits them compiles and dies on the first status item with `MissingForeignRegistrationError`.
  This is the failure #31 and #34 already taught.

## Windows

- `shell32.dll` is a new lookup. `Shell_NotifyIconW` takes `NIM_ADD`, `NIM_MODIFY` and
  `NIM_DELETE`.
- `NOTIFYICONDATAW` is 976 bytes on x64, with `szTip` at offset 40 and `szInfoTitle` at 820. The
  layout is the part a typo breaks silently — the icon appears with the wrong tip, or not at all
  — so the offsets are asserted, the way `WinMenu.verify` asserts what a built menu carries.
- The icon needs a window to report to. **Not a message-only window** (`HWND_MESSAGE`): those do
  not receive broadcast messages, and `TaskbarCreated` — the message Explorer sends when it
  restarts — is a broadcast. A hidden, ordinary top-level window is the right host for it.
- The callback message uses a `WM_APP + n` value distinct from `WM_APP_RUNNABLE` (`0x8000`).
- A right click is `SetForegroundWindow`, `GetCursorPos`, then
  `TrackPopupMenu(TPM_RIGHTBUTTON | TPM_RETURNCMD)`. The returned command id is the click; it
  needs no `WM_COMMAND` round trip. A `PostMessage(WM_NULL)` afterwards is the documented
  workaround that lets the menu dismiss when focus moves.
- Explorer restart must re-add the icon on `TaskbarCreated`, or the icon is gone for the rest of
  the session.
- Quit from the tray is `PostQuitMessage` (after `NIM_DELETE`), not `WM_CLOSE`: with close-to-tray
  in place, `WM_CLOSE` would hide the window and leave the process running.

## How this is verified

- **Portable slice, now:** unit tests on any platform, including Linux CI — numbering, the id →
  binding table, the second callback, the lifecycle default, and the "host without a tray still
  runs" case.
- **macOS:** the demo self-test creates a status item and drives its menu programmatically, the
  same way `clickMenuItem` drives the menu bar. The check can assert the item exists, its menu
  holds the declared items, and a chosen item reaches its binding. It cannot assert that a human
  sees the icon in the menu bar.
- **Windows:** `WindowsSelfTest` can assert that `NIM_ADD` is accepted and that the callback
  message reaches the host. **GitHub's Windows runners are in session 0 with no Explorer**, so a
  real notification-area icon cannot be verified there; that check belongs on the interactive
  Windows 11 box, and a person should look at it once. Saying so is part of the result.

## Decisions this document makes

1. The tray is a separate surface with its own id table and its own callback. An item belongs to
   one surface.
2. The tray is optional at the host level (`TrayHost`), so Linux and any third host run unchanged.
3. Declaring a tray that the host accepts flips the quit-on-last-window default; an unaccepted
   tray does not.
4. The default icon is the framework's, and a `Path` overrides it.
5. One tray icon per application, one flat menu.

## Open

- **Activation policy on macOS.** `Accessory` is what a menu-bar app usually wants and what makes
  the Dock icon disappear; `Regular` keeps the app in the Dock and its main menu in the menu bar.
  This needs a decision when the macOS slice lands, not before.
- **Show/hide window as platform operations.** `Platform.SHOW_WINDOW` and `Platform.HIDE_WINDOW`
  arrive with the platform slices, because `Platform` is a published enum and a constant should
  ship with a host that can honour it.
- **Multiple icons.** Deferred, and the reason the API takes one `TraySpec` rather than a list.
- **A window-less mode.** Deferred with multi-window: it changes how `UiHost.start` is called and
  what "the last window" means.

## What is left to the builder

- The macOS slice: status item, icon, tooltip, menu, show/hide, metadata, demo self-test.
- The Windows slice: `shell32` bindings, `NOTIFYICONDATAW`, hidden callback window, popup menu,
  `TaskbarCreated`, quit route, metadata, `WindowsSelfTest` extension.
- The `SHOW_WINDOW`/`HIDE_WINDOW` operations, with the hosts that can honour them.
- The README roadmap line moves from "next up" to "shipped" only when both hosts have been
  verified, not when the model exists.
