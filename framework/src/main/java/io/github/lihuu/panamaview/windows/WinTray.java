package io.github.lihuu.panamaview.windows;

import io.github.lihuu.panamaview.Item;
import io.github.lihuu.panamaview.TraySpec;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The Windows tray: a {@code Shell_NotifyIconW} entry at the right end of the taskbar, and the
 * hidden window whose messages it reports to.
 *
 * <p>This is the notification area, not a second spelling of the window menu. The icon lives
 * in the taskbar's right end, it is there for an application with no window at all, and its
 * clicks do not arrive as {@code WM_COMMAND} — the icon reports mouse events through one
 * callback message to the window the data structure names, and the menu behind it is a popup
 * this class builds, shows and destroys around one call. The command id the popup's
 * {@code TPM_RETURNCMD} returns is the whole of the click: the framework's own id, arriving
 * with no round trip.
 *
 * <p>The {@code NOTIFYICONDATAW} the icon is added with is the part a typo breaks silently —
 * a wrong offset puts the tooltip text over the icon handle, and the icon appears with a
 * garbage tip, or not at all, with nothing throwing. So the layout's offsets are asserted
 * against the values measured on a real Windows 11 box before anything is added with them,
 * the way {@code WinMenu.verify} reads back what a built menu carries.
 */
final class WinTray {

    // ----------------------------------------------------------------- the constants

    /** NIM_ADD — add the icon. The same call with NIM_MODIFY and NIM_DELETE changes and removes it. */
    static final int NIM_ADD = 0x00000000;
    static final int NIM_MODIFY = 0x00000001;
    static final int NIM_DELETE = 0x00000002;

    /** NIF_MESSAGE — uCallbackMessage is valid: the icon has a window to report to. */
    private static final int NIF_MESSAGE = 0x00000001;
    /** NIF_ICON — hIcon is valid. */
    private static final int NIF_ICON = 0x00000002;
    /** NIF_TIP — szTip is valid. */
    private static final int NIF_TIP = 0x00000004;

    /** TPM_RIGHTBUTTON — either mouse button chooses from the popup. */
    private static final int TPM_RIGHTBUTTON = 0x0002;
    /** TPM_RETURNCMD — the choice comes back as the return value, not a WM_COMMAND. */
    private static final int TPM_RETURNCMD = 0x0100;

    /** The icon id, when more than one tray icon could exist. The framework has one. */
    private static final int ICON_ID = 1;

    // ------------------------------------------------------------------ the layout

