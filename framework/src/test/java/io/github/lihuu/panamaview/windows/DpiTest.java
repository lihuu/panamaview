package io.github.lihuu.panamaview.windows;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The conversion from logical to physical window size.
 *
 * <p>This is the arithmetic behind the Windows scaling fix. Before it, a window asked for as
 * 1180×760 was created at 96 DPI in a process Windows considered DPI-unaware, so the system
 * bitmap-scaled the whole window: on a 200% display a normal-sized window covered most of a 4K
 * screen and every glyph was drawn at half resolution. The window is now created in the pixels
 * the monitor actually has.
 */
class DpiTest {

    @Test
    void leavesTheBaseDpiAlone() {
        assertEquals(1180, Dpi.scale(1180, 96));
        assertEquals(760, Dpi.scale(760, 96));
    }

    @Test
    void doublesAtTwoHundredPercent() {
        assertEquals(2360, Dpi.scale(1180, 192));
        assertEquals(1520, Dpi.scale(760, 192));
    }

    @Test
    void roundsAtOneHundredFiftyPercent() {
        assertEquals(1770, Dpi.scale(1180, 144));
        assertEquals(1140, Dpi.scale(760, 144));
    }

    @Test
    void roundsToTheNearestPixel() {
        assertEquals(101, Dpi.scale(100, 97));
        assertEquals(100, Dpi.scale(100, 96));
    }

    @Test
    void treatsAnUnavailableDpiAsTheBase() {
        assertEquals(1180, Dpi.scale(1180, 0));
        assertEquals(1180, Dpi.scale(1180, -1));
    }
}
