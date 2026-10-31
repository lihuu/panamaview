package io.github.lihuu.panamaview.macos;

import io.github.lihuu.panamaview.Item;
import io.github.lihuu.panamaview.Menu;
import io.github.lihuu.panamaview.MenuBar;
import io.github.lihuu.panamaview.Menus;
import io.github.lihuu.panamaview.Platform;
import io.github.lihuu.panamaview.Shortcut;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The sliver of AppKit this framework needs: an application object, a menu bar, one window.
 *
 * <p>Every call here must happen on the process' first thread — see {@link ObjC}.
 */
final class Cocoa {

    private Cocoa() {
    }

    /** NSWindowStyleMask */
    public static final long STYLE_TITLED = 1L << 0;
    public static final long STYLE_CLOSABLE = 1L << 1;
    public static final long STYLE_MINIATURIZABLE = 1L << 2;
    public static final long STYLE_RESIZABLE = 1L << 3;

    /** NSBackingStoreBuffered */
    public static final long BACKING_BUFFERED = 2L;

    /** NSApplicationActivationPolicyRegular — gives the process a Dock icon and a menu bar. */
    public static final long ACTIVATION_POLICY_REGULAR = 0L;

    /** NSEventModifierFlagShift */
    public static final long MODIFIER_SHIFT = 1L << 17;
    /** NSEventModifierFlagControl */
    public static final long MODIFIER_CONTROL = 1L << 18;
    /** NSEventModifierFlagOption */
    public static final long MODIFIER_OPTION = 1L << 19;
    /** NSEventModifierFlagCommand */
    public static final long MODIFIER_COMMAND = 1L << 20;

    public static MemorySegment sharedApplication() {
        return ObjC.send(ObjC.classNamed("NSApplication"), ObjC.selector("sharedApplication"));
    }

    public static void setActivationPolicyRegular(MemorySegment app) {
        // NSApplicationActivationPolicy is an NSInteger, i.e. 64-bit — not a BOOL.
        ObjC.sendVoidLong(app, ObjC.selector("setActivationPolicy:"), ACTIVATION_POLICY_REGULAR);
    }

    /**
     * Builds the application's menu bar out of the model and installs it.
     *
     * <p>Without a main menu, ⌘Q does nothing and the keyboard cannot reach the web view at
     * all: AppKit dispatches keyboard shortcuts through <em>menu item key equivalents</em>, so
     * ⌘C, ⌘V, ⌘X and ⌘A only reach the focused view if a menu item carries both the key
     * equivalent and the responder action ({@code copy:}, {@code paste:}, {@code cut:},
     * {@code selectAll:}). With no Edit menu those actions are unreachable from the keyboard,
     * and a {@code WKWebView}'s text fields silently stop responding to them.
     *
     * <p>The shape is AppKit's own: the bar is an {@code NSMenu}, a top-level menu joins it
     * through a menu item that holds the menu as its submenu, and the item carries the title the
     * user sees.
     *
     * @param bar     the model the menu is built from
     * @param appName the application's name, which the model has no way to know
     * @param target  the shared target of the binding items
     * @return the menus it built, one per top-level menu, in the model's order
     */
    public static List<MemorySegment> installMenuBar(ObjC.Scope scope, MemorySegment app, MenuBar bar,
                                                     String appName, MemorySegment target) {
        MemorySegment mainMenu = newMenu(scope, "");
        List<MemorySegment> built = new ArrayList<>();

        for (Menu menu : bar.menus()) {
            // The application's own menu is the one the model could not name: the model has no
            // application name, so it leaves a placeholder there, and the platform is the only
            // side that knows the name. The item's own title is left unset, as it has always
            // been — AppKit then shows the application name by itself.
            boolean appMenu = isAppMenu(menu);

            MemorySegment menuItem = ObjC.newObject("NSMenuItem");
            if (!appMenu) {
                ObjC.sendVoid(menuItem, ObjC.selector("setTitle:"), ObjC.nsString(scope, menu.title()));
            }
            ObjC.sendVoid(mainMenu, ObjC.selector("addItem:"), menuItem);

            MemorySegment submenu = newMenu(scope, appMenu ? appName : menu.title());
            ObjC.sendVoid(menuItem, ObjC.selector("setSubmenu:"), submenu);
            built.add(submenu);

            for (Item item : menu.items()) {
                addItem(scope, submenu, item, itemTitle(menu, item, appName), target,
                        MenuTarget.ACTION);
            }
        }

        ObjC.sendVoid(app, ObjC.selector("setMainMenu:"), mainMenu);
        return built;
    }

