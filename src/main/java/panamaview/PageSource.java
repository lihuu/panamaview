package panamaview;

import java.net.URI;
import java.nio.file.Path;

/**
 * Where the page comes from.
 *
 * <p>Two cases, because there genuinely are two: a file on disk that the platform should load
 * and grant read access to a directory for, or a URL — which is what a development server
 * serves.
 *
 * <p>The two paths are not interchangeable, and that is the point of modelling them separately
 * rather than with a nullable field. A file load has a <em>read-access root</em>, without which
 * the page cannot reach its own sibling assets; a URL load has no such notion, and the assets
 * arrive over the network instead. Handing one shape of value to the wrong case is exactly the
 * kind of mistake a null would hide.
 */
public sealed interface PageSource {

    /**
     * A file to load, plus the directory the page may read from.
     *
     * <p>{@code readAccessRoot} is not optional-in-practice: WebKit resolves relative
     * {@code <script src>} and {@code <link href>} against it, and without it a page can load
     * while none of its assets can.
     */
    record FromFile(Path entry, Path readAccessRoot) implements PageSource {
    }

    /**
     * A page served over the network — in practice a development server
     * ({@code http://localhost:5173/}) that the platform loads verbatim.
     *
     * <p>No read-access root is involved, so live reloading, hot module replacement and dev
     * server middleware all keep working the way the frontend toolchain expects.
     */
    record FromUrl(URI url) implements PageSource {
    }
}
