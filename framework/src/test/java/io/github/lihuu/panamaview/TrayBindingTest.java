package io.github.lihuu.panamaview;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The tray as the portable layer sees it: handoff, click dispatch, and the lifecycle default.
 *
 * <p>The two things this file is really about are the ones a surface adds to the menu bar's
 * contract. A tray has its own id table, so the same number in both surfaces must reach two
 * different bindings. And a tray changes what closing the last window means — but only when the
 * host can actually show the icon.
 */
class TrayBindingTest {

    /** A host that takes a bar, a tray, and remembers the callback. */
    private static final class RecordingHost implements UiHost, MenuHost, TrayHost {
        UiHost.Callbacks callbacks;
        MenuBar bar;
        TraySpec tray;
        /** The host calls in the order they happened, so the order can be asserted on. */
        final List<String> calls = new ArrayList<>();

        @Override public void start(Callbacks c, WindowSpec spec) { this.callbacks = c; calls.add("start"); }
        @Override public void evaluate(String script) { }
        @Override public void onUiThread(Runnable action) { action.run(); }
        @Override public boolean isUiThread() { return true; }
        @Override public void setMenu(MenuBar bar) { this.bar = bar; calls.add("setMenu"); }
        @Override public void setTray(TraySpec tray) { this.tray = tray; calls.add("setTray"); }
    }

    /** A host that takes neither a menu nor a tray. */
    private static final class PlainHost implements UiHost {
        UiHost.Callbacks callbacks;

        @Override public void start(Callbacks c, WindowSpec spec) { this.callbacks = c; }
        @Override public void evaluate(String script) { }
        @Override public void onUiThread(Runnable action) { action.run(); }
        @Override public boolean isUiThread() { return true; }
    }

    @Test
    void theTrayReachesTheHostBeforeItStarts() throws Exception {
        var host = new RecordingHost();
        var app = new WebApp(host).quiet()
                .tray(new TraySpec("Demo", List.of(Menus.item("show", "Show"))));
        app.menu(Menus.menu(Menus.menu("File", Menus.item("export", "Export"))));
        app.run();

        assertNotNull(host.tray);
        assertEquals(List.of("setMenu", "setTray", "start"), host.calls,
                "both surfaces must reach the host before it starts");
    }

    @Test
    void aTrayClickInvokesTheBindingItNames() throws Exception {
        var host = new RecordingHost();
        var calls = new AtomicInteger();
        var app = new WebApp(host).bind("show", params -> { calls.incrementAndGet(); return "ok"; }).quiet();
        app.tray(new TraySpec("Demo", List.of(Menus.item("show", "Show"))));
        app.run();

        host.callbacks.onTrayMenuItem(1);
        await(() -> calls.get() == 1);
        assertEquals(1, calls.get(), "the binding named by the chosen tray item must run");
    }

    @Test
    void theSameIdInBothSurfacesNamesTwoDifferentBindings() throws Exception {
        var host = new RecordingHost();
        var bar = new AtomicInteger();
        var tray = new AtomicInteger();
        var app = new WebApp(host)
                .bind("export", params -> { bar.incrementAndGet(); return "bar"; })
                .bind("settings", params -> { tray.incrementAndGet(); return "tray"; })
                .quiet();
        app.menu(Menus.menu(Menus.menu("File", Menus.item("export", "Export"))));
        app.tray(new TraySpec("Demo", List.of(Menus.item("settings", "Settings"))));
        app.run();

        // Both surfaces number their first item 1. The callback is what keeps them apart.
        host.callbacks.onMenuItem(1);
        await(() -> bar.get() == 1);
        assertEquals(0, tray.get(), "a bar click must not reach a tray binding");

        host.callbacks.onTrayMenuItem(1);
        await(() -> tray.get() == 1);
        assertEquals(1, tray.get(), "a tray click must reach its own table");
        assertEquals(1, bar.get(), "a tray click must not reach a bar binding");
    }

    @Test
    void aTrayClickOnAnUndeclaredBindingRunsNothingAndDoesNotThrow() throws Exception {
        var host = new RecordingHost();
        var app = new WebApp(host).quiet()
                .tray(new TraySpec("Demo", List.of(Menus.item("show", "Show"))));
        app.run();

        assertDoesNotThrow(() -> host.callbacks.onTrayMenuItem(99));
    }

    @Test
    void aHostThatTakesNoTrayStillRuns() {
        var host = new PlainHost();
        var app = new WebApp(host).quiet()
                .tray(new TraySpec("Demo", List.of(Menus.item("show", "Show"))));

        assertDoesNotThrow(app::run);
        assertNotNull(host.callbacks);

        // The host could not show the icon, so the application must not become a background
        // process nobody can reach: the quit-on-close default stays what it was.
        assertTrue(host.callbacks.shouldQuitWhenLastWindowCloses(),
                "a tray the host could not take must not keep the process alive");
    }

    @Test
    void aNullTrayMeansNoTray() throws Exception {
        var host = new RecordingHost();
        var app = new WebApp(host).quiet();

        app.tray(null);
        assertDoesNotThrow(app::run);
        assertNull(host.tray, "no spec means the host is given no tray");
        assertTrue(host.callbacks.shouldQuitWhenLastWindowCloses());
    }

    @Test
    void anAcceptedTrayKeepsTheProcessAliveByDefault() throws Exception {
        var host = new RecordingHost();
        var app = new WebApp(host).quiet()
                .tray(new TraySpec("Demo", List.of(Menus.item("show", "Show"))));
        app.run();

        assertFalse(host.callbacks.shouldQuitWhenLastWindowCloses(),
                "closing the window is how a tray application is put away, not how it ends");
    }

    @Test
    void anExplicitChoiceWinsOverTheTrayDefault() throws Exception {
        var withTray = new RecordingHost();
        var quitting = new WebApp(withTray).quiet()
                .tray(new TraySpec("Demo", List.of(Menus.item("show", "Show"))))
                .quitWhenLastWindowCloses(true);
        quitting.run();
        assertTrue(withTray.callbacks.shouldQuitWhenLastWindowCloses());

        var withoutTray = new RecordingHost();
        var staying = new WebApp(withoutTray).quiet().quitWhenLastWindowCloses(false);
        staying.run();
        assertFalse(withoutTray.callbacks.shouldQuitWhenLastWindowCloses(),
                "an application may ask to outlive its window without a tray");
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("condition not met within 10 seconds");
    }
}
