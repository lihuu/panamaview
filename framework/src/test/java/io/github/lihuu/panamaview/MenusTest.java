package io.github.lihuu.panamaview;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

class MenusTest {

    @Test
    void aBarIsAMenuOfMenus() {
        var bar = Menus.menu(
                Menus.appMenu(),
                Menus.menu("文件",
                        Menus.item("export", "导出"),
                        Menus.separator()),
                Menus.editMenu());
        assertEquals(3, bar.menus().size());
        assertEquals("文件", bar.menus().get(1).title());
        assertEquals(2, bar.menus().get(1).items().size());
    }

    @Test
    void aBindingItemNamesItsBinding() {
        var item = Menus.item("export", "导出");
        assertEquals("export", item.binding());
        assertEquals("导出", item.title());
    }

    @Test
    void aPlatformItemNamesAPlatformOperation() {
        var item = Menus.platformItem(Platform.QUIT, "退出");
        assertEquals(Platform.QUIT, item.platformAction());
    }

    @Test
    void aShortcutParsesItsModifiers() {
        var shortcut = Shortcut.parse("CmdOrCtrl+Shift+E");
        assertEquals("E", shortcut.key());
        assertEquals(Set.of(Shortcut.Modifier.CMD_OR_CTRL, Shortcut.Modifier.SHIFT), shortcut.modifiers());
    }

    @Test
    void aShortcutWithNoModifiersHasAnEmptySet() {
        var shortcut = Shortcut.parse("C");
        assertEquals("C", shortcut.key());
        assertTrue(shortcut.modifiers().isEmpty());
    }

    @Test
    void aShortcutIsTheEmptyOneWhenThereIsNone() {
        assertNull(Menus.item("export", "导出").shortcut());
    }

    @Test
    void shortcutStringSetterWorks() {
        var item = Menus.item("export", "导出").shortcut("CmdOrCtrl+E");
        assertEquals("E", item.shortcut().key());
        assertEquals(Set.of(Shortcut.Modifier.CMD_OR_CTRL), item.shortcut().modifiers());
    }

