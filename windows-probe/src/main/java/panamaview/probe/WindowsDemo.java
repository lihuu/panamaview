package panamaview.probe;

import panamaview.WebApp;
import panamaview.windows.WindowsHost;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The interactive demo, on the real framework path: {@link WindowsHost} behind the portable
 * {@link WebApp}, assets staged out of this module's resources, the bridge carrying every
 * button press. What each control proves:
 *
 * <ul>
 *   <li>打招呼 — an input and a click crossing into Java and back, once per press;</li>
 *   <li>再数一次 — state held in Java's heap, not in the page;</li>
 *   <li>慢任务 — a handler on a virtual thread pushing progress into the page, cancellable
 *       from the page, interrupted for real on the Java side;</li>
 *   <li>窗口在哪 — a {@code bindUi} handler, running on the pump thread where window
 *       questions are allowed.</li>
 * </ul>
 *
 * <p>Close the window to quit — that is the quit path, and it exercises it.
 */
public final class WindowsDemo {

    private static final AtomicInteger COUNT = new AtomicInteger();
    private static WebApp app;

    public static void main(String[] args) {
        WindowsHost host = new WindowsHost();
        app = new WebApp(host)
                .title("panamaview demo")
                .size(980, 700)
                .load("/web/index.html")
                .bind("hello", params -> "你好, " + params.get("name")
                        + "! — 来自 Java(pid " + ProcessHandle.current().pid() + ", "
                        + System.getProperty("java.version") + ")")
                .bind("bump", params -> COUNT.incrementAndGet())
                .bind("slowTask", WindowsDemo::slowTask, Duration.ofSeconds(60))
                .bindUi("windowInfo", params -> Map.of(
                        "window", host.windowRectangle(),
                        "onUiThread", host.isUiThread()))
                .onReady(ready -> ready.postToJs(Map.of(
                        "message", "Java 已就绪，桥是通的",
                        "pid", ProcessHandle.current().pid())));
        app.run();
        System.out.println("[demo] window closed, demo over");
    }

    /** Long work on a virtual thread: pushes progress, and stops when the page cancels it. */
    private static Object slowTask(Map<String, Object> params) throws InterruptedException {
        int steps = ((Number) params.getOrDefault("steps", 8)).intValue();
        try {
            for (int step = 1; step <= steps; step++) {
                Thread.sleep(700);
                app.postToJs(Map.of("task", "progress", "step", step, "of", steps));
            }
            return steps + " 步全部完成";
        } catch (InterruptedException interrupted) {
            return "在第 " + COUNT.get() + " 次计数附近被取消（Java 侧真的被中断了）";
        }
    }
}
