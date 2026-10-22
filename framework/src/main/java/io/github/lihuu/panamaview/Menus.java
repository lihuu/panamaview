package io.github.lihuu.panamaview;

import java.util.Arrays;
import java.util.Collections;

public final class Menus {
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
        return new Menu("[Application Name]",
            Collections.singletonList(platformItem(Platform.QUIT, "Quit")));
    }

    public static Menu editMenu() {
        return new Menu("Edit", Arrays.asList(
            platformItem(Platform.UNDO, "Undo"),
            platformItem(Platform.REDO, "Redo"),
            separator(),
            platformItem(Platform.CUT, "Cut"),
            platformItem(Platform.COPY, "Copy"),
            platformItem(Platform.PASTE, "Paste"),
            separator(),
            platformItem(Platform.SELECT_ALL, "Select All")
        ));
    }
}
