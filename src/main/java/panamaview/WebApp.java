package panamaview;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Application lifecycle and the exploratory front door.
 *
 * <p>Portable on purpose: nothing in this file knows what a {@code WKWebView} or a
 * {@code MemorySegment} is. The platform is supplied as a {@link UiHost}, which is what makes a
 * second platform a new {@code UiHost} rather than a rewrite.
 *
 * <pre>{@code
 * UiHost host = new MacOsHost();
 * new WebApp(host)
 *       .title("Java WebView PoC")
 *       .size(1000, 700)
 *       .load("/web/index.html")
 *       .bind("hello", params -> "Hello " + params.get("name"))
 *       .bind("research", App::slowThing, Duration.ofMinutes(10))   // outlives the default
 *       .run();
 * }</pre>
 */
public final class WebApp {

    /** A Java method JavaScript can call. The return value is serialised as JSON. */
    @FunctionalInterface
    public interface Fn {
        Object apply(Map<String, Object> params) throws Exception;
    }

    private final UiHost host;
    private final Bridge bridge;

    private String title = "Java WebView";
    private int width = 1000;
    private int height = 700;
    private String entry = "/web/index.html";
    private Duration timeout = Bridge.DEFAULT_TIMEOUT;
    private Consumer<WebApp> onReady;
    private boolean verbose = true;

    public WebApp(UiHost host) {
        this.host = host;
        this.bridge = new Bridge(host);
    }

    public WebApp title(String value) {
        this.title = value;
        return this;
    }

    public WebApp size(int w, int h) {
        this.width = w;
        this.height = h;
        return this;
    }

    /** @param resourcePath classpath resource, e.g. {@code /web/index.html} */
    public WebApp load(String resourcePath) {
        this.entry = resourcePath;
        return this;
    }

    /**
     * Sets the deadline every {@link #bind} handler inherits: 30 seconds by default.
     *
     * <p>Handlers that legitimately outlive it should say so at their own {@code bind} site
     * rather than have this number raised for everything — the point of the default is to
     * catch the handler that would otherwise hang forever.
     */
    public WebApp timeout(Duration value) {
        this.timeout = value;
        return this;
    }

    /**
     * Exposes {@code fn} to JavaScript as {@code java.invoke(method, params)}.
     *
     * <p>Runs on a virtual thread, off the UI thread, so a slow handler cannot freeze the
     * window, and is bounded by the app-wide {@link #timeout} (30 s by default). Replies are
     * correlated by id, so they may come back out of order.
     */
    public WebApp bind(String method, Fn fn) {
        return bind(method, fn, timeout);
    }

    /**
     * Like {@link #bind}, with a deadline of its own.
     *
     * @param deadline how long to allow, or {@code null} for no deadline at all — the right
     *                 choice for the work that is honestly unbounded, such as a long agent
     *                 run. Prefer an explicit generous value over {@code null} when you can
     *                 estimate one; {@code null} means a stuck handler stays stuck.
     */
    public WebApp bind(String method, Fn fn, Duration deadline) {
        bridge.bind(method, fn::apply, false, deadline);
        return this;
    }

    /**
     * Like {@link #bind}, but runs inline on the UI thread.
     *
     * <p>Required for handlers that must touch the window — resizing it, snapshotting it —
     * because those operations are only legal there. The trade is explicit: these are the
     * handlers that <em>can</em> freeze the interface, so keep them quick, and note that no
     * deadline is applied to them. A stalled UI-thread handler cannot be rescued, because the
     * only thread that could report the stall is the one that is busy.
     */
    public WebApp bindUi(String method, Fn fn) {
        bridge.bind(method, fn::apply, true, null);
        return this;
    }

    /** Called once the page has loaded — the first safe moment to call into JavaScript. */
    public WebApp onReady(Consumer<WebApp> callback) {
        this.onReady = callback;
        return this;
    }

    public WebApp quiet() {
        this.verbose = false;
        return this;
    }

    /** True when the calling thread is the platform's UI thread. */
    public boolean isUiThread() {
        return host.isUiThread();
    }

    /** How many requests are still waiting for an outcome. Diagnostics. */
    public int inFlight() {
        return bridge.inFlightCount();
    }

    /** Pushes a message to the page by calling {@code window.onJavaMessage(payload)}. */
    public void postToJs(Object payload) {
        if (verbose) {
            System.out.println("[panamaview] java → js  window.onJavaMessage(" + Json.write(payload) + ")");
        }
        bridge.push(payload);
    }

    /** Stops every request still in flight. Called automatically when the page goes away. */
    public void cancelInFlight(String reason) {
        bridge.cancelAll(reason);
    }

    /** Runs the platform event loop. Blocks until the application quits. */
    public void run() throws IOException {
        String directory = entry.substring(0, entry.lastIndexOf('/'));
        String fileName = entry.substring(entry.lastIndexOf('/') + 1);

        // Staged before the window opens: WebKit needs real files, and a native image has no
        // classpath directory to serve them from.
        Path webRoot = Resources.stage(directory);

        bridge.verbose(verbose);
        if (verbose) {
            System.out.println("[panamaview] web root: " + webRoot);
        }

        host.start(new UiHost.Callbacks() {

            @Override
            public void onMessage(String json) {
                bridge.onIncoming(json);
            }

            @Override
            public void onPageLoaded(String url, String title) {
                if (verbose) {
                    System.out.println("[panamaview] page loaded: url=" + url + " title=" + title);
                }
                if (onReady != null) {
                    onReady.accept(WebApp.this);
                }
            }

            @Override
            public void onPageFailed(String reason) {
                System.err.println("[panamaview] page failed to load: " + reason);
            }

            @Override
            public void onPageUnloading() {
                // Requests belong to the page that made them; when it navigates away they are
                // over. Any reply would be addressed to a document that no longer exists.
                bridge.cancelAll("the page navigated away");
            }

            @Override
            public boolean shouldQuitWhenLastWindowCloses() {
                return true;
            }
        }, new WindowSpec(title, width, height, webRoot.resolve(fileName), webRoot));
    }
}
