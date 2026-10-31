package io.github.lihuu.panamaview.demo;

import io.github.lihuu.panamaview.Item;
import io.github.lihuu.panamaview.Menu;
import io.github.lihuu.panamaview.MenuBar;
import io.github.lihuu.panamaview.Menus;
import io.github.lihuu.panamaview.Platform;
import io.github.lihuu.panamaview.TraySpec;
import io.github.lihuu.panamaview.UiHost;
import io.github.lihuu.panamaview.WebApp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The demo application: portable handlers plus the page's self-test handshake.
 *
 * <p>Split out from the old {@code Main} so the demo application and the platform entry
 * point are separate things. This file is portable — it drives windows, sleeps on virtual
 * threads and reads system properties, none of which is macOS-specific — while
 * {@code Main} owns the macOS-only parts.
 */
public final class App {

    /** Where the page should write its self-portrait, if the caller asked for one. */
    public static final String SNAPSHOT_PROPERTY = "panamaview.snapshot";

    /** The application's name, which the menu model has no way to know. */
    private static final String APP_NAME = "panamaview";

    /** The binding the application's own first menu item names. */
    private static final String PROBE = "menuProbe";

    /** The binding the application's own second menu item names. */
    private static final String OTHER = "menuProbe2";

    /** The binding the tray's binding item names. */
    private static final String TRAY_PROBE = "trayProbe";

    /**
     * How a menu item is chosen without a human choosing it.
     *
     * <p>The activation is a platform call, and this file is portable, so the demo takes it as
     * a port and the composition root supplies the platform's own. Nothing here knows what a
     * menu is on the other side. Both surfaces take it — the bar's items and the tray's items
     * are the same kind of thing, an item of a menu, and they differ in which table their ids
     * name — which is why one interface serves two ports.
     */
    public interface MenuClicker {
        /**
         * Activates the menu item with this id.
         *
         * <p>The id is the framework's own, so the caller reads it out of the declared bar
         * rather than counting items.
         */
        void click(int id);
    }

    private final WebApp web;
    private final MenuClicker clicker;
    private final MenuClicker trayClicker;

    /**
     * The menu bar the demo declares.
     *
     * <p>A declared bar <em>replaces</em> the framework's own, so it has to carry the
     * framework's built-in menus too: on macOS the Edit menu is what puts ⌘C into the web
     * view's text field, and leaving it out brings back issue #19.
     *
     * <p>The application's own menu has a separator in it on purpose — the position of an
     * item in a menu is not the number of the items before it, and a menu with a separator in
     * it is where that shows.
     */
    private final MenuBar bar = Menus.menu(
            Menus.appMenu(),
            Menus.menu("文件",
                    Menus.item(PROBE, "探测"),
                    Menus.separator(),
                    Menus.item(OTHER, "另一个")),
            Menus.editMenu());

    /**
     * The tray the demo declares.
     *
     * <p>One item of each kind — a binding item, a separator, the platform's Quit — because
     * the smallest tray that shows all three is the one worth testing. The tooltip is what a
     * pointer resting on the icon says. The icon itself is left to the framework, the same
     * way the application menu is: the default is the framework's business until an
     * application says otherwise.
     *
     * <p>Declaring it also changes what closing the window means — the process now outlives
     * its window by default, because the icon would outlive it too. That is the lifecycle
     * change this feature makes, and the demo runs with it on purpose.
     */
    private final TraySpec tray = new TraySpec("panamaview demo", java.util.List.of(
            Menus.item(TRAY_PROBE, "托盘探测"),
            Menus.separator(),
            Menus.platformItem(Platform.QUIT, "退出")));

    /**
     * How many times a handler has actually been interrupted.
     *
     * <p>This exists to make cancellation <em>observable</em>. A page that cancels and stops
     * waiting has proved nothing about Java: the promise rejects locally either way. The only
     * honest evidence is that the handler on the other side was really stopped, and a counter
     * that moves is that evidence.
     */
    private final AtomicInteger interrupts = new AtomicInteger();

    /** How many times each of the menu's bindings ran — the effect the menu check asserts. */
    private final AtomicInteger probeClicks = new AtomicInteger();
    private final AtomicInteger otherClicks = new AtomicInteger();

    /** How many times the tray's binding ran — the effect the tray check asserts. */
    private final AtomicInteger trayProbeClicks = new AtomicInteger();

