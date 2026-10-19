package io.github.lihuu.panamaview.probe;

import io.github.lihuu.panamaview.windows.WebView2;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Windows port's milestone-1 check, and its CI story has the same shape as the macOS one:
 * it proves the framework resolves and the native chain works without a desktop, so it runs
 * anywhere a JVM runs — over SSH, in session 0, on a headless runner.
 *
 * <p>What PASS means: {@code WebView2Loader.dll} was found and loaded, FFM called into it, and
 * the installed Evergreen runtime answered with its version. What it does not mean: no window
 * was opened and no page loaded — that is milestone 2, which needs a desktop session not
 * because the code is missing but because Windows does not render one to a headless session.
 */
public final class WindowsProbe {

    public static void main(String[] args) {
        System.out.println("os      " + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + ", Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        try {
            Path loader = loaderDll();
            if (!Files.isRegularFile(loader)) {
                System.err.println("loader  " + loader.toAbsolutePath() + " is missing");
                System.err.println("        fetch it first:");
                System.err.println("        powershell -ExecutionPolicy Bypass -File windows-probe\\fetch-loader.ps1");
                System.exit(2);
            }
            System.out.println("loader  " + loader.toAbsolutePath());
            String version = WebView2.browserVersion(loader);
            System.out.println("runtime WebView2 " + version);
            // ASCII only, on purpose: the Windows console's code page has its own opinion
            // about anything wider, and a PASS line that renders as "PASS ??" proves nothing.
            System.out.println("[probe] PASS - FFM reached the loader, and the installed runtime answered");
        } catch (RuntimeException e) {
            System.err.println("[probe] FAIL - " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Where {@code WebView2Loader.dll} is: {@code -Dpanamaview.webview2.loader} if set, then
     * the {@code loader/} directory beside the working directory (what fetch-loader.ps1
     * writes), then the working directory itself.
     */
    private static Path loaderDll() {
        String property = System.getProperty("panamaview.webview2.loader");
        if (property != null) {
            return Path.of(property);
        }
        Path staged = Path.of("loader", "WebView2Loader.dll");
        if (Files.isRegularFile(staged)) {
            return staged;
        }
        return Path.of("WebView2Loader.dll");
    }
}
