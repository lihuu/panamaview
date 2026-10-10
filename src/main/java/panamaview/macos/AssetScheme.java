package panamaview.macos;

import java.nio.file.Path;

/**
 * A private URL scheme that serves the app's own assets, so the page is a normal web page.
 *
 * <p>Loading the page over {@code file://} looks like the obvious thing and is a trap. A
 * {@code file://} origin is opaque, and anything that goes through the fetch machinery is then
 * refused:
 *
 * <ul>
 *   <li><b>ES modules</b> — a module script is always fetched in CORS mode, so it never loads.
 *       Vite, and every other modern bundler, emits modules by default.</li>
 *   <li><b>{@code fetch()} and {@code XMLHttpRequest}</b> — refused outright.</li>
 *   <li><b>Web Workers</b> — same-origin is required, and an opaque origin has no origin.</li>
 * </ul>
 *
 * <p>Plain {@code <script src>} keeps working because it is fetched in no-cors mode, which is
 * exactly why this goes unnoticed in a hand-written page and then bites in a bundled one.
 *
 * <p>Registering a scheme handler gives the page an origin it actually owns —
 * {@code panamaview://app} — and every one of those capabilities starts working. This is what
 * Tauri and Electron do; neither uses {@code file://} for the app's own content.
 *
 * @param name        the scheme, without {@code ://}. Lowercase letters, digits, {@code +},
 *                    {@code -} and {@code .} only, as URL schemes require.
 * @param root        the directory to serve. Requests are resolved inside it and nowhere else.
 * @param entry       document to serve for client-side routes, relative to {@code root}
 * @param spaFallback when a path with no file extension matches nothing, serve {@code entry}
 *                    instead of a 404. See {@link panamaview.PageSource.FromAssets} for why the
 *                    extension check is the point rather than a detail.
 */
record AssetScheme(String name, Path root, String entry, boolean spaFallback) {
}
