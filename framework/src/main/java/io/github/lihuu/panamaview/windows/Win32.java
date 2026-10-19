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
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The Win32 a window needs: a class, a window, a message pump, and the thread messages that
 * let any Java thread hand work to the one that owns them. No JNI, no JNA — every call here is
 * a downcall into user32 or kernel32, and the window procedure is an upcall stub.
 *
 * <p>Windows, unlike AppKit, does not require the process' first thread; what it requires is
 * that the thread which creates a window pumps its messages. The host runs that thread and
 * this class is the machinery underneath.
 */
final class Win32 {

    private Win32() {
    }

    static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final Arena LIFE = Arena.global();

    // ------------------------------------------------------------- window procedure

    /** The messages a host cares about; everything else goes to {@code DefWindowProcW}. */
    interface Procedure {
        long onMessage(MemorySegment hwnd, int message, long wparam, long lparam);
    }

    static final int WM_DESTROY = 0x0002;
    static final int WM_SIZE = 0x0005;
    static final int WM_CLOSE = 0x0010;
    static final int WM_QUIT = 0x0012;
    static final int WM_APP_RUNNABLE = 0x8000;   // WM_APP: for the host's own use

    // ------------------------------------------------------------- layouts

    private static final MemoryLayout WNDCLASSEX = MemoryLayout.structLayout(
            JAVA_INT.withName("cbSize"), JAVA_INT.withName("style"),
            ADDRESS.withName("lpfnWndProc"), JAVA_INT.withName("cbClsExtra"), JAVA_INT.withName("cbWndExtra"),
            ADDRESS.withName("hInstance"), ADDRESS.withName("hIcon"), ADDRESS.withName("hCursor"),
            ADDRESS.withName("hbrBackground"), ADDRESS.withName("lpszMenuName"), ADDRESS.withName("lpszClassName"),
            ADDRESS.withName("hIconSm"));

    // Field handles, not raw element indexes: the struct mixes 4- and 8-byte fields, and an
    // ADDRESS element index on a mixed layout is a byte-offset lie.
    private static final VarHandle WCX_CBSIZE = field(WNDCLASSEX, "cbSize", int.class);
    private static final VarHandle WCX_STYLE = field(WNDCLASSEX, "style", int.class);
    private static final VarHandle WCX_WNDPROC = field(WNDCLASSEX, "lpfnWndProc", MemorySegment.class);
    private static final VarHandle WCX_CLS_EXTRA = field(WNDCLASSEX, "cbClsExtra", int.class);
    private static final VarHandle WCX_WND_EXTRA = field(WNDCLASSEX, "cbWndExtra", int.class);
    private static final VarHandle WCX_INSTANCE = field(WNDCLASSEX, "hInstance", MemorySegment.class);
    private static final VarHandle WCX_ICON = field(WNDCLASSEX, "hIcon", MemorySegment.class);
    private static final VarHandle WCX_CURSOR = field(WNDCLASSEX, "hCursor", MemorySegment.class);
    private static final VarHandle WCX_BACKGROUND = field(WNDCLASSEX, "hbrBackground", MemorySegment.class);
    private static final VarHandle WCX_MENU_NAME = field(WNDCLASSEX, "lpszMenuName", MemorySegment.class);
    private static final VarHandle WCX_CLASS_NAME = field(WNDCLASSEX, "lpszClassName", MemorySegment.class);
    private static final VarHandle WCX_ICON_SMALL = field(WNDCLASSEX, "hIconSm", MemorySegment.class);

    private static VarHandle field(MemoryLayout struct, String name, Class<?> carrier) {
        return struct.varHandle(MemoryLayout.PathElement.groupElement(name));
    }

    /** {@code {LONG left; LONG top; LONG right; LONG bottom}} — 16 bytes. */
    static final MemoryLayout RECT = MemoryLayout.structLayout(
            JAVA_INT.withName("left"), JAVA_INT.withName("top"),
            JAVA_INT.withName("right"), JAVA_INT.withName("bottom"));

