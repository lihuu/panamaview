package panamaview;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The PoC application: portable handlers plus the page's self-test handshake.
 *
 * <p>Split out from the old {@code Main} so the demo application and the platform entry
 * point are separate things. This file is portable — it drives windows, sleeps on virtual
 * threads and reads system properties, none of which is macOS-specific — while
 * {@code panamaview.macos.Main} owns the macOS-only parts.
 */
public final class App {

    /** Where the page should write its self-portrait, if the caller asked for one. */
    public static final String SNAPSHOT_PROPERTY = "panamaview.snapshot";

    private final WebApp web;

    /**
     * How many times a handler has actually been interrupted.
     *
     * <p>This exists to make cancellation <em>observable</em>. A page that cancels and stops
     * waiting has proved nothing about Java: the promise rejects locally either way. The only
     * honest evidence is that the handler on the other side was really stopped, and a counter
     * that moves is that evidence.
     */
    private final AtomicInteger interrupts = new AtomicInteger();

    public App(UiHost host) {
        this.web = new WebApp(host)
                .title("Java WebView PoC")
                .size(1000, 700)
                .load("/web/index.html");
    }

    /** The underlying app, for wiring platform-specific extras onto. */
    public WebApp web() {
        return web;
    }

    public App install() {
        // The call the brief asks for: java.invoke("hello", { name: "World" })
        web.bind("hello", params -> "Hello " + params.getOrDefault("name", "world"));

        // Round-trips a structured value, to exercise the codec rather than just strings.
        web.bind("echo", params -> params);

        // Facts only the Java side can answer, including where this handler actually ran —
        // which is the evidence that it is no longer on the UI thread.
        web.bind("runtime", params -> runtimeReport());

        // Deliberately slow, and deliberately off the UI thread. The page holds one of
        // these open and confirms other calls still complete while it is in flight; if the
        // handler were still on the UI thread, nothing else could be answered until it
        // finished.
        //
        // The sleep also makes this the cancellation test: an interrupted Thread.sleep throws,
        // so "stop working" is observable rather than merely requested.
        web.bind("slow", params -> {
            long millis = ((Number) params.getOrDefault("ms", 500)).longValue();
            Thread.sleep(millis);
            return "slept " + millis + " ms on " + Thread.currentThread();
        });

        // A deliberately short deadline, to prove the Java-side timer fires on its own —
        // without relying on the page's own fuse, which is much longer on purpose.
        web.bind("deadlineProbe", params -> {
            Thread.sleep(5000);
            return "should never be reached: the deadline is 300 ms";
        }, Duration.ofMillis(300));

        // Sleeps, and records whether it was interrupted doing so.
        web.bind("interruptible", params -> {
            try {
                Thread.sleep(((Number) params.getOrDefault("ms", 5000)).longValue());
                return "completed on its own";
            } catch (InterruptedException e) {
                interrupts.incrementAndGet();
                throw e;
            }
        });

        // Lets the page read that counter back, so the assertion lives in the test.
        web.bind("interrupts", params -> interrupts.get());

        // No deadline at all. This is the escape hatch for work that is honestly unbounded —
        // the agent-run case — where a fixed number would be a guess that eventually fails a
        // real request.
        web.bind("unbounded", params -> {
            long millis = ((Number) params.getOrDefault("ms", 50)).longValue();
            Thread.sleep(millis);
            return "ran " + millis + " ms with no deadline";
        }, null);

        // The control experiment for the line above. Same work, but pinned to the UI thread,
        // so it *should* stall everything — a test that only ever passes proves nothing, and
        // this one exists to fail loudly if the bridge ever stops scheduling off-thread.
        web.bindUi("slowUi", params -> {
            long millis = ((Number) params.getOrDefault("ms", 500)).longValue();
            Thread.sleep(millis);
            return "slept " + millis + " ms on " + Thread.currentThread();
        });

        // The page sends its self-test verdict here, so the result ends up in stdout and can
        // be checked mechanically instead of by reading it off a screenshot.
        web.bind("report", params -> {
            int passed = ((Number) params.getOrDefault("passed", 0)).intValue();
            int failed = ((Number) params.getOrDefault("failed", 0)).intValue();
            System.out.println("[selftest] " + passed + " passed, " + failed + " failed");
            if (params.get("checks") instanceof java.util.List<?> checks) {
                for (Object raw : checks) {
                    if (raw instanceof Map<?, ?> c) {
                        boolean ok = Boolean.TRUE.equals(c.get("ok"));
                        System.out.println("[selftest]   " + (ok ? "PASS" : "FAIL") + "  "
                                + c.get("name") + " — " + c.get("detail"));
                    }
                }
            }
            return failed == 0 ? "ok" : "failed";
        });

        // Java → JavaScript, fired the moment the page is ready. The page treats the
        // `autotest` flag as "run the whole bridge self-test".
        web.onReady(app -> app.postToJs(handshake()));

        return this;
    }

    private Map<String, Object> runtimeReport() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("javaVendor", System.getProperty("java.vendor"));
        info.put("vmName", System.getProperty("java.vm.name"));
        info.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        info.put("arch", System.getProperty("os.arch"));
        info.put("imageKind", System.getProperty("org.graalvm.nativeimage.imagecode", "jvm"));
        // Handler threading: the whole point of the bridge's dispatch design.
        info.put("handlerIsVirtual", Thread.currentThread().isVirtual());
        info.put("handlerOnUiThread", web.isUiThread());
        info.put("handlerThread", Thread.currentThread().getName().isEmpty()
                ? "VirtualThread" : Thread.currentThread().getName());
        return info;
    }

    private Map<String, Object> handshake() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", "Hello from Java");
        payload.put("javaVersion", System.getProperty("java.version"));
        payload.put("imageKind", System.getProperty("org.graalvm.nativeimage.imagecode", "jvm"));
        payload.put("autotest", true);
        // Off by default: the page ends by navigating away, which is useful once for proving
        // the unload path but would make every ordinary run land on a blank document.
        payload.put("navigateAway", Boolean.getBoolean("panamaview.navigateAway"));
        String snapshot = System.getProperty(SNAPSHOT_PROPERTY);
        if (snapshot != null) {
            payload.put("snapshot", snapshot);
        }
        return payload;
    }
}
