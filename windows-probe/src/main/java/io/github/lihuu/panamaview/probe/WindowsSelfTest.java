package io.github.lihuu.panamaview.probe;

import io.github.lihuu.panamaview.WebApp;
import io.github.lihuu.panamaview.windows.WindowsHost;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The bridge self-test, on Windows.
 *
 * <p>The macOS side has had this since the beginning: thirteen checks, run by the page, with
 * the verdict handed to Java so it lands in stdout where a machine can read it. Windows had
 * nothing equivalent — {@link WindowsDemo} is an interactive demo with buttons, which proves
 * the same things to a person watching the screen and to nobody else. A bug that only shows
 * up on Windows therefore had nowhere to show up: the demo runs on macOS, where the portable
 * half of the framework behaves the same way it always has.
 *
 * <p>What is checked here is the portable half of the contract, because that is the half the
 * two platforms have to agree on: calls cross the bridge, structured values survive the trip,
 * a slow handler does not block the window, a UI-thread handler does, a cancelled handler is
 * really stopped, Java's own deadline fires, and two hundred replies are correlated by id
 * rather than by arrival order.
 *
 * <p>Two of the macOS checks are absent here, and their absence is deliberate rather than an
 * oversight. One is a by-value {@code NSRect} round trip, which is AppKit's ABI and has no
 * meaning on Windows. The other is a programmatic resize: Windows has no bound for it yet
 * ({@code Win32} binds no {@code SetWindowPos} or {@code MoveWindow}), so there is nothing to
 * call. A check that cannot fail is not a check.
 *
 * <p>The exit code is the verdict: 0 when every check passed, 1 otherwise. That is what makes
 * it usable from a script.
 */
public final class WindowsSelfTest {

    /** How many handlers have actually been interrupted. The evidence that cancellation works. */
    private static final AtomicInteger INTERRUPTS = new AtomicInteger();

    private static WebApp app;

    public static void main(String[] args) throws Exception {
        WindowsHost host = new WindowsHost();
        app = new WebApp(host)
                .title("panamaview self-test")
                .size(1000, 700)
                .load("/selftest/selftest.html")

                // 1. A plain call.
                .bind("hello", params -> "Hello " + params.getOrDefault("name", "world"))
                // 2. Structured values, so this is a codec test and not a string test.
                .bind("echo", params -> params)

                // 3. Slow work on a virtual thread: the window must not wait for it.
                .bind("slow", params -> {
                    long millis = ((Number) params.getOrDefault("ms", 500)).longValue();
                    Thread.sleep(millis);
                    return "slept " + millis + " ms";
                })

                // 3b. The control: the same work, on the UI thread, which *should* block.
                // Without this, check 3 could pass for reasons that have nothing to do with
                // scheduling. Same work, same duration, different thread.
                .bindUi("slowUi", params -> {
                    long millis = ((Number) params.getOrDefault("ms", 500)).longValue();
                    Thread.sleep(millis);
                    return "slept " + millis + " ms on the UI thread";
                })

                // 4. A deliberately short deadline, to prove the Java-side timer fires on its
                // own — without relying on the page's own fuse, which is much longer.
                .bind("deadlineProbe", params -> {
                    Thread.sleep(5000);
                    return "should never be reached: the deadline is 300 ms";
                }, Duration.ofMillis(300))

                // 5. No deadline at all: the escape hatch for honestly unbounded work.
                .bind("unbounded", params -> {
                    long millis = ((Number) params.getOrDefault("ms", 50)).longValue();
                    Thread.sleep(millis);
                    return "ran " + millis + " ms with no deadline";
                }, null)

                // 6. Sleeps, and records whether it was interrupted doing so.
                .bind("interruptible", params -> {
                    try {
                        Thread.sleep(((Number) params.getOrDefault("ms", 5000)).longValue());
                        return "completed on its own";
                    } catch (InterruptedException e) {
                        INTERRUPTS.incrementAndGet();
                        throw e;
                    }
                })
                .bind("interrupts", params -> INTERRUPTS.get())

                // 7. The platform extra. bindUi, because it asks the window a question and
                // window questions are only legal on the UI thread.
                .bindUi("windowInfo", params -> Map.of(
                        "window", host.windowRectangle(),
                        "onUiThread", host.isUiThread()))

                // 8. Runtime facts only Java can supply, including which thread answered.
                .bind("runtime", params -> runtime())

                // 9. The verdict goes here, so it lands in stdout where a machine can read it.
                .bind("report", params -> {
                    int passed = ((Number) params.getOrDefault("passed", 0)).intValue();
                    int failed = ((Number) params.getOrDefault("failed", 0)).intValue();
                    System.out.println("[selftest] " + passed + " passed, " + failed + " failed");
                    if (params.get("checks") instanceof java.util.List<?> checks) {
                        for (Object raw : checks) {
                            if (raw instanceof Map<?, ?> c) {
                                boolean ok = Boolean.TRUE.equals(c.get("ok"));
                                System.out.println("[selftest]   " + (ok ? "PASS" : "FAIL") + "  "
                                        + c.get("name") + " - " + c.get("detail"));
                            }
                        }
                    }
                    System.out.println("[selftest] DONE");
                    // The window stays up a moment longer, so a screenshot taken from outside
                    // can catch it: the verdict is in this log, but a log does not show that
                    // anything was actually drawn.
                    new Thread(() -> {
                        try {
                            Thread.sleep(8000);
                        } catch (InterruptedException ignored) {
                            // exiting anyway
                        }
                        System.exit(failed == 0 ? 0 : 1);
                    }, "selftest-exit").start();
                    return "ok";
                });

        app.onReady(a -> a.postToJs(Map.of("message", "ready", "autotest", true)));
        app.run();
    }

    private static Map<String, Object> runtime() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("vmName", System.getProperty("java.vm.name"));
        info.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        info.put("arch", System.getProperty("os.arch"));
        info.put("handlerIsVirtual", Thread.currentThread().isVirtual());
        info.put("handlerOnUiThread", app.isUiThread());
        info.put("handlerThread", Thread.currentThread().getName().isEmpty()
                ? "VirtualThread" : Thread.currentThread().getName());
        return info;
    }

    private WindowsSelfTest() {
    }
}
