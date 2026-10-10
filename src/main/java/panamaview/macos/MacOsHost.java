package panamaview.macos;

import panamaview.PageSource;
import panamaview.UiHost;
import panamaview.WindowSpec;

import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The macOS implementation of the seam: Cocoa plus {@code WKWebView}, owned entirely by
 * this package.
 *
 * <p>Nothing above this class imports {@code java.lang.foreign} or an Objective-C type.
 * Everything from {@link ObjC} down is package-private on purpose — with the platform code
 * in its own package, "the application must not touch native code" is at least enforceable
 * by a compiler-driven convention rather than by hoping. (A single Maven module is a
 * deliberate choice; the day a second platform exists, splitting the packages into modules
 * turns that convention into a build-time guarantee.)
 *
 * <p>This class is also where the platform's <em>extras</em> live — {@link #geometry},
 * {@link #resize}, {@link #snapshot}. They are not on {@link UiHost} because inventing
 * cross-platform signatures for them before a second implementation exists would be a guess;
 * {@code Main} registers them as ordinary bindings instead.
 */
public final class MacOsHost implements UiHost {

    private Callbacks callbacks;
    private WebView webView;
    private MemorySegment window;

    @Override
    public void start(Callbacks callbacks, WindowSpec spec) {
        this.callbacks = callbacks;

        if (!ObjC.onMainThread()) {
            System.err.println("[panamaview] WARNING: this is not the process' first thread.");
            System.err.println("[panamaview] AppKit requires it — start the JVM with -XstartOnFirstThread.");
        }

        MemorySegment application;
        try (ObjC.Scope scope = ObjC.scope()) {
            application = Cocoa.sharedApplication();
            Cocoa.setActivationPolicyRegular(application);
            Cocoa.installMenuBar(scope, application, spec.title());
            Cocoa.finishLaunching(application);

            MemorySegment dispatch = ObjCDispatch.create(this);
            Cocoa.setDelegate(application, dispatch);

            window = Cocoa.createWindow(scope, spec.title(), spec.width(), spec.height());
            webView = WebView.create(scope, spec.width(), spec.height(), dispatch, assets(spec.page()));
            Cocoa.setContentView(window, webView.handle());
            Cocoa.show(window);
            Cocoa.activate(application);

            switch (spec.page()) {
                case PageSource.FromAssets assets -> webView.loadUrl(scope, URI.create(assets.url()));
                case PageSource.FromUrl url -> webView.loadUrl(scope, url.url());
            }
        }

        // Blocks until the application terminates; everything after this is a callback.
        Cocoa.run(application);
    }

    @Override
    public void evaluate(String script) {
        if (webView == null) {
            return;
        }
        try (ObjC.Scope scope = ObjC.scope()) {
            webView.evaluateJavaScript(scope, script);
        }
    }

    @Override
    public void onUiThread(Runnable action) {
        UiThread.run(action);
    }

    @Override
    public boolean isUiThread() {
        return ObjC.onMainThread();
    }

    /** The scheme to serve for a page held on disk; null for a page a dev server already serves. */
    private static AssetScheme assets(PageSource page) {
        return page instanceof PageSource.FromAssets a
                ? new AssetScheme(a.scheme(), a.root(), a.entry(), a.spaFallback())
                : null;
    }

    // ------------------------------------------------------- callbacks for ObjCDispatch

    Callbacks callbacks() {
        return callbacks;
    }

    void pageLoaded() {
        try (ObjC.Scope scope = ObjC.scope()) {
            callbacks.onPageLoaded(webView.currentUrl(), webView.pageTitle());
        }
    }

    void pageUnloading() {
        callbacks.onPageUnloading();
    }

    void pageFailed(String reason) {
        callbacks.onPageFailed(reason);
    }

    boolean quitWhenWindowCloses() {
        return callbacks.shouldQuitWhenLastWindowCloses();
    }

    // -------------------------------------------------------------- platform extras

    /**
     * Reads the web view's frame, writes it straight back, and reports the window number.
     *
     * <p>Exists to exercise the by-value {@code NSRect} paths in both directions plus an
     * {@code NSInteger} getter — signatures that are easy to get wrong and easy to never
     * notice you got wrong.
     */
    public Map<String, Object> geometry() {
        try (ObjC.Scope scope = ObjC.scope()) {
            double[] frame = webView.frame(scope);
            webView.setFrame(scope, frame[0], frame[1], frame[2], frame[3]);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("x", frame[0]);
            result.put("y", frame[1]);
            result.put("width", frame[2]);
            result.put("height", frame[3]);
            result.put("windowNumber", webView.windowNumber());
            result.put("roundTripStable", Arrays.equals(frame, webView.frame(scope)));
            result.put("onUiThread", isUiThread());
            return result;
        }
    }

    /**
     * Resizes the window's content area and reports where the web view ended up.
     *
     * <p>This is the resize contract under test: the web view is the window's content view,
     * so AppKit's autoresizing should keep it filling the window with no resize code of our
     * own. If that ever stops being true, the reported frame drifts from the requested size
     * and says so.
     */
    public Map<String, Object> resize(double requestedWidth, double requestedHeight) {
        try (ObjC.Scope scope = ObjC.scope()) {
            Cocoa.setContentSize(scope, window, requestedWidth, requestedHeight);
            double[] frame = webView.frame(scope);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("requested", requestedWidth + "x" + requestedHeight);
            result.put("webViewFrame", frame[2] + "x" + frame[3]);
            result.put("followed", frame[2] == requestedWidth && frame[3] == requestedHeight);
            result.put("onUiThread", isUiThread());
            return result;
        }
    }

    /** Re-fetches the current page; on a file URL this re-reads from disk. */
    public void reload() {
        if (webView == null) {
            return;
        }
        try (ObjC.Scope scope = ObjC.scope()) {
            webView.reload();
        }
    }

    /** Renders the current page to a PNG file and returns the path it wrote. */
    public String snapshot(String path) {
        webView.snapshotToPng(path);
        return path;
    }
}
