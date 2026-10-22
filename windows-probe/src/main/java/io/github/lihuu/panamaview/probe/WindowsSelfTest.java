package io.github.lihuu.panamaview.probe;

import io.github.lihuu.panamaview.MenuBar;
import io.github.lihuu.panamaview.Menus;
import io.github.lihuu.panamaview.WebApp;
import io.github.lihuu.panamaview.windows.WindowsHost;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

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
 * <p>The eleventh check is the menu, and it is the one this platform owes the other one. The
 * framework's Windows half — the {@code HMENU}, the command ids, the {@code WM_COMMAND}
 * handling and the read-back that refuses to start with a menu that does not match the model
 * — had never been run before this. The check asserts the <em>effect</em>: the click was
 * posted, the host's guard let it through, the id found its binding, and the binding ran.
 * A click that goes nowhere does not throw, it does nothing, silently, which is the failure
 * mode the whole feature is exposed to — so the counter the binding moves is the only
 * evidence that counts.
 *
 * <p>What the check cannot prove is that the <em>native</em> menu carries that id: the check
 * posts the id itself, so a menu built with the wrong ids would pass it. That is what the
 * framework's own read-back is for, and it is a hard failure at start-up, so the two together
 * cover the ground — the menu is right, and the click works.
 *
 * <p>The exit code is the verdict: 0 when every check passed, 1 otherwise. That is what makes
 * it usable from a script. And a watchdog stands behind that verdict, because the exit code
 * belongs to the application and the application cannot be blamed for the framework's
 * failures: a menu whose titles do not match the model stops the host before the window
 * exists, the page then has nothing to report, and the process would otherwise sit there
 * and exit 0 — loud on stderr, and a pass to anything reading the exit code.
 */
public final class WindowsSelfTest {

    /** How many handlers have actually been interrupted. The evidence that cancellation works. */
    private static final AtomicInteger INTERRUPTS = new AtomicInteger();

    /**
     * How many times the menu's binding item has run.
     *
     * <p>The evidence that the click reached the binding. A counter that has not moved says
     * the click went nowhere, and a click that goes nowhere does not throw.
     */
    private static final AtomicInteger PROBE_COUNT = new AtomicInteger();

    /** How many times the second menu item has run. It is never clicked; it is declared. */
    private static final AtomicInteger PROBE_COUNT_2 = new AtomicInteger();

    /** Whether the page has reported. The watchdog's reason to stay quiet. */
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    /** The window's title, which is also how the capture finds the window. */
    private static final String WINDOW_TITLE = "panamaview self-test";

    /**
     * How long the page has to report before the watchdog calls the run a failure.
     *
     * <p>A minute is generous for a slow machine, and well inside the 180 seconds the runner
     * allows — so the watchdog fires long before the runner's own timeout, and says why.
     */
    private static final long WATCHDOG_MILLIS = 60_000;

    /** The menu title the capture opens, so the picture shows the binding item in it. */
    private static final String MENU_TITLE = "文件";

    private static WebApp app;

