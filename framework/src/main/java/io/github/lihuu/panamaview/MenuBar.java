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
                item.assignId(currentId++);
            }
        }
    }

    public List<Menu> menus() { return menus; }
}
