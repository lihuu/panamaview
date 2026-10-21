package io.github.lihuu.panamaview;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live reload must work on a host whose UI thread does not exist yet.
 *
 * <p>This is the Windows case, and it is a bug that reading the code would not have found. The
 * portable layer registered its file watcher only when {@code host.isUiThread()} was true, on the
 * theory that reloading a window is a UI-thread operation. It is not — reloading is a
 * <em>request</em>, and the request is delivered later, on whichever thread the platform calls
 * the UI thread. The question that was actually being asked was "is the <em>caller</em> on the
 * UI thread right now", and on Windows the answer is no for the whole of the window's creation:
 * the event loop runs on a thread the host creates inside {@code start()}, and the watcher was
 * registered before that.
 *
 * <p>So on Windows the watcher was never registered, live reload silently did nothing, and the
 * template README said it worked. No error, no message — a feature that was simply absent.
 *
 * <p>What this test pins down is the <em>shape</em> of the failure, not the platform: a host
 * that reports it is not on the UI thread — exactly as Windows does before its event loop is up —
 * must still get a working watcher. That way the next platform to arrive inherits the fix
 * instead of the bug.
 */
class LiveReloadTest {

    /**
     * A host that behaves like Windows before its event loop exists: {@code isUiThread()} is
     * false, and work submitted for the UI thread is queued rather than run immediately.
     *
     * <p>The queue is drained by the host, not by the caller, so a test that only checked "a
     * watcher was registered" would still pass if the reload never reached the page. This one
     * asserts on the effect.
     */
    private static final class LateUiHost implements UiHost {

        private final LinkedBlockingQueue<Runnable> uiQueue = new LinkedBlockingQueue<>();
        private final AtomicInteger reloads = new AtomicInteger();

        @Override
        public void start(Callbacks callbacks, WindowSpec spec) {
            throw new UnsupportedOperationException("this test never opens a window");
        }

        @Override
        public void evaluate(String script) {
            if (script.contains("location.reload")) {
                reloads.incrementAndGet();
            }
        }

        @Override
        public void onUiThread(Runnable action) {
            uiQueue.add(action);
        }

        /** What a real host does on its UI thread: run what was submitted. */
        void drain() {
            Runnable action;
            while ((action = uiQueue.poll()) != null) {
                action.run();
            }
        }

        @Override
        public boolean isUiThread() {
            // The whole point: this host does not think the caller is on its UI thread.
            return false;
        }
    }

    @Test
    void reloadsWhenTheHostIsNotOnItsUiThread() throws Exception {
        Path webRoot = Path.of("target", "test-classes", "web");
        Files.createDirectories(webRoot);
        Files.writeString(webRoot.resolve("index.html"), "<h1>before</h1>");

        LateUiHost host = new LateUiHost();

        WebApp app = new WebApp(host)
                .title("live reload test")
                .load("/web/index.html")
                .quiet();

        // run() resolves the page source and registers the watcher before it starts the host,
        // and this fake host has no event loop. Everything under test happened before that.
        try {
            app.run();
        } catch (UnsupportedOperationException expected) {
            // Deliberate: the fake host refuses to open a window.
        }

        // The change has to be seen. The watcher is registered on the source directory, and a
        // modification to it must produce a reload.
        Files.writeString(webRoot.resolve("index.html"), "<h1>after</h1>");

        assertTrue(awaitReload(host),
                "a file change must reload the page, even when the host reports it is not on its "
                        + "UI thread — this is the Windows case, where the event loop does not "
                        + "exist yet at the moment the watcher is registered");

        assertEquals(1, host.reloads.get(), "exactly one reload for one change");
    }

    /** Waits for the watcher to notice the change. Generous, because the debounce is 180 ms. */
    private boolean awaitReload(LateUiHost host) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            host.drain();
            if (host.reloads.get() > 0) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
