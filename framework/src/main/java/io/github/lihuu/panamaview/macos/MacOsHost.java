package io.github.lihuu.panamaview.macos;

import io.github.lihuu.panamaview.PageSource;
import io.github.lihuu.panamaview.UiHost;
import io.github.lihuu.panamaview.WindowSpec;

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

            if (spec.origin() != null) {
                // An explicit position is a decision, not a preference: it is applied on every
                // launch, so there is no frame to restore and none worth saving. Note that this
                // also skips registering the autosave name — a placed window can still be
                // dragged, but where it lands is deliberately not remembered.
                Cocoa.setFrameOrigin(scope, window, spec.origin().x(), spec.origin().y());
            } else if (!Cocoa.restoreFrame(scope, window, frameAutosaveName(spec))) {
                // A saved frame wins, and then centring would immediately undo it. Centring is
                // what a first launch does, which is why the restore result decides.
                Cocoa.center(window);
            }
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

    /**
     * The name the window's frame is remembered under.
     *
     * <p>The title is the only stable identity a {@link WindowSpec} carries, and inside a
     * defaults domain the name is the whole key. That domain is wider than "this application"
     * for a bundleless JVM: every {@code java -cp} panamaview app writes to
     * {@code net.java.openjdk.java}, while a native-image build falls back to its executable's
     * name. So the title is what separates one panamaview app from another, and two apps that
     * choose the same title will share one remembered frame — a title is already the app's
     * statement of its own identity, and the spec has nothing better to offer.
     */
    private static String frameAutosaveName(WindowSpec spec) {
        return spec.title();
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

    // ------------------------------------------------------------------- clipboard

    /**
     * The system clipboard's text, or {@code null} when it holds none.
     *
     * <p>For the program that needs the clipboard, as opposed to the user pressing ⌘V in a text
     * field — that one is AppKit's, handled by the menu bar and the responder chain with no Java
     * involved. This is the other direction: a handler, a start-up read, an event pushed to the
     * page. Safe to call from a binding handler; the general pasteboard takes any thread.
     */
    public String readClipboard() {
        return Pasteboard.readText();
    }

    /** Replaces the system clipboard with {@code text}. */
    public void writeClipboard(String text) {
        Pasteboard.writeText(text);
    }

    /**
     * The pasteboard's change counter.
     *
     * <p>Every write bumps it — this app's, another app's, the user's ⌘C — so comparing two reads
     * is how an app notices that the clipboard changed without polling its contents.
     */
    public long clipboardChangeCount() {
        return Pasteboard.changeCount();
    }

    // ---------------------------------------------------------- system notifications

    /**
     * The application's bundle identifier, or {@code null} when this process is not running from
     * a {@code .app}.
     *
     * <p>Worth reporting rather than hiding: it is the value that decides whether the system
     * notification centre can identify the app, and the answer depends on how the app was
     * packaged, not on anything the code did. Null also means "do not ask the notification
     * centre anything" — see {@link Notifications}.
     */
    public String bundleIdentifier() {
        return Notifications.bundleIdentifier();
    }

    /**
     * True when this process can address the notification centre.
     *
     * <p>False means this process is not running from an application bundle (a bare
     * {@code java -cp} launch, for instance). Notifications are then unavailable, and asking
     * anyway would abort the process rather than return an error — see {@link Notifications}.
     * The honest move for an app is to hide or explain the affordance.
     */
    public boolean canNotify() {
        return Notifications.canNotify();
    }

    /**
     * Asks the system for permission to notify, and answers on the UI thread.
     *
     * <p>The answer is remembered per app: once the user has decided, this returns that decision
     * without a prompt. Ask from a user gesture, not at start-up — the prompt needs a reason the
     * user can see.
     */
    public void requestNotificationPermission(java.util.function.Consumer<Boolean> onResult) {
        Notifications.requestAuthorization(onResult);
    }

    /**
     * Posts a notification immediately.
     *
     * @throws IllegalStateException when the process has no bundle identifier; check
     *                               {@link #canNotify()} first if the answer is unknown
     */
    public void notify(String title, String body) {
        Notifications.post(title, body);
    }
}
