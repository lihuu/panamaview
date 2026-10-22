package io.github.lihuu.panamaview;

import io.github.lihuu.panamaview.windows.WinMenu;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model facts the macOS menu builder depends on, and the Windows plan that turns the same
 * model into a native menu.
 *
 * <p>The AppKit half cannot be tested here: CI has no window server, and AppKit cannot be
 * touched without one. What can be tested is the model the builder reads — which item is a
 * binding item, which is a platform item, which operation a platform item names, and which key
 * equivalent each of the built-in items carries.
 *
 * <p>The key equivalents are the ones that bite. The built-in Edit menu is what makes ⌘C reach
 * the web view's text field (issue #19), and it gets there through a key equivalent on every
 * item. A model that carried the items but not their key equivalents would build a menu that
 * looks right and does not work — which is exactly the kind of difference a screenshot cannot
 * see.
 *
 * <p>The Windows half has the same problem and the same answer. The native half of the Windows
 * menu cannot be touched here either — {@code Win32}'s static initializer binds
 * {@code user32.dll}, so loading it on macOS throws — but the decisions it rests on are pure:
 * which flags an item gets, which command id it carries, what its text is, and which
 * {@code wParam} is a menu command at all. Those are the parts a typo breaks silently, and
 * they are what the plan is for.
 */
class MenuModelTest {

    @Test
    void aBindingItemNamesItsBindingAndNoOperation() {
        var item = Menus.item("export", "导出");
        assertEquals("export", item.binding());
        assertNull(item.platformAction(),
                "a binding item has no platform operation: the two kinds are different in kind");
    }

    @Test
    void aPlatformItemNamesItsOperationAndNoBinding() {
        var item = Menus.platformItem(Platform.COPY, "复制");
        assertEquals(Platform.COPY, item.platformAction());
        assertNull(item.binding(),
                "a platform item has no binding behind it — that is what lets AppKit walk the "
                        + "responder chain instead of stopping at a Java target");
    }

    @Test
    void aSeparatorNamesNeither() {
        var item = Menus.separator();
        assertNull(item.binding());
        assertNull(item.platformAction());
        assertNull(item.title());
    }

    @Test
    void theAppMenuCarriesQuit() {
        var items = Menus.appMenu().items();
        assertEquals(1, items.size());
        assertEquals(Platform.QUIT, items.get(0).platformAction());
    }

    @Test
    void theAppMenuTitleIsThePlaceholderThePlatformReplaces() {
        // The model has no application name — only the platform has one — so the model leaves a
        // placeholder. The platform puts the real name there and never shows this.
        assertEquals("[Application Name]", Menus.appMenu().title(),
                "the application's own title must not reach the menu bar");
    }

    @Test
    void theQuitItemCarriesTheQKeyEquivalent() {
        var quit = Menus.appMenu().items().get(0);
        assertNotNull(quit.shortcut(), "the Quit item must carry its key equivalent");
        assertEquals("q", quit.shortcut().key());
        assertTrue(quit.shortcut().modifiers().contains(Shortcut.Modifier.CMD_OR_CTRL),
                "⌘Q is how the application is quit from the keyboard");
    }

    @Test
    void theEditMenuCarriesTheShippedKeyEquivalents() {
        var items = Menus.editMenu().items();

        assertEquals("z", items.get(0).shortcut().key(), "Undo");
        assertEquals("Z", items.get(1).shortcut().key(), "Redo");
        assertTrue(items.get(1).shortcut().modifiers().contains(Shortcut.Modifier.CMD_OR_CTRL),
                "the shipped Redo item's mask was Command: it passed \"Z\" and set no mask, so "
                        + "AppKit's default applied");
        assertFalse(items.get(1).shortcut().modifiers().contains(Shortcut.Modifier.SHIFT),
                "the shipped Redo item's mask was Command and not Shift: the uppercase key "
                        + "equivalent changed only the displayed key, never the mask");
        assertEquals("x", items.get(3).shortcut().key(), "Cut");
        assertEquals("c", items.get(4).shortcut().key(), "Copy");
        assertEquals("v", items.get(5).shortcut().key(), "Paste");
        assertEquals("a", items.get(7).shortcut().key(), "Select All");
    }

    @Test
    void undoAndRedoDoNotShareAKeyEquivalent() {
        // Measured on real AppKit: among items with different actions, the second one to
        // declare the same key and the same mask loses its key equivalent entirely, and is
        // then reachable by no chord at all — and that reaches across the whole main menu, not
        // only within one of its menus. Items that share an action both keep theirs — two of
        // the framework's binding items with the same key and the same mask both kept theirs.
        // Undo and Redo are platform items, each with its own action, so they have to differ;
        // the shipped menu's "z" and "Z" are what kept both of them alive.
        var undo = Menus.editMenu().items().get(0).shortcut();
        var redo = Menus.editMenu().items().get(1).shortcut();

        assertFalse(undo.key().equals(redo.key()) && undo.modifiers().equals(redo.modifiers()),
                "Undo and Redo must not declare the same key equivalent and the same mask, or "
                        + "AppKit drops the second one's shortcut entirely — anywhere in the bar");
    }

    @Test
    void everyBuiltInShortcutIsCommandOnMacOs() {
        // The built-in menus used to pass a bare character and let AppKit apply ⌘ by default.
        // Now that the platform sets the mask explicitly, the modifier has to be in the model.
        var items = new ArrayList<Item>(Menus.appMenu().items());
        items.addAll(Menus.editMenu().items());

        for (var item : items) {
            if (item.platformAction() == null) {
                // A separator, which carries no shortcut.
                continue;
            }
            assertNotNull(item.shortcut(),
                    "every built-in item carries a key equivalent: " + item.title());
            assertTrue(item.shortcut().modifiers().contains(Shortcut.Modifier.CMD_OR_CTRL),
                    "the built-in items are ⌘-shortcuts: " + item.title());
        }
    }

    // ------------------------------------------------------------- the Windows plan

    @Test
    void theIdsABarAssignsStartAtOne() {
        var bar = Menus.menu(Menus.menu("文件",
                Menus.item("export", "导出"),
                Menus.separator(),
                Menus.item("save", "保存")));
        assertEquals(1, bar.menus().get(0).items().get(0).id());
        assertEquals(2, bar.menus().get(0).items().get(1).id(),
                "a separator still takes an id, so the ids after it are not shifted");
        assertEquals(3, bar.menus().get(0).items().get(2).id());
    }

    @Test
    void aSeparatorStillTakesAnId() {
        // The separator's assigned id is a real one, so the ids after it are not shifted. What
        // arrives when a separator is clicked is 0 — that is the Windows side's business.
        var bar = Menus.menu(Menus.menu("文件",
                Menus.item("export", "导出"),
                Menus.separator()));
        assertEquals(1, bar.menus().get(0).items().get(0).id());
        assertEquals(2, bar.menus().get(0).items().get(1).id());
    }

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
