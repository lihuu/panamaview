package io.github.lihuu.panamaview;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MenuBar {
    private final List<Menu> menus;
    private final Map<Integer, String> bindings;

    /**
     * Takes a copy of the menus, so the bar owns what it was given.
     *
     * <p>Same reason as {@link Menu}: the list that comes in is the caller's, and the ids
     * are assigned from the menus it holds. A menu moved in or out afterwards would leave the
     * id table describing a bar that is not the one the platform is given.
     */
    public MenuBar(List<Menu> menus) {
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
     */
    private Map<Integer, String> numberItems() {
        Map<Integer, String> names = new HashMap<>();
        int currentId = 1;
        for (Menu menu : menus) {
            for (Item item : menu.items()) {
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

    public List<Menu> menus() { return menus; }

    /**
     * The id → binding name table.
     *
     * <p>What the platform reports is an id; what the framework dispatches is a name. This
     * table is where one becomes the other.
     */
    Map<Integer, String> bindings() { return bindings; }
}
