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
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
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
    static final int WM_COMMAND = 0x0111;
    static final int WM_DPICHANGED = 0x02E0;
    static final int WM_APP_RUNNABLE = 0x8000;   // WM_APP: for the host's own use
    /** WM_APP + 1: the tray icon's callback message, distinct from the runnable nudge. */
    static final int WM_APP_TRAY = 0x8001;
    /** WM_NULL — the documented post-TrackPopupMenu dismissal nudge, and nothing else. */
    static final int WM_NULL = 0x0000;

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

    /** {@code {LONG x; LONG y}} — the cursor position {@code GetCursorPos} fills. */
    static final MemoryLayout POINT = MemoryLayout.structLayout(
            JAVA_INT.withName("x"), JAVA_INT.withName("y"));

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
        final java.lang.foreign.SymbolLookup SHELL32 = java.lang.foreign.SymbolLookup.libraryLookup("shell32.dll", LIFE);
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
    // DPI. Both calls are Windows 10 1607/1703 and later, and WebView2 already requires a
    // Windows 10 that has them, so they are ordinary bindings rather than optional ones.
    private static final MethodHandle SET_PROCESS_DPI_AWARENESS_CONTEXT =
            bind(LOOKUPS.USER32, "SetProcessDpiAwarenessContext",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GET_DPI_FOR_SYSTEM =
            bind(LOOKUPS.USER32, "GetDpiForSystem", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle GET_DPI_FOR_WINDOW =
            bind(LOOKUPS.USER32, "GetDpiForWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GET_THREAD_DPI_AWARENESS_CONTEXT =
            bind(LOOKUPS.USER32, "GetThreadDpiAwarenessContext", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle SET_THREAD_DPI_AWARENESS_CONTEXT =
            bind(LOOKUPS.USER32, "SetThreadDpiAwarenessContext", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GET_AWARENESS_FROM_DPI_AWARENESS_CONTEXT =
            bind(LOOKUPS.USER32, "GetAwarenessFromDpiAwarenessContext",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SET_WINDOW_POS =
            bind(LOOKUPS.USER32, "SetWindowPos",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT,
                            JAVA_INT, JAVA_INT, JAVA_INT));
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

    private static final MethodHandle CREATE_MENU =
            bind(LOOKUPS.USER32, "CreateMenu", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle APPEND_MENU =
            bind(LOOKUPS.USER32, "AppendMenuW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS));
    private static final MethodHandle DESTROY_MENU =
            bind(LOOKUPS.USER32, "DestroyMenu", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GET_MENU_ITEM_COUNT =
            bind(LOOKUPS.USER32, "GetMenuItemCount", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GET_MENU_ITEM_ID =
            bind(LOOKUPS.USER32, "GetMenuItemID", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle GET_MENU_STRING =
            bind(LOOKUPS.USER32, "GetMenuStringW",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle GET_SUB_MENU =
            bind(LOOKUPS.USER32, "GetSubMenu", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

    // The tray. Shell_NotifyIconW is the whole notification-area API in one function: the
    // same call adds, modifies and removes the icon, and the code says which with dwMessage.
    private static final MethodHandle SHELL_NOTIFY_ICON =
            bind(LOOKUPS.SHELL32, "Shell_NotifyIconW", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle REGISTER_WINDOW_MESSAGE =
            bind(LOOKUPS.USER32, "RegisterWindowMessageW", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GET_CURSOR_POS =
            bind(LOOKUPS.USER32, "GetCursorPos", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle TRACK_POPUP_MENU =
            bind(LOOKUPS.USER32, "TrackPopupMenu",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle SET_FOREGROUND_WINDOW =
            bind(LOOKUPS.USER32, "SetForegroundWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle LOAD_IMAGE =
            bind(LOOKUPS.USER32, "LoadImageW",
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle LOAD_ICON =
            bind(LOOKUPS.USER32, "LoadIconW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    private static final MethodHandle OPEN_CLIPBOARD =
            bind(LOOKUPS.USER32, "OpenClipboard", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CLOSE_CLIPBOARD =
            bind(LOOKUPS.USER32, "CloseClipboard", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle EMPTY_CLIPBOARD =
            bind(LOOKUPS.USER32, "EmptyClipboard", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle GET_CLIPBOARD_DATA =
            bind(LOOKUPS.USER32, "GetClipboardData", FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle SET_CLIPBOARD_DATA =
            bind(LOOKUPS.USER32, "SetClipboardData", FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle CLIPBOARD_SEQUENCE =
            bind(LOOKUPS.USER32, "GetClipboardSequenceNumber", FunctionDescriptor.of(JAVA_INT));
    // A global block is what the clipboard can own: it must be movable, because Windows moves the
    // memory itself when the pasteboard's contents change hands between processes.
    private static final MethodHandle GLOBAL_ALLOC =
            bind(LOOKUPS.KERNEL32, "GlobalAlloc", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_LONG));
    private static final MethodHandle GLOBAL_LOCK =
            bind(LOOKUPS.KERNEL32, "GlobalLock", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GLOBAL_UNLOCK =
            bind(LOOKUPS.KERNEL32, "GlobalUnlock", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GLOBAL_FREE =
            bind(LOOKUPS.KERNEL32, "GlobalFree", FunctionDescriptor.of(ADDRESS, ADDRESS));

    // CoCreateInstance is how every COM object gets made — the common item dialogs included.
    // HRESULT(REFCLSID rclsid, void* pUnkOuter, DWORD dwClsContext, REFIID riid, void** ppv).
    private static final MethodHandle CO_CREATE_INSTANCE =
            bind(LOOKUPS.OLE32, "CoCreateInstance",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    // The COM task allocator: what GetDisplayName's returned strings are made of, and the
    // only thing that may free them.
    static final MethodHandle CO_TASK_MEM_FREE =
            bind(LOOKUPS.OLE32, "CoTaskMemFree", FunctionDescriptor.ofVoid(ADDRESS));

    private static final int CS_HREDRAW_VREDRAW = 0x0003;
    private static final int WS_OVERLAPPEDWINDOW = 0x00CF0000;
    private static final int CW_USEDEFAULT = 0x80000000;
    private static final int SW_SHOW = 5;
    /** SW_HIDE — the put-away a tray application's close becomes. */
    static final int SW_HIDE = 0;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final long COLOR_WINDOW_PLUS_ONE = 6;
    private static final long IDC_ARROW = 32512;
    /** IDI_APPLICATION — the stock icon a tray falls back to when the application brings none. */
    private static final long IDI_APPLICATION = 32512;
    /** IMAGE_ICON — the LoadImageW type for an icon handle. */
    private static final int IMAGE_ICON = 1;
    /** LR_LOADFROMFILE — LoadImageW reads the icon from a file instead of a resource. */
    private static final int LR_LOADFROMFILE = 0x0010;

    /**
     * {@code DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2}: the context that makes Windows hand a
     * process physical pixels and tell it, through {@code WM_DPICHANGED}, when the monitor's DPI
     * changes. A pseudo-handle, hence the negative value.
     */
    private static final long DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = -4L;

    /** CF_UNICODETEXT — what a text field pastes from. */
    private static final int CF_UNICODETEXT = 13;
    private static final int GMEM_MOVEABLE = 0x0002;

    /**
     * {@code CLSCTX_INPROC_SERVER} — the dialogs live in-process in shell32's neighbour,
     * and every COM object this framework makes is an in-proc one.
     */
    static final int CLSCTX_INPROC_SERVER = 0x1;

    /**
     * Makes a COM object: {@code CoCreateInstance(rclsid, null, CLSCTX_INPROC_SERVER, riid, &out)}.
     *
     * <p>The GUIDs arrive already parsed by {@link Com#guid}, so the caller states which class
     * and which interface it means by name; this writes the little-endian bytes, makes the
     * call, and hands back the interface pointer the caller asked for. Must run on the
     * apartment thread — the pump thread, where {@link #coInitializeApartmentThreaded} ran.
     */
    static MemorySegment coCreateInstance(byte[] clsid, byte[] iid, String what) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment clsidSegment = arena.allocate(16);
            MemorySegment.copy(MemorySegment.ofArray(clsid), 0, clsidSegment, 0, 16);
            MemorySegment iidSegment = arena.allocate(16);
            MemorySegment.copy(MemorySegment.ofArray(iid), 0, iidSegment, 0, 16);
            MemorySegment out = arena.allocate(ADDRESS);
            int hresult = (int) CO_CREATE_INSTANCE.invoke(
                    clsidSegment, MemorySegment.NULL, CLSCTX_INPROC_SERVER, iidSegment, out);
            Com.check(hresult, what);
            MemorySegment created = out.getAtIndex(ADDRESS, 0);
            if (created.address() == 0) {
                throw new IllegalStateException(what + " succeeded but returned nothing");
            }
            return created.reinterpret(Long.MAX_VALUE);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(what + " could not be called", t);
        }
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

    /** Shows or hides a window: {@code SW_SHOW} for a birth, {@code SW_HIDE} for a put-away. */
    static void showWindow(MemorySegment hwnd, int command) {
        try {
            SHOW_WINDOW.invoke(hwnd, command);
        } catch (Throwable t) {
            throw new IllegalStateException("ShowWindow could not be called", t);
        }
    }

    /**
     * Asks the window's own procedure, from any thread, to run something on the pump thread.
     *
     * <p>The result is dropped on purpose, and unlike the menu's posts this one is not fatal:
     * it is a nudge, and the queue it wakes is the source of truth. A nudge that did not
     * arrive costs a wake-up, not a message.
     */
    static void postRunnable(MemorySegment hwnd, long wparamForFlags) {
        postMessage(hwnd, WM_APP_RUNNABLE, wparamForFlags, 0L);
    }

    /**
     * Posts a message to a window.
     *
     * <p>A click on a menu item is a message, so a synthesized click is one too: that is how
     * the self-test chooses an item without a human, and how the Quit operation asks the
     * window to close.
     */
    static boolean postMessage(MemorySegment hwnd, int message, long wparam, long lparam) {
        try {
            return (int) POST_MESSAGE.invoke(hwnd, message, wparam, lparam) != 0;
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
     * Makes the process per-monitor DPI aware, before its first window exists.
     *
     * <p>Without this the process is DPI-unaware: Windows never tells it the monitor's DPI and
     * bitmap-scales everything it draws. On a 200% display, a window asked for as 1180×760 is
     * drawn at 96 DPI and then blown up to 2360×1520 — it covers most of a 4K screen and every
     * glyph is a stretched 96-DPI glyph.
     *
     * <p>The call fails harmlessly when the process is already aware — a second call, or a
     * manifest that declared awareness — and the window keeps whatever awareness it had.
     */
    static void enableDpiAwareness() {
        try {
            MemorySegment perMonitorV2 = MemorySegment.ofAddress(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
            int ok = (int) SET_PROCESS_DPI_AWARENESS_CONTEXT.invokeExact(perMonitorV2);
            int error = ok == 0 ? (int) GET_LAST_ERROR.invoke() : 0;
            System.out.println("[panamaview] process dpi awareness: " + awarenessName()
                    + (ok == 0 ? " (the call was refused, GetLastError " + error + ")" : ""));
        } catch (Throwable t) {
            System.out.println("[panamaview] process dpi awareness could not be set: " + t);
        }
    }

    /**
     * Sets this thread's DPI awareness before it creates a window.
     *
     * <p>The process-level call is refused on some images — Windows answers ERROR_ACCESS_DENIED
     * — because the awareness was already fixed when the process started. A thread may still
     * choose its own context, and the window it creates follows that choice. This is the call
     * the window actually depends on.
     */
    static void enableThreadDpiAwareness() {
        try {
            // The call returns the previous context; the value is not used, but invokeExact is
            // signature-polymorphic and infers a void return when the call is a statement.
            MemorySegment previous = (MemorySegment) SET_THREAD_DPI_AWARENESS_CONTEXT.invokeExact(
                    MemorySegment.ofAddress(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2));
            System.out.println("[panamaview] ui-thread dpi awareness: " + awarenessName()
                    + " (was " + previous.address() + ")");
        } catch (Throwable t) {
            System.out.println("[panamaview] ui-thread dpi awareness could not be set: " + t);
        }
    }

    /** The calling thread's DPI awareness, as a word for the log. */
    private static String awarenessName() {
        try {
            int awareness = (int) GET_AWARENESS_FROM_DPI_AWARENESS_CONTEXT.invokeExact(
                    (MemorySegment) GET_THREAD_DPI_AWARENESS_CONTEXT.invokeExact());
            return switch (awareness) {
                case 0 -> "unaware";
                case 1 -> "system";
                case 2 -> "per-monitor";
                default -> "unknown(" + awareness + ")";
            };
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** The system DPI, or 96 where the query fails. */
    static int systemDpi() {
        try {
            return (int) GET_DPI_FOR_SYSTEM.invokeExact();
        } catch (Throwable t) {
            return Dpi.BASE;
        }
    }

    /** The DPI of the monitor a window is on, or 0 when it cannot be asked. */
    static int dpiForWindow(MemorySegment hwnd) {
        try {
            return (int) GET_DPI_FOR_WINDOW.invokeExact(hwnd);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Applies the rectangle a {@code WM_DPICHANGED} carries: the new position and size Windows
     * computed for the monitor the window has just moved to.
     *
     * <p>Not optional. A per-monitor-aware window that ignores the suggestion keeps the size it
     * had for the old DPI, so dragging between monitors leaves it either too small or too large.
     */
    static void applySuggestedRect(MemorySegment hwnd, long pointer) {
        if (pointer == 0) {
            return;
        }
        MemorySegment rect = MemorySegment.ofAddress(pointer).reinterpret(RECT.byteSize());
        int left = rect.get(JAVA_INT, 0);
        int top = rect.get(JAVA_INT, 4);
        int right = rect.get(JAVA_INT, 8);
        int bottom = rect.get(JAVA_INT, 12);
        try {
            SET_WINDOW_POS.invoke(hwnd, MemorySegment.NULL, left, top,
                    right - left, bottom - top, SWP_NOZORDER | SWP_NOACTIVATE);
        } catch (Throwable t) {
            throw new IllegalStateException("SetWindowPos could not be called", t);
        }
    }

    /**
     * Registers the class, creates and shows the window, returns the HWND. The window procedure
     * is an upcall stub bound to {@code procedure}: the host answers the messages it cares
     * about, and everything else goes to {@code DefWindowProcW} — a window procedure that ate a
     * message would eat the behaviour behind it.
     *
     * <p>{@code width} and {@code height} are logical pixels, the size a caller means at 96 DPI.
     * The process is per-monitor DPI aware, so they are converted to the monitor's physical
     * pixels here rather than left for the system to scale.
     *
     * <p>The menu goes in at creation time, in {@code CreateWindowExW}'s {@code hMenu} slot,
     * and not through {@code SetMenu} afterwards: this method shows the window before it
     * returns, so a menu attached later would land on a window that is already on screen, and
     * the window would flash up without its menu.
     */
    static MemorySegment createWindow(Arena arena, String title, int width, int height,
                                      MemorySegment hMenu, Procedure procedure) {
        return createWindow(arena, title, width, height, CW_USEDEFAULT, CW_USEDEFAULT, hMenu, procedure);
    }

    /**
     * The same, at a caller-chosen position.
     *
     * <p>{@code CreateWindowExW} takes the position as two ordinary arguments, so placement needs
     * no second call and no window-manager negotiation: the window is simply born there. The
     * coordinates are virtual-screen pixels measured from the top-left of the primary monitor —
     * the same numbers, in the same pass-through spirit, as {@link io.github.lihuu.panamaview.WindowSpec.Origin}.
     */
    static MemorySegment createWindow(Arena arena, String title, int width, int height,
                                      int x, int y, MemorySegment hMenu, Procedure procedure) {
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

            // The system DPI is the best guess before there is a window; the window's own
            // monitor is authoritative, and it is knowable as soon as the window exists. The
            // system value is wrong in a service session (96 regardless of the user's display)
            // and for a window that lands on a secondary monitor.
            int dpi = systemDpi();
            int physicalWidth = Dpi.scale(width, dpi);
            int physicalHeight = Dpi.scale(height, dpi);
            MemorySegment hwnd = (MemorySegment) CREATE_WINDOW.invoke(
                    0,                    // exStyle
                    classNameSeg, windowName,
                    WS_OVERLAPPEDWINDOW,  // style
                    x, y, physicalWidth, physicalHeight,
                    MemorySegment.NULL,   // hWndParent
                    // hMenu — the menu is part of the window's birth, and a null one means
                    // the window has no menu at all. The linker wants the null address, not
                    // a null segment.
                    hMenu == null ? MemorySegment.NULL : hMenu,
                    instance, MemorySegment.NULL);
            if (hwnd.equals(MemorySegment.NULL)) {
                // CreateWindowExW reports why only through GetLastError; without it the
                // failure is a shrug.
                throw new IllegalStateException("CreateWindowExW failed, GetLastError "
                        + (int) GET_LAST_ERROR.invoke());
            }
            int windowDpi = dpiForWindow(hwnd);
            if (windowDpi > 0 && windowDpi != dpi) {
                dpi = windowDpi;
                physicalWidth = Dpi.scale(width, dpi);
                physicalHeight = Dpi.scale(height, dpi);
                // Not shown yet, so the correction is not visible. WM_SIZE follows and the web
                // view is sized from the client rectangle.
                SET_WINDOW_POS.invoke(hwnd, MemorySegment.NULL, 0, 0, physicalWidth,
                        physicalHeight, SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE);
            }
            SHOW_WINDOW.invoke(hwnd, SW_SHOW);
            System.out.println("[panamaview] window 0x" + Long.toHexString(hwnd.address())
                    + " of class '" + className + "' (register error was " + registerError + ")");
            System.out.println("[panamaview] dpi " + dpi + ", window " + physicalWidth + "x"
                    + physicalHeight + " (asked for " + width + "x" + height + ")");
            return hwnd.reinterpret(Long.MAX_VALUE);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    // ------------------------------------------------------------------ the menu

    /**
     * A new, empty menu.
     *
     * <p>The handle is an {@code HMENU}: an opaque pointer, which the FFM layer carries as an
     * address.
     */
    static MemorySegment createMenu() {
        try {
            return (MemorySegment) CREATE_MENU.invoke();
        } catch (Throwable t) {
            throw new IllegalStateException("CreateMenu could not be called", t);
        }
    }

    /**
     * Appends an item to a menu.
     *
     * <p>{@code AppendMenuW}'s third parameter is a {@code UINT_PTR} — a pointer-sized
     * <em>integer</em> — and it carries two different things: the command id of an ordinary
     * item, and the submenu's handle when {@code MF_POPUP} is set. Both are numbers, so both
     * travel as a long, and a handle is its own address.
     *
     * <p>The text is a null-terminated UTF-16 string, allocated the way the window title is:
     * {@code allocateFrom} adds the terminator, and the terminator is two bytes wide for
     * UTF-16, so a two-character title takes six bytes.
     *
     * @param text the item's title, or null for a separator, whose text is ignored
     */
    static boolean appendMenu(Arena arena, MemorySegment menu, int flags, long id, String text) {
        MemorySegment label = text == null
                ? MemorySegment.NULL
                : arena.allocateFrom(text, StandardCharsets.UTF_16LE);
        try {
            return (int) APPEND_MENU.invoke(menu, flags, id, label) != 0;
        } catch (Throwable t) {
            throw new IllegalStateException("AppendMenuW could not be called", t);
        }
    }

    /** Frees a menu the framework built. */
    static boolean destroyMenu(MemorySegment menu) {
        try {
            return (int) DESTROY_MENU.invoke(menu) != 0;
        } catch (Throwable t) {
            throw new IllegalStateException("DestroyMenu could not be called", t);
        }
    }

    /** How many items a menu holds, separators included. */
    static int getMenuItemCount(MemorySegment menu) {
        try {
            return (int) GET_MENU_ITEM_COUNT.invoke(menu);
        } catch (Throwable t) {
            throw new IllegalStateException("GetMenuItemCount could not be called", t);
        }
    }

    /**
     * The command id of an item, by its position.
     *
     * <p>Measured against the real Win32: a separator's id is 0, and a popup's is
     * {@code 0xFFFFFFFF}. Neither is a command id, and the caller has to expect them as such.
     */
    static int getMenuItemId(MemorySegment menu, int position) {
        try {
            return (int) GET_MENU_ITEM_ID.invoke(menu, position);
        } catch (Throwable t) {
            throw new IllegalStateException("GetMenuItemID could not be called", t);
        }
    }

    /**
     * The title of an item, by its position.
     *
     * <p>The flags are the caller's: {@code MF_BYPOSITION} asks for the item at that position
     * rather than the item with that id, and the menu flags are the plan's vocabulary, not
     * this class's.
     *
     * <p>{@code GetMenuStringW} returns the number of characters it wrote, so a title that
     * fills the buffer is truncated — and a truncated title is a failure, not a pass.
     *
     * <p>The guard below is {@code written + 1 >= maxChars}, and the {@code + 1} is the whole
     * point. {@code cchMax} is the buffer's size in characters <em>including</em> the null
     * terminator, so the function writes at most {@code maxChars - 1} characters and returns
     * that count — which is why it returns 511 for a 512-character buffer. A guard of
     * {@code written >= maxChars} therefore cannot ever be true, and a title too long for the
     * buffer came back truncated and was reported as a <em>mismatch</em>: the read-back
     * compared a cut-short title with the model's title and said they differed, printing two
     * strings that are the same to the eye.
     *
     * <p>What the guard can see is that the buffer was filled to its last character slot,
     * which is either a title of exactly {@code maxChars - 1} characters or a longer one cut
     * short — the return value cannot tell the two apart. So both are called "did not fit",
     * which is the safe direction: a title that long is a bug anyway, and a false "did not
     * fit" is better than a truncated title reported as a difference that is not there.
     *
     * <p>The bug was found by the Windows self-test's watchdog experiment, on the box: a menu
     * with a 602-character title, which is what the experiment needed to stop the application
     * before its window existed.
     */
    static String getMenuString(Arena arena, MemorySegment menu, int position, int maxChars, int flags) {
        MemorySegment buffer = arena.allocate((long) maxChars * 2);
        int written;
        try {
            written = (int) GET_MENU_STRING.invoke(menu, position, buffer, maxChars, flags);
        } catch (Throwable t) {
            throw new IllegalStateException("GetMenuStringW could not be called", t);
        }
        if (written + 1 >= maxChars) {
            throw new IllegalStateException("a menu title did not fit in " + maxChars + " characters");
        }
        // UTF-16, and the count is in characters: read them one code unit at a time, so a
        // surrogate pair stays the two chars it is.
        StringBuilder title = new StringBuilder(written);
        for (int i = 0; i < written; i++) {
            title.append((char) buffer.get(JAVA_SHORT, (long) i * 2));
        }
        return title.toString();
    }

    /** The submenu of a popup item, by its position. */
    static MemorySegment getSubMenu(MemorySegment menu, int position) {
        try {
            return (MemorySegment) GET_SUB_MENU.invoke(menu, position);
        } catch (Throwable t) {
            throw new IllegalStateException("GetSubMenu could not be called", t);
        }
    }

    // --------------------------------------------------------------------- the tray

    /**
     * Adds, modifies or removes the notification-area icon.
     *
     * <p>The one call does all three, and says which with {@code dwMessage}; the data it reads
     * is the {@code NOTIFYICONDATAW} {@code WinTray} owns. The boolean is the whole contract:
     * a false from {@code NIM_ADD} is a tray that was declared and never appeared, which is a
     * failure the caller reports rather than a state it guesses about.
     */
    static boolean shellNotifyIcon(int code, MemorySegment data) {
        try {
            return (int) SHELL_NOTIFY_ICON.invoke(code, data) != 0;
        } catch (Throwable t) {
            throw new IllegalStateException("Shell_NotifyIconW could not be called", t);
        }
    }

    /** Registers a window message by name; {@code TaskbarCreated} is the one this framework needs. */
    static int registerWindowMessage(Arena arena, String name) {
        try {
            int id = (int) REGISTER_WINDOW_MESSAGE.invoke(arena.allocateFrom(name, StandardCharsets.UTF_16LE));
            if (id == 0) {
                throw new IllegalStateException("RegisterWindowMessageW failed for '" + name + "'");
            }
            return id;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("RegisterWindowMessageW could not be called", t);
        }
    }

    /** The cursor's position in screen coordinates, into a {@link #POINT} buffer. */
    static void getCursorPos(Arena arena, MemorySegment point) {
        try {
            if ((int) GET_CURSOR_POS.invoke(point) == 0) {
                throw new IllegalStateException("GetCursorPos failed");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("GetCursorPos could not be called", t);
        }
    }

    /**
     * Shows the popup menu at the cursor and returns the command id that was chosen, or 0 when
     * the user dismissed it.
     *
     * <p>{@code TPM_RETURNCMD} is what makes the return value the answer: without it the choice
     * comes back as a {@code WM_COMMAND} round trip, which is the window menu's route and not
     * this one's. The menu is transient — built, shown, destroyed around this one call — so the
     * id the framework assigned is the only thing that survives it.
     */
    static int trackPopupMenu(Arena arena, MemorySegment menu, int flags, MemorySegment hwnd) {
        try {
            MemorySegment point = arena.allocate(POINT);
            getCursorPos(arena, point);
            int x = point.get(JAVA_INT, 0), y = point.get(JAVA_INT, 4);
            return (int) TRACK_POPUP_MENU.invoke(menu, flags, x, y, 0, hwnd, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new IllegalStateException("TrackPopupMenu could not be called", t);
        }
    }

    /** Brings the tray's hidden window to the foreground, the documented prerequisite for a popup. */
    static void setForegroundWindow(MemorySegment hwnd) {
        try {
            SET_FOREGROUND_WINDOW.invoke(hwnd);
        } catch (Throwable t) {
            throw new IllegalStateException("SetForegroundWindow could not be called", t);
        }
    }

    /**
     * The default icon: the stock application icon, by its resource id.
     *
     * <p>{@code MAKEINTRESOURCE} is an id smuggled through a pointer, which is what
     * {@link #idResource} does. The icon is a shared system resource, so it is never freed and
     * never destroyed — the same rule the cursor at window creation already follows.
     */
    static MemorySegment defaultIcon() {
        try {
            MemorySegment icon = (MemorySegment) LOAD_ICON.invoke(MemorySegment.NULL, idResource(IDI_APPLICATION));
            if (icon == null || icon.address() == 0) {
                throw new IllegalStateException("LoadIconW returned no icon");
            }
            return icon;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("LoadIconW could not be called", t);
        }
    }

    /**
     * The application's own icon, loaded from an {@code .ico} file.
     *
     * <p>{@code LR_LOADFROMFILE} reads the file at call time; the handle is owned by this
     * process and is never freed, because the tray lives as long as the process does. A file
     * that will not load is an error rather than a silent fall-back to the default: the
     * application asked for its own icon, and quietly showing the framework's instead would be
     * a wrong icon presented as the right one.
     */
    static MemorySegment iconFromFile(Arena arena, java.nio.file.Path path) {
        try {
            MemorySegment icon = (MemorySegment) LOAD_IMAGE.invoke(MemorySegment.NULL,
                    arena.allocateFrom(path.toString(), StandardCharsets.UTF_16LE),
                    IMAGE_ICON, 0, 0, LR_LOADFROMFILE);
            if (icon == null || icon.address() == 0) {
                throw new IllegalArgumentException("the tray icon could not be loaded: " + path
                        + " (a Windows tray icon is an .ico file)");
            }
            return icon;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("LoadImageW could not be called", t);
        }
    }

    /**
     * Registers a class and creates the tray's message window, invisible and ordinary.
     *
     * <p>Ordinary is the point, and it is measured, not stylistic: a message-only window
     * ({@code HWND_MESSAGE}) does not receive broadcast messages, and {@code TaskbarCreated} —
     * the message Explorer sends when it restarts and the notification area is rebuilt — is a
     * broadcast. An icon that misses it stays gone for the rest of the session. So this is a
     * real top-level window that is simply never shown, with its own procedure bound the same
     * way the main window's is.
     */
    static MemorySegment createHiddenWindow(Arena arena, Procedure procedure) {
        MemorySegment wndProc = LINKER.upcallStub(
                mh(ProcedureAdapter.class, "dispatch",
                        MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class),
                        new ProcedureAdapter(procedure)),
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG), LIFE);

        try (Arena confined = Arena.ofConfined()) {
            String className = "panamaview-tray-" + Long.toHexString(System.nanoTime());
            MemorySegment classNameSeg = confined.allocateFrom(className, StandardCharsets.UTF_16LE);
            MemorySegment instance = (MemorySegment) GET_MODULE_HANDLE.invoke(MemorySegment.NULL);
            MemorySegment cursor = (MemorySegment) LOAD_CURSOR.invoke(MemorySegment.NULL, idResource(IDC_ARROW));

            MemorySegment wcx = arena.allocate(WNDCLASSEX);
            WCX_CBSIZE.set(wcx, 0L, (int) WNDCLASSEX.byteSize());
            WCX_STYLE.set(wcx, 0L, 0);                                   // no redraw: nothing is drawn
            WCX_WNDPROC.set(wcx, 0L, wndProc);
            WCX_CLS_EXTRA.set(wcx, 0L, 0);
            WCX_WND_EXTRA.set(wcx, 0L, 0);
            WCX_INSTANCE.set(wcx, 0L, instance);
            WCX_ICON.set(wcx, 0L, MemorySegment.NULL);
            WCX_CURSOR.set(wcx, 0L, cursor);
            WCX_BACKGROUND.set(wcx, 0L, MemorySegment.NULL);             // never painted
            WCX_MENU_NAME.set(wcx, 0L, MemorySegment.NULL);
            WCX_CLASS_NAME.set(wcx, 0L, classNameSeg);
            WCX_ICON_SMALL.set(wcx, 0L, MemorySegment.NULL);
            short atom = (short) REGISTER_CLASS.invoke(wcx);
            if (atom == 0) {
                throw new IllegalStateException("RegisterClassExW failed for the tray window, GetLastError "
                        + (int) GET_LAST_ERROR.invoke());
            }
            MemorySegment hwnd = (MemorySegment) CREATE_WINDOW.invoke(
                    0, classNameSeg, classNameSeg,
                    0,                     // style: not overlapped, not visible — a message receiver
                    0, 0, 0, 0,
                    MemorySegment.NULL, MemorySegment.NULL, instance, MemorySegment.NULL);
            if (hwnd.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("CreateWindowExW failed for the tray window, GetLastError "
                        + (int) GET_LAST_ERROR.invoke());
            }
            System.out.println("[panamaview] tray window 0x" + Long.toHexString(hwnd.address())
                    + " of class '" + className + "'");
            return hwnd.reinterpret(Long.MAX_VALUE);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    // ------------------------------------------------------------------ clipboard

    /**
     * The clipboard's text, or {@code null} when it holds none.
     *
     * <p>{@code CF_UNICODETEXT} is the format a text field pastes from, and it is what the other
     * platforms call plain text. The handle the clipboard hands back belongs to it, so the only
     * thing left to do is lock, copy and unlock — never free.
     */
    static String readClipboard() {
        try {
            if (!openClipboard()) {
                throw new IllegalStateException("cannot open the clipboard");
            }
            try {
                MemorySegment handle = (MemorySegment) GET_CLIPBOARD_DATA.invoke(CF_UNICODETEXT);
                if (handle == null || handle.address() == 0) {
                    return null;
                }
                MemorySegment pointer = (MemorySegment) GLOBAL_LOCK.invoke(handle);
                if (pointer == null || pointer.address() == 0) {
                    return null;
                }
                try {
                    // UTF-16, NUL-terminated, length unknown: walk it. Reading code unit by code
                    // unit keeps surrogate pairs as the two chars they are.
                    MemorySegment chars = pointer.reinterpret(Long.MAX_VALUE);
                    StringBuilder text = new StringBuilder();
                    for (long offset = 0; ; offset += 2) {
                        short unit = chars.get(JAVA_SHORT, offset);
                        if (unit == 0) {
                            return text.toString();
                        }
                        text.append((char) unit);
                    }
                } finally {
                    GLOBAL_UNLOCK.invoke(handle);
                }
            } finally {
                CLOSE_CLIPBOARD.invoke();
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("cannot read the clipboard", t);
        }
    }

    /** Replaces the clipboard with {@code text}. */
    static void writeClipboard(String text) {
        try {
            byte[] utf16 = text.getBytes(StandardCharsets.UTF_16LE);
            int size = utf16.length + 2;      // the terminating NUL is part of the string
            MemorySegment handle = (MemorySegment) GLOBAL_ALLOC.invoke(GMEM_MOVEABLE, (long) size);
            if (handle == null || handle.address() == 0) {
                throw new IllegalStateException("GlobalAlloc failed for " + size + " bytes");
            }
            boolean handedOver = false;
            try {
                MemorySegment pointer = (MemorySegment) GLOBAL_LOCK.invoke(handle);
                if (pointer == null || pointer.address() == 0) {
                    throw new IllegalStateException("GlobalLock failed");
                }
                try {
                    MemorySegment memory = pointer.reinterpret(size);
                    MemorySegment.copy(utf16, 0, memory, JAVA_BYTE, 0, utf16.length);
                    memory.set(JAVA_SHORT, utf16.length, (short) 0);
                } finally {
                    GLOBAL_UNLOCK.invoke(handle);
                }

                if (!openClipboard()) {
                    throw new IllegalStateException("cannot open the clipboard");
                }
                try {
                    EMPTY_CLIPBOARD.invoke();
                    MemorySegment owned = (MemorySegment) SET_CLIPBOARD_DATA.invoke(CF_UNICODETEXT, handle);
                    if (owned == null || owned.address() == 0) {
                        throw new IllegalStateException("SetClipboardData refused the text");
                    }
                    // From here the clipboard owns the block; freeing it would be a double free.
                    handedOver = true;
                } finally {
                    CLOSE_CLIPBOARD.invoke();
                }
            } finally {
                if (!handedOver) {
                    GLOBAL_FREE.invoke(handle);
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("cannot write the clipboard", t);
        }
    }

    /** The clipboard's sequence number — a write by anyone, anywhere, increments it. */
    static long clipboardChangeCount() {
        try {
            return Integer.toUnsignedLong((int) CLIPBOARD_SEQUENCE.invoke());
        } catch (Throwable t) {
            throw new IllegalStateException("cannot read the clipboard sequence number", t);
        }
    }

    /**
     * Opens the clipboard, waiting out whichever process is holding it.
     *
     * <p>Only one window station owns the clipboard at a time and every app on the machine is a
     * candidate holder, so a single attempt is how a copy silently fails.
     */
    private static boolean openClipboard() {
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                if ((int) OPEN_CLIPBOARD.invoke(MemorySegment.NULL) != 0) {
                    return true;
                }
            } catch (Throwable t) {
                throw new IllegalStateException("OpenClipboard failed", t);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
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
