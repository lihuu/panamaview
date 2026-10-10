package panamaview.macos;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * Getting from a worker thread back onto the UI thread, which is a platform-specific
 * question and therefore belongs in this package rather than in the bridge.
 *
 * <p>On macOS the answer is libdispatch: the main dispatch queue is drained by the same
 * run loop that {@code -[NSApplication run]} drives, so handing work to it is exactly
 * "run this on the UI thread". Both halves of that claim were verified by hand before
 * this class was written — the run loop does drain the queue, and work submitted this way
 * does run on the main thread.
 *
 * <p>Two details worth knowing:
 *
 * <ul>
 *   <li>{@code dispatch_async_f} takes a <em>function pointer and an opaque context</em>,
 *       not a block. That matters a great deal here: an FFM upcall stub already is a C
 *       function pointer, so this hop needs no hand-built block struct (compare
 *       {@link NativeBlock}, which exists because some APIs really do require blocks).</li>
 *   <li>{@code dispatch_get_main_queue()} is an inline in the headers, not an exported
 *       symbol, so it cannot be resolved. The queue object itself is exported as
 *       {@code _dispatch_main_q} and its symbol address <em>is</em> the queue — verified,
 *       because the alternative would be a pointer to a pointer and would fail at run time.</li>
 * </ul>
 *
 * <p>The context pointer carries a small integer token rather than a real address, so there
 * is nothing to free and no lifetime to track; the token indexes a map of pending work.
 */
final class UiThread {

    private static final MemorySegment MAIN_QUEUE;
    private static final MemorySegment DRAIN;
    private static final MethodHandle DISPATCH_ASYNC_F;

    private static final Map<Long, Runnable> PENDING = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT = new AtomicLong();

    static {
        SymbolLookup lib = SymbolLookup.libraryLookup("/usr/lib/libSystem.B.dylib", Arena.global());

        MAIN_QUEUE = lib.find("_dispatch_main_q")
                .orElseThrow(() -> new UnsatisfiedLinkError("_dispatch_main_q"));

        MemorySegment asyncF = lib.find("dispatch_async_f")
                .orElseThrow(() -> new UnsatisfiedLinkError("dispatch_async_f"));
        DISPATCH_ASYNC_F = Linker.nativeLinker()
                .downcallHandle(asyncF, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

        // Global arena: the stub must outlive every request, since it is the function
        // pointer the queue will call back into.
        DRAIN = ObjC.upcall(UiThread.class, "drain", FunctionDescriptor.ofVoid(ADDRESS));
    }

    private UiThread() {
    }

    /** Schedules {@code action} on the UI thread. Callable from any thread. */
    static void run(Runnable action) {
        long token = NEXT.incrementAndGet();
        PENDING.put(token, action);
        try {
            DISPATCH_ASYNC_F.invokeExact(MAIN_QUEUE, MemorySegment.ofAddress(token), DRAIN);
        } catch (Throwable t) {
            PENDING.remove(token);
            throw new IllegalStateException("cannot reach the UI thread", t);
        }
    }

    /**
     * Runs on the UI thread when the run loop drains the queue.
     *
     * <p>Must be public and must not throw: it is reached from native code through a
     * reflective lookup, and an exception escaping it aborts the process.
     */
    public static void drain(MemorySegment context) {
        Runnable action = PENDING.remove(context.address());
        if (action == null) {
            return;
        }
        try {
            action.run();
        } catch (Throwable t) {
            System.err.println("[panamaview] a UI-thread task failed: " + t);
            t.printStackTrace(System.err);
        }
    }
}
