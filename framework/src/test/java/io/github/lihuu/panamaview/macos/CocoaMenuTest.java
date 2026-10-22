package io.github.lihuu.panamaview.macos;

import io.github.lihuu.panamaview.Menus;
import io.github.lihuu.panamaview.Platform;
import io.github.lihuu.panamaview.Shortcut;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The two translations the macOS menu builder makes out of the model.
 *
 * <p>Neither of them touches AppKit, and that is the point: the rest of the builder cannot be
 * exercised without a window server, so these are the only parts of it that a build can check.
 * They are also the parts a typo would break silently — a wrong selector is an item that does
 * nothing when it is chosen, and a wrong modifier mask is a shortcut that does not fire.
 *
 * <p>The class under test has no static initializer that reaches for a framework, and the
 * methods under test call into nothing, so this runs the same on every platform.
 */
class CocoaMenuTest {

    @Test
    void everyPlatformOperationHasItsAppKitAction() {
        // The platform item's action selector, with a nil target: AppKit walks the responder
        // chain, and that is how ⌘C reaches the web view's text field.
        assertEquals("terminate:", Cocoa.platformAction(Platform.QUIT));
        assertEquals("undo:", Cocoa.platformAction(Platform.UNDO));
        assertEquals("redo:", Cocoa.platformAction(Platform.REDO));
        assertEquals("cut:", Cocoa.platformAction(Platform.CUT));
        assertEquals("copy:", Cocoa.platformAction(Platform.COPY));
        assertEquals("paste:", Cocoa.platformAction(Platform.PASTE));
        assertEquals("selectAll:", Cocoa.platformAction(Platform.SELECT_ALL));
    }

    @Test
    void everyModifierHasItsAppKitFlag() {
        assertEquals(1L << 20, Cocoa.modifierMask(Set.of(Shortcut.Modifier.CMD_OR_CTRL)),
                "NSEventModifierFlagCommand — CmdOrCtrl is ⌘ on macOS");
        assertEquals(1L << 18, Cocoa.modifierMask(Set.of(Shortcut.Modifier.CTRL)),
                "NSEventModifierFlagControl");
        assertEquals(1L << 17, Cocoa.modifierMask(Set.of(Shortcut.Modifier.SHIFT)),
                "NSEventModifierFlagShift");
        assertEquals(1L << 19, Cocoa.modifierMask(Set.of(Shortcut.Modifier.ALT)),
                "NSEventModifierFlagOption");
    }

    @Test
    void aShortcutWithNoModifiersHasAnEmptyMask() {
        // The mask replaces AppKit's default rather than adding to it, and AppKit's default for a
        // bare key equivalent is ⌘. A declaration that means no modifier has to clear the mask,
        // or "C" would quietly become ⌘C.
        assertEquals(0L, Cocoa.modifierMask(Set.of()));
    }

    @Test
    void theModifiersOfOneShortcutAddUp() {
        assertEquals((1L << 20) | (1L << 17),
                Cocoa.modifierMask(Set.of(Shortcut.Modifier.CMD_OR_CTRL, Shortcut.Modifier.SHIFT)));
    }

    @Test
    void theApplicationMenuSaysQuitWithTheApplicationName() {
        var quit = Menus.appMenu().items().get(0);
        assertEquals("Quit MyApp", Cocoa.itemTitle(Menus.appMenu(), quit, "MyApp"),
                "the shipped menu titled its Quit item with the application name, and only the "
                        + "platform knows the name");
    }

    @Test
    void anApplicationMenuItemOfItsOwnKeepsTheModelsTitle() {
        var about = Menus.platformItem(Platform.COPY, "About");
        var appMenu = Menus.menu(Menus.APP_MENU_TITLE, about);
        assertEquals("About", Cocoa.itemTitle(appMenu, about, "MyApp"),
                "only the Quit item of the application menu is composed");
    }

    @Test
    void aQuitItemOfAnApplicationsOwnMenuKeepsTheModelsTitle() {
        var quit = Menus.platformItem(Platform.QUIT, "退出");
        var own = Menus.menu("文件", quit);
        assertEquals("退出", Cocoa.itemTitle(own, quit, "MyApp"),
                "the application menu is the framework's; an application's own menu says what it "
                        + "says");
    }

    @Test
    void anItemWithNoTitleGetsAnEmptyOne() {
        // A separator has no title, and a null must not leave the method: the native call that
        // takes a title would be handed one.
        assertEquals("", Cocoa.itemTitle(Menus.appMenu(), Menus.separator(), "MyApp"));
    }
}