    public App(UiHost host, MenuClicker clicker, MenuClicker trayClicker) {
        this.clicker = clicker;
        this.trayClicker = trayClicker;
        this.web = new WebApp(host)
                .title(APP_NAME)
                .size(1000, 700)
                .load("/web/index.html")
                .menu(bar)
                .tray(tray);
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

        // The two bindings the demo's own menu items name. The count is the effect: a menu
        // item that reaches nobody does not throw, it does nothing, silently, and that is
        // the failure this whole feature is exposed to.
        web.bind(PROBE, params -> {
            announce(PROBE, probeClicks.incrementAndGet());
            return "the first item ran";
        });
        web.bind(OTHER, params -> {
            announce(OTHER, otherClicks.incrementAndGet());
            return "the second item ran";
        });

        // The tray's binding item, counted the same way: a tray item that reaches nobody
        // does not throw either, and the count is the only evidence it did anything.
        web.bind(TRAY_PROBE, params -> "the tray item ran " + trayProbeClicks.incrementAndGet());

        // Chooses an item of the demo's own menu, the way a click would, and waits for the
        // binding behind it to run.
        //
        // The wait belongs here rather than in the page: the activation crosses three hops —
        // the menu item's action, the target's upcall, and the binding on a virtual thread —
        // so the effect is not there the instant the call returns. Waiting here makes the
        // answer arrive with the result, in one round trip, with no polling in the page and
        // no race in the check. Two seconds is generous for an activation that has already
        // happened.
        web.bind("menuClick", params -> {
            String which = String.valueOf(params.getOrDefault("item", PROBE));
            AtomicInteger counter = counterOf(which);
            int before = counter.get();
            clicker.click(idOf(which));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (counter.get() == before && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            return counter.get();
        });

        // What the bar holds, and how many times each of its items has been chosen — so the
        // page can show the menu a person uses, and a person clicking an item in the real
        // menu bar sees the count move.
        web.bind("menuState", params -> menuState());

        // Chooses an item of the demo's own tray, the way a click in the icon's menu would,
        // and waits for the binding behind it to run. Same shape and same reasoning as
        // menuClick, over the tray's own id table: the activation crosses the item's action,
        // the target's upcall and the binding, so the wait belongs here, in one round trip.
        web.bind("trayClick", params -> {
            AtomicInteger counter = counterOf(TRAY_PROBE);
            int before = counter.get();
            trayClicker.click(trayIdOf(TRAY_PROBE));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (counter.get() == before && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            return counter.get();
        });

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

    /**
     * The id of the item that names a binding.
     *
     * <p>The framework numbers the items when the bar is built, and the number is what the
     * platform reports when the item is chosen — so the id is read out of the model rather
     * than counted, which would break the moment a separator came before the item meant.
     */
    private int idOf(String binding) {
        for (Menu menu : bar.menus()) {
            for (Item item : menu.items()) {
                if (binding.equals(item.binding())) {
                    return item.id();
                }
            }
        }
        throw new IllegalStateException("the menu has no item bound to '" + binding + "'");
    }

    /** How many times a menu item's binding has run. */
    private int clicksOf(String binding) {
        return counterOf(binding).get();
    }

    /** The counter of the item that names this binding. */
    private AtomicInteger counterOf(String binding) {
        return switch (binding) {
            case PROBE -> probeClicks;
            case OTHER -> otherClicks;
            case TRAY_PROBE -> trayProbeClicks;
            default -> throw new IllegalArgumentException("no menu item is bound to '" + binding + "'");
        };
    }

    /**
     * The id of the tray item that names a binding.
     *
     * <p>Read out of the tray's own numbering, the way {@link #idOf} reads the bar's: an id
     * is the framework's own, and the tray's table is a different table from the bar's.
     */
    private int trayIdOf(String binding) {
        for (Item item : tray.items()) {
            if (binding.equals(item.binding())) {
                return item.id();
            }
        }
        throw new IllegalStateException("the tray has no item bound to '" + binding + "'");
    }

    /**
     * Tells the page that an item was chosen.
     *
     * <p>The page shows the menu a person uses, and a person clicking an item in the real
     * menu bar has to be able to see that it did something. The message is the only way the
     * page learns of it: a menu click does not go through the page at all.
     */
    private void announce(String binding, int clicks) {
        web.postToJs(Map.of("menuChosen", binding, "clicks", clicks));
    }

    /**
     * The bar, as the page shows it.
     *
     * <p>The model as it was declared, with one substitution: the application menu is titled
     * with the application's name, because the model has no way to know it. The Quit item
     * keeps the model's own title — the platform composes the application's name into it,
     * and that composition is the platform's business.
     */
    private Map<String, Object> menuState() {
        List<Map<String, Object>> menus = new ArrayList<>();
        for (Menu menu : bar.menus()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Item item : menu.items()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("title", item.title() == null ? "" : item.title());
                entry.put("kind", kindOf(item));
                if (item.binding() != null) {
                    entry.put("binding", item.binding());
                    entry.put("clicks", clicksOf(item.binding()));
                }
                items.add(entry);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("title", Menus.APP_MENU_TITLE.equals(menu.title()) ? APP_NAME : menu.title());
            entry.put("items", items);
            menus.add(entry);
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("menus", menus);
        return state;
    }

    /** What kind of item this is: the two kinds differ in more than their decoration. */
    private static String kindOf(Item item) {
        if (item.binding() != null) {
            return "binding";
        }
        return item.platformAction() != null ? "platform" : "separator";
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