    /**
     * Builds the tray's popup out of the model: one flat menu, the items in the order the
     * spec declares them.
     *
     * <p>The same {@link #addItem} builds both surfaces' items, because a binding item, a
     * platform item and a separator mean the same thing in both places. Two things differ.
     * The action a binding item carries is the tray's own, so a click lands on the tray's
     * handler and reads its own id table — a bar id and a tray id can both be 1, and the
     * action is what keeps them apart. And no tray is the application menu, so every item
     * keeps the title the model gave it: the application-name composition that
     * {@link #itemTitle} does for the bar has no tray counterpart, and a Quit declared in a
     * tray says what the application called it.
     */
    public static MemorySegment buildTrayMenu(ObjC.Scope scope, List<Item> items,
                                              MemorySegment target) {
        MemorySegment menu = newMenu(scope, "");
        for (Item item : items) {
            String title = item.title() == null ? "" : item.title();
            addItem(scope, menu, item, title, target, MenuTarget.TRAY_ACTION);
        }
        return menu;
    }

    /**
     * Where an item sits in the menu that was built: which top-level menu it is in, and its
     * position inside that menu.
     *
     * <p>The position is the item's index in the model's own item list, because the builder
     * appends the items in exactly that order. The separators are in that list too, and each
     * one occupies a position of its own — so the item after a separator is not where it looks
     * to be, and an activation that counted only the items would land on the wrong one.
     *
     * <p>An id that names no item is an error rather than a shrug: the id is the framework's
     * own, so an id nobody holds is a bar that was not the one that was built.
     */
    static Position positionOf(MenuBar bar, int id) {
        List<Menu> menus = bar.menus();
        for (int menu = 0; menu < menus.size(); menu++) {
            List<Item> items = menus.get(menu).items();
            for (int index = 0; index < items.size(); index++) {
                if (items.get(index).id() == id) {
                    return new Position(menu, index);
                }
            }
        }
        throw new IllegalArgumentException("the menu has no item with the id " + id);
    }

    /** Where an item sits in the built menu: the top-level menu, and the position in it. */
    record Position(int menu, int index) {
    }

    /**
     * The title an item shows.
     *
     * <p>The application menu's Quit item carries the application's name, as it always did: the
     * model has no application name, and the platform is the only side that knows it. Every
     * other item keeps the title the model gave it, so an application's own menu says what it
     * says — a {@code platformItem(Platform.QUIT, "退出")} of an application's own menu is still
     * 退出.
     */
    static String itemTitle(Menu menu, Item item, String appName) {
        if (isAppMenu(menu) && item.platformAction() == Platform.QUIT) {
            return "Quit " + appName;
        }
        // A separator has no title, and the null must not leave this method: the native call
        // that takes a title would be handed one.
        String title = item.title();
        return title == null ? "" : title;
    }

    /** Whether this is the application's own menu — the one the model could not name. */
    private static boolean isAppMenu(Menu menu) {
        return Menus.APP_MENU_TITLE.equals(menu.title());
    }

