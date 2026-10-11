package panamaview.windows;

import panamaview.UiHost;
import panamaview.WindowSpec;

/**
 * The Windows implementation of {@link UiHost} — the counterpart of
 * {@code panamaview.macos.MacOsHost}, and the reason the {@code windows} package exists.
 * Only this class is public here, matching the macOS package's discipline: an application
 * cannot reach the native machinery, and the platform seam is the whole surface.
 *
 * <p>Status: skeleton. The WebView2 runtime is reachable through FFM ({@link WebView2} and
 * the {@code windows-probe} consumer prove it), but no window opens yet. What fills
 * {@link #start} in is a known sequence, not a design question: a Win32 message pump,
 * a native window, {@code CreateCoreWebView2EnvironmentWithOptions}, a controller over that
 * window, and the {@code WebMessageReceived}/{@code ExecuteScript} pair behind the bridge.
 * It lands when it runs end to end — a placeholder at the seam would be a guess about
 * an interface that already has one real implementation to be consistent with.
 *
 * <h2>The threading difference from macOS, up front</h2>
 * AppKit demands the process' first thread; WebView2 only demands that the thread which
 * creates the environment pumps messages. So this host runs its pump on a dedicated thread
 * and the JVM's main thread stays Java's — the {@code -XstartOnFirstThread} flag that is
 * compulsory on macOS has no Windows counterpart, and a generated project here needs neither
 * it nor its workarounds.
 */
public final class WindowsHost implements UiHost {

    private static UnsupportedOperationException notYet(String what) {
        return new UnsupportedOperationException(
                "the Windows host does not " + what + " yet — see the WindowsHost class javadoc");
    }

    @Override
    public void start(Callbacks callbacks, WindowSpec spec) {
        throw notYet("open a window");
    }

    @Override
    public void evaluate(String script) {
        throw notYet("evaluate scripts");
    }

    @Override
    public void onUiThread(Runnable action) {
        throw notYet("schedule UI-thread work");
    }

    @Override
    public boolean isUiThread() {
        throw notYet("report its UI thread");
    }
}
