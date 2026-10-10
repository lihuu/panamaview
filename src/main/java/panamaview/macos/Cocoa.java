package panamaview.macos;

import java.lang.foreign.MemorySegment;

/**
 * The sliver of AppKit this PoC needs: an application object, a menu bar, one window.
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
     * A one-item menu bar containing "Quit". Without a main menu, ⌘Q does nothing —
     * the menu bar is the only thing that gives the app keyboard-level control.
     */
    public static void installMenuBar(ObjC.Scope scope, MemorySegment app, String appName) {
        MemorySegment mainMenu = newMenu(scope, "");
        MemorySegment appMenuItem = ObjC.newObject("NSMenuItem");
        ObjC.sendVoid(mainMenu, ObjC.selector("addItem:"), appMenuItem);

        MemorySegment appMenu = newMenu(scope, appName);
        ObjC.sendVoid(appMenuItem, ObjC.selector("setSubmenu:"), appMenu);
        ObjC.send(appMenu, ObjC.selector("addItemWithTitle:action:keyEquivalent:"),
                ObjC.nsString(scope, "Quit " + appName),
                ObjC.selector("terminate:"),
                ObjC.nsString(scope, "q"));

        ObjC.sendVoid(app, ObjC.selector("setMainMenu:"), mainMenu);
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

    public static void show(MemorySegment window) {
        ObjC.sendVoid(window, ObjC.selector("center"));
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
