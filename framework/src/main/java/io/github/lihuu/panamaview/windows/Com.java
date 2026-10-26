package io.github.lihuu.panamaview.windows;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * COM through the FFM API: the Windows counterpart of what {@code io.github.lihuu.panamaview.macos.ObjC} does
 * for the Objective-C runtime.
 *
 * <p>A COM interface is a C++ object whose first field points at its vtable — an array of
 * function pointers, one slot per method in declaration order. Calling a method is two reads
 * and a downcall: read the vtable pointer out of the object, read the function pointer at the
 * method's slot, call it with the object itself as the first argument. No JNI, no JNA, no stub
 * compiler — the same trick re-typing {@code objc_msgSend} per call site plays on macOS, and
 * the reason the no-glue-library design ports here at all.
 *
 * <p>Two rules this code lives by, both learned the hard way in the prototype that proved the
 * platform out (see NOTES.md, the Windows section):
 *
 * <ul>
 *   <li>a pointer that crosses from native into Java arrives as a zero-length segment, and is
 *       reinterpreted before its first dereference — every time, including inside upcalls;</li>
 *   <li>vtable slots are read out of the shipped header's own annotations, never out of
 *       memory — an off-by-one registers the remove_ listener where the add_ belongs and
 *       waits forever for an event nobody asked for.</li>
 * </ul>
 *
 * <h2>Reference counting, for real</h2>
 * COM objects are reference counted, and this side does from the start what the Objective-C
 * side admits it does not (NOTES.md section 7). Objects built here carry an atomic count next
 * to their vtable; {@code AddRef} bumps it, {@code Release} drops it, and reaching zero frees
 * the object — vtable, stubs and all, each in an arena of its own.
 */
final class Com {

    private Com() {
    }

    static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /** IUnknown's slots — every COM interface begins with these three. */
    static final int QUERY_INTERFACE = 0;
    static final int ADD_REF = 1;
    static final int RELEASE = 2;

    private static final MemoryLayout OBJECT = MemoryLayout.structLayout(
            ADDRESS.withName("lpVtbl"), MemoryLayout.paddingLayout(4), JAVA_INT.withName("refCount"),
            MemoryLayout.paddingLayout(4));
    private static final VarHandle REF_COUNT = OBJECT.varHandle(MemoryLayout.PathElement.groupElement("refCount"));

    private static final int E_NOINTERFACE = 0x80004002;

    private static final byte[] IID_IUNKNOWN = {
            0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xC0, 0, 0, 0, 0, 0, 0, 0x46};

    private record VtableSlot(MemorySegment vtable, int slot) {
    }

    private static final ConcurrentHashMap<VtableSlot, MethodHandle> BOUND = new ConcurrentHashMap<>();

    /** The vtable pointer is a COM object's first field. */
    static MemorySegment vtable(MemorySegment object) {
        return object.getAtIndex(ADDRESS, 0).reinterpret(Long.MAX_VALUE);
    }

