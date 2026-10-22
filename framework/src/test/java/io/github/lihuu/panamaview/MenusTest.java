package io.github.lihuu.panamaview;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

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
        assertEquals(java.util.Set.of(Shortcut.Modifier.CMD_OR_CTRL, Shortcut.Modifier.SHIFT), shortcut.modifiers());
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
}
