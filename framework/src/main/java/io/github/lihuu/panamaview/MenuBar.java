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
                // Use a internal method since Item.assignId is no longer public
                if (item instanceof Item.BindingItem b) b.assignId(currentId++);
                else if (item instanceof Item.PlatformItem p) p.assignId(currentId++);
                else if (item instanceof Item.SeparatorItem s) s.assignId(currentId++);
            }
        }
    }

    public List<Menu> menus() { return menus; }
}