    /** {@code {HWND; UINT; WPARAM; LPARAM; DWORD time; POINT pt; DWORD lPrivate}} — 48 bytes on x64. */
    private static final MemoryLayout MSG = MemoryLayout.structLayout(
            ADDRESS.withName("hwnd"), JAVA_INT.withName("message"), MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("wParam"), JAVA_LONG.withName("lParam"),
            JAVA_INT.withName("time"), JAVA_INT.withName("ptX"), JAVA_INT.withName("ptY"),
            MemoryLayout.paddingLayout(4));

    // ------------------------------------------------------------- bindings

    private static final SymbolLookups LOOKUPS = new SymbolLookups();

    static final class SymbolLookups {
        final java.lang.foreign.SymbolLookup KERNEL32 = java.lang.foreign.SymbolLookup.libraryLookup("kernel32.dll", LIFE);
        final java.lang.foreign.SymbolLookup KERNELBASE = java.lang.foreign.SymbolLookup.libraryLookup("kernelbase.dll", LIFE);
        final java.lang.foreign.SymbolLookup USER32 = java.lang.foreign.SymbolLookup.libraryLookup("user32.dll", LIFE);
        final java.lang.foreign.SymbolLookup OLE32 = java.lang.foreign.SymbolLookup.libraryLookup("ole32.dll", LIFE);
    }

