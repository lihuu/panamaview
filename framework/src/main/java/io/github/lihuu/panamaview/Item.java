package io.github.lihuu.panamaview;

public sealed interface Item permits Item.BindingItem, Item.PlatformItem, Item.SeparatorItem {
    String title();
    String binding();          // null unless this is a BindingItem
    Platform platformAction(); // null unless this is a PlatformItem
    Shortcut shortcut();
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
        @Override public Item shortcut(Shortcut s) { this.shortcut = s; return this; }
        @Override public int id() { return id; }
        void assignId(int id) { this.id = id; }
    }
}
