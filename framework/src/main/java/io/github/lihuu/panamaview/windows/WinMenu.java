package io.github.lihuu.panamaview.windows;

import io.github.lihuu.panamaview.Item;
import io.github.lihuu.panamaview.Menu;
import io.github.lihuu.panamaview.MenuBar;
import io.github.lihuu.panamaview.Menus;
import io.github.lihuu.panamaview.Platform;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Windows menu: what the model becomes, and the native menu that carries it.
 *
 * <p>Win32's menu is an {@code HMENU} rather than an {@code NSMenu}, a command id rather than
 * a tag, and a {@code WM_COMMAND} message rather than an Objective-C action. The model says the
 * same thing on both sides — a bar of menus, each with items — and this is where the two
 * vocabularies meet.
 *
 * <p>The menu is built in two halves, and the split is what makes the decisions checkable.
 * {@link #plan} is pure: it turns a bar into the flags, the command ids and the texts that
 * the native menu will carry, and it touches no Windows at all. {@link #build} walks that plan
 * and makes the calls. The decisions — which flags an item gets, which command id it carries,
 * what its text says, which {@code wParam} is a menu command — are the parts a typo breaks
 * silently, and they are the parts a build can check: the native half cannot be touched
 * outside Windows at all, because {@code Win32}'s static initializer binds {@code user32.dll}.
 *
 * <p>So the class is package-private, like the rest of this package except {@code WindowsHost}:
 * an application cannot reach the plan, and the platform seam stays the whole surface. The
 * plan's records are public inside it, which is what lets this package's own test read them —
 * the same shape the macOS package has, where {@code Cocoa} is package-private and its methods
 * are not.
 *
 * <p>The flags live here rather than in {@code Win32}, whose static initializer reaches for
 * {@code user32.dll}: a constant in the class that binds the library is a subtlety nobody
 * should have to hold in their head.
 */
final class WinMenu {

    private WinMenu() {
    }

    // ------------------------------------------------------------- the menu flags

    /** MF_STRING — the item's text is a string. The absence of a flag, not a bit to set. */
    public static final int MF_STRING = 0x00000000;
    /** MF_DISABLED — the item is visible and cannot be chosen. */
    public static final int MF_DISABLED = 0x00000002;
    /** MF_POPUP — {@code uIDNewItem} is an {@code HMENU} rather than a command id. */
    public static final int MF_POPUP = 0x00000010;
    /** MF_SEPARATOR — the item's text and its id are both ignored. */
    public static final int MF_SEPARATOR = 0x00000800;

    /** MF_BYPOSITION — {@code GetMenuStringW} reads the title by position rather than by id. */
    private static final int MF_BYPOSITION = 0x00000400;

    /**
     * The command id a popup reports, which is not a command id at all.
     *
     * <p>Measured against the real Win32 on a Windows 11 box: a top-level menu is an item
     * with {@code MF_POPUP}, and the system reports {@code 0xFFFFFFFF} for it — {@code -1} read
     * as a signed int. Masking that to the low word gives 65535, which is a number the
     * framework could look up by mistake, so it is named here instead of being written out
     * where it is used.
     */
    private static final int POPUP_ID = -1;

    /** No command: the {@code wParam} did not carry an id the framework may act on. */
    public static final int NO_COMMAND = -1;

    /** The most characters a menu title may hold, and the buffer the read-back uses. */
    private static final int MAX_TITLE_CHARS = 512;

    // ---------------------------------------------------------------- the plan

    /**
     * One item, as the native menu will hold it.
     *
     * @param flags      the {@code MF_*} flags the item is appended with
     * @param commandId  the id the item carries: the framework's own id, or 0 for a separator,
     *                   whose id the system ignores
     * @param text       the item's title, exactly as the model or the platform composed it
     * @param operation  the platform operation, or null when the item is a binding item and
     *                   the portable layer's business
     */
    public record ItemPlan(int flags, int commandId, String text, Platform operation) {

        /** Whether this item is a separator, which carries neither an id nor a text. */
        public boolean isSeparator() {
            return (flags & MF_SEPARATOR) != 0;
        }

        /** Whether the item is visible and cannot be chosen. */
        public boolean isDisabled() {
            return (flags & MF_DISABLED) != 0;
        }
    }

    /**
     * One top-level menu: the title the bar shows, and the items it holds.
     *
     * @param title the title the user sees — the application's name for the application menu
     * @param items the items, in the order the bar will show them
     */
    public record MenuPlan(String title, List<ItemPlan> items) {
    }

    /**
     * The whole bar: what to build, and what each command id means.
     *
     * <p>The id table is here because the host cannot ask the portable layer for it:
     * {@code MenuBar.bindings()} is package-private in the root package, and this package is
     * not the root package. The host needs to know which command ids it performs itself and
     * which are the portable layer's, and this is where that is said.
     *
     * @param menus the top-level menus, in order
     * @param itemsByCommandId the items by the command id they carry
     * @param unsupportedOperations the platform operations the framework has no Windows route
     *                             for, and whose items are therefore disabled
     */
    public record Plan(List<MenuPlan> menus, Map<Integer, ItemPlan> itemsByCommandId,
                       List<Platform> unsupportedOperations) {

        /** The item a command id names, or null when the id is not the menu's. */
        public ItemPlan itemFor(int id) {
            return itemsByCommandId.get(id);
        }
    }

    /**
     * Turns a bar into what the native menu will hold.
     *
     * <p>Pure, and reachable from anywhere: the plan is where the Windows-specific decisions
     * live, and they are the parts a typo breaks silently — a wrong flag is an item that
     * cannot be chosen, a wrong id is a click that runs the wrong binding.
     *
     * @param bar     the model's bar
     * @param appName the application's name, which the model has no way to know
     */
    public static Plan plan(MenuBar bar, String appName) {
        List<MenuPlan> menus = new ArrayList<>();
        Map<Integer, ItemPlan> items = new LinkedHashMap<>();
        Set<Platform> unsupported = new TreeSet<>();

        for (Menu menu : bar.menus()) {
            // The application's own menu is the one the model could not name: the model has no
            // application name, so it leaves a placeholder there. Win32 has no AppKit-style
            // convention of showing the application's name by itself, so the popup's text is
            // the text, and the platform is the only side that knows the name.
            boolean appMenu = isAppMenu(menu);
            List<ItemPlan> entries = new ArrayList<>();
            for (Item item : menu.items()) {
                ItemPlan entry = planItem(menu, item, appName);
                entries.add(entry);
                items.put(entry.commandId(), entry);
                if (entry.isDisabled()) {
                    unsupported.add(entry.operation());
                }
            }
            menus.add(new MenuPlan(appMenu ? appName : menu.title(), List.copyOf(entries)));
        }
        return new Plan(List.copyOf(menus), Map.copyOf(items), List.copyOf(unsupported));
    }

    /**
     * The command id a {@code WM_COMMAND}'s {@code wParam} carries, or {@link #NO_COMMAND}.
     *
     * <p>The guard is on the whole {@code wParam}, and both halves of it matter. The high word
     * says where the message came from, and only 0 means a menu: an accelerator is 1, and a
     * control notification is whatever the control defines — and the WebView2 child is full of
     * controls, one of which may carry the same identifier as a menu item. The low word is
     * the id, except that 0 is what a separator reports and {@code 0xFFFF} is what a popup
     * reports, and neither is an id: masking a popup's {@code 0xFFFFFFFF} gives 65535, which
     * is a number the framework would look up by mistake.
     */
    public static int commandId(long wparam) {
        if ((wparam & 0xFFFF0000L) != 0L) {
            return NO_COMMAND;                   // an accelerator, or a control notification
        }
        int id = (int) (wparam & 0xFFFFL);
        if (id == 0 || id == 0xFFFF) {
            return NO_COMMAND;                   // a separator, or a popup
        }
        return id;
    }

    // ---------------------------------------------------------------- the build

    /**
     * Builds the native menu, and reads it back to check it against the plan.
     *
     * <p>Null when the plan holds no menu: Windows installs nothing of its own, so an
     * application that declares no bar gets no menu. That is the whole of the difference from
     * macOS, which installs the framework's Quit and Edit bar when the application declares
     * none.
     *
     * @throws IllegalStateException when the built menu does not match the plan. A menu whose
     *                               ids are wrong dispatches the wrong binding, and it does so
     *                               silently: the item is there, the click works, and the
     *                               wrong handler runs.
     */
    static MemorySegment build(Plan plan) {
        if (plan.menus().isEmpty()) {
            return null;
        }
        if (!plan.unsupportedOperations().isEmpty()) {
            // Said once, and here: a silent difference between the platforms is the kind of
            // thing that costs an afternoon.
            System.err.println("[panamaview] these menu operations have no Windows route, so "
                    + "their items are disabled: " + plan.unsupportedOperations());
        }

        try (Arena scope = Arena.ofConfined()) {
            MemorySegment bar = Win32.createMenu();
            try {
                List<MemorySegment> submenus = new ArrayList<>();
                for (MenuPlan menu : plan.menus()) {
                    MemorySegment submenu = Win32.createMenu();
                    for (ItemPlan item : menu.items()) {
                        // A separator's text and id are both ignored, so neither is passed.
                        Win32.appendMenu(scope, submenu, item.flags(), item.commandId(),
                                item.isSeparator() ? null : item.text());
                    }
                    // A top-level menu joins the bar as an item with MF_POPUP, whose uIDNewItem
                    // is the submenu's handle rather than a command id.
                    Win32.appendMenu(scope, bar, MF_POPUP, submenu.address(), menu.title());
                    submenus.add(submenu);
                }
                verify(bar, plan, submenus, scope);
            } catch (Throwable t) {
                // The menu was never given to a window, so it is the framework's to free. A
                // menu that did reach a window is a different matter, and is left to the
                // process — see the host.
                Win32.destroyMenu(bar);
                throw t;
            }
            return bar;
        }
    }

    /**
     * Reads the built menu back and compares it with the plan.
     *
     * <p>Reading the menu back is the only thing that can catch a menu that looks right and
     * is wrong — the plan says "item 3 has command id 7" and the call puts 7 in the text slot,
     * and the menu looks perfect on screen. The titles are compared too, because a menu that
     * carries a mnemonic marker hands it back verbatim: measured against the real Win32, a
     * title of "&File" comes back with its "&", and a title of 导出 comes back exactly.
     */
    private static void verify(MemorySegment bar, Plan plan, List<MemorySegment> submenus, Arena scope) {
        int menus = Win32.getMenuItemCount(bar);
        if (menus != plan.menus().size()) {
            throw new IllegalStateException("the built menu holds " + menus + " menu(s) and the "
                    + "plan asked for " + plan.menus().size());
        }
        for (int position = 0; position < menus; position++) {
            MenuPlan menu = plan.menus().get(position);
            int id = Win32.getMenuItemId(bar, position);
            if (id != POPUP_ID) {
                throw new IllegalStateException("the bar's item at position " + position + " ('"
                        + menu.title() + "') should be a popup and carries command id " + id);
            }
            MemorySegment submenu = Win32.getSubMenu(bar, position);
            if (submenu.address() != submenus.get(position).address()) {
                throw new IllegalStateException("the bar's item at position " + position + " ('"
                        + menu.title() + "') points at a menu the framework did not build");
            }
            String title = Win32.getMenuString(scope, bar, position, MAX_TITLE_CHARS, MF_BYPOSITION);
            if (!title.equals(menu.title())) {
                throw new IllegalStateException("the bar's item at position " + position
                        + " is titled '" + title + "' and the plan asked for '" + menu.title() + "'");
            }
            verifyItems(submenu, menu, scope);
        }
    }

    private static void verifyItems(MemorySegment submenu, MenuPlan menu, Arena scope) {
        int count = Win32.getMenuItemCount(submenu);
        if (count != menu.items().size()) {
            throw new IllegalStateException("the menu '" + menu.title() + "' holds " + count
                    + " item(s) and the plan asked for " + menu.items().size());
        }
        for (int position = 0; position < count; position++) {
            ItemPlan item = menu.items().get(position);
            int id = Win32.getMenuItemId(submenu, position);
            if (id != item.commandId()) {
                // Both ids are named: the person reading this is looking at a menu and not at
                // a stack trace.
                throw new IllegalStateException("the menu '" + menu.title() + "', item " + position
                        + " ('" + item.text() + "'), carries command id " + id + " and the plan asked for "
                        + item.commandId());
            }
            String title = Win32.getMenuString(scope, submenu, position, MAX_TITLE_CHARS, MF_BYPOSITION);
            if (!title.equals(item.text())) {
                throw new IllegalStateException("the menu '" + menu.title() + "', item " + position
                        + ", is titled '" + title + "' and the plan asked for '" + item.text() + "'");
            }
        }
    }

    // ------------------------------------------------------------- the decisions

    /**
     * The title an item shows.
     *
     * <p>The application menu's Quit item carries the application's name, as it always did: the
     * model has no application name, and the platform is the only side that knows it. Every
     * other item keeps the title the model gave it, so an application's own menu says what it
     * says — a {@code platformItem(Platform.QUIT, "退出")} of an application's own menu is
     * still 退出. This is the same composition the macOS side makes, and the two must agree.
     */
    static String itemTitle(Menu menu, Item item, String appName) {
        if (isAppMenu(menu) && item.platformAction() == Platform.QUIT) {
            return "Quit " + appName;
        }
        // A separator has no title, and the null must not leave this method.
        String title = item.title();
        return title == null ? "" : title;
    }

    /** Whether this is the application's own menu — the one the model could not name. */
    private static boolean isAppMenu(Menu menu) {
        return Menus.APP_MENU_TITLE.equals(menu.title());
    }

    /**
     * Whether the framework has a Windows route for a platform operation.
     *
     * <p>Quit does: {@code WM_CLOSE} on the window. The editing commands do not, and inventing
     * one would take the key away from the page — measured on a Windows 11 box, the WebView2
     * control performs the clipboard operations by itself, and that call is the code path
     * Chromium's own Ctrl+C runs. So the browser already does the editing commands, and the
     * framework's menu would be a second path to a capability the page has.
     */
    static boolean hasWindowsRoute(Platform operation) {
        return switch (operation) {
            case QUIT -> true;
            case UNDO, REDO, CUT, COPY, PASTE, SELECT_ALL -> false;
        };
    }

    /** One item, as the plan will hold it. */
    private static ItemPlan planItem(Menu menu, Item item, String appName) {
        return switch (item) {
            case Item.SeparatorItem ignored -> new ItemPlan(MF_SEPARATOR, 0, "", null);
            case Item.BindingItem binding -> new ItemPlan(MF_STRING, binding.id(),
                    title(binding.title()), null);
            case Item.PlatformItem platform -> {
                int flags = MF_STRING;
                if (!hasWindowsRoute(platform.platformAction())) {
                    // Visible, and out of reach: an item that looks live and does nothing is
                    // worse than one that says it cannot be had.
                    flags |= MF_DISABLED;
                }
                yield new ItemPlan(flags, platform.id(),
                        itemTitle(menu, item, appName), platform.platformAction());
            }
        };
    }

    private static String title(String title) {
        return title == null ? "" : title;
    }
}