    @Test
    void parserThrowsOnMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> Shortcut.parse("Ctrl+"));
        assertThrows(IllegalArgumentException.class, () -> Shortcut.parse("Ctrl+Shift+"));
        assertThrows(IllegalArgumentException.class, () -> Shortcut.parse("Ctrl"));
        assertThrows(IllegalArgumentException.class, () -> Shortcut.parse("Shift"));
        assertThrows(IllegalArgumentException.class, () -> Shortcut.parse("Ctrl+Shift"));
    }

    @Test
    void parserTrimsKey() {
        var shortcut = Shortcut.parse("Ctrl + E");
        assertEquals("E", shortcut.key());
        assertEquals(Set.of(Shortcut.Modifier.CTRL), shortcut.modifiers());
    }

    @Test
    void aComplexShortcutStillParses() {
        var shortcut = Shortcut.parse("Ctrl+Shift+E");
        assertEquals("E", shortcut.key());
        assertEquals(Set.of(Shortcut.Modifier.CTRL, Shortcut.Modifier.SHIFT), shortcut.modifiers());
    }

    @Test
    void parserIsLocaleInsensitive() {
        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var shortcut = Shortcut.parse("Shift+E");
            assertEquals("E", shortcut.key());
            assertTrue(shortcut.modifiers().contains(Shortcut.Modifier.SHIFT),
                "Parsing 'Shift' must work regardless of default locale");
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    @Test
    void appMenuHasQuit() {
        var menu = Menus.appMenu();
        assertEquals(1, menu.items().size());
        assertEquals(Platform.QUIT, menu.items().get(0).platformAction());
    }

    @Test
    void editMenuHasCorrectPlatformItems() {
        var menu = Menus.editMenu();
        assertEquals(8, menu.items().size());
        assertEquals(Platform.UNDO, menu.items().get(0).platformAction());
        assertEquals(Platform.REDO, menu.items().get(1).platformAction());
        assertNull(menu.items().get(2).platformAction());
        assertNull(menu.items().get(2).binding());
        assertEquals(Platform.CUT, menu.items().get(3).platformAction());
        assertEquals(Platform.COPY, menu.items().get(4).platformAction());
        assertEquals(Platform.PASTE, menu.items().get(5).platformAction());
        assertNull(menu.items().get(6).platformAction());
        assertNull(menu.items().get(6).binding());
        assertEquals(Platform.SELECT_ALL, menu.items().get(7).platformAction());
    }

    @Test
    void aShortcutRefusesANullKey() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> new Shortcut(null, Set.of()));
        assertTrue(thrown.getMessage().contains("key"),
                "the message must say what is wrong: " + thrown.getMessage());
    }

    @Test
    void aShortcutRefusesANullModifierSet() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> new Shortcut("E", null));
        assertTrue(thrown.getMessage().contains("modifier"),
                "the message must say what is wrong: " + thrown.getMessage());
    }

    @Test
    void aShortcutDoesNotChangeWhenTheSetItWasGivenIsChanged() {
        var modifiers = new HashSet<>(Set.of(Shortcut.Modifier.CMD_OR_CTRL));
        var shortcut = new Shortcut("E", modifiers);

        modifiers.add(Shortcut.Modifier.SHIFT);

        assertEquals(Set.of(Shortcut.Modifier.CMD_OR_CTRL), shortcut.modifiers(),
                "a shortcut must mean what it meant when it was made");
    }

    @Test
    void aCallerCannotChangeTheMenuOrTheBarAfterBuildingThem() {
        var items = new ArrayList<Item>(List.of(Menus.item("export", "导出")));
        var menu = new Menu("文件", items);
        var bar = new MenuBar(List.of(menu));

        // The caller's list, changed after the fact.
        items.set(0, Menus.item("other", "别的"));

        assertEquals("导出", menu.items().get(0).title(),
                "the menu owns the list it was given");
        assertEquals("导出", bar.menus().get(0).items().get(0).title(),
                "and so does the bar — the id table and the items must not be able to disagree");
    }

    @Test
    void aShortcutRefusesAModifierAsItsKey() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> new Shortcut("Ctrl", Set.of()));
        assertTrue(thrown.getMessage().contains("Modifier"),
                "the message must say what is wrong: " + thrown.getMessage());

        // The check is on the uppercased form, the way parse's is, and the key itself keeps
        // its own case.
        assertThrows(IllegalArgumentException.class, () -> new Shortcut("ctrl", Set.of()));
        assertEquals("e", new Shortcut("e", Set.of()).key(), "the key keeps its case");
    }

    @Test
    void aBarRefusesTheSameItemTwice() {
        var item = Menus.item("export", "导出");
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new MenuBar(List.of(
                        new Menu("文件", List.of(item)),
                        new Menu("其他", List.of(item)))));
        assertTrue(thrown.getMessage().contains("导出"),
                "the message must name the item: " + thrown.getMessage());
    }

    @Test
    void aBarRefusesTheSameMenuTwice() {
        var menu = Menus.menu("文件", Menus.item("export", "导出"));
        var thrown = assertThrows(IllegalArgumentException.class, () -> new MenuBar(List.of(menu, menu)));
        assertTrue(thrown.getMessage().contains("文件"),
                "the message must name the menu: " + thrown.getMessage());
    }

    @Test
    void aMenuRefusesANullItem() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new Menu("文件", Arrays.asList((Item) null)));
        assertTrue(thrown.getMessage().contains("null"),
                "the message must say what is wrong: " + thrown.getMessage());
    }

    @Test
    void aBarRefusesANullMenu() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> new MenuBar(Arrays.asList((Menu) null)));
        assertTrue(thrown.getMessage().contains("null"),
                "the message must say what is wrong: " + thrown.getMessage());
    }

    @Test
    void aBindingItemMustHaveATitle() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> Menus.item("export", null));
        assertTrue(thrown.getMessage().contains("export"),
                "the message must name the item: " + thrown.getMessage());
    }

    @Test
    void aPlatformItemMustHaveATitle() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> Menus.platformItem(Platform.QUIT, null));
        assertTrue(thrown.getMessage().contains("QUIT"),
                "the message must name the item: " + thrown.getMessage());
    }

    @Test
    void aSeparatorStillHasNoTitle() {
        // A separator is the one item without a title, and it is a kind of its own: nothing
        // about it changed, and the model still makes one.
        assertNull(Menus.separator().title());
        assertNull(Menus.separator().binding());
        assertNull(Menus.separator().platformAction());
    }
}
