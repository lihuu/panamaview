package io.github.lihuu.panamaview;

import java.util.List;

public final class Menu {
    private final String title;
    private final List<Item> items;

    /**
     * Takes a copy of the items, so the menu owns what it was given.
     *
     * <p>The list that comes in is the caller's, and it can still be changed afterwards: the
     * factories hand over {@code Arrays.asList(...)} of the caller's own array. An item moved
     * out from under the menu after the bar was built would leave the id table and the items
     * disagreeing about which item is which, and that table is the one thing this model has
     * to keep straight.
     */
    public Menu(String title, List<Item> items) {
        this.title = title;
        this.items = List.copyOf(items);
    }

    public String title() { return title; }
    public List<Item> items() { return items; }
}
