package panamaview.probe;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * Milestone-2a smoke: a real window, a real WebView2, and {@code ExecuteScript("2+2")} answered
 * back through a hand-built COM callback — the whole milestone-2 sequence with none of the
 * framework around it yet. What PASS proves: window creation, the message pump, environment
 * and controller creation, and a call from Windows' thread back into Java, in one run.
 *
 * <p>This must run in an interactive desktop session (RDP or the console): over SSH the
 * process lives in session 0, where WebView2 does not run. Every stage is logged to stdout
 * and to {@code smoke-result.txt} beside this module, so the run can be judged afterwards by
 * someone who was not watching the screen.
 */
public final class Smoke {

    private static final Path REPORT = Path.of("smoke-result.txt");
    private static final List<String> LOG = new ArrayList<>();
    private static volatile String failure;
    private static volatile String scriptResult;

    public static void main(String[] args) throws Exception {
        // Everything WebView2 touches lives on a thread of its own, not Java's main: the JVM's
        // launcher hands main() a thread whose COM state nobody here chose, and the browser's
        // event delivery is married to the apartment of the thread that created it.
        Thread pumpThread = new Thread(null, Smoke::runOnDedicatedThread, "panamaview-pump", 0);
        pumpThread.start();
        pumpThread.join();
        writeReport();
        System.exit(failure == null ? 0 : 1);
    }

    private static void runOnDedicatedThread() {
        try (Arena arena = Arena.ofConfined()) {
            run(arena);
        }
    }

    private static void run(Arena arena) {
        try {
            stage("os", System.getProperty("os.name") + " " + System.getProperty("os.arch")
                    + ", Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");

            Path loaderDll = loaderDll();
            stage("loader", loaderDll.toAbsolutePath() + " (" + Files.size(loaderDll) + " bytes)");
            // The framework's own version probe first: if the runtime is not even installed,
            // say that in those words instead of a raw HRESULT later in the sequence.
            String version = panamaview.windows.WebView2.browserVersion(loaderDll);
            stage("runtime", "WebView2 " + version);

            int coInit = Native.coInitializeApartmentThreaded();
            stage("com", "CoInitializeEx returned 0x" + Integer.toHexString(coInit)
                    + (coInit == 0x80010106 ? " (RPC_E_CHANGED_MODE - the apartment was not ours to choose)" : ""));

            MemorySegment hwnd = Native.createWindow(arena, "panamaview smoke", 900, 600);
            stage("window", "created (hwnd 0x" + Long.toHexString(hwnd.address()) + "): "
                    + Native.windowState(arena, hwnd));

            MemorySegment[] webview = new MemorySegment[1];
            MemorySegment[] controllerBox = new MemorySegment[1];

            int[] attempts = {0};
            Runnable[] attempt = new Runnable[1];

            MemorySegment scriptDone = Native.completedHandler((self, errorCode, result) -> {
                try {
                    if (errorCode < 0) {
                        attempts[0]++;
                        if (attempts[0] >= 30) {
                            return failAndQuit("ExecuteScript kept failing: 0x" + Integer.toHexString(errorCode));
                        }
                        // The page is not ready yet (ERROR_INVALID_STATE). The browser starts
                        // on its own schedule; the run waits for it instead of racing it.
                        stage("script", "attempt " + attempts[0] + " rejected with 0x"
                                + Integer.toHexString(errorCode) + " - page not ready, retrying");
                        Native.after(5000, hwnd, attempt[0]);
                        return errorCode;
                    }
                    MemorySegment json = result.reinterpret(Long.MAX_VALUE).getAtIndex(ADDRESS, 0);
                    scriptResult = json.equals(MemorySegment.NULL) ? "(null)"
                            : json.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
                    stage("script", "2+2 returned " + scriptResult);
                    Native.postQuit();
                    return 0;
                } catch (Throwable t) {
                    return failAndQuit(t);
                }
            });

            attempt[0] = () -> {
                try {
                    Native.executeScript(webview[0], "2+2", scriptDone);
                } catch (IllegalStateException rejected) {
                    // ExecuteScript fails synchronously when the document is not ready; the
                    // async path (scriptDone) would only see the failures that get queued.
                    attempts[0]++;
                    if (attempts[0] >= 30) {
                        failAndQuit("ExecuteScript kept failing: " + rejected.getMessage());
                        return;
                    }
                    stage("script", "attempt " + attempts[0] + " rejected - page not ready, retrying");
                    Native.after(5000, hwnd, attempt[0]);
                } catch (Throwable t) {
                    failAndQuit(t);
                }
            };

            MemorySegment navigationDone = Native.eventHandler((self, sender, args) -> {
                try {
                    MemorySegment source = sender.reinterpret(Long.MAX_VALUE);
                    // NavigationCompleted fires for the initial about:blank too; executing
                    // there is what 0x8007139F (ERROR_INVALID_STATE) was punishing. The
                    // title is how we tell that from our own page.
                    String title = Native.documentTitle(source);
                    stage("navigation", "completed, title is '" + title + "'");
                    if (!"panamaview smoke".equals(title)) {
                        return 0;
                    }
                    Native.executeScript(source, "2+2", scriptDone);
                    return 0;
                } catch (Throwable t) {
                    return failAndQuit(t);
                }
            });

            MemorySegment controllerDone = Native.completedHandler((self, errorCode, controllerArg) -> {
                try {
                    if (errorCode < 0) {
                        return failAndQuit("CreateCoreWebView2Controller completed with 0x" + Integer.toHexString(errorCode));
                    }
                    stage("controller", "created");
                    // Every pointer arriving from native is a zero-length segment until
                    // reinterpreted; the crash earlier in this branch named that rule.
                    MemorySegment controller = controllerArg.reinterpret(Long.MAX_VALUE);
                    controllerBox[0] = controller;
                    stage("refcount", "controller AddRef says " + Native.addRef(controller));
                    webview[0] = Native.coreWebView2(controller);
                    stage("refcount", "webview AddRef says " + Native.addRef(webview[0]));
                    Native.putIsVisible(controller, true);
                    Native.putBounds(controller, Native.clientRect(arena, hwnd));
                    stage("webview", "obtained and sized to the client rect");
                    // Event registration is out of this run on purpose: if navigation happens
                    // with no handler of ours registered, the handler objects were the poison.
                    Native.navigateToString(webview[0], pageHtml());
                    // Evidence, not a workaround: three seconds in, ask the page directly
                    // what it is and run the script without waiting for any event. If this
                    // works while NavigationCompleted never fires, the event path is what is
                    // broken; if the page is not there either, the navigation itself is.
                    Native.after(3000, hwnd, () -> {
                        try {
                            String source = Native.currentSource(webview[0]);
                            String title = Native.documentTitle(webview[0]);
                            stage("timer", "3 s in, source is '" + source + "', title '" + title
                                    + "', controller vtable 0x" + Long.toHexString(
                                            controllerBox[0].getAtIndex(ADDRESS, 0).address()));
                        } catch (Throwable t) {
                            stage("timer", "state probe failed: " + t);
                        }
                        // The eyes: whatever the state says, the picture is the truth.
                        try {
                            Native.capturePreview(webview[0], Path.of("smoke-page.png").toAbsolutePath(),
                                    () -> stage("capture", "smoke-page.png written"),
                                    error -> stage("capture", "failed: " + error));
                        } catch (Throwable t) {
                            stage("capture", "failed to start: " + t);
                        }
                        attempt[0].run();
                    });
                    return 0;
                } catch (Throwable t) {
                    return failAndQuit(t);
                }
            });

            MemorySegment environmentDone = Native.completedHandler((self, errorCode, environmentArg) -> {
                try {
                    if (errorCode < 0) {
                        return failAndQuit("environment completed with 0x" + Integer.toHexString(errorCode));
                    }
                    stage("environment", "created");
                    MemorySegment environment = environmentArg.reinterpret(Long.MAX_VALUE);
                    Native.createController(environment, hwnd, controllerDone);
                    return 0;
                } catch (Throwable t) {
                    return failAndQuit(t);
                }
            });

            Path userData = Path.of("target", "wv2-userdata").toAbsolutePath().normalize();
            Files.createDirectories(userData);
            stage("userdata", userData.toString());

            Native.createEnvironment(Native.loader(loaderDll),
                    arena.allocateFrom(userData.toString(), StandardCharsets.UTF_16LE), environmentDone);

            long mainThread = Native.currentThreadId();
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(240_000);
                } catch (InterruptedException ignored) {
                    return;
                }
                if (failure == null && scriptResult == null) {
                    stage("watchdog", "240 s with no result - ending the run so it cannot hang");
                    Native.postThreadQuit(mainThread);
                }
            }, "smoke-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            stage("pump", "running - the window should be on screen now");
            Native.pump(arena);
            stage("pump", "exited");

