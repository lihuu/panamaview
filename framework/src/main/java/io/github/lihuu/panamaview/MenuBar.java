package io.github.lihuu.panamaview;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MenuBar {
    private final List<Menu> menus;
    private final Map<Integer, String> bindings;

    /**
     * Takes a copy of the menus, so the bar owns what it was given.
     *
     * <p>Same reason as {@link Menu}: the list that comes in is the caller's, and a menu moved
     * in or out afterwards would leave the bar describing a bar that is not the one the
     * platform is given. The copy closes that.
     *
     * <p>It does not reach as far as the items: an item shared between two bars is still
     * renumbered by the second bar's walk, so the first bar's table and the item it holds
     * would disagree about its id.
     */
    public MenuBar(List<Menu> menus) {
        for (Menu menu : menus) {
            if (menu == null) {
                throw new IllegalArgumentException("A bar cannot hold a null menu");
            }
        }
        this.menus = List.copyOf(menus);
        this.bindings = numberItems();
    }

    /**
     * Numbers the items and records what each number means, in one walk.
     *
     * <p>The two belong together: the ids are what the platform reports when an item is
     * chosen, and the names are what the bindings are registered under. A second walk to
     * derive the names would be a second opinion about the same numbering, and the two would
     * drift the moment the model grew a level.
     *
     * <p>An item that is in the bar twice is refused here, where the ids are handed out.
     * The ids live in the items themselves, so one item in two places would be given its
     * second id and lose its first: both places would carry the same id, and the two places
     * would become indistinguishable — a click could no longer say which one was chosen,
     * while the id table kept an entry for an id no item carries. The binding itself would
     * still be the right one, because it is the same item; what stops agreeing is the
     * places. That is the one way the table and the numbering could still disagree, so it
     * is not left to be discovered.
     */
    private Map<Integer, String> numberItems() {
        Map<Integer, String> names = new HashMap<>();
        // Identity, not equality: two items that happen to look alike are still two items.
        Set<Item> seenItems = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Menu> seenMenus = Collections.newSetFromMap(new IdentityHashMap<>());
        int currentId = 1;
        for (Menu menu : menus) {
            if (!seenMenus.add(menu)) {
                throw new IllegalArgumentException("The same menu is in the bar twice: "
                        + describe(menu) + ". A menu may appear only once: its items would be "
                        + "in the bar twice, and each of them would carry one id for two places.");
            }
            for (Item item : menu.items()) {
                if (!seenItems.add(item)) {
                    throw new IllegalArgumentException("The same menu item is in the bar twice: "
                            + describe(item) + ". An item may appear only once: one id would "
                            + "serve both places, so a click could not say which place was "
                            + "chosen, and the id table would keep an entry for an id no item "
                            + "carries.");
                }
                // Assign sequential IDs to all items to support platform mapping
                switch (item) {
                    case Item.BindingItem b -> b.assignId(currentId);
                    case Item.PlatformItem p -> p.assignId(currentId);
                    case Item.SeparatorItem s -> s.assignId(currentId);
                }
                // Only a binding item names a binding. A platform item is the platform's own
                // business, and a separator names nothing at all.
                if (item.binding() != null) {
                    names.put(item.id(), item.binding());
                }
                currentId++;
            }
        }
        return Map.copyOf(names);
    }

    /**
     * The item as an error should name it: its title, and its binding when it has one.
     *
     * <p>The binding is the part that matters — it is what a click dispatches to — and a
     * separator has neither a title nor a binding, so it is named for what it is.
     */
    private static String describe(Item item) {
        String title = item.title() == null ? "a separator" : "\"" + item.title() + "\"";
        String binding = item.binding();
        return binding == null ? title : title + " (binding \"" + binding + "\")";
    }

    /** The menu as an error should name it. */
    private static String describe(Menu menu) {
        return menu.title() == null ? "a menu with no title" : "\"" + menu.title() + "\"";
    }

    public List<Menu> menus() { return menus; }

    /**
     * The id → binding name table.
     *
     * <p>What the platform reports is an id; what the framework dispatches is a name. This
     * table is where one becomes the other.
     */
    Map<Integer, String> bindings() { return bindings; }
}
