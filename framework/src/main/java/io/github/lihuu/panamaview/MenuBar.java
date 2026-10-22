package io.github.lihuu.panamaview;

import java.util.List;

public final class MenuBar {
    private final List<Menu> menus;

    public MenuBar(List<Menu> menus) {
        this.menus = menus;
        assignIds();
    }

    private void assignIds() {
        int currentId = 1;
        for (Menu menu : menus) {
            for (Item item : menu.items()) {
                // Assign sequential IDs to all items to support platform mapping
                switch (item) {
                    case Item.BindingItem b -> b.assignId(currentId++);
                    case Item.PlatformItem p -> p.assignId(currentId++);
                    case Item.SeparatorItem s -> s.assignId(currentId++);
                }
            }
        }
    }

    public List<Menu> menus() { return menus; }
}