    /**
     * One menu item, built from the model.
     *
     * <p>The two kinds of item differ in exactly one place — the target — and that is the whole
     * point of the distinction. A <em>binding item</em> gets the shared target, so the click
     * reaches Java. A <em>platform item</em> gets a nil target, so AppKit walks the responder
     * chain and the first responder handles the action: that is what makes ⌘C reach the web
     * view's text field, and a target that stopped the action there would break it.
     */
    private static void addItem(ObjC.Scope scope, MemorySegment menu, Item item, String title,
                                MemorySegment target, String bindingAction) {
        switch (item) {
            case Item.SeparatorItem ignored -> {
                // A separator is a class-method singleton, [+NSMenuItem separatorItem].
                MemorySegment separator = ObjC.send(ObjC.classNamed("NSMenuItem"),
                        ObjC.selector("separatorItem"));
                ObjC.sendVoid(menu, ObjC.selector("addItem:"), separator);
            }
            case Item.BindingItem binding -> {
                MemorySegment menuItem = newItem(scope, title, bindingAction,
                        binding.shortcut());
                ObjC.sendVoid(menuItem, ObjC.selector("setTarget:"), target);
                // The tag is the id the framework assigned, and it is how the target learns
                // which item was chosen.
                ObjC.sendVoidLong(menuItem, ObjC.selector("setTag:"), binding.id());
                ObjC.sendVoid(menu, ObjC.selector("addItem:"), menuItem);
            }
            case Item.PlatformItem platform -> {
                MemorySegment menuItem = newItem(scope, title,
                        platformAction(platform.platformAction()), platform.shortcut());
                // Explicit, because a nil target is the platform item's whole behaviour.
                ObjC.sendVoid(menuItem, ObjC.selector("setTarget:"), MemorySegment.NULL);
                ObjC.sendVoid(menu, ObjC.selector("addItem:"), menuItem);
            }
        }
    }

    /** A menu item with its title, its action and its key equivalent. */
    private static MemorySegment newItem(ObjC.Scope scope, String title, String action,
                                         Shortcut shortcut) {
        MemorySegment menuItem = ObjC.send(
                ObjC.send(ObjC.classNamed("NSMenuItem"), ObjC.selector("alloc")),
                ObjC.selector("initWithTitle:action:keyEquivalent:"),
                ObjC.nsString(scope, title),
                ObjC.selector(action),
                ObjC.nsString(scope, shortcut == null ? "" : shortcut.key()));

        if (shortcut != null) {
            // The mask replaces AppKit's default rather than adding to it, and AppKit's default
            // for a bare key equivalent is ⌘ — so the mask is set even when it is empty.
            // Leaving it alone would turn a declaration of "C" into ⌘C.
            ObjC.sendVoidLong(menuItem, ObjC.selector("setKeyEquivalentModifierMask:"),
                    modifierMask(shortcut.modifiers()));
        }
        return menuItem;
    }

    /**
     * The AppKit action for a platform operation.
     *
     * <p>The model's operations are the framework's; the selectors are AppKit's. This is the
     * table that translates one into the other, and it is the whole of the difference between
     * the two platforms' vocabularies.
     */
    static String platformAction(Platform operation) {
        return switch (operation) {
            case QUIT -> "terminate:";
            case UNDO -> "undo:";
            case REDO -> "redo:";
            case CUT -> "cut:";
            case COPY -> "copy:";
            case PASTE -> "paste:";
            case SELECT_ALL -> "selectAll:";
        };
    }

    /**
     * The AppKit modifier mask for a shortcut's modifiers.
     *
     * <p>{@code CmdOrCtrl} is ⌘ here and Ctrl on Windows, which is the whole reason the
     * spelling exists: the same declaration means the same user-visible action on both.
     */
    static long modifierMask(Set<Shortcut.Modifier> modifiers) {
        long mask = 0;
        for (Shortcut.Modifier modifier : modifiers) {
            mask |= switch (modifier) {
                case CMD_OR_CTRL -> MODIFIER_COMMAND;
                case CTRL -> MODIFIER_CONTROL;
                case SHIFT -> MODIFIER_SHIFT;
                case ALT -> MODIFIER_OPTION;
            };
        }
        return mask;
    }

    private static MemorySegment newMenu(ObjC.Scope scope, String title) {
        return ObjC.send(ObjC.send(ObjC.classNamed("NSMenu"), ObjC.selector("alloc")),
                ObjC.selector("initWithTitle:"), ObjC.nsString(scope, title));
    }

