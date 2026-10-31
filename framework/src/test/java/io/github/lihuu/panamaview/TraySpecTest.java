package io.github.lihuu.panamaview;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The tray model: numbering, the id table, and what a spec refuses.
 *
 * <p>The platform half cannot be tested here — a status item needs a window server and a
 * notification-area icon needs Explorer. What can be tested is what both hosts read: the ids the
 * framework assigned, and which of them name bindings.
 */
class TraySpecTest {

    @Test
    void itemsAreNumberedAcrossSeparators() {
        var save = Menus.item("save", "Save");
        var separator = Menus.separator();
        var quit = Menus.platformItem(Platform.QUIT, "Quit");

        new TraySpec("Demo", List.of(save, separator, quit));

        // The separator takes an id of its own, exactly as it does in a bar: the item after it
        // is not where it looks to be if the numbering skipped separators.
        assertEquals(1, save.id());
        assertEquals(2, separator.id());
        assertEquals(3, quit.id());
    }

    @Test
    void onlyBindingItemsNameBindings() {
        var tray = new TraySpec("Demo", List.of(
                Menus.item("save", "Save"),
                Menus.separator(),
                Menus.platformItem(Platform.QUIT, "Quit")));

        // A platform item is the platform's business and a separator names nothing, so the
        // table a click is looked up in carries one entry.
        assertEquals(Map.of(1, "save"), tray.bindings());
    }

    @Test
    void theSameItemTwiceIsRefused() {
        var save = Menus.item("save", "Save");

        // One item in two places would be given its second id and lose its first, and the two
        // places would become indistinguishable. Refused where the ids are handed out.
        var error = assertThrows(IllegalArgumentException.class,
                () -> new TraySpec("Demo", List.of(save, save)));
        assertTrue(error.getMessage().contains("tray menu"),
                "the error should name the surface it is about: " + error.getMessage());
    }

    @Test
    void aNullListIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new TraySpec("Demo", null, null));
    }

    @Test
    void aNullItemIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new TraySpec("Demo", java.util.Arrays.asList(Menus.item("save", "Save"), null)));
    }

    @Test
    void aNullTooltipIsTheEmptyOne() {
        // Null means "no tooltip", not "ask the platform to guess": the value a host passes on
        // is a string either way.
        assertEquals("", new TraySpec(null, List.of()).tooltip());
    }

    @Test
    void theIconIsKeptAsGiven() {
        var icon = Path.of("/tmp/icon.png");
        assertEquals(icon, new TraySpec("Demo", List.of(), icon).icon());
        assertNull(new TraySpec("Demo", List.of()).icon(), "no icon means the framework's own");
    }
}