    public static void main(String[] args) throws Exception {
        WindowsHost host = new WindowsHost();

        // The menu, declared the way an application would: the application's own menu, and a
        // menu of the application's own with a binding item in it.
        //
        // The titles are not ASCII on purpose. 「文件」and「探测」must not be mojibake in the
        // real menu, and the screenshot is where that shows; the framework's own read-back
        // check, which refuses to start with a menu whose titles do not match the model, is
        // what makes a mojibake title fail the run rather than quietly look wrong.
        MenuBar bar = Menus.menu(
                Menus.appMenu(),
                Menus.menu(MENU_TITLE,
                        Menus.item("menuProbe", "探测"),
                        Menus.separator(),
                        Menus.item("menuProbe2", "另一个")));

        // The id the platform will report, read out of the model rather than guessed. The
        // model numbers its items while it is built, so this is the id the native menu
        // carries and the id a click comes back with.
        int probeId = bar.menus().get(1).items().get(0).id();

        app = new WebApp(host)
                .title(WINDOW_TITLE)
                .size(1000, 700)
                .load("/selftest/selftest.html")
                // The bar, handed over before the platform starts: a platform builds its native
                // menu during start-up, so a bar handed over afterwards would be too late to
                // be in the window.
                .menu(bar)

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

                // 9. The menu's two bindings, which the page's tenth check uses. The first is
                // the item the platform dispatches to when the native menu is chosen, and the
                // counter it moves is the effect the check asserts; the second is the click
                // that chooses it.
                .bind("menuProbe", params -> PROBE_COUNT.incrementAndGet())
                .bind("menuProbe2", params -> PROBE_COUNT_2.incrementAndGet())

                // The click, and the wait for its effect.
                //
                // The click is a message: it is posted to the window and handled on the pump
                // thread, which then dispatches the binding on a virtual thread. So the
                // effect is not there the instant the call returns, and the check has to
                // wait for it rather than read a counter that has not moved yet. The wait
                // lives here, in the binding, and not in the page: the answer then arrives
                // with the result, in one round trip, with no polling in the page and no
                // race in the check.
                //
                // The wait is bounded. Two seconds is generous for a message that is already
                // queued, and a check that can wait forever is not a check.
                .bind("menuClick", params -> {
                    host.clickMenuItem(probeId);
                    for (int waited = 0; waited < 200 && PROBE_COUNT.get() == 0; waited++) {
                        Thread.sleep(10);
                    }
                    return PROBE_COUNT.get();
                })

                // 10. The verdict goes here, so it lands in stdout where a machine can read it.
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
                    // The verdict is in; the watchdog has nothing left to watch for.
                    REPORTED.set(true);
                    // The picture: the menu bar is the one thing the log cannot show, so the
                    // capture happens here, where the window is known to be up and the timing
                    // stops being a race.
                    capture();
                    System.out.println("[selftest] DONE");
                    // The window stays up a moment longer, so a person watching the screen can
                    // see what the run did: the verdict is in this log, and the page on the
                    // screen says the same thing.
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

        // Before the run starts: a failure before the window exists leaves the page with
        // nothing to report, and the process would then sit there and exit 0.
        startWatchdog();

        app.run();
    }

