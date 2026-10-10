package panamaview;

import java.nio.file.Path;

/**
 * Everything the platform host needs in order to put a window on screen.
 *
 * <p>Paths, not resources: the classpath copy has already been staged on disk by
 * {@link Resources} before this is built, because WebKit's file loading — and a native
 * executable, which has no classpath directory at all — both need real files.
 *
 * @param entry   the file to load, e.g. {@code …/web/index.html}
 * @param webRoot the directory to grant the page read access to, so sibling assets
 *                ({@code app.js}, {@code style.css}) resolve
 */
public record WindowSpec(String title, int width, int height, Path entry, Path webRoot) {
}
