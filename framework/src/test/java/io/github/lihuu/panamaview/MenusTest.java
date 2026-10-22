package io.github.lihuu.panamaview;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
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
}
