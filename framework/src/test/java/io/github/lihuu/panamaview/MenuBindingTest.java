package io.github.lihuu.panamaview;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

class MenuBindingTest {

    /** A host that takes a menu and remembers the callback. */
    private static final class RecordingHost implements UiHost, MenuHost {
        UiHost.Callbacks callbacks;
        MenuBar bar;

        @Override public void start(Callbacks c, WindowSpec spec) { this.callbacks = c; }
        @Override public void evaluate(String script) { }
        @Override public void onUiThread(Runnable action) { action.run(); }
        @Override public boolean isUiThread() { return true; }
        @Override public void setMenu(MenuBar bar) { this.bar = bar; }
    }

    /** A host that takes no menu at all. */
    private static final class PlainHost implements UiHost {
        UiHost.Callbacks callbacks;

        @Override public void start(Callbacks c, WindowSpec spec) { this.callbacks = c; }
        @Override public void evaluate(String script) { }
        @Override public void onUiThread(Runnable action) { action.run(); }
        @Override public boolean isUiThread() { return true; }
    }

    @Test
    void aClickInvokesTheBindingItNames() throws Exception {
        var host = new RecordingHost();
        var calls = new AtomicInteger();
        var app = new WebApp(host).bind("export", params -> { calls.incrementAndGet(); return "exported"; }).quiet();
        app.menu(Menus.menu(Menus.menu("文件", Menus.item("export", "导出"))));
        app.run();

        // The bar reached the host.
        assertNotNull(host.bar);

        // Clicking item 1 is a call to the "export" binding.
        host.callbacks.onMenuItem(1);
        await(() -> calls.get() == 1);
        assertEquals(1, calls.get(), "the binding named by the clicked item must run");
    }

    @Test
    void aClickOnAnUndeclaredBindingRunsNothingAndDoesNotThrow() throws Exception {
        var host = new RecordingHost();
        var app = new WebApp(host).quiet();
        app.menu(Menus.menu(Menus.menu("文件", Menus.item("export", "导出"))));
        app.run();

        // The framework reports it; it does not throw, and it does not run anything.
        assertDoesNotThrow(() -> host.callbacks.onMenuItem(1));
    }

    @Test
    void aMenuItemMayNotNameAUiThreadBinding() throws Exception {
        var host = new RecordingHost();
        var calls = new AtomicInteger();
        var app = new WebApp(host).bindUi("export", params -> { calls.incrementAndGet(); return null; }).quiet();
        app.menu(Menus.menu(Menus.menu("文件", Menus.item("export", "导出"))));
        app.run();

        // A UI-thread binding has no deadline and would run inline on the UI thread, which is
        // where a menu click already is. The framework refuses it.
        assertDoesNotThrow(() -> host.callbacks.onMenuItem(1));
        assertEquals(0, calls.get(), "a bindUi binding must not run from a menu item");
    }

    @Test
    void aThrowingBindingDoesNotStopTheNextOne() throws Exception {
        var host = new RecordingHost();
        var calls = new AtomicInteger();
        var app = new WebApp(host)
                .bind("boom", params -> { throw new IllegalStateException("boom"); })
                .bind("after", params -> { calls.incrementAndGet(); return "ok"; })
                .quiet();
        app.menu(Menus.menu(Menus.menu("文件",
                Menus.item("boom", "爆炸"),
                Menus.item("after", "之后"))));
        app.run();

        assertDoesNotThrow(() -> host.callbacks.onMenuItem(1));
        // The first item has settled — it threw — before the second one is clicked, so
        // "stranded" means stranded and not merely "not yet".
        await(() -> app.inFlight() == 0);
        host.callbacks.onMenuItem(2);
        await(() -> calls.get() == 1);
        assertEquals(1, calls.get(), "one bad action must not strand the ones after it");
    }

    @Test
    void aHostThatTakesNoMenuStillRuns() {
        var host = new PlainHost();
        var app = new WebApp(host).bind("greet", params -> "hi").quiet();
        app.menu(Menus.menu(Menus.menu("文件", Menus.item("greet", "你好"))));

        // The application declared a menu and the host cannot take one. The application runs.
        assertDoesNotThrow(app::run);
        assertNotNull(host.callbacks);
    }

    /**
     * Waits for a condition the click caused.
     *
     * <p>A menu binding is dispatched the way a page call is — on a virtual thread, off the UI
     * thread — so the click returns before the handler has run. What the assertions above are
     * about is what the click <em>caused</em>, not about the click being synchronous, so the
     * test waits for the outcome rather than assuming one.
     */
    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for a menu binding");
            }
        }
        fail("the menu binding did not run within 10 s");
    }
}
