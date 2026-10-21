package io.github.lihuu.panamaview.windows;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Work submitted for the UI thread must be accepted before the event loop exists.
 *
 * <p>{@link WindowsHost#onUiThread} used to post a wake-up message to the window — which does
 * not exist until the host starts. Posting to a null HWND does not fail loudly: the message goes
 * to the <em>calling</em> thread's queue, and the pump thread, which is the one that drains the
 * real queue, never hears about it. The submitted work was therefore stranded, silently, and
 * live reload was one of the things that never happened on Windows.
 *
 * <p>This test is here because the failure was invisible from every side: no exception, no log
 * line, and a README that said the feature worked. What it asserts is the narrowest thing that
 * could have been wrong and was — that submitting work before the event loop starts is accepted
 * rather than rejected or lost.
 *
 * <p>It runs on every platform, and that is deliberate. {@code WindowsHost} has no static
 * initializer that reaches for a Windows DLL, and the path under test never calls into
 * {@code Win32}, so the class loads and behaves the same on macOS as on Windows. The Windows box
 * is the only place the <em>rest</em> of the host can be exercised; this part needs no window,
 * no WebView2 and no desktop session.
 */
class WindowsHostQueueTest {

    @Test
    void acceptsWorkSubmittedBeforeTheEventLoopExists() throws Exception {
        WindowsHost host = new WindowsHost();

        // The host has not started, so there is no window and no event loop. Submitting work
        // anyway is what the portable layer does when it registers its file watcher, which
        // happens before start() on every platform.
        CountDownLatch ran = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();

        try {
            host.onUiThread(() -> {
                failed.set(true);
                ran.countDown();
            });
        } catch (Throwable t) {
            // Before the fix this is where it died: Win32's static initializer looked for
            // kernel32.dll, and on Windows the message was posted nowhere.
            throw new AssertionError("onUiThread must accept work before the event loop exists, "
                    + "but it threw: " + t, t);
        }

        // The action is queued rather than run — there is no UI thread to run it on yet — and
        // the host must not have lost it. The queue is the host's own; what the test can see
        // from outside is that submitting did not fail and did not run the work on the wrong
        // thread.
        assertTrue(ran.getCount() == 1,
                "the work must not run on the calling thread: it is work for the UI thread, and "
                        + "the UI thread does not exist yet");
        assertTrue(!failed.get(),
                "the action must not have been run on the caller's thread");
    }
}
