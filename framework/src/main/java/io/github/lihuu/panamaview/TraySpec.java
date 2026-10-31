package io.github.lihuu.panamaview;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The tray icon an application declares: a tooltip, a flat menu, and an optional icon.
 *
 * <p>A tray is a surface of its own, not a second spelling of the menu bar. A popup attached to a
 * tray icon has no title, and a tray menu cannot hold top-level menus — which is why this is a
 * list of items rather than a {@link MenuBar}. The items are still the model's items: a binding
 * item names a binding, a platform item names an operation, and a separator draws a line.
 *
 * <p>The ids are assigned by the same walk that numbers a menu bar, but into a table of their
 * own: a tray item and a bar item may both be id 1, and a click tells them apart by the callback
 * it arrives on — {@link UiHost.Callbacks#onTrayMenuItem(int)} for the tray,
 * {@link UiHost.Callbacks#onMenuItem(int)} for the bar. One surface, one id table.
 *
 * <p>An item belongs to exactly one surface. {@link Item} carries its id, so the second surface
 * to number a shared item would overwrite the first surface's id, and the first surface's table
 * would then disagree with the item it holds. This is the same constraint {@link MenuBar}
 * documents for an item shared between two bars.
 *
 * <p>{@code tooltip} is what the platform shows when the pointer rests on the icon; a null is
 * the empty tooltip. {@code icon} is the application's own artwork, or null for the framework's
 * default — a decision that keeps a first tray from requiring an asset in every project.
 */
public final class TraySpec {

    private final String tooltip;
    private final List<Item> items;
    private final Path icon;
    private final Map<Integer, String> bindings;

    /** A tray with the framework's own icon. */
    public TraySpec(String tooltip, List<Item> items) {
        this(tooltip, items, null);
    }

    /**
     * @param tooltip the text shown on hover, or null for none
     * @param items   the menu, in the order it will be shown
     * @param icon    a file the platform loads, or null for the framework's default
     */
    public TraySpec(String tooltip, List<Item> items, Path icon) {
        if (items == null) {
            throw new IllegalArgumentException("A tray cannot be made with a null list of items");
        }
        for (Item item : items) {
            if (item == null) {
                throw new IllegalArgumentException("A tray cannot hold a null item");
            }
        }
        this.tooltip = tooltip == null ? "" : tooltip;
        this.items = List.copyOf(items);
        this.icon = icon;
        this.bindings = MenuBar.number(this.items, "tray menu");
    }

    public String tooltip() { return tooltip; }

    public List<Item> items() { return items; }

    /** The icon file, or null when the framework supplies its own. */
    public Path icon() { return icon; }

    /**
     * The id → binding name table, built by the same walk that numbered the items.
     *
     * <p>Package-private for the same reason as {@link MenuBar#bindings()}: the host reports ids,
     * and turning one into a name is the portable layer's business.
     */
    Map<Integer, String> bindings() { return bindings; }
}
