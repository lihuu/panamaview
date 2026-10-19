package io.github.lihuu.panamaview;

/**
 * Everything the platform host needs in order to put a window on screen.
 *
 * <p>See {@link PageSource} for what the page is: a staged file plus the directory it may read,
 * or a URL served by a development server.
 *
 * <p>Two things are deliberately absent. There is no "remember where it was" flag, because the
 * platform already does that: a window opened without an {@link Origin} comes back where the user
 * left it, and there is nothing to configure. And there is no display index, because a screen
 * position is the one coordinate system that is genuinely platform-native — an index would have
 * to mean "the nth display in the platform's own enumeration order", which is exactly the sort
 * of invented abstraction this seam exists to avoid.
 *
 * @param page   where the page comes from
 * @param origin where the window opens, or {@code null} to let the platform decide — centred on
 *               the first launch, then remembered from then on
 */
public record WindowSpec(String title, int width, int height, PageSource page, Origin origin) {

    /**
     * A position on the screen, in the platform's own coordinates.
     *
     * <p>Not a portable concept, on purpose. macOS measures from the bottom-left of the primary
     * display and Windows from the top-left of the virtual screen, and displays are arranged
     * differently on every machine, so any translation this framework invented would be wrong
     * for someone. The numbers are passed through; the caller is the one who knows its users'
     * screens.
     *
     * <p>The point identifies the window's <em>frame</em>, not its content area — the title bar
     * is included, because that is what the platforms position.
     */
    public record Origin(int x, int y) {
    }

    /** A spec with no opinion about where the window opens — the ordinary case. */
    public WindowSpec(String title, int width, int height, PageSource page) {
        this(title, width, height, page, null);
    }
}
