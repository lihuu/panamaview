package io.github.lihuu.panamaview;

public sealed interface Item permits Item.BindingItem, Item.PlatformItem, Item.SeparatorItem {
    /** The item's title. Null for a separator, which is the one item that has no title. */
    String title();
    String binding();          // null unless this is a BindingItem
    Platform platformAction(); // null unless this is a PlatformItem

    /** The item's shortcut, or null when it has none. */
    Shortcut shortcut();

    /**
     * Sets the item's shortcut.
     *
     * <p>The shortcut is optional, and {@code null} is how the model says there is none: an
     * item with no shortcut gets no key equivalent, which is what the platform builds for it.
     * So a null shortcut is allowed, and it means "no shortcut" — the state the item starts in.
     * The plain {@code null} is ambiguous between the two overloads, so a null is passed as
     * {@code (Shortcut) null}.
     *
     * <p>The {@link String} overload is the other way in, and it refuses a null or a blank
     * string: there is no shortcut to name, so there is nothing to set.
     *
     * <p>A separator refuses a shortcut: the platforms draw it as a line and carry no key
     * equivalent for it, so a shortcut there would be accepted and do nothing. A blanket loop
     * over a menu's items — {@code for (Item item : menu.items()) item.shortcut(...)} — would
     * therefore throw on the first separator it reaches. A null is still allowed on a
     * separator, and does nothing: null means "no shortcut", and a separator has none.
     *
     * @throws IllegalArgumentException when the item is a separator and the shortcut is not
     *                                  null
     */
    Item shortcut(Shortcut s);

    default Item shortcut(String s) {
        return shortcut(Shortcut.parse(s));
    }

    int id();

    final class BindingItem implements Item {
        private final String binding;
        private final String title;
        private Shortcut shortcut;
        private int id;

        BindingItem(String binding, String title) {
            // The factories in Menus refuse a null binding or title. This constructor is
            // package-private, so it is not a door an application has.
            this.binding = binding;
            this.title = title;
        }

        @Override public String title() { return title; }
        @Override public String binding() { return binding; }
        @Override public Platform platformAction() { return null; }
        @Override public Shortcut shortcut() { return shortcut; }
        @Override public Item shortcut(Shortcut s) { this.shortcut = s; return this; }
        @Override public int id() { return id; }
        void assignId(int id) { this.id = id; }
    }

    final class PlatformItem implements Item {
        private final Platform platformAction;
        private final String title;
        private Shortcut shortcut;
        private int id;

        PlatformItem(Platform platformAction, String title) {
            // The factories in Menus refuse a null operation or title. This constructor is
            // package-private, so it is not a door an application has.
            this.platformAction = platformAction;
            this.title = title;
        }

        @Override public String title() { return title; }
        @Override public String binding() { return null; }
        @Override public Platform platformAction() { return platformAction; }
        @Override public Shortcut shortcut() { return shortcut; }
        @Override public Item shortcut(Shortcut s) { this.shortcut = s; return this; }
        @Override public int id() { return id; }
        void assignId(int id) { this.id = id; }
    }

    final class SeparatorItem implements Item {
        private Shortcut shortcut;
        private int id;

        SeparatorItem() { }

        @Override public String title() { return null; }
        @Override public String binding() { return null; }
        @Override public Platform platformAction() { return null; }
        @Override public Shortcut shortcut() { return shortcut; }
        @Override public Item shortcut(Shortcut s) {
            if (s != null) {
                // A separator carries no key equivalent on either platform, so a shortcut here
                // would be accepted and do nothing — the same shape this model refuses
                // everywhere else. A null is fine: null means "no shortcut", and a separator
                // has none.
                throw new IllegalArgumentException("A separator cannot have a shortcut: the "
                        + "platforms draw it as a line and carry no key equivalent for it");
            }
            this.shortcut = s;
            return this;
        }
        @Override public int id() { return id; }
        void assignId(int id) { this.id = id; }
    }
}
