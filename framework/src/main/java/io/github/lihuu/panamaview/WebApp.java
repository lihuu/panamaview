package io.github.lihuu.panamaview;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
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
 *       .title("My App")
 *       .size(1000, 700)
 *       .load("/web/index.html")
 *       .bind("hello", params -> "Hello " + params.get("name"))
 *       .bind("research", this::research, Duration.ofMinutes(10))   // outlives the default
 *       .run();
 * }</pre>
 */
public final class WebApp {

    /**
     * The URL scheme the app's own assets are served under.
     *
     * <p>Not {@code file://}, because an opaque origin cannot load ES modules, {@code fetch}, or
     * Web Workers — a bundled frontend needs all three. See {@link PageSource.FromAssets}.
     */
    private static final String SCHEME = "panamaview";

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
    private URI devUrl;
    private boolean liveReload = true;
    private boolean spaFallback = true;
    private Path stagedRoot;
    private WindowSpec.Origin origin;
    private MenuBar menuBar;
    private Map<Integer, String> menuBindings = Map.of();
    private TraySpec traySpec;
    private Map<Integer, String> trayBindings = Map.of();
    /** Null means "let the tray decide": the default is computed when the window closes. */
    private Boolean quitOnLastClose;
    /** Set at run time: a tray counts only when the host can actually show it. */
    private boolean trayAccepted;

    public WebApp(UiHost host) {
        this.host = host;
        this.bridge = new Bridge(host);
    }

    public WebApp title(String value) {
        this.title = value;
        return this;
    }

    /**
     * The window's size in logical pixels — the size it should occupy on a 96-DPI display. A
     * high-DPI host converts it for the monitor, so the window keeps its apparent size and its
     * content stays sharp.
     */
    public WebApp size(int w, int h) {
        this.width = w;
        this.height = h;
        return this;
    }

