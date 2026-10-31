package io.github.lihuu.panamaview.macos;

import io.github.lihuu.panamaview.TraySpec;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

/**
 * The tray: an {@code NSStatusItem} in the menu bar's status area.
 *
 * <p>This is the third surface, not a second spelling of one of the other two. The status
 * area sits at the right end of the same strip the application menu occupies, but it is a
 * different object with a different API, and it appears even for an application with no
 * window — which is the whole point of the feature: an application that lives in the tray is
 * reachable when its window is not.
 *
 * <p>Nothing here is torn down. The item is created once per process, next to the menu bar,
 * and lives until the process does: a tray that could disappear was never a tray. That is
 * also why the icon needs no {@code TaskbarCreated}-style re-add — on this platform nothing
 * takes the strip away and gives it back.
 */
final class StatusBar {

    /**
     * {@code NSVariableStatusItemLength} — the item is exactly as wide as its content, which
     * is what an icon wants: a fixed length would pad or crop it.
     */
    private static final double VARIABLE_LENGTH = -1.0;

    /**
     * The framework's default icon, an SF Symbol.
     *
     * <p>An application without its own artwork still gets a legible icon, the same way it
     * still gets a legible application menu without declaring one. {@code macwindow} is a
     * window glyph — the shape of what a panamaview application is. A {@code Path} on the
     * spec overrides it.
     */
    private static final String DEFAULT_SYMBOL = "macwindow";

    /**
     * The item, held in a static field — and that is not tidiness.
     *
     * <p>{@code statusItemWithLength:} is not an {@code alloc}/{@code new}/{@code copy}
     * method, so what it hands back is autoreleased: the scope that created it will release
     * it on drain, and a status item nobody retained loses its icon when it goes. The
     * {@code retain} is what survives the pool; the static field is where the retained
     * address lives for the rest of the process. The same rule {@link MenuTarget} records —
     * hold what native code references weakly, or watch it become nil — applies to the one
     * object this file creates that nothing else keeps.
     */
    private static MemorySegment item;

    private StatusBar() {
    }

    /**
     * Creates the status item out of the spec: icon, tooltip, menu.
     *
     * <p>Call once, on the UI thread, before the run loop — where {@code start} already is.
     * The menu is the flat one {@link Cocoa#buildTrayMenu} builds, and setting it as the
     * item's menu is what makes a left click open it: AppKit shows the menu itself, with no
     * action target on the button and no code of ours in the click path.
     *
     * @return the status item, retained — the caller may read its menu back through it
     */
    static MemorySegment install(ObjC.Scope scope, TraySpec tray, MemorySegment target) {
        MemorySegment statusBar = ObjC.send(ObjC.classNamed("NSStatusBar"),
                ObjC.selector("systemStatusBar"));
        MemorySegment created = ObjC.send(statusBar, ObjC.selector("statusItemWithLength:"),
                VARIABLE_LENGTH);
        ObjC.retain(created);
        item = created;

        MemorySegment button = ObjC.send(created, ObjC.selector("button"));
        MemorySegment image = image(scope, tray.icon());
        if (!ObjC.isNull(image)) {
            ObjC.sendVoid(button, ObjC.selector("setImage:"), image);
        }
        if (!tray.tooltip().isEmpty()) {
            ObjC.sendVoid(button, ObjC.selector("setToolTip:"), ObjC.nsString(scope, tray.tooltip()));
        }

        MemorySegment menu = Cocoa.buildTrayMenu(scope, tray.items(), target);
        ObjC.sendVoid(created, ObjC.selector("setMenu:"), menu);
        return created;
    }

    /**
     * The icon to show: the application's own file, or the framework's SF Symbol.
     *
     * <p>The two are treated differently on purpose. The default symbol is marked
     * <em>template</em>, so the menu bar tints it for light and dark appearances — that is
     * what a framework-owned icon should do, and it is the only way one icon reads correctly
     * in both. An application's own file is shown as authored: the artwork was chosen by
     * someone who could see it, and a framework that re-tinted it would be overruling a
     * decision it was handed.
     *
     * <p>A file that cannot be loaded is an error rather than a silent fall-back to the
     * default: the application asked for its own icon, and quietly showing the framework's
     * instead would be a wrong icon presented as the right one.
     */
    private static MemorySegment image(ObjC.Scope scope, Path icon) {
        if (icon != null) {
            MemorySegment fromFile = ObjC.send(
                    ObjC.send(ObjC.classNamed("NSImage"), ObjC.selector("alloc")),
                    ObjC.selector("initWithContentsOfFile:"), ObjC.nsString(scope, icon.toString()));
            if (ObjC.isNull(fromFile)) {
                throw new IllegalArgumentException("the tray icon could not be loaded: " + icon);
            }
            return fromFile;
        }

        // The class method, not an instance init: NSImage has no
        // initWithSystemSymbolName:accessibilityDescription: to send — the Swift convenience
        // initializer is the class method's wrapper, and the selector this runtime answers to
        // is this one. The image comes back autoreleased, which is fine: it is used within
        // this scope, and the button's property retains what it is handed.
        MemorySegment symbol = ObjC.send(ObjC.classNamed("NSImage"),
                ObjC.selector("imageWithSystemSymbolName:accessibilityDescription:"),
                ObjC.nsString(scope, DEFAULT_SYMBOL), ObjC.nsString(scope, "panamaview tray"));
        // A symbol name that resolves on one macOS can be absent on an older one; the class
        // is loaded, so a nil here is the symbol, not the framework.
        if (ObjC.isNull(symbol)) {
            throw new IllegalStateException("the default tray icon's SF Symbol \""
                    + DEFAULT_SYMBOL + "\" is not available on this macOS");
        }
        ObjC.sendVoidBool(symbol, ObjC.selector("setTemplate:"), true);
        // The symbol's own point size is the menu bar's — a macwindow arrives 18×14 — so the
        // size it carries is the right one, and forcing a square would distort a vector drawn
        // for this exact place.
        return symbol;
    }
}
