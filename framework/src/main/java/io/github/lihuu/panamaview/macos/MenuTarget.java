package io.github.lihuu.panamaview.macos;

import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The target of every binding item: an Objective-C class created at runtime whose one method is
 * an FFM upcall into Java.
 *
 * <p>One target serves every binding item, and the item that was chosen identifies itself
 * through its {@code tag} — the id the framework assigned. That is what keeps the class to one
 * method: a target per item would need a class per item, because there is no way to read a
 * selector's name back out of the runtime here.
 *
 * <p>The instance lives in a static field, and that is not tidiness. {@code NSMenuItem.target}
 * is a <em>weak</em> reference, so a target Java's collector reclaims becomes nil — and a nil
 * target is exactly what a <em>platform</em> item does. A binding item whose target was
 * collected would quietly stop being one and start walking the responder chain, which is the
 * failure this whole distinction exists to prevent. {@link ObjCDispatch} keeps its instance
 * alive the same way.
 */
final class MenuTarget {

    /** The action a binding item carries: {@code -panamaviewMenuItem:}. */
    static final String ACTION = "panamaviewMenuItem:";

    private static final String CLASS_NAME = "PanamaViewMenuTarget";

    private static MacOsHost host;
    private static MemorySegment instance;

    private MenuTarget() {
    }

    /** Defines and instantiates the target. Call once, on the UI thread. */
    static MemorySegment create(MacOsHost owner) {
        host = owner;

        Map<String, ObjC.Method> methods = new LinkedHashMap<>();
        // The encoding is v@:@ — void, receiver, selector, one object. The descriptor is
        // VOID1 because that is the same three pointers: the receiver and the selector are
        // counted, and the sender is the third.
        methods.put(ACTION, new ObjC.Method("v@:@",
                ObjC.upcall(MenuTarget.class, "handleMenuItem", ObjC.VOID1)));

        ObjC.defineClass(CLASS_NAME, "NSObject", methods);
        instance = ObjC.newObject(CLASS_NAME);
        return instance;
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
}
