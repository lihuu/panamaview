package panamaview;

import java.net.URI;
import java.nio.file.Path;

/**
 * Where the page comes from.
 *
 * <p>Two cases, because there genuinely are two: assets on disk, served by the platform over a
 * private URL scheme so the page has an origin of its own; or a URL served by a frontend dev
 * server.
 *
 * <p>The two are not interchangeable, which is why they are modelled as a sealed pair rather
 * than with a nullable field. One needs an asset root and a scheme to serve it from; the other
 * needs neither, because the dev server is already serving everything.
 */
public sealed interface PageSource {

    /**
     * Assets in a directory, served by the platform over this app's own URL scheme.
     *
     * <p>Not {@code file://}, and the difference is not cosmetic. A {@code file://} origin is
     * opaque, so ES modules, {@code fetch} and Web Workers are all refused — which is to say a
     * bundled frontend cannot run. A private scheme gives the page a real origin and all three
     * work. See the macOS {@code AssetScheme} for the full account.
     *
     * <p>The scheme name is chosen here rather than by the platform so that the URL a page is
     * given is decided in one place; the platform is responsible for actually serving it.
     *
     * @param scheme the URL scheme to serve these assets under
     * @param root   the directory to serve. Requests are resolved inside it and nowhere else.
     * @param entry  file to load, relative to {@code root}
     */
    record FromAssets(String scheme, Path root, String entry) implements PageSource {

        /** The host part of the URL. Fixed: one app serves one asset root. */
        public static final String HOST = "app";

        /** e.g. {@code panamaview://app/index.html} */
        public String url() {
            String path = entry.startsWith("/") ? entry : "/" + entry;
            return scheme + "://" + HOST + path;
        }
    }

    /**
     * A page served over the network — in practice a development server
     * ({@code http://localhost:5173/}) that the platform loads verbatim.
     *
     * <p>No asset root is involved, so live reloading, hot module replacement and dev server
     * middleware all keep working the way the frontend toolchain expects.
     */
    record FromUrl(URI url) implements PageSource {
    }
}
