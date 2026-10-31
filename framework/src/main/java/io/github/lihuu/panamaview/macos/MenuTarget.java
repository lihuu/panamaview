package io.github.lihuu.panamaview.macos;

import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The target of every binding item, on both menu surfaces: an Objective-C class created at
 * runtime whose methods are FFM upcalls into Java.
 *
 * <p>One target serves every binding item of its surface, and the item that was chosen
 * identifies itself through its {@code tag} — the id the framework assigned. That is what
 * keeps the class to two methods: a target per item would need a class per item, because
 * there is no way to read a selector's name back out of the runtime here.
 *
 * <p>There are two instances, one per surface, because the two surfaces number their items
 * in two tables: the same tag means two things, and the surface a click came from is the only
 * way to know which table to read. The surface travels in the item's <em>action</em> — a bar
 * item carries {@link #ACTION}, a tray item carries {@link #TRAY_ACTION} — and each action
 * lands on its own handler, which reads the tag against its own surface's table. Both
 * instances are of the one class; the tray item's action is simply the selector the bar's
 * items never carry.
 *
 * <p>The instances live in static fields, and that is not tidiness. {@code NSMenuItem.target}
 * is a <em>weak</em> reference, so a target that is released becomes nil — and a nil target
 * is exactly what a <em>platform</em> item does. A binding item whose target had gone would
 * quietly stop being one and start walking the responder chain, which is the failure this
 * whole distinction exists to prevent.
 *
 * <p>So the rule is: never release the targets, and hold their addresses in static fields so
 * the handles stay valid. The instances are Objective-C objects, so Java's collector was
 * never going to reclaim them — a release is. {@link ObjCDispatch} keeps its instance the
 * same way.
 */
final class MenuTarget {

    /** The action a menu bar's binding item carries: {@code -panamaviewMenuItem:}. */
    static final String ACTION = "panamaviewMenuItem:";

    /** The action a tray's binding item carries: {@code -panamaviewTrayMenuItem:}. */
    static final String TRAY_ACTION = "panamaviewTrayMenuItem:";

    private static final String CLASS_NAME = "PanamaViewMenuTarget";

    private static MacOsHost host;
    private static MemorySegment instance;
    private static MemorySegment trayInstance;

    private MenuTarget() {
    }

    /** Defines and instantiates the targets. Call once, on the UI thread. */
    static MemorySegment create(MacOsHost owner) {
        host = owner;

        Map<String, ObjC.Method> methods = new LinkedHashMap<>();
        // The encoding is v@:@ — void, receiver, selector, one object. The descriptor is
        // VOID1 because that is the same three pointers: the receiver and the selector are
        // counted, and the sender is the third.
        methods.put(ACTION, new ObjC.Method("v@:@",
                ObjC.upcall(MenuTarget.class, "handleMenuItem", ObjC.VOID1)));
        // The tray's action has the same shape: an AppKit action is a void method taking the
        // sender, whatever surface owns the menu the item sits in. Defining it on the same
        // class is what lets both surfaces share the one target class while keeping their own
        // handlers — and an item can only reach the handler its action names.
        methods.put(TRAY_ACTION, new ObjC.Method("v@:@",
                ObjC.upcall(MenuTarget.class, "handleTrayMenuItem", ObjC.VOID1)));

        ObjC.defineClass(CLASS_NAME, "NSObject", methods);
        instance = ObjC.newObject(CLASS_NAME);
        trayInstance = ObjC.newObject(CLASS_NAME);
        return instance;
    }

    /** The target the tray's binding items carry. Call after {@link #create}. */
    static MemorySegment trayTarget() {
        return trayInstance;
    }

    /**
     * A binding item was chosen.
     *
     * <p>AppKit sends the chosen item as the argument, so the id comes off the item's
     * {@code tag} rather than off the target — which is what lets one target serve every item.
     */
    public static void handleMenuItem(MemorySegment self, MemorySegment cmd, MemorySegment sender) {
        try {
            long id = ObjC.sendLong(sender, ObjC.selector("tag"));
            host.menuItem((int) id);
        } catch (Throwable t) {
            // An exception must not escape an upcall target: FFM turns one into a process abort.
            System.err.println("[panamaview] uncaught error in " + ACTION + ": " + t);
            t.printStackTrace(System.err);
        }
    }

    /**
     * A tray binding item was chosen — the tray twin of {@link #handleMenuItem}.
     *
     * <p>Same shape, same tag-reading, a different table: the id goes to the tray's callback,
     * where the portable layer looks it up in the numbering the {@code TraySpec} walk produced.
     * A tray id and a bar id can both be 1, and nothing here could tell them apart — which is
     * exactly why the tray's items carry a different action.
     */
    public static void handleTrayMenuItem(MemorySegment self, MemorySegment cmd, MemorySegment sender) {
        try {
            long id = ObjC.sendLong(sender, ObjC.selector("tag"));
            host.trayMenuItem((int) id);
        } catch (Throwable t) {
            System.err.println("[panamaview] uncaught error in " + TRAY_ACTION + ": " + t);
            t.printStackTrace(System.err);
        }
    }
}