    /** Creates the window with the given <em>content</em> size (the title bar is added on top). */
    public static MemorySegment createWindow(ObjC.Scope scope, String title, double width, double height) {
        MemorySegment window = ObjC.sendRectStyleMaskBackingDefer(
                ObjC.send(ObjC.classNamed("NSWindow"), ObjC.selector("alloc")),
                ObjC.selector("initWithContentRect:styleMask:backing:defer:"),
                scope.rect(0, 0, width, height),
                STYLE_TITLED | STYLE_CLOSABLE | STYLE_MINIATURIZABLE | STYLE_RESIZABLE,
                BACKING_BUFFERED,
                false);
        ObjC.sendVoid(window, ObjC.selector("setTitle:"), ObjC.nsString(scope, title));
        ObjC.sendVoidBool(window, ObjC.selector("setReleasedWhenClosed:"), false);
        return window;
    }

    /**
     * Makes the view fill the window. AppKit keeps a window's content view at the content
     * rect automatically, which is why resize needs no code of its own — the web view is
     * simply resized by the framework.
     */
    public static void setContentView(MemorySegment window, MemorySegment view) {
        ObjC.sendVoid(window, ObjC.selector("setContentView:"), view);
    }

    /** {@code [window setContentSize:]} — by-value NSSize on the Objective-C side. */
    public static void setContentSize(ObjC.Scope scope, MemorySegment window, double width, double height) {
        ObjC.sendVoidSize(window, ObjC.selector("setContentSize:"), scope.size(width, height));
    }

    /**
     * {@code [window setFrameOrigin:]} — moves the window and leaves its size alone.
     *
     * <p>The point is the frame's origin in AppKit's screen coordinates, measured from the
     * bottom-left of the primary display. Callers get that coordinate space and not a
     * translated one — see {@link io.github.lihuu.panamaview.WindowSpec.Origin}.
     */
    public static void setFrameOrigin(ObjC.Scope scope, MemorySegment window, double x, double y) {
        ObjC.sendVoidPoint(window, ObjC.selector("setFrameOrigin:"), scope.point(x, y));
    }

    /**
     * Puts the window back where the user last left it, and asks AppKit to keep saving it there.
     *
     * <p>This is the platform's own persistence, not a side file: {@code setFrameAutosaveName:}
     * makes the window write its frame — origin <em>and</em> size — into the process' user
     * defaults whenever it moves or is resized, and {@code setFrameUsingName:} reads it back
     * here. The name is the only thing the framework has to choose; within one defaults domain
     * it is the whole key, so two windows get two memories.
     *
     * <p>The return value is not a detail. A window that has never been moved has nothing saved,
     * and then the caller's size plus {@link #center} still decide — first launch looks exactly
     * as it did before this existed. A window that <em>has</em> been moved must not be centred,
     * or the restore is undone one line later.
     *
     * @return true when a saved frame was restored
     */
    public static boolean restoreFrame(ObjC.Scope scope, MemorySegment window, String autosaveName) {
        MemorySegment name = ObjC.nsString(scope, autosaveName);
        boolean restored = ObjC.sendBool(window, ObjC.selector("setFrameUsingName:"), name);
        // Registers for saving from now on; when nothing was restored, the first move creates
        // the entry.
        ObjC.sendBool(window, ObjC.selector("setFrameAutosaveName:"), name);
        return restored;
    }

    public static void center(MemorySegment window) {
        ObjC.sendVoid(window, ObjC.selector("center"));
    }

    public static void show(MemorySegment window) {
        ObjC.sendVoid(window, ObjC.selector("makeKeyAndOrderFront:"), MemorySegment.NULL);
    }

    public static void activate(MemorySegment app) {
        ObjC.sendVoidBool(app, ObjC.selector("activateIgnoringOtherApps:"), true);
    }

    public static void setDelegate(MemorySegment app, MemorySegment delegate) {
        // NSApplication holds its delegate weakly, so Java must keep the reference alive.
        ObjC.sendVoid(app, ObjC.selector("setDelegate:"), delegate);
    }

    public static void finishLaunching(MemorySegment app) {
        ObjC.sendVoid(app, ObjC.selector("finishLaunching"));
    }

    /** Runs the AppKit event loop. Blocks this thread until the application terminates. */
    public static void run(MemorySegment app) {
        ObjC.sendVoid(app, ObjC.selector("run"));
    }
}
