package io.github.lihuu.panamaview;

/**
 * Everything the platform host needs in order to put a window on screen.
 *
 * <p>See {@link PageSource} for what the page is: a staged file plus the directory it may read,
 * or a URL served by a development server.
 *
 * @param page where the page comes from
 */
public record WindowSpec(String title, int width, int height, PageSource page) {
}
