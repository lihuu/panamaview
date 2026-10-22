package io.github.lihuu.panamaview;

import java.util.Arrays;
import java.util.Collections;

public final class Menus {

    /**
     * The title the application menu carries in the model.
     *
     * <p>A placeholder, because the model has no application name — only the platform has one, and
     * the platform is what puts it there. A menu that still carries this title is the application
     * menu, and the platform knows not to show it.
     */
    public static final String APP_MENU_TITLE = "[Application Name]";

    private Menus() {}

    public static MenuBar menu(Menu... menus) {
        return new MenuBar(Arrays.asList(menus));
    }

    public static Menu menu(String title, Item... items) {
        return new Menu(title, Arrays.asList(items));
    }

    public static Item item(String binding, String title) {
        return new Item.BindingItem(binding, title);
    }

    public static Item platformItem(Platform action, String title) {
        return new Item.PlatformItem(action, title);
    }

    public static Item separator() {
        return new Item.SeparatorItem();
    }

    public static Menu appMenu() {
        return new Menu(APP_MENU_TITLE,
            Collections.singletonList(platformItem(Platform.QUIT, "Quit").shortcut("CmdOrCtrl+q")));
    }

    /**
     * The Edit menu: the platform items that make the web view's text fields work.
     *
     * <p>The key equivalents are the ones the framework has always used, and they are what make
     * ⌘C reach the focused text field — AppKit dispatches keyboard shortcuts through menu items,
     * so an item with no key equivalent is an item the keyboard cannot reach. They are declared
     * with their modifier rather than as a bare character, because the platform now sets the
     * modifier mask explicitly: AppKit's default for a bare character is ⌘, and a mask that
     * replaces that default would turn a bare character into no shortcut at all.
     *
     * <p>Redo is ⌘Z, and it stays that way. The shipped menu passed an uppercase "Z" and set no
     * mask, so AppKit's default mask — Command — applied and the chord was ⌘Z; the uppercase
     * letter changed only the displayed key, never the mask. The model states Command and no
     * Shift, which is the same chord.
     *
     * <p>Redo's key stays uppercase for one more reason, measured on real AppKit: the main menu
     * drops the key equivalent of an item that duplicates an earlier one in the same menu — the
     * same key <em>and</em> the same mask — and such an item is then reachable by no chord at
     * all. Undo already holds ("z", Command), so Redo has to differ from it, and the shipped
     * menu's "z" and "Z" are what kept both of them alive, both bound to ⌘Z.
     */
    public static Menu editMenu() {
        return new Menu("Edit", Arrays.asList(
            platformItem(Platform.UNDO, "Undo").shortcut("CmdOrCtrl+z"),
            platformItem(Platform.REDO, "Redo").shortcut("CmdOrCtrl+Z"),
            separator(),
            platformItem(Platform.CUT, "Cut").shortcut("CmdOrCtrl+x"),
            platformItem(Platform.COPY, "Copy").shortcut("CmdOrCtrl+c"),
            platformItem(Platform.PASTE, "Paste").shortcut("CmdOrCtrl+v"),
            separator(),
            platformItem(Platform.SELECT_ALL, "Select All").shortcut("CmdOrCtrl+a")
        ));
    }
}
