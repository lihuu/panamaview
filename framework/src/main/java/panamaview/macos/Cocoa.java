package panamaview.macos;

import java.lang.foreign.MemorySegment;

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

    public static MemorySegment sharedApplication() {
        return ObjC.send(ObjC.classNamed("NSApplication"), ObjC.selector("sharedApplication"));
    }

    public static void setActivationPolicyRegular(MemorySegment app) {
        // NSApplicationActivationPolicy is an NSInteger, i.e. 64-bit — not a BOOL.
        ObjC.sendVoidLong(app, ObjC.selector("setActivationPolicy:"), ACTIVATION_POLICY_REGULAR);
    }

    /**
     * The menu bar: an application menu containing "Quit", and an Edit menu.
     *
     * <p>Without a main menu, ⌘Q does nothing — the menu bar is the only thing that gives the
     * app keyboard-level control. The Edit menu exists for the same reason one level down:
     * AppKit dispatches keyboard shortcuts through <em>menu item key equivalents</em>, so ⌘C,
     * ⌘V, ⌘X and ⌘A only reach the focused view if a menu item carries both the key
     * equivalent and the responder action ({@code copy:}, {@code paste:}, {@code cut:},
     * {@code selectAll:}). With no Edit menu those actions are unreachable from the keyboard,
     * and a {@code WKWebView}'s text fields silently stop responding to them.
     *
     * <p>Every item is added with a nil target on purpose: with no target, AppKit walks the
     * responder chain, so the web view's content view — the first responder while typing —
     * handles the action natively and text editing keeps working exactly as it does in Safari.
     */
    public static void installMenuBar(ObjC.Scope scope, MemorySegment app, String appName) {
        MemorySegment mainMenu = newMenu(scope, "");

        MemorySegment appMenuItem = ObjC.newObject("NSMenuItem");
        ObjC.sendVoid(mainMenu, ObjC.selector("addItem:"), appMenuItem);

        MemorySegment appMenu = newMenu(scope, appName);
        ObjC.sendVoid(appMenuItem, ObjC.selector("setSubmenu:"), appMenu);
        addItem(scope, appMenu, "Quit " + appName, "terminate:", "q");

        MemorySegment editMenuItem = ObjC.newObject("NSMenuItem");
        ObjC.sendVoid(editMenuItem, ObjC.selector("setTitle:"), ObjC.nsString(scope, "Edit"));
        ObjC.sendVoid(mainMenu, ObjC.selector("addItem:"), editMenuItem);

        MemorySegment editMenu = newMenu(scope, "Edit");
        ObjC.sendVoid(editMenuItem, ObjC.selector("setSubmenu:"), editMenu);
        addItem(scope, editMenu, "Undo", "undo:", "z");
        // An uppercase key equivalent implies Shift, so this is ⌘⇧Z.
        addItem(scope, editMenu, "Redo", "redo:", "Z");
        addSeparator(editMenu);
        addItem(scope, editMenu, "Cut", "cut:", "x");
        addItem(scope, editMenu, "Copy", "copy:", "c");
        addItem(scope, editMenu, "Paste", "paste:", "v");
        addSeparator(editMenu);
        addItem(scope, editMenu, "Select All", "selectAll:", "a");

        ObjC.sendVoid(app, ObjC.selector("setMainMenu:"), mainMenu);
    }

    /** One menu item. The target stays nil, which means "whoever is first responder". */
    private static void addItem(ObjC.Scope scope, MemorySegment menu,
                                String title, String action, String keyEquivalent) {
        ObjC.send(menu, ObjC.selector("addItemWithTitle:action:keyEquivalent:"),
                ObjC.nsString(scope, title),
                ObjC.selector(action),
                ObjC.nsString(scope, keyEquivalent));
    }

    private static void addSeparator(MemorySegment menu) {
        // A separator is a class-method singleton, [+NSMenuItem separatorItem].
        MemorySegment separator = ObjC.send(ObjC.classNamed("NSMenuItem"),
                ObjC.selector("separatorItem"));
        ObjC.sendVoid(menu, ObjC.selector("addItem:"), separator);
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