    /** A downcall handle for one slot of the interface's vtable, cached per (vtable, slot). */
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
            case 0x8007139F -> " (ERROR_INVALID_STATE — the browser is not ready for this yet)";
            default -> "";
        };
    }

    // ------------------------------------------------------------- calls into foreign objects

    /** A plain {@code HRESULT method(self)} call, for the IUnknown members. */
    static int callSelf(MemorySegment object, int slot, String what) {
        try {
            return (int) bind(object, slot, FunctionDescriptor.of(JAVA_INT, ADDRESS)).invoke(object);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(what + " could not be called", t);
        }
    }

    /** A {@code HRESULT method(self, pointer)} call, for getters that write through an out-pointer. */
    static void callGetter(MemorySegment object, int slot, MemorySegment out, String what) {
        try {
            check((int) bind(object, slot, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)).invoke(object, out), what);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(what + " could not be called", t);
        }
    }

    // ------------------------------------------------------------- objects we build

    /** {@code Invoke(self, HRESULT, pointer)} — the shape of every completed-handler callback. */
    interface Completed {
        int invoke(MemorySegment self, int errorCode, MemorySegment result);
    }

    /** {@code Invoke(self, sender, args)} — the shape of every event-handler callback. */
    interface Event {
        int invoke(MemorySegment self, MemorySegment sender, MemorySegment args);
    }

    private static final FunctionDescriptor COMPLETED_INVOKE =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor EVENT_INVOKE =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor QI_DESCRIPTOR =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor COUNT_DESCRIPTOR =
            FunctionDescriptor.of(JAVA_INT, ADDRESS);

    /**
     * A handler object the browser can hold: four slots (QueryInterface, AddRef, Release,
     * Invoke) and an honest reference count.
     *
     * <p>QueryInterface answers IUnknown and nothing else. These objects are passed and stored
     * through their typed pointers, and the one interface every COM object must answer is
     * IUnknown; inventing yes-answers for interfaces the object does not implement is a lie
     * that crashes later, somewhere else.
     *
     * <h2>Why Release never frees</h2>
     * The browser QIs and Releases these objects as temporaries <em>inside the very call that
     * receives them</em> - the first demo run died on exactly that: reaching zero closed the
     * arena while the Release upcall itself was still on the object's stub, and FFM refused
     * ("Session is acquired by 1 clients"). A handler is bounded by the number of registrations
     * an application makes - a handful per lifetime - so the honest count stands and the
     * memory lives as long as the process, the same call the macOS side makes for everything.
     */
    static MemorySegment completed(Completed body) {
        return build(LINKER.upcallStub(
                mh(Completed.class, "invoke",
                        MethodType.methodType(int.class, MemorySegment.class, int.class, MemorySegment.class), body),
                COMPLETED_INVOKE, Arena.global()));
    }

    static MemorySegment event(Event body) {
        return build(LINKER.upcallStub(
                mh(Event.class, "invoke",
                        MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class), body),
                EVENT_INVOKE, Arena.global()));
    }

    /** A completed handler that only cares about the HRESULT — fire-and-forget calls. */
    static MemorySegment acknowledging(java.util.function.IntConsumer onResult) {
        return completed((self, errorCode, result) -> {
            onResult.accept(errorCode);
            return 0;
        });
    }

    private static MethodHandle mh(Class<?> type, String name, MethodType shape, Object receiver) {
        try {
            return LOOKUP.findVirtual(type, name, shape).bindTo(receiver);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Assembles the object around an invoke stub the caller already built.
     *
     * <p>The invoke stub is built by the caller, next to the {@code FunctionDescriptor} that
     * describes it, because that is the only place the descriptor and the stub can be seen
     * together -- the native-image registration check reads descriptors where they are
     * built, and a descriptor passed through a helper is a descriptor it cannot vouch for.
     */
    private static MemorySegment build(MemorySegment invoke) {
        Arena arena = Arena.global();   // handler objects live as long as the process; see the class comment on Release
        MemorySegment object = arena.allocate(OBJECT);
        MemorySegment vtbl = arena.allocate(ADDRESS, 4);
        vtbl.setAtIndex(ADDRESS, QUERY_INTERFACE, LINKER.upcallStub(QI_MH, QI_DESCRIPTOR, arena));
        vtbl.setAtIndex(ADDRESS, ADD_REF, LINKER.upcallStub(ADD_REF_MH, COUNT_DESCRIPTOR, arena));
        vtbl.setAtIndex(ADDRESS, RELEASE, LINKER.upcallStub(RELEASE_MH, COUNT_DESCRIPTOR, arena));
        vtbl.setAtIndex(ADDRESS, 3, invoke);
        object.setAtIndex(ADDRESS, 0, vtbl);
        REF_COUNT.set(object, 0L, 0);
        return object;
    }

    private static int queryInterface(MemorySegment selfArg, MemorySegment riid, MemorySegment out) {
        MemorySegment self = selfArg.reinterpret(Long.MAX_VALUE);
        MemorySegment iid = riid.reinterpret(16);
        for (int i = 0; i < 16; i++) {
            if (iid.get(JAVA_BYTE, i) != IID_IUNKNOWN[i]) {
                return E_NOINTERFACE;
            }
        }
        out.reinterpret(8).setAtIndex(ADDRESS, 0, self);
        bump(self, 1);
        return 0;
    }

    private static int addRef(MemorySegment selfArg) {
        return bump(selfArg.reinterpret(Long.MAX_VALUE), 1);
    }

    private static int release(MemorySegment selfArg) {
        // The count is honest; the memory is not freed - see the javadoc on completed() for
        // why freeing from inside a Release upcall cannot work.
        return bump(selfArg.reinterpret(Long.MAX_VALUE), -1);
    }

    private static int bump(MemorySegment self, int delta) {
        int previous = (int) REF_COUNT.getAndAdd(self, 0L, delta);
        return previous + delta;
    }

    private static final MethodHandle QI_MH;
    private static final MethodHandle ADD_REF_MH;
    private static final MethodHandle RELEASE_MH;

    static {
        try {
            QI_MH = LOOKUP.findStatic(Com.class, "queryInterface", MethodType.methodType(
                    int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class));
            ADD_REF_MH = LOOKUP.findStatic(Com.class, "addRef", MethodType.methodType(int.class, MemorySegment.class));
            RELEASE_MH = LOOKUP.findStatic(Com.class, "release", MethodType.methodType(int.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ------------------------------------------------------------- QueryInterface, outbound

    /** {@code {DWORD; WORD; WORD; BYTE[8]}} — the memory form of a GUID, parsed from its text. */
    static byte[] guid(String text) {
        String hex = text.replace("-", "");
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        // Data1, Data2 and Data3 ride little-endian in memory; Data4 as written.
        swap(out, 0, 3);
        swap(out, 1, 2);
        swap(out, 4, 5);
        swap(out, 6, 7);
        return out;
    }

    private static void swap(byte[] bytes, int a, int b) {
        byte t = bytes[a];
        bytes[a] = bytes[b];
        bytes[b] = t;
    }

    /**
     * Asks a foreign object for one of its interfaces. The returned pointer comes with a
     * reference the caller owns; this framework keeps its long-lived objects for the process
     * lifetime, the same way the macOS side keeps everything it was handed.
     */
    static MemorySegment queryInterface(MemorySegment unknown, String iid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment iidMemory = arena.allocate(16);
            MemorySegment.copy(MemorySegment.ofArray(guid(iid)), 0, iidMemory, 0, 16);
            MemorySegment out = arena.allocate(ADDRESS);
            try {
                int hresult = (int) bind(unknown, QUERY_INTERFACE,
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)).invoke(unknown, iidMemory, out);
                check(hresult, "QueryInterface");
            } catch (IllegalStateException e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException("QueryInterface could not be called", t);
            }
            MemorySegment result = out.getAtIndex(ADDRESS, 0);
            if (result.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("QueryInterface for " + iid + " succeeded but returned nothing");
            }
            return result.reinterpret(Long.MAX_VALUE);
        }
    }
}