    /**
     * Watches for the page's report, and calls the run a failure when it does not come.
     *
     * <p>This is not new behaviour for the self-test, and it is worth having anyway: any
     * failure before the page loads has the same shape — the page never reports, and the
     * process waits. The framework says what went wrong on stderr ("the Windows host
     * stopped: …"), but the exit code belongs to the application, and the application did
     * not fail: the framework did. So a harness that reads the exit code would call that a
     * pass, and this is what says otherwise.
     *
     * <p>The thread is deliberately not a daemon: when the host fails, {@code app.run()}
     * returns and the JVM would exit 0 on its own, before the watchdog ever got to speak.
     */
    private static void startWatchdog() {
        Thread watchdog = new Thread(() -> {
            long deadline = System.nanoTime() + Duration.ofMillis(WATCHDOG_MILLIS).toNanos();
            while (System.nanoTime() < deadline) {
                if (REPORTED.get()) {
                    return;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (REPORTED.get()) {
                return;
            }
            System.err.println("[selftest] TIMEOUT - the page never reported within "
                    + (WATCHDOG_MILLIS / 1000) + " s. Whatever the framework said about what went"
                    + " wrong, if it said anything, is in the lines above this one.");
            System.exit(1);
        }, "selftest-watchdog");
        watchdog.setDaemon(false);
        watchdog.start();
    }

    /**
     * Takes the picture: the app's own window, with its own menu, on a real desktop.
     *
     * <p>The verdict is in the log; the picture is the evidence that something was actually
     * drawn. It is taken from inside the self-test rather than from outside, because the
     * self-test knows exactly when its window is up and what is on the page, and a script
     * that has to wait for a window to appear is racing it.
     *
     * <p>The menu is opened before the capture, because the binding item lives in a popup and
     * a closed popup is not in a picture. It is opened the way a person opens it: the window
     * is found by its title, the window says where its menu bar is, and the mouse goes
     * there.
     *
     * <p>Nothing in here can fail the run. A picture is worth having; it is not the verdict,
     * and a machine that cannot take one has still run the checks.
     */
    private static void capture() {
        String target = System.getenv().getOrDefault("PANAMAVIEW_SCREENSHOT", "selftest-window.png");
        try {
            openMenu();
            Robot robot = new Robot();
            Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage image = robot.createScreenCapture(screen);
            ImageIO.write(image, "png", new File(target));
            System.out.println("[selftest] the screenshot is at " + target
                    + " (" + image.getWidth() + "x" + image.getHeight() + ")");
        } catch (Throwable t) {
            System.err.println("[selftest] the screenshot could not be taken: " + t);
        }
    }

    /**
     * Opens the menu, so the picture shows the binding item in it.
     *
     * <p>Two clicks at most: the first click on a window that is not in the foreground
     * activates it, and a click that only activates is a click that has not opened the menu
     * yet. Whether the menu opened is asked of Windows rather than guessed: an open menu is
     * a window of its own, and that window is found by its class.
     */
    private static void openMenu() {
        try {
            MemorySegment window = Win32Menu.findWindow(WINDOW_TITLE);
            if (window == null) {
                System.err.println("[selftest] the screenshot: no window titled '" + WINDOW_TITLE + "'");
                return;
            }
            MemorySegment menu = Win32Menu.getMenu(window);
            if (menu == null) {
                System.err.println("[selftest] the screenshot: the window has no menu");
                return;
            }
            int position = Win32Menu.findItem(menu, MENU_TITLE);
            if (position < 0) {
                System.err.println("[selftest] the screenshot: the menu has no '" + MENU_TITLE + "'");
                return;
            }
            Rectangle item = Win32Menu.itemRectangle(window, menu, position);
            System.out.println("[selftest] the screenshot: the menu item '" + MENU_TITLE
                    + "' is at " + item.x + "," + item.y + " " + item.width + "x" + item.height);
            for (int attempt = 0; attempt < 2; attempt++) {
                // The click is in Win32's own coordinates, not in AWT's. They are not the same
                // space: this machine's display runs at 200% scaling, so the window the
                // framework asked for 1000x700 wide is 1000x700 *physical* pixels, while a
                // screen capture is 1920x1080 *logical* pixels of a 3840x2160 desktop. The
                // menu's rectangle comes from Win32 and the click goes to Win32, so the two
                // never have to be reconciled — and a click computed in AWT's space would
                // land half a screen away from the item.
                Win32Menu.clickAt(item.x + item.width / 2, item.y + item.height / 2);
                Thread.sleep(600);
                if (Win32Menu.menuIsOpen()) {
                    return;
                }
            }
            System.err.println("[selftest] the screenshot: the menu did not open");
        } catch (Throwable t) {
            System.err.println("[selftest] the screenshot: the menu could not be opened: " + t);
        }
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

    /**
     * The few Win32 calls the screenshot needs.
     *
     * <p>This is the probe's own Win32 and not the framework's: the framework's is package
     * private on purpose, and a test that reached into the thing it is checking would be
     * checking itself.
     */
    private static final class Win32Menu {

        /** {@code {LONG left; LONG top; LONG right; LONG bottom}} — 16 bytes. */
        private static final MemoryLayout RECT = MemoryLayout.structLayout(
                JAVA_INT.withName("left"), JAVA_INT.withName("top"),
                JAVA_INT.withName("right"), JAVA_INT.withName("bottom"));

        /** {@code MF_BYPOSITION} — the title is read by position rather than by id. */
        private static final int MF_BYPOSITION = 0x00000400;

        /** The window class every Win32 menu popup has. */
        private static final String MENU_WINDOW_CLASS = "#32768";

        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());

        private static final MethodHandle FIND_WINDOW = bind("FindWindowW",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        private static final MethodHandle GET_MENU = bind("GetMenu",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        private static final MethodHandle GET_MENU_ITEM_COUNT = bind("GetMenuItemCount",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle GET_MENU_ITEM_RECT = bind("GetMenuItemRect",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        private static final MethodHandle GET_MENU_STRING = bind("GetMenuStringW",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        private static final MethodHandle SET_CURSOR_POS = bind("SetCursorPos",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
        private static final MethodHandle MOUSE_EVENT = bind("mouse_event",
                FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));

        /** {@code MOUSEEVENTF_LEFTDOWN} and {@code MOUSEEVENTF_LEFTUP}. */
        private static final int MOUSEEVENTF_LEFTDOWN = 0x0002;
        private static final int MOUSEEVENTF_LEFTUP = 0x0004;

        private Win32Menu() {
        }

        private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(USER32.find(name).orElseThrow(
                    () -> new UnsatisfiedLinkError(name)), descriptor);
        }

        /** The window with this title, or null when there is none. */
        static MemorySegment findWindow(String title) {
            try (Arena scope = Arena.ofConfined()) {
                MemorySegment name = scope.allocateFrom(title, StandardCharsets.UTF_16LE);
                MemorySegment found = (MemorySegment) FIND_WINDOW.invoke(MemorySegment.NULL, name);
                return found.equals(MemorySegment.NULL) ? null : found;
            } catch (Throwable t) {
                throw new IllegalStateException("FindWindowW could not be called", t);
            }
        }

        /** The menu the window was created with, or null when it has none. */
        static MemorySegment getMenu(MemorySegment window) {
            try {
                MemorySegment menu = (MemorySegment) GET_MENU.invoke(window);
                return menu.equals(MemorySegment.NULL) ? null : menu;
            } catch (Throwable t) {
                throw new IllegalStateException("GetMenu could not be called", t);
            }
        }

        /** The position of the item with this title, or -1 when the menu has no such item. */
        static int findItem(MemorySegment menu, String title) {
            int count = itemCount(menu);
            for (int position = 0; position < count; position++) {
                if (title.equals(itemTitle(menu, position))) {
                    return position;
                }
            }
            return -1;
        }

        /** How many items the menu holds. */
        static int itemCount(MemorySegment menu) {
            try {
                return (int) GET_MENU_ITEM_COUNT.invoke(menu);
            } catch (Throwable t) {
                throw new IllegalStateException("GetMenuItemCount could not be called", t);
            }
        }

        /** The title of the item at this position, read by position. */
        static String itemTitle(MemorySegment menu, int position) {
            int maxChars = 256;
            try (Arena scope = Arena.ofConfined()) {
                MemorySegment buffer = scope.allocate(maxChars * 2L);
                int written = (int) GET_MENU_STRING.invoke(menu, position, buffer, maxChars, MF_BYPOSITION);
                if (written <= 0) {
                    return "";
                }
                StringBuilder title = new StringBuilder(written);
                for (int i = 0; i < written; i++) {
                    title.append((char) buffer.get(JAVA_SHORT, (long) i * 2));
                }
                return title.toString();
            } catch (Throwable t) {
                throw new IllegalStateException("GetMenuStringW could not be called", t);
            }
        }

        /** Where the item at this position is, on the screen. */
        static Rectangle itemRectangle(MemorySegment window, MemorySegment menu, int position) {
            try (Arena scope = Arena.ofConfined()) {
                MemorySegment rect = scope.allocate(RECT);
                int ok = (int) GET_MENU_ITEM_RECT.invoke(window, menu, position, rect);
                if (ok == 0) {
                    throw new IllegalStateException("GetMenuItemRect said the item has no rectangle");
                }
                int left = rect.get(JAVA_INT, 0);
                int top = rect.get(JAVA_INT, 4);
                int right = rect.get(JAVA_INT, 8);
                int bottom = rect.get(JAVA_INT, 12);
                return new Rectangle(left, top, right - left, bottom - top);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException("GetMenuItemRect could not be called", t);
            }
        }

        /** Whether a menu is open right now: an open menu is a window of its own. */
        static boolean menuIsOpen() {
            try (Arena scope = Arena.ofConfined()) {
                MemorySegment name = scope.allocateFrom(MENU_WINDOW_CLASS, StandardCharsets.UTF_16LE);
                MemorySegment found = (MemorySegment) FIND_WINDOW.invoke(name, MemorySegment.NULL);
                return !found.equals(MemorySegment.NULL);
            } catch (Throwable t) {
                return false;
            }
        }

        /**
         * Moves the mouse and clicks, in Win32's own screen coordinates.
         *
         * <p>{@code mouse_event} rather than {@code SendInput}: the old API takes the same
         * coordinates {@code GetMenuItemRect} gives, and the new one would need an input
         * struct that has to be laid out by hand.
         */
        static void clickAt(int x, int y) {
            try {
                SET_CURSOR_POS.invoke(x, y);
                Thread.sleep(150);
                MOUSE_EVENT.invoke(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0L);
                Thread.sleep(60);
                MOUSE_EVENT.invoke(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0L);
            } catch (Throwable t) {
                throw new IllegalStateException("the mouse could not be moved to " + x + "," + y, t);
            }
        }
    }

    private WindowsSelfTest() {
    }
}
