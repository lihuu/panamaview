package panamaview.probe;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The smoke's private Win32 and COM plumbing: the prototype of what milestone 2 promotes into
 * {@code panamaview.windows}. Everything here is written against the vtable orders and the
 * signatures of the shipped {@code WebView2.h} (SDK 1.0.4258.31), not against memory — slot
 * arithmetic wrong by one does not fail loudly, it fails sideways.
 *
 * <h2>What is deliberately unfinished</h2>
 * The handler objects never free, and report a reference count of 1 forever: they live in the
 * process-lifetime arena and die with the process, which is fine for a smoke that exits by
 * itself and exactly what the framework must do differently. QueryInterface refuses everything;
 * the loader never asks. That is a measured simplification, not a forgotten case.
 */
final class Native {

    private Native() {
    }

    static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final Arena LIFE = Arena.global();

    // ---------------------------------------------------------------- COM

    static void check(int hresult, String what) {
        if (hresult < 0) {   // the severity bit
            throw new IllegalStateException(what + " failed: HRESULT 0x" + Integer.toHexString(hresult));
        }
    }

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
    private static final FunctionDescriptor QI =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor REF =
            FunctionDescriptor.of(JAVA_INT, ADDRESS);

    /** A four-slot COM object whose only live method is Invoke, backed by {@code body}. */
    static MemorySegment completedHandler(Completed body) {
        return comObject(COMPLETED_INVOKE, mh(Completed.class, "invoke",
                MethodType.methodType(int.class, MemorySegment.class, int.class, MemorySegment.class), body));
    }