    /**
     * {@code NOTIFYICONDATAW} — 976 bytes on x64, measured against the real struct.
     *
     * <p>The two offsets the design names are asserted, not trusted: {@code szTip} at 40 and
     * {@code szInfoTitle} at 820. Everything between them follows from the same arithmetic —
     * a fixed-width struct of handles, UINTs and two UTF-16 arrays — and an offset that is
     * wrong anywhere is wrong visibly once asserted and silently everywhere if not.
     */
    private static final MemoryLayout NOTIFY_ICON_DATA = MemoryLayout.structLayout(
            JAVA_INT.withName("cbSize"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("hWnd"),
            JAVA_INT.withName("uID"),
            JAVA_INT.withName("uFlags"),
            JAVA_INT.withName("uCallbackMessage"),
            JAVA_INT.withName("uVersion"),
            ADDRESS.withName("hIcon"),
            MemoryLayout.sequenceLayout(128, JAVA_SHORT).withName("szTip"),
            JAVA_INT.withName("dwState"),
            JAVA_INT.withName("dwStateMask"),
            MemoryLayout.sequenceLayout(256, JAVA_SHORT).withName("szInfo"),
            JAVA_INT.withName("uTimeoutOrVersion"),
            MemoryLayout.sequenceLayout(64, JAVA_SHORT).withName("szInfoTitle"),
            JAVA_INT.withName("dwInfoFlags"),
            MemoryLayout.sequenceLayout(4, JAVA_INT).withName("guidItem"),
            ADDRESS.withName("hBalloonIcon"));

    private static final VarHandle CB_SIZE = varHandle("cbSize", JAVA_INT);
    private static final VarHandle HWND = varHandle("hWnd", ADDRESS);
    private static final VarHandle UID = varHandle("uID", JAVA_INT);
    private static final VarHandle UFLAGS = varHandle("uFlags", JAVA_INT);
    private static final VarHandle UCALLBACK_MESSAGE = varHandle("uCallbackMessage", JAVA_INT);
    private static final VarHandle HICON = varHandle("hIcon", ADDRESS);
    private static final long SZ_TIP = NOTIFY_ICON_DATA.byteOffset(MemoryLayout.PathElement.groupElement("szTip"));
    private static final long SZ_TIP_BYTES = 128L * 2;

    /** The tooltip capacity: 128 UTF-16 code units, the last of which is the terminator. */
    private static final int MAX_TIP_CHARS = 127;

    /** The offsets the struct is built on, checked once before anything is added with them. */
    private static void verifyLayout() {
        List<String> wrong = new ArrayList<>();
        expect(wrong, "hWnd", NOTIFY_ICON_DATA.byteOffset(MemoryLayout.PathElement.groupElement("hWnd")), 8);
        expect(wrong, "uFlags", NOTIFY_ICON_DATA.byteOffset(MemoryLayout.PathElement.groupElement("uFlags")), 20);
        expect(wrong, "hIcon", NOTIFY_ICON_DATA.byteOffset(MemoryLayout.PathElement.groupElement("hIcon")), 32);
        expect(wrong, "szTip", SZ_TIP, 40);
        expect(wrong, "szInfoTitle",
                NOTIFY_ICON_DATA.byteOffset(MemoryLayout.PathElement.groupElement("szInfoTitle")), 820);
        if (NOTIFY_ICON_DATA.byteSize() != 976) {
            wrong.add("the struct is " + NOTIFY_ICON_DATA.byteSize() + " bytes, and x64 says 976");
        }
        if (!wrong.isEmpty()) {
            throw new IllegalStateException("NOTIFYICONDATAW does not match the x64 layout the "
                    + "framework measured: " + String.join("; ", wrong));
        }
    }

    private static void expect(List<String> wrong, String field, long actual, long wanted) {
        if (actual != wanted) {
            wrong.add(field + " is at " + actual + " and the x64 struct says " + wanted);
        }
    }

    private static VarHandle varHandle(String name, ValueLayout ignored) {
        return NOTIFY_ICON_DATA.varHandle(MemoryLayout.PathElement.groupElement(name));
    }

    // ------------------------------------------------------------------- the state

    private final TraySpec spec;
    /** The popup's items, planned once: the ids are the framework's and must not move. */
    private final List<WinMenu.ItemPlan> plan;
    private final Map<Integer, WinMenu.ItemPlan> itemsById;
    private final MemorySegment window;
    private final MemorySegment data;
    private final IntConsumer onCommand;
    private final int taskbarCreated;
    private boolean added;

    private WinTray(TraySpec spec, List<WinMenu.ItemPlan> plan, Map<Integer, WinMenu.ItemPlan> itemsById,
                    MemorySegment window, MemorySegment data, int taskbarCreated, IntConsumer onCommand) {
        this.spec = spec;
        this.plan = plan;
        this.itemsById = itemsById;
        this.window = window;
        this.data = data;
        this.taskbarCreated = taskbarCreated;
        this.onCommand = onCommand;
    }

    /**
     * Adds the tray icon.
     *
     * <p>The window the icon reports to is created here too — the icon needs one, and it is a
     * real top-level window that is never shown, because {@code TaskbarCreated} is a broadcast
     * and a message-only window never hears one. The call is on the pump thread, like every
     * other window's birth.
     *
     * @param arena     the pump's arena, which outlives everything the tray keeps
     * @param spec      the declared tray, already numbered by the portable layer
     * @param procedure the tray window's message procedure — the host's, not this class's
     * @param onCommand receives the id of whatever the user chose in the popup
     * @throws IllegalStateException when {@code NIM_ADD} is refused: a tray that was declared
     *                               and never appeared is a failure to report, not a state to
     *                               keep running in
     */
    static WinTray install(Arena arena, TraySpec spec, Win32.Procedure procedure, IntConsumer onCommand) {
        verifyLayout();

        MemorySegment window = Win32.createHiddenWindow(arena, procedure);
        int taskbarCreated = Win32.registerWindowMessage(arena, "TaskbarCreated");

        WinTray tray = new WinTray(spec, planOf(spec), itemsOf(spec), window, arena.allocate(NOTIFY_ICON_DATA),
                taskbarCreated, onCommand);
        tray.fill(arena);
        if (!Win32.shellNotifyIcon(NIM_ADD, tray.data)) {
            throw new IllegalStateException("Shell_NotifyIconW refused the tray icon (NIM_ADD)");
        }
        tray.added = true;
        return tray;
    }

    /** The icon's data structure, filled once and reused for every modify and re-add. */
    private void fill(Arena arena) {
        CB_SIZE.set(data, 0L, (int) NOTIFY_ICON_DATA.byteSize());
        HWND.set(data, 0L, window);
        UID.set(data, 0L, ICON_ID);
        UFLAGS.set(data, 0L, NIF_MESSAGE | NIF_ICON | NIF_TIP);
        UCALLBACK_MESSAGE.set(data, 0L, Win32.WM_APP_TRAY);
        HICON.set(data, 0L, spec.icon() != null ? Win32.iconFromFile(arena, spec.icon()) : Win32.defaultIcon());

        String tooltip = spec.tooltip();
        if (tooltip.length() > MAX_TIP_CHARS) {
            throw new IllegalArgumentException("the tray tooltip is " + tooltip.length()
                    + " characters and the notification area holds " + MAX_TIP_CHARS);
        }
        // The array is fixed-width and the buffer is zero-filled, so the terminator is already
        // there; the text is copied over it, UTF-16 code units in Windows' own order.
        byte[] tip = tooltip.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment.copy(MemorySegment.ofArray(tip), 0L, data, SZ_TIP, tip.length);
    }

    /**
     * The menu behind the icon, shown at the cursor and answered with the chosen id.
     *
     * <p>Both buttons open it, the same way a click on the macOS status item opens its menu:
     * the same declaration should mean the same gesture on both platforms. The popup is
     * transient — built, verified, shown, destroyed around this one call — and the id it
     * returns is the framework's own, dispatched with no round trip. {@code SetForegroundWindow}
     * first is the documented prerequisite: a popup shown without it does not dismiss when
     * focus moves, and the {@code WM_NULL} afterwards is the documented other half of the same
     * remedy.
     */
    void showPopup() {
        MemorySegment menu = Win32.createMenu();
        try (Arena scope = Arena.ofConfined()) {
            for (WinMenu.ItemPlan item : plan) {
                Win32.appendMenu(scope, menu, item.flags(), item.commandId(),
                        item.isSeparator() ? null : item.text());
            }
            verifyPopup(menu, scope);
            Win32.setForegroundWindow(window);
            int chosen = Win32.trackPopupMenu(scope, menu, TPM_RIGHTBUTTON | TPM_RETURNCMD, window);
            if (chosen != 0) {
                onCommand.accept(chosen);
            }
            Win32.postMessage(window, Win32.WM_NULL, 0L, 0L);
        } finally {
            // Built for one showing, and never attached to a window: the framework's to free.
            Win32.destroyMenu(menu);
        }
    }

    /**
     * Reads the popup back and compares it with the plan, before it is ever shown.
     *
     * <p>The same check {@code WinMenu.verify} gives the window menu, because the same failure
     * is available here: a menu that looks right and dispatches wrong does not throw when
     * chosen — it runs the wrong binding, silently. A separator carries the plan's 0 and reads
     * back as the system's 0 — the same value, on this Win32: the documentation says a
     * separator's {@code GetMenuItemID} is {@code -1}, and the window menu's verify has never
     * agreed, because it compares the plan's 0 against the real menu and has never failed. This
     * check believed the documentation once, and every click threw before the menu could show;
     * it believes the measurement now.
     */
    private void verifyPopup(MemorySegment menu, Arena scope) {
        int count = Win32.getMenuItemCount(menu);
        if (count != plan.size()) {
            throw new IllegalStateException("the tray popup holds " + count + " item(s) and the "
                    + "spec asked for " + plan.size());
        }
        for (int position = 0; position < count; position++) {
            WinMenu.ItemPlan item = plan.get(position);
            int id = Win32.getMenuItemId(menu, position);
            if (id != item.commandId()) {
                throw new IllegalStateException("the tray popup, item " + position + " ('"
                        + item.text() + "'), carries command id " + id + " and the spec asked for "
                        + item.commandId());
            }
            String title = Win32.getMenuString(scope, menu, position, 512, 0x00000400);
            if (!title.equals(item.text())) {
                throw new IllegalStateException("the tray popup, item " + position + ", is titled '"
                        + title + "' and the spec asked for '" + item.text() + "'");
            }
        }
    }

    /**
     * Puts the icon back, after Explorer rebuilt the notification area.
     *
     * <p>{@code TaskbarCreated} is broadcast exactly when the taskbar is created anew — a
     * Explorer restart, mostly — and an icon that does not answer it stays gone for the rest of
     * the session, which is the rest of the application's life. The data structure is the one
     * the icon was first added with; nothing about the icon has changed.
     */
    void reAdd() {
        if (Win32.shellNotifyIcon(NIM_ADD, data)) {
            added = true;
        } else {
            System.err.println("[panamaview] the tray icon could not be re-added after the taskbar "
                    + "was rebuilt");
        }
    }

    /** Removes the icon: the one thing a quit must do before its message loop ends. */
    void remove() {
        if (added && !Win32.shellNotifyIcon(NIM_DELETE, data)) {
            System.err.println("[panamaview] the tray icon could not be removed");
        }
        added = false;
    }

    /** Whether the icon is believed to be in the notification area right now. */
    boolean added() {
        return added;
    }

    /** The hidden window the icon reports to, for a test that posts to it directly. */
    MemorySegment window() {
        return window;
    }

    /** The message id {@code TaskbarCreated} registered as on this boot. */
    int taskbarCreated() {
        return taskbarCreated;
    }

    /** The item a popup id names, or null when the id is not the tray's. */
    WinMenu.ItemPlan itemFor(int id) {
        return itemsById.get(id);
    }

    /** How many items the popup plan holds — what a built popup is checked against. */
    int itemCount() {
        return plan.size();
    }

    /** Whether a callback message's {@code lParam} is a click that opens the popup. */
    static boolean opensPopup(long lParam) {
        // WM_LBUTTONUP and WM_RBUTTONUP, the classic callback protocol this icon speaks.
        return lParam == 0x0202 || lParam == 0x0205;
    }

    /** The plan: the same decisions the window menu's planner makes, minus the bar. */
    private static List<WinMenu.ItemPlan> planOf(TraySpec spec) {
        List<WinMenu.ItemPlan> entries = new ArrayList<>();
        for (Item item : spec.items()) {
            entries.add(planItem(item));
        }
        return List.copyOf(entries);
    }

    private static Map<Integer, WinMenu.ItemPlan> itemsOf(TraySpec spec) {
        Map<Integer, WinMenu.ItemPlan> items = new LinkedHashMap<>();
        for (WinMenu.ItemPlan entry : planOf(spec)) {
            items.put(entry.commandId(), entry);
        }
        return Map.copyOf(items);
    }

    /**
     * One item, as the popup will hold it.
     *
     * <p>No application-name composition, because no tray is the application menu: every item
     * keeps the title the model gave it. The route check is the window menu's own, and an
     * operation with no Windows route is built disabled, exactly as there.
     */
    private static WinMenu.ItemPlan planItem(Item item) {
        return switch (item) {
            case Item.SeparatorItem ignored -> new WinMenu.ItemPlan(WinMenu.MF_SEPARATOR, 0, "", null);
            case Item.BindingItem binding -> new WinMenu.ItemPlan(WinMenu.MF_STRING, binding.id(),
                    binding.title() == null ? "" : binding.title(), null);
            case Item.PlatformItem platform -> {
                int flags = WinMenu.MF_STRING;
                if (!WinMenu.hasWindowsRoute(platform.platformAction())) {
                    flags |= WinMenu.MF_DISABLED;
                }
                yield new WinMenu.ItemPlan(flags, platform.id(),
                        platform.title() == null ? "" : platform.title(), platform.platformAction());
            }
        };
    }
}
