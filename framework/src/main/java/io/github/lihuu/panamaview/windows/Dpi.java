package io.github.lihuu.panamaview.windows;

/**
 * Window sizes are logical pixels — the application asks for a 1180×760 window and means the
 * size a person would measure at 96 DPI. A per-monitor-DPI-aware process is handed physical
 * pixels by Windows, so the two have to be reconciled before a window is created.
 *
 * <p>Kept apart from {@link Win32} so the arithmetic is testable everywhere: touching that class
 * binds user32, which only exists on Windows.
 */
final class Dpi {

    /** The DPI that logical applications are defined against. */
    static final int BASE = 96;

    private Dpi() {
    }

    /**
     * The physical size of a logical size at {@code dpi}.
     *
     * <p>A DPI of zero or less means the system could not be asked; returning the value
     * unchanged is the identity scale, which is the same answer 96 gives.
     */
    static int scale(int value, int dpi) {
        if (dpi <= 0) {
            return value;
        }
        return Math.round(value * (float) dpi / BASE);
    }
}
