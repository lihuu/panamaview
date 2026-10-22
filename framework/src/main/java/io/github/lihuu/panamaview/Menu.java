package io.github.lihuu.panamaview;

import java.util.List;

public final class Menu {
    private final String title;
    private final List<Item> items;

    /**
     * Takes a copy of the items, so the menu owns what it was given.
     *
     * <p>The list that comes in is the caller's, and it can still be changed afterwards: the
     * factories hand over {@code Arrays.asList(...)} of the caller's own array. The copy closes
     * that — once the menu is made, the items in it are the ones it was made with, and a list
     * edited afterwards cannot move an item out from under the bar's numbering.
     *
     * <p>What the copy does <em>not</em> cover is an item shared between two bars: the second
     * bar's walk renumbers it, so the first bar's table and the item it holds would disagree
     * about which id that item has. That is a different mistake — a shared item rather than a
     * shared list — and the copy does not reach it.
     */
    public Menu(String title, List<Item> items) {
        this.title = title;
        for (Item item : items) {
            if (item == null) {
                throw new IllegalArgumentException("A menu cannot hold a null item: \"" + title + "\"");
            }
        }
        this.items = List.copyOf(items);
    }

    public String title() { return title; }
    public List<Item> items() { return items; }
}
