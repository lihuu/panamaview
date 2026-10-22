package io.github.lihuu.panamaview.windows;

import io.github.lihuu.panamaview.Menus;
import io.github.lihuu.panamaview.Platform;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Windows menu's decisions: which flags an item gets, which command id it carries, what
 * its text says, and which {@code wParam} is a menu command at all.
 *
 * <p>None of this touches Windows, and that is the point. The native half of the Windows menu
 * cannot be exercised here at all — {@code Win32}'s static initializer binds
 * {@code user32.dll}, so loading it on macOS throws — but the decisions it rests on are pure,
 * and they are the parts a typo breaks silently: a wrong flag is an item that cannot be
 * chosen, a wrong id is a click that runs the wrong binding, and a wrong guard is a control
 * notification that fires a menu item's binding.
 *
 * <p>The class under test has no static initializer that reaches for a framework, and the
 * methods under test call into nothing, so this runs the same on every platform.
 */
class WinMenuTest {

    @Test
    void aBindingItemIsAStringWithItsCommandId() {
        var plan = WinMenu.plan(Menus.menu(Menus.menu("文件",
                Menus.item("export", "导出"))), "MyApp");
        var item = plan.menus().get(0).items().get(0);

        assertEquals(1, item.commandId(), "the command id the framework assigned");
        assertEquals("导出", item.text());
        assertEquals(WinMenu.MF_STRING, item.flags(),
                "MF_STRING is the absence of a flag, not a bit to set");
        assertNull(item.operation(),
                "a binding item is the portable layer's business, not the host's");
    }

    @Test
    void aSeparatorIsASeparatorAndNothingElse() {
        var plan = WinMenu.plan(Menus.menu(Menus.menu("文件",
                Menus.item("export", "导出"),
                Menus.separator(),
                Menus.item("save", "保存"))), "MyApp");
        var items = plan.menus().get(0).items();

        assertEquals(WinMenu.MF_SEPARATOR, items.get(1).flags());
        assertEquals(0, items.get(1).commandId(),
                "a separator carries no id: the text and the id are both ignored, and the "
                        + "system reports 0 for it");
        assertEquals("", items.get(1).text());
        assertEquals(3, items.get(2).commandId(),
                "the separator does not shift the ids after it");
    }

    @Test
    void anOperationWithNoWindowsRouteIsBuiltDisabled() {
        // The browser already performs the editing commands on Windows, so the framework has
        // no route for them — and an item that looks live and does nothing is worse than one
        // that is visibly out of reach.
        var plan = WinMenu.plan(Menus.menu(Menus.menu("Edit",
                Menus.platformItem(Platform.COPY, "复制"))), "MyApp");
        var item = plan.menus().get(0).items().get(0);

        assertTrue((item.flags() & WinMenu.MF_DISABLED) != 0,
                "MF_DISABLED: visible, and cannot be chosen");
        assertEquals(Platform.COPY, item.operation(),
                "the operation is still recorded, so the host can say it has no route for it");
    }

    @Test
    void theQuitOperationIsBuiltEnabled() {
        // WM_CLOSE on the window is the one route Windows has, and the framework performs it.
        var plan = WinMenu.plan(Menus.menu(Menus.menu("File",
                Menus.platformItem(Platform.QUIT, "Quit"))), "MyApp");
        var item = plan.menus().get(0).items().get(0);

        assertEquals(0, item.flags() & WinMenu.MF_DISABLED, "Quit has a Windows route");
        assertEquals(Platform.QUIT, item.operation());
    }

    @Test
    void theWindowsPlanNamesTheOperationsItCannotPerform() {
        var plan = WinMenu.plan(Menus.menu(Menus.menu("Edit",
                Menus.platformItem(Platform.COPY, "复制"),
                Menus.platformItem(Platform.PASTE, "粘贴"),
                Menus.platformItem(Platform.QUIT, "Quit"))), "MyApp");

        assertEquals(List.of(Platform.COPY, Platform.PASTE), plan.unsupportedOperations(),
                "the framework says once which operations it could not perform, rather than "
                        + "leaving the two platforms quietly different");
    }

