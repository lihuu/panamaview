package io.github.lihuu.panamaview.macos;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * The Objective-C class this platform defines at runtime, so that Objective-C can call Java.
 *
 * <p>Normally you would write a {@code .m} file with a {@code WKScriptMessageHandler}
 * subclass and compile it. Here {@code objc_allocateClassPair} creates the class on the fly
 * and every method's implementation pointer is an FFM upcall stub — a real native function
 * whose body is a Java static method. The runtime cannot tell the difference, and no binding
 * generator is involved.
 *
 * <p>It forwards into {@link io.github.lihuu.panamaview.UiHost.Callbacks} rather than into the application,
 * so the application never sees an Objective-C-shaped call.
 *
 * <h2>The rules this class has to obey</h2>
 * <ul>
 *   <li>No exception may escape an upcall target. FFM turns an escaping exception into a
 *       process abort, so every method catches {@link Throwable} and reports instead.</li>
 *   <li>The instance is kept in a static field. {@code NSApplication}'s delegate is a
 *       <em>weak</em> reference, and a garbage-collected delegate is a dangling pointer.</li>
 *   <li>Signatures must match the {@link FunctionDescriptor} exactly, including that
 *       {@code BOOL} is a single byte — hence the {@code byte} return below.</li>
 * </ul>
 */
final class ObjCDispatch {

    static final String CLASS_NAME = "PanamaViewDispatch";

    private static final FunctionDescriptor VOID_4 =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor VOID_5 =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor BOOL_3 =
            FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS);

    private static MacOsHost host;
    private static MemorySegment instance;

    private ObjCDispatch() {
    }

    /** Defines and instantiates the dispatch object. Call once, on the UI thread. */
    static MemorySegment create(MacOsHost owner) {
        host = owner;

        Map<String, ObjC.Method> methods = new LinkedHashMap<>();
        methods.put("userContentController:didReceiveScriptMessage:",
                new ObjC.Method("v@:@@", ObjC.upcall(ObjCDispatch.class, "handleScriptMessage", VOID_4)));
        methods.put("webView:didStartProvisionalNavigation:",
                new ObjC.Method("v@:@@", ObjC.upcall(ObjCDispatch.class, "handleDidStartNavigation", VOID_4)));
        methods.put("webView:didFinishNavigation:",
                new ObjC.Method("v@:@@", ObjC.upcall(ObjCDispatch.class, "handleDidFinishNavigation", VOID_4)));
        methods.put("webView:didFailProvisionalNavigation:withError:",
                new ObjC.Method("v@:@@@", ObjC.upcall(ObjCDispatch.class, "handleDidFailNavigation", VOID_5)));
        methods.put("applicationShouldTerminateAfterLastWindowClosed:",
                new ObjC.Method("c@:@", ObjC.upcall(ObjCDispatch.class, "shouldTerminateAfterLastWindowClosed", BOOL_3)));

        ObjC.defineClass(CLASS_NAME, "NSObject", methods);
        instance = ObjC.newObject(CLASS_NAME);
        return instance;
    }

    // ------------------------------------------------- JavaScript → Java entry

    public static void handleScriptMessage(MemorySegment self, MemorySegment cmd,
                                           MemorySegment contentController, MemorySegment message) {
        try (ObjC.Scope scope = ObjC.scope()) {
            MemorySegment body = ObjC.send(message, ObjC.selector("body"));
            // Still on the UI thread here. Bridge hands anything slow to a virtual thread.
            host.callbacks().onMessage(ObjC.toJavaString(body));
        } catch (Throwable t) {
            report("userContentController:didReceiveScriptMessage:", t);
        }
    }

    // ------------------------------------------------------- navigation hooks

    /**
     * The page is leaving. Fires for the first load too, when nothing is in flight — which is
     * why the bridge treats an empty cancellation sweep as a no-op rather than logging it.
     */
    public static void handleDidStartNavigation(MemorySegment self, MemorySegment cmd,
                                                MemorySegment webView, MemorySegment navigation) {
        try {
            host.pageUnloading();
        } catch (Throwable t) {
            report("webView:didStartProvisionalNavigation:", t);
        }
    }

    public static void handleDidFinishNavigation(MemorySegment self, MemorySegment cmd,
                                                 MemorySegment webView, MemorySegment navigation) {
        try {
            host.pageLoaded();
        } catch (Throwable t) {
            report("webView:didFinishNavigation:", t);
        }
    }

    public static void handleDidFailNavigation(MemorySegment self, MemorySegment cmd,
                                               MemorySegment webView, MemorySegment navigation,
                                               MemorySegment error) {
        try (ObjC.Scope scope = ObjC.scope()) {
            String description = ObjC.isNull(error)
                    ? "unknown"
                    : ObjC.toJavaString(ObjC.send(error, ObjC.selector("localizedDescription")));
            host.pageFailed(description);
        } catch (Throwable t) {
            report("webView:didFailProvisionalNavigation:withError:", t);
        }
    }

    // ------------------------------------------------------- application hook

    public static byte shouldTerminateAfterLastWindowClosed(MemorySegment self, MemorySegment cmd,
                                                            MemorySegment sender) {
        try {
            return (byte) (host.quitWhenWindowCloses() ? 1 : 0);
        } catch (Throwable t) {
            report("applicationShouldTerminateAfterLastWindowClosed:", t);
            return 0;
        }
    }

    private static void report(String context, Throwable t) {
        System.err.println("[panamaview] uncaught error in " + context + ": " + t);
        t.printStackTrace(System.err);
    }
}
