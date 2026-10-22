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
     * <p>Redo states its Shift instead of relying on AppKit's rule that an uppercase key
     * equivalent implies one, and keeps the key lowercase — AppKit matches a key equivalent
     * against the event's shift-independent characters.
     */
    public static Menu editMenu() {
        return new Menu("Edit", Arrays.asList(
            platformItem(Platform.UNDO, "Undo").shortcut("CmdOrCtrl+z"),
            platformItem(Platform.REDO, "Redo").shortcut("CmdOrCtrl+Shift+z"),
            separator(),
            platformItem(Platform.CUT, "Cut").shortcut("CmdOrCtrl+x"),
            platformItem(Platform.COPY, "Copy").shortcut("CmdOrCtrl+c"),
            platformItem(Platform.PASTE, "Paste").shortcut("CmdOrCtrl+v"),
            separator(),
            platformItem(Platform.SELECT_ALL, "Select All").shortcut("CmdOrCtrl+a")
        ));
    }
}
