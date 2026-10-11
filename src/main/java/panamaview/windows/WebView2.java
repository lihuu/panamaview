package panamaview.windows;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The WebView2 loader — the first thing the Windows side binds, and the one file this platform
 * carries that macOS does not. Microsoft ships the loader inside the Microsoft.Web.WebView2
 * NuGet package ({@code windows-probe/fetch-loader.ps1} extracts it, checksum-verified); its
 * job is locating the installed Evergreen runtime, which on Windows plays the part AppKit and
 * WebKit play on macOS: a system component, preinstalled on Windows 11, shared by every
 * WebView2 application rather than bundled by each one.
 *
 * <p>The probe of record is {@link #browserVersion(Path)}: it needs no window, no desktop and
 * no GPU, so it runs in session 0 over SSH. It is the milestone-1 check that the FFM to
 * Windows chain works at all.
 *
 * <p>Public on purpose, and the only public member of this package besides
 * {@link WindowsHost}: whether the runtime is installed, and which version, is a question a
 * consumer application legitimately asks before opening anything. Everything below that —
 * the COM machinery, the window, the web view — stays package-private, as on macOS.
 */
public final class WebView2 {

    private WebView2() {
    }

    private static final Linker LINKER = Linker.nativeLinker();

    /** What {@code GetAvailableCoreWebView2BrowserVersionString} returns with no runtime installed. */
    private static final int E_FILENOTFOUND = 0x80070002;

    /**
     * The version of the installed WebView2 runtime, or a failure that says what to do about it.
     *
     * <p>A null {@code browserExecutableFolder} — the only form used here — asks about the
     * Evergreen runtime rather than a fixed one.
     *
     * @param loaderDll path to {@code WebView2Loader.dll}
     */
    public static String browserVersion(Path loaderDll) {
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup loader = SymbolLookup.libraryLookup(loaderDll, arena);
            MethodHandle getAvailableVersion = LINKER.downcallHandle(
                    loader.find("GetAvailableCoreWebView2BrowserVersionString").orElseThrow(
                            () -> new UnsatisfiedLinkError("GetAvailableCoreWebView2BrowserVersionString not found in " + loaderDll)),
                    // HRESULT GetAvailableCoreWebView2BrowserVersionString(LPCWSTR folder, LPWSTR *version)
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

            // The loader allocates the version string with the COM task allocator, so only that
            // allocator's free may release it.
            SymbolLookup ole32 = SymbolLookup.libraryLookup("ole32.dll", arena);
            MethodHandle coTaskMemFree = LINKER.downcallHandle(
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
                return version.getString(0, StandardCharsets.UTF_16LE);
            } finally {
                coTaskMemFree.invokeExact(version);
            }
        } catch (Throwable t) {
            throw t instanceof RuntimeException r ? r : new IllegalStateException(t);
        }
    }
}
