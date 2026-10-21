package io.github.lihuu.panamaview.windows;

import io.github.lihuu.panamaview.PageSource;
import io.github.lihuu.panamaview.UiHost;
import io.github.lihuu.panamaview.WindowSpec;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The Windows implementation of the seam: Win32 plus WebView2, owned entirely by this package.
 * Only this class is public here — an application cannot reach the COM machinery, and the
 * platform seam is the whole surface, exactly as on macOS.
 *
 * <h2>Threading, and where this differs from macOS</h2>
 * AppKit demands the process' first thread; WebView2 only demands that the thread which
 * created the environment pumps messages for the rest of its life. So this host runs the
 * event loop on a thread of its own — created in {@link #start}, kept for the application's
 * lifetime — and the JVM's main thread stays Java's. The {@code -XstartOnFirstThread} flag
 * that is compulsory on macOS has no Windows counterpart, and a generated project here needs
 * neither it nor its workarounds. {@link #start} still blocks the calling thread until the
 * application quits, so the contract the portable layer sees is the same on both platforms;
 * the pump simply lives elsewhere.
 *
 * <p>The one visible consequence is that {@link #isUiThread} is false until the pump is up.
 * That is a fact about <em>now</em>, and it used to be read as a fact about <em>later</em>:
 * work that wanted the UI thread asked {@code isUiThread()} first, and on this platform the
 * answer was no for the whole of the window's creation — so the work was never done at all.
 * The queue ({@link #onUiThread}) is the answer instead: it accepts work from any thread at
 * any time, and the pump runs it.
 *
 * <h2>What the page is served from</h2>
 * The macOS side answers {@code scheme://app/…} with its own URL scheme handler. Windows has
 * no such hook in the base interface, so this host maps a virtual host name onto the staged
 * directory — {@code https://<scheme>.panamaview/…} — which gives the page the same thing the
 * macOS scheme does: a real origin, so ES modules, {@code fetch} and code splitting work.
 * Relative URLs inside the page behave identically on both platforms; only absolute URLs
 * differ, and a page that wants its own address asks Java instead of guessing.
 */
public final class WindowsHost implements UiHost {

    private static final String VIRTUAL_HOST_SUFFIX = ".panamaview";

    private volatile Callbacks callbacks;
    private volatile Thread pumpThread;
    private volatile long pumpThreadId;
    private volatile MemorySegment hwnd;
    private volatile MemorySegment controller;
    private volatile MemorySegment webview;
    private final LinkedBlockingQueue<Runnable> uiQueue = new LinkedBlockingQueue<>();
    private final CountDownLatch done = new CountDownLatch(1);
    private Arena pumpArena;

    @Override
    public void start(Callbacks callbacks, WindowSpec spec) {
        this.callbacks = callbacks;
        Thread pump = new Thread(null, () -> pump(spec), "panamaview-ui", 0);
        this.pumpThread = pump;
        pump.start();
        try {
            // The contract: start blocks until the application quits. The loop itself runs on
            // the pump thread, which is the thread whose apartment and message queue the
            // browser is married to.
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the event loop", e);
        }
    }

    private void pump(WindowSpec spec) {
        pumpThreadId = Thread.currentThread().threadId();
        pumpArena = Arena.ofConfined();
        try {
            Win32.coInitializeApartmentThreaded();

            WindowSpec.Origin origin = spec.origin();
            hwnd = origin == null
                    ? Win32.createWindow(pumpArena, spec.title(), spec.width(), spec.height(), this::onWindowMessage)
                    : Win32.createWindow(pumpArena, spec.title(), spec.width(), spec.height(),
                            origin.x(), origin.y(), this::onWindowMessage);

            // Work submitted before the window existed has no message to wake the pump, because
            // there was no window to post it to. The window exists now, so the queue is drained
            // here — on the pump thread, which is the only thread these actions may run on.
            //
            // Draining here rather than later is deliberate: the web view is created by a COM
            // completion handler, so a drain that waited for it would never run at all if the
            // environment failed to start — and a queued action would be lost rather than
            // reported. Nothing is lost by running early either: an action that touches the web
            // view before it exists is a no-op, because evaluate() has nothing to evaluate on,
            // and the page has not loaded yet for a reload to be about.
            drainUiQueue();

            Path loader = findLoader();
            WebView2.createEnvironment(WebView2.load(loader),
                    pumpArena.allocateFrom(userDataFolder().toString(), java.nio.charset.StandardCharsets.UTF_16LE),
                    Com.completed((self, errorCode, environment) -> {
                        if (errorCode < 0) {
                            callbacks.onPageFailed("the WebView2 environment failed: 0x"
                                    + Integer.toHexString(errorCode));
                            quit();
                            return errorCode;
                        }
                        MemorySegment env = environment.reinterpret(Long.MAX_VALUE);
                        WebView2.createController(env, hwnd,
                                Com.completed((self2, code, controllerResult) -> {
                                    if (code < 0) {
                                        callbacks.onPageFailed("the WebView2 controller failed: 0x"
                                                + Integer.toHexString(code));
                                        quit();
                                        return code;
                                    }
                                    // The controller arrives with a reference the completion
                                    // owns — and the prototype proved that without taking our
                                    // own, the runtime reclaims the object within seconds.
                                    MemorySegment controllerHandle = controllerResult.reinterpret(Long.MAX_VALUE);
                                    Com.callSelf(controllerHandle, Com.ADD_REF, "AddRef");
                                    this.controller = controllerHandle;
                                    webview = WebView2.coreWebView2(controllerHandle);
                                    Com.callSelf(webview, Com.ADD_REF, "AddRef");
                                    WebView2.putIsVisible(controllerHandle, true);
                                    resizeWebView();

                                    WebView2.addWebMessageReceived(webview, Com.event((self3, sender, args) -> {
                                        MemorySegment eventArgs = args.reinterpret(Long.MAX_VALUE);
                                        callbacks.onMessage(WebView2.webMessageAsString(eventArgs));
                                        return 0;
                                    }));
                                    WebView2.addNavigationStarting(webview, Com.event((self3, sender, args) -> {
                                        callbacks.onPageUnloading();
                                        return 0;
                                    }));
                                    WebView2.addNavigationCompleted(webview, Com.event((self3, sender, args) -> {
                                        MemorySegment eventArgs = args.reinterpret(Long.MAX_VALUE);
                                        MemorySegment page = sender.reinterpret(Long.MAX_VALUE);
                                        if (WebView2.navigationSucceeded(eventArgs)) {
                                            callbacks.onPageLoaded(WebView2.currentSource(page),
                                                    WebView2.documentTitle(page));
                                        } else {
                                            callbacks.onPageFailed("the page failed to load");
                                        }
                                        return 0;
                                    }));

                                    navigate(spec.page());
                                    return 0;
                                }));
                        return 0;
                    }));

            Win32.pump(pumpArena);
        } catch (Throwable t) {
            System.err.println("[panamaview] the Windows host stopped: " + t);
            t.printStackTrace();
            if (callbacks != null) {
                callbacks.onPageFailed(String.valueOf(t));
            }
        } finally {
            done.countDown();
        }
    }

    private long onWindowMessage(MemorySegment hwndArg, int message, long wparam, long lparam) {
        switch (message) {
            case Win32.WM_SIZE -> {
                resizeWebView();
            }
            case Win32.WM_APP_RUNNABLE -> {
                drainUiQueue();
            }
            case Win32.WM_DESTROY -> {
                if (callbacks.shouldQuitWhenLastWindowCloses()) {
                    quit();
                }
            }
            default -> {
                // DefWindowProc is not optional: it is where close buttons, focus, painting
                // and everything else this host never heard of get handled. Its result is the
                // answer Windows expects - WM_NCCREATE answered 0 aborts window creation
                // itself, which is exactly what the first demo run did.
                return Win32.defWindowProc(hwndArg, message, wparam, lparam);
            }
        }
        // The cases above handle-or-defer; for them a zero answer is correct.
        return 0;
    }

    /**
     * Runs every action submitted for the UI thread, in the order it was submitted.
     *
     * <p>Called on the pump thread, which is the only thread these may run on: they touch the
     * window and the web view. One action that throws does not strand the rest, because the
     * queue is drained in a loop and a failure here would otherwise leave every later action
     * unrun — which is how one bad reload could silently stop all reloading.
     */
    private void drainUiQueue() {
        Runnable action;
        while ((action = uiQueue.poll()) != null) {
            try {
                action.run();
            } catch (Throwable t) {
                System.err.println("[panamaview] a UI-thread task failed: " + t);
                t.printStackTrace(System.err);
            }
        }
    }

    private void resizeWebView() {
        MemorySegment controllerHandle = controller;
        if (controllerHandle != null && hwnd != null) {
            WebView2.putBounds(controllerHandle, Win32.clientRect(pumpArena, hwnd));
        }
    }

    private void navigate(PageSource page) {
        switch (page) {
            case PageSource.FromAssets assets -> {
                // A virtual host name needs the shape of a real one, so the scheme becomes a
                // subdomain: panamaview://app/… on macOS, https://app.panamaview/… here.
                String hostName = PageSource.FromAssets.HOST + VIRTUAL_HOST_SUFFIX;
                WebView2.setVirtualHostToFolderMapping(webview, hostName, assets.root());
                WebView2.navigate(webview, "https://" + hostName + pathOf(assets.entry()));
            }
            case PageSource.FromUrl url -> WebView2.navigate(webview, url.url().toString());
        }
    }

    private static String pathOf(String entry) {
        String path = entry.startsWith("/") ? entry : "/" + entry;
        return path;
    }

    private void quit() {
        Win32.postQuit();
    }

    // ---------------------------------------------------------------- UiHost

    @Override
    public void evaluate(String script) {
        MemorySegment webviewHandle = webview;
        if (webviewHandle == null) {
            return;
        }
        // Replies are fire and forget on this side: the page resolves them itself through
        // __pvResolve, and a completed handler per reply would be machinery for nothing.
        WebView2.executeScript(webviewHandle, script, evaluateAcknowledgement);
    }

    private final MemorySegment evaluateAcknowledgement = Com.acknowledging((int hresult) -> {
        if (hresult < 0) {
            System.err.println("[panamaview] evaluate failed: 0x" + Integer.toHexString(hresult));
        }
    });

    @Override
    public void onUiThread(Runnable action) {
        uiQueue.add(action);
        // The window is the pump's wake-up bell, and it does not exist yet when this is called
        // before start() — the pump thread is created inside start(). Posting to a null HWND
        // does not fail loudly: PostMessageW returns 0, the message goes to the *calling*
        // thread's queue, and the pump thread never hears about it. The action would sit in
        // uiQueue forever, which is how live reload came to do nothing on Windows at all.
        //
        // So the queue is the source of truth and the message is only the nudge. When the
        // window is not there yet, the pump drains the queue itself once it is up (see pump()).
        // When it is there, the message wakes the pump, which drains the queue in turn.
        MemorySegment window = hwnd;
        if (window != null) {
            Win32.postRunnable(window, 0);
        }
    }

    @Override
    public boolean isUiThread() {
        return Thread.currentThread().threadId() == pumpThreadId && pumpThreadId != 0;
    }

    // ---------------------------------------------------------------- platform extras

    /** Where the window is, as {@code widthxheight at x,y}. Registered by the composition root. */
    public String windowRectangle() {
        return Win32.windowRectangle(pumpArena, hwnd);
    }

    /**
     * The clipboard's text, or {@code null} when it holds none.
     *
     * <p>The program's side of the clipboard, as on macOS: ⌘C/⌘V in the page never reach this,
     * because the web view handles them itself. What does reach it is a handler behind a copy
     * button, a read at start-up, or a page copying something it fetched asynchronously — where
     * the browser has stopped treating the click as a fresh gesture.
     */
    public String readClipboard() {
        return Win32.readClipboard();
    }

    /** Replaces the clipboard with {@code text}. */
    public void writeClipboard(String text) {
        Win32.writeClipboard(text);
    }

    /**
     * The clipboard's sequence number.
     *
     * <p>Windows increments it for every write, from any process, which is the same contract as
     * {@code NSPasteboard.changeCount} on macOS: compare two reads to notice a change without
     * reading the contents.
     */
    public long clipboardChangeCount() {
        return Win32.clipboardChangeCount();
    }

    // ---------------------------------------------------------------- paths

    /**
     * The loader DLL beside the running application: {@code loader/WebView2Loader.dll} under
     * the working directory, or the directory the binary lives in. The probe module's fetch
     * script stages it; a packaged application ships it beside itself.
     */
    private static Path findLoader() {
        Path staged = Path.of("loader", "WebView2Loader.dll");
        if (java.nio.file.Files.isRegularFile(staged)) {
            return staged.toAbsolutePath();
        }
        Path beside = Path.of("WebView2Loader.dll");
        if (java.nio.file.Files.isRegularFile(beside)) {
            return beside.toAbsolutePath();
        }
        throw new IllegalStateException("WebView2Loader.dll not found — stage it with windows-probe/fetch-loader.ps1, "
                + "or ship it beside the application");
    }

    /** Browser state lives here rather than wherever the exe happens to be. */
    private static Path userDataFolder() {
        Path folder = Path.of(System.getProperty("user.home"), ".panamaview", "webview2");
        try {
            java.nio.file.Files.createDirectories(folder);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not create the WebView2 user data folder " + folder, e);
        }
        return folder;
    }
}