    @Test
    void theAppMenuIsTitledWithTheApplicationName() {
        // AppKit shows the application name by itself and the model's placeholder is never
        // shown; Win32 has no such convention, so the popup's text is the text.
        var plan = WinMenu.plan(Menus.menu(Menus.appMenu()), "MyApp");

        assertEquals("MyApp", plan.menus().get(0).title());
    }

    @Test
    void anApplicationsOwnMenuKeepsItsTitle() {
        var plan = WinMenu.plan(Menus.menu(Menus.menu("文件",
                Menus.item("export", "导出"))), "MyApp");

        assertEquals("文件", plan.menus().get(0).title(),
                "only the application menu is renamed, because only the application menu is the "
                        + "framework's");
    }

    @Test
    void theWindowsPlanCarriesNoAccelerator() {
        // The model carries the key equivalents, because macOS's menu items have them and the
        // built-in menus need them. The Windows builder ignores them: a shortcut the
        // framework handled would have to take the key away from the page, which is the
        // opposite of what the measurement says, and the accelerator route is a separate
        // piece of work. So the plan says nothing about them — there is no field for them.
        var plan = WinMenu.plan(Menus.menu(Menus.menu("文件",
                Menus.item("export", "导出").shortcut("CmdOrCtrl+E"))), "MyApp");

        assertEquals(WinMenu.MF_STRING, plan.menus().get(0).items().get(0).flags(),
                "the shortcut contributes no flag and no accelerator is registered");
    }

    @Test
    void theAppMenuSaysQuitWithTheApplicationName() {
        // The same composition the macOS side does: the model says "Quit" and the platform
        // adds the name, because the model has no application name.
        var plan = WinMenu.plan(Menus.menu(Menus.appMenu()), "MyApp");

        assertEquals("Quit MyApp", plan.menus().get(0).items().get(0).text());
    }

    @Test
    void aQuitItemOfAnApplicationsOwnMenuKeepsTheModelsTitle() {
        // The application menu is the framework's; an application's own menu says what it says.
        var plan = WinMenu.plan(Menus.menu(Menus.menu("文件",
                Menus.platformItem(Platform.QUIT, "退出"))), "MyApp");

        assertEquals("退出", plan.menus().get(0).items().get(0).text());
    }

    @Test
    void aNonAsciiTitleSurvivesThePlan() {
        var plan = WinMenu.plan(Menus.menu(Menus.menu("文件",
                Menus.item("export", "导出"))), "MyApp");
        var item = plan.menus().get(0).items().get(0);

        assertEquals("导出", item.text());
        assertEquals(2, item.text().length(), "two code units");
        assertEquals(6, item.text().getBytes(StandardCharsets.UTF_16LE).length + 2,
                "six bytes with the terminator the native allocation adds");
    }

    @Test
    void onlyAMenuCommandIsAMenuCommand() {
        // The high word of wParam says where the message came from, and only 0 means a menu.
        assertEquals(7, WinMenu.commandId(7L), "a menu command carries the id in the low word");
        assertEquals(WinMenu.NO_COMMAND, WinMenu.commandId(0x00010007L),
                "an accelerator is not a menu command");
        assertEquals(WinMenu.NO_COMMAND, WinMenu.commandId(0x00020007L),
                "a control notification is not a menu command");
        assertEquals(WinMenu.NO_COMMAND, WinMenu.commandId(0L),
                "0 is what a separator reports");
        assertEquals(WinMenu.NO_COMMAND, WinMenu.commandId(0xFFFFL),
                "0xFFFF is what a popup reports, and masking the whole value would look up the "
                        + "binding numbered 65535");
        assertEquals(WinMenu.NO_COMMAND, WinMenu.commandId(0xFFFFFFFFL),
                "a popup's id is 0xFFFFFFFF, which is not an id either");
    }
}
