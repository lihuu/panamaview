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
        if (menus == null) {
            throw new IllegalArgumentException("A bar cannot be made with a null list of menus");
        }
        return new MenuBar(Arrays.asList(menus));
    }

    public static Menu menu(String title, Item... items) {
        if (items == null) {
            throw new IllegalArgumentException("A menu cannot be made with a null list of items");
        }
        return new Menu(title, Arrays.asList(items));
    }

    /**
     * A binding item: it names a binding, and it has a title.
     *
     * <p>A titleless item is a separator, and a separator is a kind of its own — so an item
     * that is not one has to say what it is called. The two platforms would otherwise handle
     * the same mistake differently: one would fail on a null title, the other would draw an
     * empty row.
     */
    public static Item item(String binding, String title) {
        if (binding == null) {
            throw new IllegalArgumentException("A binding item must have a binding: the title \""
                    + title + "\"");
        }
        if (title == null) {
            throw new IllegalArgumentException("A binding item must have a title: the binding \""
                    + binding + "\"");
        }
        return new Item.BindingItem(binding, title);
    }

    /**
     * A platform item: it names a platform operation, and it has a title.
     *
     * <p>Same rule as a binding item: a titleless item is a separator, and a separator is
     * made by {@link #separator()}.
     */
    public static Item platformItem(Platform action, String title) {
        if (action == null) {
            throw new IllegalArgumentException("A platform item must have an operation: the title \""
                    + title + "\"");
        }
        if (title == null) {
            throw new IllegalArgumentException("A platform item must have a title: the operation "
                    + action);
        }
        return new Item.PlatformItem(action, title);
    }

    /**
     * A separator: the one item with no title, and the one that is not either kind of item
     * that does something.
     *
     * <p>Its null title is its nature, not a hole: the platform draws a separator and carries
     * no title, no action and no key equivalent for it.
     */
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