    static MemorySegment eventHandler(Event body) {
        return comObject(EVENT_INVOKE, mh(Event.class, "invoke",
                MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class), body));
    }

    private static MethodHandle mh(Class<?> type, String name, MethodType shape, Object receiver) {
        try {
            return LOOKUP.findVirtual(type, name, shape).bindTo(receiver);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MemorySegment comObject(FunctionDescriptor invokeDescriptor, MethodHandle invoke) {
        MemorySegment vtbl = LIFE.allocate(ADDRESS, 4);
        vtbl.setAtIndex(ADDRESS, 0, LINKER.upcallStub(QI_MH, QI, LIFE));
        vtbl.setAtIndex(ADDRESS, 1, LINKER.upcallStub(ONE_MH, REF, LIFE));
        vtbl.setAtIndex(ADDRESS, 2, LINKER.upcallStub(ONE_MH, REF, LIFE));
        vtbl.setAtIndex(ADDRESS, 3, LINKER.upcallStub(invoke, invokeDescriptor, LIFE));
        MemorySegment object = LIFE.allocate(ADDRESS);
        object.setAtIndex(ADDRESS, 0, vtbl);
        return object;
    }

    private static int queryInterface(MemorySegment self, MemorySegment riid, MemorySegment out) {
        return 0x80004002;   // E_NOINTERFACE
    }

    private static int one(MemorySegment self) {
        return 1;
    }

    private static final MethodHandle QI_MH;
    private static final MethodHandle ONE_MH;

    static {
        try {
            QI_MH = LOOKUP.findStatic(Native.class, "queryInterface", MethodType.methodType(
                    int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class));
            ONE_MH = LOOKUP.findStatic(Native.class, "one", MethodType.methodType(int.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ---------------------------------------------------------------- WebView2

    /** Slot 3 of ICoreWebView2Environment (after IUnknown's three). */
    private static final int ENV_CREATE_CONTROLLER = 3;

    /** ICoreWebView2Controller slots, from the shipped WebView2.h. */
    private static final int CONTROLLER_PUT_IS_VISIBLE = 4;
    private static final int CONTROLLER_PUT_BOUNDS = 6;
    private static final int CONTROLLER_GET_CORE_WEBVIEW2 = 25;

    /** ICoreWebView2 slots, from the shipped WebView2.h. The numbers are read out of the
     * DECLSPEC_XFGVIRT annotations, whose Nth line is slot N+2 after IUnknown's three - an
     * off-by-one here registers the remove_ listener where the add_ belongs and waits forever
     * for an event that was never asked for. */
    private static final int WEBVIEW_GET_SOURCE = 4;
    private static final int WEBVIEW_NAVIGATE = 5;
    private static final int WEBVIEW_NAVIGATE_TO_STRING = 6;
    private static final int WEBVIEW_ADD_NAVIGATION_COMPLETED = 15;
    private static final int WEBVIEW_EXECUTE_SCRIPT = 29;
    private static final int WEBVIEW_GET_DOCUMENT_TITLE = 48;

    private static final FunctionDescriptor HR_THIS_PTR = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    private static final FunctionDescriptor HR_THIS_PTR_PTR = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor HR_THIS_PTR_INT = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);

    static SymbolLookup loader(Path dll) {
        return SymbolLookup.libraryLookup(dll, LIFE);
    }

    /**
     * {@code HRESULT CreateCoreWebView2EnvironmentWithOptions(PCWSTR folder, PCWSTR userData,
     * ICoreWebView2EnvironmentOptions*, ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler*)}
     * — exported by the loader, not a vtable method. A null folder asks for the Evergreen
     * runtime; a null options object asks for defaults.
     */
    static void createEnvironment(SymbolLookup loader, MemorySegment userDataFolder, MemorySegment handler) {
        MethodHandle create = LINKER.downcallHandle(loader.find("CreateCoreWebView2EnvironmentWithOptions").orElseThrow(
                        () -> new UnsatisfiedLinkError("CreateCoreWebView2EnvironmentWithOptions not in the loader")),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        call("CreateCoreWebView2EnvironmentWithOptions", create,
                MemorySegment.NULL, userDataFolder, MemorySegment.NULL, handler);
    }

    /** Reads one vtable slot of a COM object and wraps it as a downcall. */
    private static MethodHandle slot(MemorySegment object, int index, FunctionDescriptor fd) {
        // The vtable pointer read out of the object is itself a zero-length segment - the same
        // rule as every other pointer crossing the boundary - so it is reinterpreted before
        // its own slot read. Third time this rule bit this file; it goes in the NOTES.
        MemorySegment vtable = object.getAtIndex(ADDRESS, 0).reinterpret(Long.MAX_VALUE);
        return LINKER.downcallHandle(vtable.getAtIndex(ADDRESS, index), fd);
    }

    static void createController(MemorySegment environment, MemorySegment hwnd, MemorySegment handler) {
        call("CreateCoreWebView2Controller", slot(environment, ENV_CREATE_CONTROLLER,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)),
                environment, hwnd, handler);
    }

    static MemorySegment coreWebView2(MemorySegment controller) {
        MemorySegment out = LIFE.allocate(ADDRESS);
        call("get_CoreWebView2", slot(controller, CONTROLLER_GET_CORE_WEBVIEW2, HR_THIS_PTR_PTR),
                controller, out);
        // A pointer that crosses from native into Java arrives as a zero-length segment;
        // reinterpret gives it back the length its bytes imply. Every native-received pointer
        // in this file gets the same treatment before its first dereference.
        return out.getAtIndex(ADDRESS, 0).reinterpret(Long.MAX_VALUE);
    }

    static void putIsVisible(MemorySegment controller, boolean visible) {
        call("put_IsVisible", slot(controller, CONTROLLER_PUT_IS_VISIBLE, HR_THIS_PTR_INT),
                controller, visible ? 1 : 0);
    }

    static void putBounds(MemorySegment controller, MemorySegment rect) {
        // RECT is 16 bytes: past the 8-byte register cutoff, so the x64 ABI passes a pointer to
        // a temporary. The linker knows; the descriptor just says struct by value.
        call("put_Bounds", slot(controller, CONTROLLER_PUT_BOUNDS,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, RECT)),
                controller, rect);
    }

    static void navigate(MemorySegment webview, MemorySegment uri) {
        call("Navigate", slot(webview, WEBVIEW_NAVIGATE, HR_THIS_PTR_PTR), webview, uri);
    }

    static void navigateToString(MemorySegment webview, String html) {
        try (Arena confined = Arena.ofConfined()) {
            call("NavigateToString", slot(webview, WEBVIEW_NAVIGATE_TO_STRING, HR_THIS_PTR_PTR),
                    webview, confined.allocateFrom(html, StandardCharsets.UTF_16LE));
        }
    }

    static void addNavigationCompleted(MemorySegment webview, MemorySegment handler) {
        MemorySegment token = LIFE.allocate(JAVA_LONG);   // EventRegistrationToken: one 64-bit value
        call("add_NavigationCompleted", slot(webview, WEBVIEW_ADD_NAVIGATION_COMPLETED,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)),
                webview, handler, token);
    }

    /** The page currently loaded, or null. Used to tell our navigation from the initial one. */
    static String currentSource(MemorySegment webview) {
        MemorySegment out = LIFE.allocate(ADDRESS);
        call("get_Source", slot(webview, WEBVIEW_GET_SOURCE, HR_THIS_PTR_PTR), webview, out);
        MemorySegment value = out.getAtIndex(ADDRESS, 0);
        return value.equals(MemorySegment.NULL) ? null
                : value.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
    }

    /** The loaded page's title: how the smoke tells its own navigation from the initial one. */
    static String documentTitle(MemorySegment webview) {
        MemorySegment out = LIFE.allocate(ADDRESS);
        call("get_DocumentTitle", slot(webview, WEBVIEW_GET_DOCUMENT_TITLE, HR_THIS_PTR_PTR), webview, out);
        MemorySegment value = out.getAtIndex(ADDRESS, 0);
        return value.equals(MemorySegment.NULL) ? null
                : value.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
    }

    static void executeScript(MemorySegment webview, String script, MemorySegment handler) {
        try (Arena confined = Arena.ofConfined()) {
            call("ExecuteScript", slot(webview, WEBVIEW_EXECUTE_SCRIPT,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)),
                    webview, confined.allocateFrom(script, StandardCharsets.UTF_16LE), handler);
        }
    }

    // ---------------------------------------------------------------- Win32

    /** {@code {LONG left; LONG top; LONG right; LONG bottom}} — 16 bytes. */
    static final MemoryLayout RECT = MemoryLayout.structLayout(
            JAVA_INT.withName("left"), JAVA_INT.withName("top"),
            JAVA_INT.withName("right"), JAVA_INT.withName("bottom"));

    private static final MemoryLayout WNDCLASSEX = MemoryLayout.structLayout(
            JAVA_INT.withName("cbSize"), JAVA_INT.withName("style"),
            ADDRESS.withName("lpfnWndProc"), JAVA_INT.withName("cbClsExtra"), JAVA_INT.withName("cbWndExtra"),
            ADDRESS.withName("hInstance"), ADDRESS.withName("hIcon"), ADDRESS.withName("hCursor"),
            ADDRESS.withName("hbrBackground"), ADDRESS.withName("lpszMenuName"), ADDRESS.withName("lpszClassName"),
            ADDRESS.withName("hIconSm"));

    // Field handles, not raw element indexes: the struct mixes 4- and 8-byte fields, and an
    // ADDRESS element index on a mixed layout is a byte-offset lie (this file already made
    // that mistake once - the crash named it).
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

    /** {@code {HWND; UINT; WPARAM; LPARAM; DWORD time; POINT pt; DWORD lPrivate}} — 48 bytes on x64. */
    private static final MemoryLayout MSG = MemoryLayout.structLayout(
            ADDRESS.withName("hwnd"), JAVA_INT.withName("message"), MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("wParam"), JAVA_LONG.withName("lParam"),
            JAVA_INT.withName("time"), JAVA_INT.withName("ptX"), JAVA_INT.withName("ptY"),
            MemoryLayout.paddingLayout(4));

    private static final int WM_DESTROY = 0x0002;
    private static final int WM_QUIT = 0x0012;
    private static final int CS_HREDRAW_VREDRAW = 0x0003;
    private static final int WS_OVERLAPPEDWINDOW = 0x00CF0000;
    private static final int CW_USEDEFAULT = 0x80000000;
    private static final int SW_SHOW = 5;
    private static final long COLOR_WINDOW_PLUS_ONE = 6;
    private static final long IDC_ARROW = 32512;

    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32.dll", LIFE);
    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", LIFE);
    private static final SymbolLookup OLE32 = SymbolLookup.libraryLookup("ole32.dll", LIFE);

    private static MethodHandle bind(SymbolLookup lookup, String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), fd);
    }

    private static final MethodHandle GET_MODULE_HANDLE =
            bind(KERNEL32, "GetModuleHandleW", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GET_CURRENT_THREAD_ID =
            bind(KERNEL32, "GetCurrentThreadId", FunctionDescriptor.of(JAVA_INT));
    // PostThreadMessage is a USER32 function, not KERNEL32 - the names mislead, the headers do
    // not. Four arguments: DWORD idThread, UINT msg, WPARAM, LPARAM - dropping the UINT from
    // the descriptor strands the pump with no way out, which is how a run hangs.
    private static final MethodHandle POST_THREAD_MESSAGE =
            bind(USER32, "PostThreadMessageW", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle CO_INITIALIZE =
            bind(OLE32, "CoInitializeEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle REGISTER_CLASS =
            bind(USER32, "RegisterClassExW", FunctionDescriptor.of(JAVA_SHORT, ADDRESS));
    private static final MethodHandle CREATE_WINDOW =
            bind(USER32, "CreateWindowExW", FunctionDescriptor.of(ADDRESS,
                    JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle SHOW_WINDOW =
            bind(USER32, "ShowWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle LOAD_CURSOR =
            bind(USER32, "LoadCursorW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GET_CLIENT_RECT =
            bind(USER32, "GetClientRect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle DEF_WINDOW_PROC =
            bind(USER32, "DefWindowProcW", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle POST_QUIT_MESSAGE =
            bind(USER32, "PostQuitMessage", FunctionDescriptor.ofVoid(JAVA_INT));
    private static final MethodHandle GET_MESSAGE =
            bind(USER32, "GetMessageW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle TRANSLATE_MESSAGE =
            bind(USER32, "TranslateMessage", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle DISPATCH_MESSAGE =
            bind(USER32, "DispatchMessageW", FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    /** COM apartment threading: WebView2 wants the creating thread to be an STA that pumps. */
    static void coInitializeApartmentThreaded() {
        call("CoInitializeEx", CO_INITIALIZE,
                MemorySegment.NULL, 0x2 /* COINIT_APARTMENTTHREADED */);
    }

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

    static MemorySegment clientRect(Arena arena, MemorySegment hwnd) {
        MemorySegment rect = arena.allocate(RECT);
        call("GetClientRect", GET_CLIENT_RECT, hwnd, rect);
        return rect;
    }

    /**
     * Registers the class, creates and shows the window, returns the HWND. The window procedure
     * is an upcall stub: {@code WM_DESTROY} quits, everything else goes to {@code DefWindowProcW}
     * — a window procedure that ate a message would eat the behaviour behind it.
     */
    static MemorySegment createWindow(Arena arena, String title, int width, int height) {
        MemorySegment wndProc = LINKER.upcallStub(
                mh(WndProcHandler.class, "dispatch",
                        MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class),
                        new WndProcHandler()),
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG), LIFE);

        try (Arena confined = Arena.ofConfined()) {
            MemorySegment className = confined.allocateFrom("panamaview-smoke", StandardCharsets.UTF_16LE);
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
            WCX_CLASS_NAME.set(wcx, 0L, className);                      // lpszClassName
            WCX_ICON_SMALL.set(wcx, 0L, MemorySegment.NULL);             // hIconSm
            short atom = (short) REGISTER_CLASS.invoke(wcx);
            if (atom == 0) {
                throw new IllegalStateException("RegisterClassExW failed");
            }

            MemorySegment hwnd = (MemorySegment) CREATE_WINDOW.invoke(
                    0,                    // exStyle
                    className, windowName,
                    WS_OVERLAPPEDWINDOW,  // style
                    CW_USEDEFAULT, CW_USEDEFAULT, width, height,
                    MemorySegment.NULL, MemorySegment.NULL, instance, MemorySegment.NULL);
            if (hwnd.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("CreateWindowExW failed");
            }
            SHOW_WINDOW.invoke(hwnd, SW_SHOW);
            return hwnd;
        } catch (Throwable t) {
            throw t instanceof RuntimeException r ? r : new IllegalStateException(t);
        }
    }

    /** {@code MAKEINTRESOURCEW}: small constants ride in the pointer itself. */
    private static MemorySegment idResource(long id) {
        return MemorySegment.ofAddress(id);
    }

    private static final class WndProcHandler {
        long dispatch(MemorySegment hwnd, int message, long wparam, long lparam) {
            try {
                if (message == WM_DESTROY) {
                    postQuit();
                    return 0;
                }
                return (long) DEF_WINDOW_PROC.invoke(hwnd, message, wparam, lparam);
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
            throw t instanceof RuntimeException r ? r : new IllegalStateException(t);
        }
    }

    // ---------------------------------------------------------------- calls

    /**
     * Invokes the handle and checks its HRESULT. The name comes first on purpose: as a trailing
     * argument it would be indistinguishable from the call's own arguments, which is a mistake
     * this signature makes impossible.
     */
    private static void call(String what, MethodHandle handle, Object... args) {
        try {
            int hresult = (int) handle.invokeWithArguments(args);
            check(hresult, what);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(what + " could not be called", t);
        }
    }
}