    private static MethodHandle bind(java.lang.foreign.SymbolLookup lookup, String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), fd);
    }

    private static final MethodHandle GET_MODULE_HANDLE =
            bind(LOOKUPS.KERNEL32, "GetModuleHandleW", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GET_CURRENT_THREAD_ID =
            bind(LOOKUPS.KERNEL32, "GetCurrentThreadId", FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle POST_THREAD_MESSAGE =
            bind(LOOKUPS.USER32, "PostThreadMessageW",
                    // DWORD idThread, UINT msg, WPARAM, LPARAM — four arguments; dropping the
                    // UINT strands the pump with no way out, which is how a run hangs.
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle REGISTER_CLASS =
            bind(LOOKUPS.USER32, "RegisterClassExW", FunctionDescriptor.of(JAVA_SHORT, ADDRESS));
    private static final MethodHandle CREATE_WINDOW =
            bind(LOOKUPS.USER32, "CreateWindowExW", FunctionDescriptor.of(ADDRESS,
                    JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle SHOW_WINDOW =
            bind(LOOKUPS.USER32, "ShowWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle LOAD_CURSOR =
            bind(LOOKUPS.USER32, "LoadCursorW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GET_CLIENT_RECT =
            bind(LOOKUPS.USER32, "GetClientRect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GET_WINDOW_RECT =
            bind(LOOKUPS.USER32, "GetWindowRect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle DEF_WINDOW_PROC =
            bind(LOOKUPS.USER32, "DefWindowProcW", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle POST_QUIT_MESSAGE =
            bind(LOOKUPS.USER32, "PostQuitMessage", FunctionDescriptor.ofVoid(JAVA_INT));
    private static final MethodHandle POST_MESSAGE =
            bind(LOOKUPS.USER32, "PostMessageW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GET_LAST_ERROR =
            bind(LOOKUPS.KERNELBASE, "GetLastError", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle GET_MESSAGE =
            bind(LOOKUPS.USER32, "GetMessageW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle TRANSLATE_MESSAGE =
            bind(LOOKUPS.USER32, "TranslateMessage", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle DISPATCH_MESSAGE =
            bind(LOOKUPS.USER32, "DispatchMessageW", FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    private static final int CS_HREDRAW_VREDRAW = 0x0003;
    private static final int WS_OVERLAPPEDWINDOW = 0x00CF0000;
    private static final int CW_USEDEFAULT = 0x80000000;
    private static final int SW_SHOW = 5;
    private static final long COLOR_WINDOW_PLUS_ONE = 6;
    private static final long IDC_ARROW = 32512;

    static long currentThreadId() {
        try {
            return (int) GET_CURRENT_THREAD_ID.invoke();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Ends the message loop from another thread; the pump thread keeps running until then. */
    static void postThreadQuit(long threadId) {
        try {
            POST_THREAD_MESSAGE.invoke((int) threadId, WM_QUIT, 0L, 0L);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static void postQuit() {
        try {
            POST_QUIT_MESSAGE.invoke(0);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Asks the window's own procedure, from any thread, to run something on the pump thread. */
    static void postRunnable(MemorySegment hwnd, long wparamForFlags) {
        try {
            POST_MESSAGE.invoke(hwnd, WM_APP_RUNNABLE, wparamForFlags, 0L);
        } catch (Throwable t) {
            throw new IllegalStateException("PostMessage could not be called", t);
        }
    }

    static long defWindowProc(MemorySegment hwnd, int message, long wparam, long lparam) {
        try {
            return (long) DEF_WINDOW_PROC.invoke(hwnd, message, wparam, lparam);
        } catch (Throwable t) {
            throw new IllegalStateException("DefWindowProcW could not be called", t);
        }
    }

    static MemorySegment clientRect(Arena arena, MemorySegment hwnd) {
        MemorySegment rect = arena.allocate(RECT);
        try {
            int ok = (int) GET_CLIENT_RECT.invoke(hwnd, rect);
            if (ok == 0) {
                throw new IllegalStateException("GetClientRect failed");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("GetClientRect could not be called", t);
        }
        return rect;
    }

    /** The window's on-screen rectangle, as {@code x,y widthxheight} — platform extra for a host. */
    static String windowRectangle(Arena arena, MemorySegment hwnd) {
        MemorySegment rect = arena.allocate(RECT);
        try {
            int ok = (int) GET_WINDOW_RECT.invoke(hwnd, rect);
            if (ok == 0) {
                throw new IllegalStateException("GetWindowRect failed");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("GetWindowRect could not be called", t);
        }
        int left = rect.get(JAVA_INT, 0), top = rect.get(JAVA_INT, 4);
        int right = rect.get(JAVA_INT, 8), bottom = rect.get(JAVA_INT, 12);
        return (right - left) + "x" + (bottom - top) + " at " + left + "," + top;
    }

    /**
     * Registers the class, creates and shows the window, returns the HWND. The window procedure
     * is an upcall stub bound to {@code procedure}: the host answers the messages it cares
     * about, and everything else goes to {@code DefWindowProcW} — a window procedure that ate a
     * message would eat the behaviour behind it.
     */
    static MemorySegment createWindow(Arena arena, String title, int width, int height, Procedure procedure) {
        return createWindow(arena, title, width, height, CW_USEDEFAULT, CW_USEDEFAULT, procedure);
    }

    /**
     * The same, at a caller-chosen position.
     *
     * <p>{@code CreateWindowExW} takes the position as two ordinary arguments, so placement needs
     * no second call and no window-manager negotiation: the window is simply born there. The
     * coordinates are virtual-screen pixels measured from the top-left of the primary monitor —
     * the same numbers, in the same pass-through spirit, as {@link panamaview.WindowSpec.Origin}.
     */
    static MemorySegment createWindow(Arena arena, String title, int width, int height,
                                      int x, int y, Procedure procedure) {
        MemorySegment wndProc = LINKER.upcallStub(
                // Bound to the concrete adapter, by the concrete method name - the exact shape
                // the prototype proved out. CreateWindowExW dispatches WM_NCCREATE and friends
                // through this stub while the window is being built, so anything off here does
                // not surface as an error code: creation just returns NULL.
                mh(ProcedureAdapter.class, "dispatch",
                        MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class),
                        new ProcedureAdapter(procedure)),
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG), LIFE);

        try (Arena confined = Arena.ofConfined()) {
            String className = "panamaview-" + Long.toHexString(System.nanoTime());
            MemorySegment classNameSeg = confined.allocateFrom(className, StandardCharsets.UTF_16LE);
            MemorySegment windowName = confined.allocateFrom(title, StandardCharsets.UTF_16LE);
            MemorySegment instance = (MemorySegment) GET_MODULE_HANDLE.invoke(MemorySegment.NULL);
            MemorySegment cursor = (MemorySegment) LOAD_CURSOR.invoke(MemorySegment.NULL, idResource(IDC_ARROW));

            MemorySegment wcx = arena.allocate(WNDCLASSEX);
            WCX_CBSIZE.set(wcx, 0L, (int) WNDCLASSEX.byteSize());       // cbSize
            WCX_STYLE.set(wcx, 0L, CS_HREDRAW_VREDRAW);                  // style
            WCX_WNDPROC.set(wcx, 0L, wndProc);                           // lpfnWndProc
            WCX_CLS_EXTRA.set(wcx, 0L, 0);                               // cbClsExtra
            WCX_WND_EXTRA.set(wcx, 0L, 0);                               // cbWndExtra
            WCX_INSTANCE.set(wcx, 0L, instance);                         // hInstance
            WCX_ICON.set(wcx, 0L, MemorySegment.NULL);                   // hIcon
            WCX_CURSOR.set(wcx, 0L, cursor);                             // hCursor
            WCX_BACKGROUND.set(wcx, 0L, MemorySegment.ofAddress(COLOR_WINDOW_PLUS_ONE)); // hbrBackground
            WCX_MENU_NAME.set(wcx, 0L, MemorySegment.NULL);              // lpszMenuName
            WCX_CLASS_NAME.set(wcx, 0L, classNameSeg);                   // lpszClassName
            WCX_ICON_SMALL.set(wcx, 0L, MemorySegment.NULL);             // hIconSm
            int registerError = (int) GET_LAST_ERROR.invoke();
            short atom = (short) REGISTER_CLASS.invoke(wcx);
            if (atom == 0) {
                throw new IllegalStateException("RegisterClassExW failed, GetLastError "
                        + (int) GET_LAST_ERROR.invoke() + " (pre-call error " + registerError + ")");
            }

            MemorySegment hwnd = (MemorySegment) CREATE_WINDOW.invoke(
                    0,                    // exStyle
                    classNameSeg, windowName,
                    WS_OVERLAPPEDWINDOW,  // style
                    x, y, width, height,
                    MemorySegment.NULL, MemorySegment.NULL, instance, MemorySegment.NULL);
            if (hwnd.equals(MemorySegment.NULL)) {
                // CreateWindowExW reports why only through GetLastError; without it the
                // failure is a shrug.
                throw new IllegalStateException("CreateWindowExW failed, GetLastError "
                        + (int) GET_LAST_ERROR.invoke());
            }
            SHOW_WINDOW.invoke(hwnd, SW_SHOW);
            System.out.println("[panamaview] window 0x" + Long.toHexString(hwnd.address())
                    + " of class '" + className + "' (register error was " + registerError + ")");
            return hwnd.reinterpret(Long.MAX_VALUE);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** {@code MAKEINTRESOURCEW}: small constants ride in the pointer itself. */
    private static MemorySegment idResource(long id) {
        return MemorySegment.ofAddress(id);
    }

    private static final class ProcedureAdapter implements Procedure {
        private final Procedure delegate;

        ProcedureAdapter(Procedure delegate) {
            this.delegate = delegate;
        }

        @Override
        public long onMessage(MemorySegment hwnd, int message, long wparam, long lparam) {
            return dispatch(hwnd, message, wparam, lparam);
        }

        long dispatch(MemorySegment hwnd, int message, long wparam, long lparam) {
            try {
                return delegate.onMessage(hwnd, message, wparam, lparam);
            } catch (Throwable t) {
                // An exception must not escape an upcall: it would surface inside Windows' own
                // callback frame. Say something and stay alive.
                t.printStackTrace();
                return 0;
            }
        }
    }

    /** Runs until WM_QUIT. Returns only when the application is done. */
    static void pump(Arena arena) {
        try {
            MemorySegment msg = arena.allocate(MSG);
            while (true) {
                int received = (int) GET_MESSAGE.invoke(msg, MemorySegment.NULL, 0, 0);
                if (received <= 0) {   // 0 = WM_QUIT, -1 = error; either ends the run
                    return;
                }
                TRANSLATE_MESSAGE.invoke(msg);
                DISPATCH_MESSAGE.invoke(msg);
            }
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static void coInitializeApartmentThreaded() {
        try {
            MethodHandle coInitialize = bind(LOOKUPS.OLE32, "CoInitializeEx",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            coInitialize.invoke(MemorySegment.NULL, 0x2 /* COINIT_APARTMENTTHREADED */);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static MethodHandle mh(Class<?> type, String name, MethodType shape, Object receiver) {
        try {
            return LOOKUP.findVirtual(type, name, shape).bindTo(receiver);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