            if (failure == null && scriptResult != null) {
                stage("verdict", "PASS");
                System.out.println("[smoke] PASS - window, WebView2 and ExecuteScript all worked"
                        + ("4".equals(scriptResult) ? "" : " (but 2+2 returned " + scriptResult + ")"));
                if (!"4".equals(scriptResult)) {
                    failure = "2+2 returned " + scriptResult;
                }
            } else if (failure == null) {
                failure = "the run ended before any script result arrived";
            }
        } catch (Throwable t) {
            failAndQuit(t);
        }
    }

    /**
     * The page the window shows, carried by NavigateToString so the smoke needs no assets and
     * no scheme support (WebView2 does not navigate data: URLs). The title is load-bearing: it
     * is how NavigationCompleted tells this page from the initial about:blank.
     */
    private static String pageHtml() {
        return "<html><head><title>panamaview smoke</title></head>"
                + "<body style='font-family:sans-serif;background:#14181f;color:#e8eaed;"
                + "display:grid;place-items:center;height:95vh;margin:0'>"
                + "<div style='text-align:center'><h1>panamaview - Windows smoke</h1>"
                + "<p>If this window reached your screen, the WebView2 chain works.</p></div></body></html>";
    }

    private static int failAndQuit(String what) {
        failure = what;
        stage("FAILED", what);
        Native.postQuit();
        return -1;
    }

    private static int failAndQuit(Throwable t) {
        StringBuilder chain = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            chain.append(chain.isEmpty() ? "" : " caused by ")
                    .append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
        }
        return failAndQuit(chain.toString());
    }

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

    private static void stage(String name, String detail) {
        String line = "stage " + name + " ".repeat(Math.max(1, 12 - name.length())) + detail;
        LOG.add(line);
        System.out.println(line);
    }

    private static void writeReport() throws Exception {
        Files.write(REPORT, (String.join(System.lineSeparator(), LOG) + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8));
        System.out.println("report written to " + REPORT.toAbsolutePath());
    }
}
