package io.github.lihuu.panamaview;

import java.util.List;

public final class Menu {
    private final String title;
    private final List<Item> items;

    public Menu(String title, List<Item> items) {
        this.title = title;
        this.items = items;
    }

    public String title() { return title; }
    public List<Item> items() { return items; }
}
