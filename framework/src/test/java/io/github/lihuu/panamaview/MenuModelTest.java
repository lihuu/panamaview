package io.github.lihuu.panamaview;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model facts the macOS menu builder depends on.
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
        assertEquals("z", items.get(1).shortcut().key(), "Redo");
        assertTrue(items.get(1).shortcut().modifiers().contains(Shortcut.Modifier.SHIFT),
                "Redo is ⌘⇧Z — the Shift is stated, not left to AppKit's rule that an uppercase "
                        + "key equivalent implies it");
        assertEquals("x", items.get(3).shortcut().key(), "Cut");
        assertEquals("c", items.get(4).shortcut().key(), "Copy");
        assertEquals("v", items.get(5).shortcut().key(), "Paste");
        assertEquals("a", items.get(7).shortcut().key(), "Select All");
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
}
