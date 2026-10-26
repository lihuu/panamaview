package io.github.lihuu.panamaview.windows;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The WebView2 binding layer: the loader, the environment and controller dance, and every
 * method on the web view this framework touches. The one file this platform carries that
 * macOS does not is {@code WebView2Loader.dll} — Microsoft's own, fetched checksum-verified,
 * whose job is locating the installed Evergreen runtime. On Windows that runtime plays the
 * part AppKit and WebKit play on macOS: a system component, preinstalled on Windows 11,
 * shared by every WebView2 application rather than bundled by each one.
 *
 * <h2>Where the slot numbers come from</h2>
 * Every slot below was read out of the shipped {@code WebView2.h}'s own method listing, with
 * the count cross-checked against the interfaces this framework does not use. The offsets
 * between interface generations are arithmetic: ICoreWebView2 has 61 slots (0..60), _2 adds
 * seven (61..67), _3 adds five (68..72) — {@link #SET_VIRTUAL_HOST_TO_FOLDER_MAPPING} sits at
 * 71, reached by asking the web view for _3 with the interface's IID. Trusting a remembered
 * table instead is how a listener gets registered on the remove_ slot of the same event and
 * the run waits forever for an event nobody asked for.
 */
public final class WebView2 {

    private WebView2() {
    }

    // ---------------------------------------------------------------- the loader

    /** What {@code GetAvailableCoreWebView2BrowserVersionString} returns with no runtime installed. */
    private static final int E_FILENOTFOUND = 0x80070002;

    /**
     * The version of the installed WebView2 runtime, or a failure that says what to do about it.
     *
     * <p>Needs no window, no desktop and no GPU, so it runs in session 0 over SSH — the
     * milestone-one check that the FFM to Windows chain works at all.
     *
     * @param loaderDll path to {@code WebView2Loader.dll}
     */
    public static String browserVersion(Path loaderDll) {
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup loader = SymbolLookup.libraryLookup(loaderDll, arena);
            MethodHandle getAvailableVersion = Com.LINKER.downcallHandle(
                    loader.find("GetAvailableCoreWebView2BrowserVersionString").orElseThrow(
                            () -> new UnsatisfiedLinkError("GetAvailableCoreWebView2BrowserVersionString not found in " + loaderDll)),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

            SymbolLookup ole32 = SymbolLookup.libraryLookup("ole32.dll", arena);
            MethodHandle coTaskMemFree = Com.LINKER.downcallHandle(
                    ole32.find("CoTaskMemFree").orElseThrow(() -> new UnsatisfiedLinkError("CoTaskMemFree")),
                    FunctionDescriptor.ofVoid(ADDRESS));

            MemorySegment out = arena.allocate(ADDRESS);
            int hresult = (int) getAvailableVersion.invokeExact(MemorySegment.NULL, out);
            if (hresult == E_FILENOTFOUND) {
                throw new IllegalStateException(
                        "no WebView2 runtime installed — the Evergreen runtime is preinstalled on Windows 11; "
                                + "if this copy has none, install it from https://developer.microsoft.com/microsoft-edge/webview2/");
            }
            Com.check(hresult, "GetAvailableCoreWebView2BrowserVersionString");

            MemorySegment version = out.getAtIndex(ADDRESS, 0);
            if (version.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("GetAvailableCoreWebView2BrowserVersionString succeeded but returned no version");
            }
            try {
                // PCWSTR: null-terminated UTF-16, the one string encoding Windows speaks natively.
                // The pointer arrives as a zero-length segment; reinterpret gives the scan for
                // the terminator room, the same idiom ObjC.cString uses on the other side.
                return version.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
            } finally {
                coTaskMemFree.invokeExact(version);
            }
        } catch (Throwable t) {
            throw t instanceof RuntimeException r ? r : new IllegalStateException(t);
        }
    }

    /** A handle on the loader, for the one export that starts everything else. */
    static SymbolLookup load(Path loaderDll) {
        return SymbolLookup.libraryLookup(loaderDll, Arena.global());
    }

    /**
     * {@code CreateCoreWebView2EnvironmentWithOptions} — exported by the loader, not a vtable
     * method. A null folder asks for the Evergreen runtime; a null options object asks for
     * defaults; the completed handler receives the environment on the calling thread's pump.
     */
    static void createEnvironment(SymbolLookup loader, MemorySegment userDataFolder, MemorySegment handler) {
        try {
            MethodHandle create = Com.LINKER.downcallHandle(
                    loader.find("CreateCoreWebView2EnvironmentWithOptions").orElseThrow(
                            () -> new UnsatisfiedLinkError("CreateCoreWebView2EnvironmentWithOptions not in the loader")),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            Com.check((int) create.invoke(MemorySegment.NULL, userDataFolder, MemorySegment.NULL, handler),
                    "CreateCoreWebView2EnvironmentWithOptions");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("CreateCoreWebView2EnvironmentWithOptions could not be called", t);
        }
    }

    // ---------------------------------------------------------------- slots, from the header

    /** ICoreWebView2Environment: CreateCoreWebView2Controller is the first method after IUnknown. */
    private static final int ENV_CREATE_CONTROLLER = 3;

    /** ICoreWebView2Controller slots. */
    private static final int CONTROLLER_PUT_IS_VISIBLE = 4;
    private static final int CONTROLLER_PUT_BOUNDS = 6;
    private static final int CONTROLLER_GET_CORE_WEBVIEW2 = 25;

    /** ICoreWebView2 slots (61 in all, 0..60). */
    private static final int WEBVIEW_GET_SOURCE = 4;
    private static final int WEBVIEW_NAVIGATE = 5;
    private static final int WEBVIEW_ADD_NAVIGATION_STARTING = 7;
    private static final int WEBVIEW_ADD_NAVIGATION_COMPLETED = 15;
    private static final int WEBVIEW_EXECUTE_SCRIPT = 29;
    private static final int WEBVIEW_ADD_WEB_MESSAGE_RECEIVED = 34;
    private static final int WEBVIEW_GET_DOCUMENT_TITLE = 48;

    /** ICoreWebView2WebMessageReceivedEventArgs: IUnknown, then TryGetWebMessageAsString. */
    private static final int ARGS_TRY_GET_WEB_MESSAGE_AS_STRING = 5;

    /** ICoreWebView2NavigationCompletedEventArgs: IUnknown, then get_IsSuccess. */
    private static final int ARGS_GET_IS_SUCCESS = 3;

    /**
     * ICoreWebView2_3 = base (61) + _2's seven additions (61..67) + _3's own (68..72); the
     * virtual-host mapping is the fourth of _3's own.
     */
    private static final int WEBVIEW3_SET_VIRTUAL_HOST_TO_FOLDER_MAPPING = 71;

    /** IID_ICoreWebView2_3, per the interface's published declaration. */
    private static final String IID_ICORE_WEBVIEW2_3 = "A0D6DF20-3B92-416D-AA0C-437A9C727857";

    /** {@code COREWEBVIEW2_HOST_RESOURCE_ACCESS_KIND_DENY_CORS}: same origin works, the rest refused. */
    private static final int HOST_RESOURCE_ACCESS_DENY_CORS = 2;

    private static final FunctionDescriptor HR_THIS_PTR =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor HR_THIS_PTR_INT =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor HR_THIS_HANDLER_TOKEN =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);

    /** {@code {LONG left; LONG top; LONG right; LONG bottom}} — 16 bytes, passed by value. */
    static final MemoryLayout RECT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("left"), ValueLayout.JAVA_INT.withName("top"),
            ValueLayout.JAVA_INT.withName("right"), ValueLayout.JAVA_INT.withName("bottom"));

    // ---------------------------------------------------------------- environment → controller

    static void createController(MemorySegment environment, MemorySegment hwnd, MemorySegment handler) {
        try {
            Com.check((int) Com.bind(environment, ENV_CREATE_CONTROLLER,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
                    .invoke(environment, hwnd, handler), "CreateCoreWebView2Controller");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("CreateCoreWebView2Controller could not be called", t);
        }
    }

    static MemorySegment coreWebView2(MemorySegment controller) {
        MemorySegment out = Arena.global().allocate(ADDRESS);
        Com.callGetter(controller, CONTROLLER_GET_CORE_WEBVIEW2, out, "get_CoreWebView2");
        return pointer(out);
    }

    static void putIsVisible(MemorySegment controller, boolean visible) {
        try {
            Com.check((int) Com.bind(controller, CONTROLLER_PUT_IS_VISIBLE, HR_THIS_PTR_INT)
                    .invoke(controller, visible ? 1 : 0), "put_IsVisible");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("put_IsVisible could not be called", t);
        }
    }

    static void putBounds(MemorySegment controller, MemorySegment rect) {
        try {
            // RECT is 16 bytes: past the 8-byte register cutoff, so the x64 ABI passes a
            // pointer to a temporary. The linker knows; the descriptor says struct by value.
            Com.check((int) Com.bind(controller, CONTROLLER_PUT_BOUNDS,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, RECT)).invoke(controller, rect), "put_Bounds");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("put_Bounds could not be called", t);
        }
    }

    // ---------------------------------------------------------------- the web view

    static void navigate(MemorySegment webview, String uri) {
        try (Arena confined = Arena.ofConfined()) {
            Com.check((int) Com.bind(webview, WEBVIEW_NAVIGATE, HR_THIS_PTR)
                    .invoke(webview, confined.allocateFrom(uri, StandardCharsets.UTF_16LE)), "Navigate");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Navigate could not be called", t);
        }
    }

    static void addNavigationStarting(MemorySegment webview, MemorySegment handler) {
        addEventListener(webview, WEBVIEW_ADD_NAVIGATION_STARTING, handler, "add_NavigationStarting");
    }

    static void addNavigationCompleted(MemorySegment webview, MemorySegment handler) {
        addEventListener(webview, WEBVIEW_ADD_NAVIGATION_COMPLETED, handler, "add_NavigationCompleted");
    }

    static void addWebMessageReceived(MemorySegment webview, MemorySegment handler) {
        addEventListener(webview, WEBVIEW_ADD_WEB_MESSAGE_RECEIVED, handler, "add_WebMessageReceived");
    }

    private static void addEventListener(MemorySegment webview, int slot, MemorySegment handler, String what) {
        try (Arena confined = Arena.ofConfined()) {
            MemorySegment token = confined.allocate(ValueLayout.JAVA_LONG);   // EventRegistrationToken
            Com.check((int) Com.bind(webview, slot, HR_THIS_HANDLER_TOKEN)
                    .invoke(webview, handler, token), what);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(what + " could not be called", t);
        }
    }

    static void executeScript(MemorySegment webview, String script, MemorySegment handler) {
        try (Arena confined = Arena.ofConfined()) {
            Com.check((int) Com.bind(webview, WEBVIEW_EXECUTE_SCRIPT,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
                    .invoke(webview, confined.allocateFrom(script, StandardCharsets.UTF_16LE), handler), "ExecuteScript");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("ExecuteScript could not be called", t);
        }
    }

    /** The page currently loaded. NavigateToString's product reports about:blank here. */
    static String currentSource(MemorySegment webview) {
        MemorySegment out = Arena.global().allocate(ADDRESS);
        Com.callGetter(webview, WEBVIEW_GET_SOURCE, out, "get_Source");
        return wideString(out);
    }

    static String documentTitle(MemorySegment webview) {
        MemorySegment out = Arena.global().allocate(ADDRESS);
        Com.callGetter(webview, WEBVIEW_GET_DOCUMENT_TITLE, out, "get_DocumentTitle");
        return wideString(out);
    }

    /** Whether the finished navigation actually arrived somewhere. */
    static boolean navigationSucceeded(MemorySegment args) {
        MemorySegment out = Arena.global().allocate(JAVA_INT);
        Com.callGetter(args, ARGS_GET_IS_SUCCESS, out, "get_IsSuccess");
        return out.get(JAVA_INT, 0) != 0;
    }

    /** The body of a {@code window.chrome.webview.postMessage} from the page, as text. */
    static String webMessageAsString(MemorySegment args) {
        MemorySegment out = Arena.global().allocate(ADDRESS);
        Com.callGetter(args, ARGS_TRY_GET_WEB_MESSAGE_AS_STRING, out, "TryGetWebMessageAsString");
        return wideString(out);
    }

    /**
     * Maps {@code https://<hostName>/…} onto a directory, giving the staged assets a real
     * origin — the Windows counterpart of the macOS {@code AssetScheme}. The web view object
     * implements this through ICoreWebView2_3, so the call goes through QueryInterface first:
     * trusting the continuous-vtable layout instead would work right up until it did not.
     */
    static void setVirtualHostToFolderMapping(MemorySegment webview, String hostName, Path folder) {
        MemorySegment webview3 = Com.queryInterface(webview, IID_ICORE_WEBVIEW2_3);
        try (Arena confined = Arena.ofConfined()) {
            Com.check((int) Com.bind(webview3, WEBVIEW3_SET_VIRTUAL_HOST_TO_FOLDER_MAPPING,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT))
                    .invoke(webview3,
                            confined.allocateFrom(hostName, StandardCharsets.UTF_16LE),
                            confined.allocateFrom(folder.toString(), StandardCharsets.UTF_16LE),
                            HOST_RESOURCE_ACCESS_DENY_CORS),
                    "SetVirtualHostNameToFolderMapping");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("SetVirtualHostNameToFolderMapping could not be called", t);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Dereferences an out-pointer and reads the PCWSTR it points at. */
    private static String wideString(MemorySegment out) {
        MemorySegment value = out.getAtIndex(ADDRESS, 0);
        if (value.equals(MemorySegment.NULL)) {
            return null;
        }
        return value.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
    }

    /** A pointer read out of an out-pointer, as a segment this code may dereference later. */
    private static MemorySegment pointer(MemorySegment out) {
        return out.getAtIndex(ADDRESS, 0).reinterpret(Long.MAX_VALUE);
    }
}