    /**
     * Opens the window at this position, on every launch.
     *
     * <p>An explicit position is a decision, not a preference: the window is not remembered and
     * nothing is restored, so a kiosk screen, a presentation display or a test keeps the spot it
     * was given. Leave it out and the platform does what it always does — centre on the first
     * launch, then bring the window back where the user left it.
     *
     * <p>The coordinates are the platform's own, measured from the window's <em>frame</em>
     * (title bar included). See {@link WindowSpec.Origin} for why they are not translated.
     */
    public WebApp position(int x, int y) {
        this.origin = new WindowSpec.Origin(x, y);
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

    /**
     * Loads {@code pageUrl} instead of the staged file — normally a frontend dev server.
     *
     * <p>Pass {@code null} to turn it off. The usual wiring is a system property, so one flag
     * switches a run between the two modes without touching code:
     *
     * <pre>{@code
     * String dev = System.getProperty("panamaview.dev");
     * if (dev != null) app.dev(URI.create(dev));
     * }</pre>
     *
     * <p>Nothing is staged in this mode: the dev server owns the whole page, which is what keeps
     * hot module replacement and live reloading working. That also means the framework's client
     * bridge is not written to disk, so a dev-server page has to include it itself — the
     * frontend project is expected to import it from the installed node package, which is the
     * frontend-toolchain equivalent of not copying a file.
     */
    public WebApp dev(URI pageUrl) {
        this.devUrl = pageUrl;
        return this;
    }

    /**
     * Enables or disables reloading a file-backed page when its assets change.
     *
     * <p>On by default. It is what gives a plain-HTML project the same editing experience a
     * frontend dev server provides, without requiring Node to be installed. It has no effect on
     * a page served by a {@link #dev(URI) dev server}, which reloads itself.
     */
    public WebApp liveReload(boolean enabled) {
        this.liveReload = enabled;
        return this;
    }

    /**
     * Whether client-side routes fall back to the entry document. On by default.
     *
     * <p>A router in history mode puts real paths in the URL, so a reload or a deep link asks for
     * a file that was never built. Falling back to the entry document is what lets the router
     * handle it — the same thing every bundler's dev server does. Turn it off for an app whose
     * paths are always files.
     *
     * <p>Paths that look like assets (they have a file extension) are never rewritten, so a
     * misspelled asset is still an honest 404 rather than a page of HTML delivered under a
     * {@code .js} URL. See {@link PageSource.FromAssets}.
     */
    public WebApp spaFallback(boolean enabled) {
        this.spaFallback = enabled;
        return this;
    }

    /**
     * Called when the page has loaded — the first safe moment to call into JavaScript.
     *
     * <p>It fires on <em>every</em> load, not only the first one. A live reload is a page
     * load, so a reloaded page calls this again, and so does every navigation the user makes.
     * A callback that does setup work has to be safe to run more than once: starting a thread
     * here dies with {@code IllegalThreadStateException} the moment the page reloads, and
     * anything else that is not idempotent fails the same way, only less obviously.
     */
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

    /**
     * Runs a script in the page. Fire and forget — there is no return value.
     *
     * <p>Nothing is reported back, which is worth knowing before reaching for this to read state
     * out of the page: a result has to come back through the bridge as a normal call. For a
     * one-way instruction — a key binding, a focus change, a nudge to some page API — it is the
     * shortest path there is.
     */
    public void eval(String script) {
        host.evaluate(script);
    }

    /** Re-fetches the current page. Used by live reload; also handy from a key binding. */
    public void reload() {
        host.evaluate("window.location.reload()");
    }

    /**
     * Stops every request still in flight — the page's and the menu's alike.
     *
     * <p>This is the application's own sweep, so it reaches everything. The page going away is
     * a different matter: that ends the requests the page made and leaves the menu bindings
     * alone.
     */
    public void cancelInFlight(String reason) {
        bridge.cancelAll(reason);
    }

    /**
     * Decides what to load, and starts watching for changes when that is meaningful.
     *
     * <p>Three cases, in order of precedence:
     * <ol>
     *   <li>a dev server was configured — load its URL and stage nothing, because the dev server
     *       is already serving the whole page;</li>
     *   <li>otherwise the assets are staged to disk, and if the classpath resources exist as a
     *       real directory (true when running from compiled classes) that directory is watched
     *       so edits show up without a restart.</li>
     * </ol>
     */
    private PageSource resolvePage(String directory, String fileName) throws IOException {
        if (devUrl != null) {
            if (verbose) {
                System.out.println("[panamaview] dev server: " + devUrl);
            }
            return new PageSource.FromUrl(devUrl);
        }

        // Staged before the window opens: WebKit needs real files, and a native image has no
        // classpath directory to serve them from.
        stagedRoot = Resources.stage(directory);
        if (verbose) {
            System.out.println("[panamaview] web root: " + stagedRoot);
        }

        // The watcher is registered here, before the window opens, and that is deliberate: the
        // page's source directory only has to exist, and the reload itself is delivered through
        // host.onUiThread, which every platform promises to accept from any thread.
        //
        // An earlier version asked host.isUiThread() first, on the theory that reloading a window
        // is a UI-thread operation. It is not — reloading is a request, and the request is
        // delivered later, on whichever thread the platform designates as the UI thread. The
        // question that was actually being asked was "is the *caller* on the UI thread right now",
        // and on Windows the answer is no at this point in every run: the host's event loop runs
        // on a thread it creates in start(), and this code runs before start(). So the watcher was
        // never registered there, and live reload silently did nothing on Windows — no message,
        // no error, and a README that said it worked.
        //
        // The platforms differ in when their UI thread exists (macOS has one from the first
        // instruction, Windows creates one during start()), and the fix is to depend on neither:
        // onUiThread is the contract for "run this later, on the UI thread", and it is the right
        // door for every platform.
        if (liveReload) {
            Optional<Path> source = Resources.directory(directory);
            source.ifPresent(this::watchForChanges);
        }
        return new PageSource.FromAssets(SCHEME, stagedRoot, fileName, spaFallback);
    }

    /**
     * Re-copies the assets into the staged root and reloads, whenever the source changes.
     *
     * <p>The page is reloaded from the staged directory rather than pointed at the source, so
     * the framework's client bridge stays available beside the assets and the project's own
     * source tree is never written to.
     */
    private void watchForChanges(Path sourceDir) {
        String resourceDir = "/" + sourceDir.getFileName();
        try {
            DevReload.watch(sourceDir, () -> host.onUiThread(() -> {
                try {
                    Resources.stageInto(stagedRoot, resourceDir);
                    if (verbose) {
                        System.out.println("[panamaview] change detected, reloading");
                    }
                    reload();
                } catch (IOException e) {
                    System.err.println("[panamaview] reload failed: " + e);
                }
            }));
            if (verbose) {
                System.out.println("[panamaview] live reload watching " + sourceDir);
            }
        } catch (IOException e) {
            // A missing or unwatchable directory is not worth failing a run over; the app
            // works, it just will not reload by itself.
            System.err.println("[panamaview] live reload unavailable: " + e.getMessage());
        }
    }

    /**
     * Declares the menu bar.
     *
     * <p>The bar is handed to the host when {@link #run()} starts, before the platform opens
     * its window: a platform builds its native menu during start-up, so a bar handed over
     * afterwards would arrive too late to appear in either menu.
     *
     * <p>A host that does not implement {@link MenuHost} simply has no menu. The application
     * still runs — a host that cannot take a menu is still usable.
     *
     * <p>A <em>null</em> bar means the same thing as never declaring one: on macOS the
     * framework's own Quit and Edit bar, and on Windows no bar at all. The null is noticed
     * here, where the bindings would have been read from it, and acted on at the run, where
     * the host is simply not given a menu. The door does not refuse what the rest of the
     * class accepts.
     *
     * <p>That is a cost worth naming: a null arriving from a bug is now silent where it used
     * to be a {@code NullPointerException}, and on macOS the visible symptom is the
     * <em>wrong</em> menu rather than an error. The branch has decided to pay that cost
     * elsewhere too — a null shortcut is silent as well — so it is said here rather than left
     * for a reader to find.
     */
    public WebApp menu(MenuBar bar) {
        this.menuBar = bar;
        this.menuBindings = bar == null ? Map.of() : bar.bindings();
        return this;
    }

    /**
     * Declares the tray icon and its menu.
     *
     * <p>Handed to the host when {@link #run()} starts, before the platform creates its window,
     * for the same reason the menu bar is: both platforms build the icon during start-up.
     *
     * <p>A <em>null</em> spec means no tray, the same way a null bar means no menu. A host that
     * does not implement {@link TrayHost} simply has no tray; the application still runs, and
     * the absence is reported rather than left to be discovered on screen.
     *
     * <p>Declaring a tray also changes what closing the last window means: see
     * {@link #quitWhenLastWindowCloses(boolean)}.
     */
    public WebApp tray(TraySpec tray) {
        this.traySpec = tray;
        this.trayBindings = tray == null ? Map.of() : tray.bindings();
        return this;
    }

    /**
     * Whether closing the last window ends the process.
     *
     * <p>The default is true, except when a tray was declared <em>and the host accepted it</em>:
     * an application with a tray is expected to outlive its window, and quitting would take the
     * icon with it. A tray that the host could not take leaves the default alone — otherwise the
     * application would become a background process with no window and no icon.
     *
     * <p>Pass false to keep an application alive without a tray, or true to make a tray
     * application quit with its window anyway.
     */
    public WebApp quitWhenLastWindowCloses(boolean quit) {
        this.quitOnLastClose = quit;
        return this;
    }

    /**
     * A menu item was chosen.
     *
     * <p>The id names an item of the declared bar. An id that names no binding — a platform
     * item, a separator, or an id the host made up — is ignored: there is nothing for the
     * portable layer to do about it, and the platform's own items are its own business.
     */
    private void onMenuItem(int id) {
        String name = menuBindings.get(id);
        if (name == null) {
            return;
        }
        // A click carries no parameters, so the binding is called with an empty map — the
        // same params a page call would get if it passed nothing.
        bridge.invokeBinding(name, Map.of());
    }

    /**
     * A tray item was chosen.
     *
     * <p>The same contract as {@link #onMenuItem(int)}, against the tray's own table: an id that
     * names no binding — a platform item, a separator, or an id the host made up — is ignored.
     */
    private void onTrayMenuItem(int id) {
        String name = trayBindings.get(id);
        if (name == null) {
            return;
        }
        bridge.invokeBinding(name, Map.of());
    }

    /** Runs the platform event loop. Blocks until the application quits. */
    public void run() throws IOException {
        String directory = entry.substring(0, entry.lastIndexOf('/'));
        String fileName = entry.substring(entry.lastIndexOf('/') + 1);

        PageSource page = resolvePage(directory, fileName);

        bridge.verbose(verbose);

        if (menuBar != null && host instanceof MenuHost menuHost) {
            // Before start(): both platforms build their native menu during start-up, so a bar
            // handed over afterwards would come too late to be in either menu.
            menuHost.setMenu(menuBar);
        }

        if (traySpec != null) {
            if (host instanceof TrayHost trayHost) {
                // Same reason as the menu: both platforms create the icon during start-up.
                trayHost.setTray(traySpec);
                trayAccepted = true;
            } else {
                // The icon is the whole feature, and its absence is otherwise
                // indistinguishable from an icon that is there and invisible.
                System.err.println("[panamaview] this host cannot show a tray, so the tray icon "
                        + "is not shown; the application still runs");
            }
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
                // over. Any reply would be addressed to a document that no longer exists. A
                // menu binding was not made by the page, so it is not among them.
                bridge.cancelPageRequests("the page navigated away");
            }

            @Override
            public void onMenuItem(int id) {
                WebApp.this.onMenuItem(id);
            }

            @Override
            public void onTrayMenuItem(int id) {
                WebApp.this.onTrayMenuItem(id);
            }

            @Override
            public boolean shouldQuitWhenLastWindowCloses() {
                // A tray makes the process outlive its window by default: closing the window is
                // how a tray application is put away, and quitting would take the icon with it.
                // An explicit choice wins either way, and a tray the host did not accept does
                // not change the default — an application with neither a window nor an icon is
                // a background process nobody can reach.
                return quitOnLastClose != null ? quitOnLastClose : !trayAccepted;
            }
        }, new WindowSpec(title, width, height, page, origin));
    }
}
