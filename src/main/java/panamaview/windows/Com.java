package panamaview.windows;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * COM through the FFM API: the Windows counterpart of what {@code panamaview.macos.ObjC} does
 * for the Objective-C runtime.
 *
 * <p>A COM interface is a C++ object whose first field points at its vtable — an array of
 * function pointers, one slot per method in declaration order. Calling a method is two reads
 * and a downcall: read the vtable pointer out of the object, read the function pointer at the
 * method's slot, call it with the object itself as the first argument. No JNI, no JNA, no stub
 * compiler — the same trick re-typing {@code objc_msgSend} per call site plays on macOS, and
 * the reason the no-glue-library design ports here at all.
 *
 * <h2>The cache</h2>
 * Every instance of one interface class shares one vtable, and a slot's signature is fixed by
 * the interface declaration, so a handle can be cached per (vtable, slot) pair and reused
 * across objects. Call sites own the descriptor they bind with; the cache is keyed without it,
 * so binding one slot under two descriptors would be a bug this class cannot catch — it would
 * quietly hand back whichever handle arrived first.
 *
 * <h2>Reference counting</h2>
 * COM objects are reference counted, and this side does from the start what the Objective-C
 * side admits it does not (NOTES.md section 7): every interface a method hands back arrives
 * with a reference the receiver owns, and owning means calling {@code Release} — IUnknown's
 * slot 2 — when done. The long-lived-object leak that macOS documents is a debt this platform
 * has not taken on.
 */
final class Com {

    private Com() {
    }

    private static final Linker LINKER = Linker.nativeLinker();

    /** IUnknown's slots — every COM interface begins with these three. */
    static final int QUERY_INTERFACE = 0;
    static final int ADD_REF = 1;
    static final int RELEASE = 2;

    private record VtableSlot(MemorySegment vtable, int slot) {
    }

    private static final ConcurrentHashMap<VtableSlot, MethodHandle> BOUND = new ConcurrentHashMap<>();

    /** The vtable pointer is a COM object's first field. */
    static MemorySegment vtable(MemorySegment object) {
        return object.getAtIndex(ADDRESS, 0);
    }

    /**
     * A downcall handle for one slot of the interface's vtable, cached per (vtable, slot).
     * The handle's first argument is always the object itself.
     */
    static MethodHandle bind(MemorySegment object, int slot, FunctionDescriptor descriptor) {
        return BOUND.computeIfAbsent(new VtableSlot(vtable(object), slot),
                key -> LINKER.downcallHandle(key.vtable().getAtIndex(ADDRESS, key.slot()), descriptor));
    }

    /**
     * Throws unless {@code hresult} succeeded. COM reports failure in a return value, not in
     * exceptions; this is where that convention is translated, once, at the boundary.
     */
    static void check(int hresult, String what) {
        if (hresult < 0) {   // the severity bit; S_OK (0) and S_FALSE (1) pass
            throw new IllegalStateException(what + " failed: HRESULT 0x" + Integer.toHexString(hresult) + describe(hresult));
        }
    }

    private static String describe(int hresult) {
        return switch (hresult) {
            case 0x80004003 -> " (E_POINTER — a null pointer where one is required)";
            case 0x80070002 -> " (E_FILENOTFOUND)";
            case 0x80070057 -> " (E_INVALIDARG)";
            case 0x80004001 -> " (E_NOTIMPL)";
            case 0x80004005 -> " (E_FAIL)";
            default -> "";
        };
    }
}
