package io.github.lihuu.panamaview.macos;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * Serves the app's assets over a private URL scheme, which is what gives the page a real origin.
 *
 * <p>See {@link AssetScheme} for why this exists instead of {@code file://}. The short version:
 * an opaque origin cannot load ES modules, cannot {@code fetch}, and cannot start a Worker.
 *
 * <p>Implemented, like every other Objective-C object here, by defining a class at runtime whose
 * methods are Java static methods — so this is also the answer to "can a protocol carrying
 * {@code NSData} be implemented from Java without a shim?" It can.
 *
 * <h2>Details that are easy to get wrong</h2>
 * <ul>
 *   <li><b>The response must be an {@code NSHTTPURLResponse}.</b> A plain {@code NSURLResponse}
 *       carries no status code and no headers, and WebKit has nothing to act on — the failure
 *       surfaces as an unexplained navigation error.</li>
 *   <li><b>{@code Content-Type} must be right.</b> A {@code .js} served as
 *       {@code application/octet-stream} is refused by the module loader, which is a nasty thing
 *       to debug because the request itself succeeded.</li>
 *   <li><b>Errors carry a body.</b> A bare 404 renders as a blank window and tells whoever is
 *       looking at it nothing whatsoever; naming the path in the body costs one line.</li>
 * </ul>
 *
 * <p>File bytes never pass through Java: {@code NSData} reads the file itself. Java decides
 * <em>which</em> file may be served; the platform reads it.
 */
final class AssetSchemeHandler {

    static final String CLASS_NAME = "PanamaViewSchemeHandler";

    /** {@code webView:startURLSchemeTask:} and its stop counterpart. */
    private static final FunctionDescriptor VOID_4 =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS);

    private static AssetScheme scheme;
    private static MemorySegment instance;

    private AssetSchemeHandler() {
    }

    /** Defines and instantiates the handler. Call once, on the UI thread, before the web view. */
    static MemorySegment create(AssetScheme assets) {
        scheme = assets;

        Map<String, ObjC.Method> methods = new LinkedHashMap<>();
        methods.put("webView:startURLSchemeTask:",
                new ObjC.Method("v@:@@", ObjC.upcall(AssetSchemeHandler.class, "startTask", VOID_4)));
        methods.put("webView:stopURLSchemeTask:",
                new ObjC.Method("v@:@@", ObjC.upcall(AssetSchemeHandler.class, "stopTask", VOID_4)));

        ObjC.defineClass(CLASS_NAME, "NSObject", methods);
        instance = ObjC.newObject(CLASS_NAME);
        return instance;
    }

    /** The scheme this handler is registered for. */
    static String schemeName() {
        return scheme.name();
    }

    // ------------------------------------------------------------ the protocol

    public static void startTask(MemorySegment self, MemorySegment cmd,
                                 MemorySegment webView, MemorySegment task) {
        try {
            serve(task);
        } catch (Throwable t) {
            // An exception must never escape an upcall: it aborts the process. A 500 is the
            // honest answer for "something went wrong while looking for this file".
            System.err.println("[panamaview] asset request failed: " + t);
            try {
                respondText(task, 500, "text/plain", "panamaview: asset lookup failed: " + t);
            } catch (Throwable ignored) {
                // nothing further to do
            }
        }
    }

    /**
     * WebKit cancels a load it no longer wants.
     *
     * <p>Empty on purpose: this serves from disk synchronously, so there is nothing in flight to
     * stop. A streaming implementation would need it.
     */
    public static void stopTask(MemorySegment self, MemorySegment cmd,
                                MemorySegment webView, MemorySegment task) {
    }

    private static void serve(MemorySegment task) {
        String path = requestedPath(task);
        Path file = resolve(path);

        // The check that matters. Everything is resolved inside the asset root and a request that
        // would leave it is refused, so a crafted URL cannot reach the rest of the filesystem.
        // This is the one place that decision is made.
        if (file == null) {
            System.err.println("[panamaview] refused asset path: " + path);
            respondText(task, 403, "text/plain", "panamaview: forbidden: " + path);
            return;
        }

        if (!Files.isRegularFile(file)) {
            Path fallback = spaFallbackFor(path);
            if (fallback != null) {
                respondFile(task, 200, contentType(fallback), fallback);
                return;
            }
            respondText(task, 404, "text/plain", "panamaview: no such asset: " + path);
            return;
        }

        respondFile(task, 200, contentType(file), file);
    }

    /**
     * The entry document, when a path should be handed to a client-side router.
     *
     * <p>A history-mode router owns URLs like {@code /settings}, and a reload or a deep link asks
     * for something that was never built. Every bundler's dev server answers those with the entry
     * document and lets the router sort it out.
     *
     * <p>Restricted to paths with <b>no file extension in the last segment</b>, which is the whole
     * design rather than a detail: {@code /settings} and {@code /chat/42} are routes, while
     * {@code /assets/app-9f2.js} is an asset that is missing. Answering the latter with a page of
     * HTML would turn a clear 404 into a parse error somewhere else entirely, which is worse than
     * having no fallback at all.
     *
     * @return the entry file to serve instead, or null to let the 404 stand
     */
    private static Path spaFallbackFor(String path) {
        if (!scheme.spaFallback()) {
            return null;
        }
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        if (lastSegment.contains(".")) {
            return null;
        }
        Path entry = resolve(scheme.entry());
        return entry != null && Files.isRegularFile(entry) ? entry : null;
    }

    private static String requestedPath(MemorySegment task) {
        try (ObjC.Scope scope = ObjC.scope()) {
            MemorySegment request = ObjC.send(task, ObjC.selector("request"));
            MemorySegment url = ObjC.send(request, ObjC.selector("URL"));
            String path = ObjC.toJavaString(ObjC.send(url, ObjC.selector("path")));
            return path == null || path.isEmpty() ? "/" : path;
        }
    }

    /** Null when the request would leave the asset root. */
    private static Path resolve(String path) {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        if (relative.isEmpty()) {
            relative = "index.html";
        }
        Path root = scheme.root().toAbsolutePath().normalize();
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            return null;
        }
        // A request for a directory means its index, which is what a web server would do.
        if (Files.isDirectory(resolved)) {
            resolved = resolved.resolve("index.html");
        }
        return resolved;
    }

    // ------------------------------------------------------------------ responses

    private static void respondFile(MemorySegment task, int status, String mimeType, Path file) {
        try (ObjC.Scope scope = ObjC.scope()) {
            finish(scope, task, status, mimeType,
                    ObjC.send(ObjC.classNamed("NSData"), ObjC.selector("dataWithContentsOfFile:"),
                            ObjC.nsString(scope, file.toString())));
        }
    }

    private static void respondText(MemorySegment task, int status, String mimeType, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (ObjC.Scope scope = ObjC.scope()) {
            MemorySegment buffer = scope.arena()
                    .allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, bytes);
            finish(scope, task, status, mimeType,
                    ObjC.sendObjectLong(ObjC.classNamed("NSData"),
                            ObjC.selector("dataWithBytes:length:"), buffer, bytes.length));
        }
    }

    /** The response envelope both cases share, so status and headers are assembled once. */
    private static void finish(ObjC.Scope scope, MemorySegment task, int status,
                               String mimeType, MemorySegment data) {
        MemorySegment request = ObjC.send(task, ObjC.selector("request"));
        MemorySegment url = ObjC.send(request, ObjC.selector("URL"));

        MemorySegment headers = ObjC.send(ObjC.classNamed("NSDictionary"),
                ObjC.selector("dictionaryWithObject:forKey:"),
                ObjC.nsString(scope, mimeType),
                ObjC.nsString(scope, "Content-Type"));

        MemorySegment response = ObjC.send(
                ObjC.send(ObjC.classNamed("NSHTTPURLResponse"), ObjC.selector("alloc")),
                ObjC.selector("initWithURL:statusCode:HTTPVersion:headerFields:"),
                url, status, ObjC.nsString(scope, "HTTP/1.1"), headers);

        ObjC.sendVoid(task, ObjC.selector("didReceiveResponse:"), response);
        ObjC.sendVoid(task, ObjC.selector("didReceiveData:"), data);
        ObjC.sendVoid(task, ObjC.selector("didFinish"));
    }

    /**
     * The MIME type for a file, by extension.
     *
     * <p>{@code text/javascript} is the one that actually matters: WebKit refuses to execute a
     * module whose type it does not recognise, and the failure presents as a blank page rather
     * than as a MIME error.
     */
    private static String contentType(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        return switch (extension) {
            case "html", "htm" -> "text/html";
            case "js", "mjs" -> "text/javascript";
            case "css" -> "text/css";
            case "json", "map" -> "application/json";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "ico" -> "image/x-icon";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            case "wasm" -> "application/wasm";
            case "txt" -> "text/plain";
            case "xml" -> "application/xml";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "wav" -> "audio/wav";
            case "mp3" -> "audio/mpeg";
            default -> "application/octet-stream";
        };
    }
}
