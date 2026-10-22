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
     * modifier mask explicitly. AppKit's default for a bare character is ⌘, but the model has
     * no such default to fall back on: the platform sets the mask always, so a declaration with
     * no modifier means the bare key — the plain "c" on its own, not ⌘C — and the built-in menus
     * have to say which they mean.
     *
     * <p>Redo keeps the shipped item's key equivalent: an uppercase "Z", with no mask set, so
     * AppKit's default mask — Command — applied, and the uppercase letter changed only the
     * displayed key, never the mask. The model states Command and no Shift, which is the same
     * mask the shipped item had. Which chord AppKit gives an uppercase key equivalent is its
     * own business, and the model does not change it: the shipped menu passed "Z", and so does
     * the model.
     *
     * <p>Redo's key stays uppercase for one more reason, measured on real AppKit: two items
     * that declare the same key and the same mask do not always both keep their shortcut. Two
     * items with different actions — which is what the platform items are, each with its own
     * — leave the second one with no key equivalent at all, and so with no chord, and that
     * reaches across the whole main menu, not only within one of its menus. Two items that
     * share an action both keep theirs, which is how the framework's binding items, all of
     * them the same action, get away with the same shortcut. Undo already holds ("z", Command),
     * so Redo has to differ from it: the shipped menu's "z" and "Z" are what kept both of them
     * alive.
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
