package comparison;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import panamaview.UiHost;
import panamaview.WebApp;

/** Identical Java business handlers, WebApp, wire protocol and assets for both web hosts. */
public final class ComparisonApp {
    public static volatile int exitCode = 1;

    public static void run(String backend, UiHost host, Runnable close) throws Exception {
        AtomicInteger interrupts = new AtomicInteger();
        WebApp app = new WebApp(host).title("panamaview backend comparison")
                .size(960, 680).load("/web/index.html").liveReload(false);
        app.bind("hello", p -> "Hello " + p.get("name"));
        app.bind("sum", p -> ((Number) p.get("a")).intValue() + ((Number) p.get("b")).intValue());
        app.bind("echo", p -> p);
        app.bind("runtime", p -> Map.of("backend", backend,
                "virtual", Thread.currentThread().isVirtual(), "ui", app.isUiThread(),
                "image", System.getProperty("org.graalvm.nativeimage.imagecode", "jvm")));
        app.bind("slow", p -> { Thread.sleep(400); return "done"; });
        app.bindUi("slowUi", p -> { Thread.sleep(400); return "done"; });
        app.bind("interruptible", p -> {
            try { Thread.sleep(5000); return "unexpected"; }
            catch (InterruptedException interrupted) { interrupts.incrementAndGet(); throw interrupted; }
        });
        app.bind("interrupts", p -> interrupts.get());
        app.bind("deadline", p -> { Thread.sleep(5000); return "unexpected"; }, Duration.ofMillis(150));
        app.bind("report", p -> {
            int passed = ((Number) p.get("passed")).intValue();
            int failed = ((Number) p.get("failed")).intValue();
            System.out.println("[comparison] backend=" + backend + " passed=" + passed + " failed=" + failed);
            System.out.println("[comparison] checks=" + p.get("checks"));
            exitCode = failed == 0 ? 0 : 1;
            Thread.startVirtualThread(() -> {
                try { Thread.sleep(1500); host.onUiThread(close); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            return "recorded";
        });
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(45_000); System.err.println("[comparison] FAIL timeout"); System.exit(2); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        }, "comparison-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            app.onReady(web -> web.postToJs(Map.of("autotest", true, "backend", backend))).run();
        } finally {
            watchdog.interrupt();
        }
    }
}
