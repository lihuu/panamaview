package panamaview.macos;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;

/**
 * A {@code WKWebView} hosted in the process' own window — the system WebKit, not a
 * bundled browser engine.
 *
 * <p>The interesting part is that none of this needs a subclass. Instead of writing an
 * Objective-C class that implements {@code WKScriptMessageHandler}, the PoC creates one
 * at runtime whose implementations are Java upcall stubs (see {@link ObjCDispatch}).
 */
final class WebView {

    /** The name JavaScript uses: {@code window.webkit.messageHandlers.java}. */
    public static final String MESSAGE_HANDLER_NAME = "java";

    private final MemorySegment handle;

    private WebView(MemorySegment handle) {
        this.handle = handle;
    }

    public MemorySegment handle() {
        return handle;
    }

    /**
     * @param dispatch the runtime-created object acting as script message handler and
     *                 navigation delegate
     */
    public static WebView create(ObjC.Scope scope, double width, double height,
                                 MemorySegment dispatch, AssetScheme assets) {
        MemorySegment configuration = ObjC.newObject("WKWebViewConfiguration");

        // Registered before the web view exists: the configuration is where a scheme handler
        // lives, and a view cannot be given one after it has been created.
        if (assets != null) {
            MemorySegment handler = AssetSchemeHandler.create(assets);
            ObjC.sendVoid(configuration, ObjC.selector("setURLSchemeHandler:forURLScheme:"),
                    handler, ObjC.nsString(scope, AssetSchemeHandler.schemeName()));
        }

        MemorySegment contentController = ObjC.send(configuration, ObjC.selector("userContentController"));
        ObjC.sendVoid(contentController, ObjC.selector("addScriptMessageHandler:name:"),
                dispatch, ObjC.nsString(scope, MESSAGE_HANDLER_NAME));

        MemorySegment webView = ObjC.sendRectObject(
                ObjC.send(ObjC.classNamed("WKWebView"), ObjC.selector("alloc")),
                ObjC.selector("initWithFrame:configuration:"),
                scope.rect(0, 0, width, height),
                configuration);

        ObjC.sendVoid(webView, ObjC.selector("setNavigationDelegate:"), dispatch);
        return new WebView(webView);
    }

    /** Loads a page served over the network, e.g. a frontend dev server, or a private scheme. */
    public void loadUrl(ObjC.Scope scope, java.net.URI url) {
        MemorySegment nsUrl = ObjC.send(ObjC.classNamed("NSURL"), ObjC.selector("URLWithString:"),
                ObjC.nsString(scope, url.toString()));
        if (ObjC.isNull(nsUrl)) {
            throw new IllegalArgumentException("not a URL WebKit will accept: " + url);
        }
        ObjC.send(handle, ObjC.selector("loadRequest:"),
                ObjC.send(ObjC.classNamed("NSURLRequest"), ObjC.selector("requestWithURL:"), nsUrl));
    }

    /** Re-fetches the current page. On a file URL this re-reads from disk. */
    public void reload() {
        ObjC.send(handle, ObjC.selector("reload"));
    }

    /**
     * Runs {@code script} in the page. Must be called on the main thread.
     *
     * <p>The completion handler is passed as NULL: the reply travels back over the
     * message handler instead, which keeps one path for Java→JS and avoids having to
     * build an Objective-C block.
     */
    public void evaluateJavaScript(ObjC.Scope scope, String script) {
        ObjC.sendVoid(handle, ObjC.selector("evaluateJavaScript:completionHandler:"),
                ObjC.nsString(scope, script), MemorySegment.NULL);
    }

    /** The page's current URL, useful as evidence that a load really happened. */
    public String currentUrl() {
        MemorySegment url = ObjC.send(handle, ObjC.selector("URL"));
        if (ObjC.isNull(url)) {
            return null;
        }
        return ObjC.toJavaString(ObjC.send(url, ObjC.selector("absoluteString")));
    }

    public String pageTitle() {
        return ObjC.toJavaString(ObjC.send(handle, ObjC.selector("title")));
    }

    // -------------------------------------------------------------- geometry

    /**
     * The view's frame as {@code [x, y, width, height]}.
     *
     * <p>This is {@code [view frame]}, which returns an {@code NSRect} <em>by value</em> —
     * the one marshalling case that a naive binding gets wrong, because it needs the
     * struct returned through registers/memory exactly as the platform ABI dictates.
     */
    public double[] frame(ObjC.Scope scope) {
        MemorySegment rect = ObjC.sendRect(scope, handle, ObjC.selector("frame"));
        return new double[]{
                rect.get(JAVA_DOUBLE, 0),
                rect.get(JAVA_DOUBLE, 8),
                rect.get(JAVA_DOUBLE, 16),
                rect.get(JAVA_DOUBLE, 24)};
    }

    /** {@code [view setFrame:]} — an {@code NSRect} passed <em>by value</em> in the other direction. */
    public void setFrame(ObjC.Scope scope, double x, double y, double width, double height) {
        ObjC.sendVoidRect(handle, ObjC.selector("setFrame:"), scope.rect(x, y, width, height));
    }

    /** {@code [[view window] windowNumber]} — an {@code NSInteger}, i.e. a 64-bit scalar. */
    public long windowNumber() {
        return ObjC.sendLong(ObjC.send(handle, ObjC.selector("window")),
                ObjC.selector("windowNumber"));
    }

    // -------------------------------------------------------------- snapshot

    private static final FunctionDescriptor SNAPSHOT_HANDLER =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);

    /** Where the in-flight snapshot should land. The completion handler is static. */
    private static volatile String snapshotPath;

    /**
     * Renders the page to a PNG with WebKit's own snapshot API.
     *
     * <p>{@code takeSnapshotWithConfiguration:completionHandler:} is the reason
     * {@link NativeBlock} exists: the completion handler is an Objective-C block whose
     * body is a Java method, so the finished image arrives as an ordinary Java callback
     * with no delegate object anywhere in sight.
     */
    public void snapshotToPng(String path) {
        snapshotPath = path;
        MemorySegment configuration = ObjC.newObject("WKSnapshotConfiguration");
        ObjC.sendVoidBool(configuration, ObjC.selector("setAfterScreenUpdates:"), true);
        ObjC.sendVoid(handle, ObjC.selector("takeSnapshotWithConfiguration:completionHandler:"),
                configuration,
                NativeBlock.of(WebView.class, "handleSnapshot", SNAPSHOT_HANDLER));
    }

    public static void handleSnapshot(MemorySegment block, MemorySegment image, MemorySegment error) {
        String path = snapshotPath;
        try (ObjC.Scope scope = ObjC.scope()) {
            if (ObjC.isNull(image)) {
                System.err.println("[panamaview] snapshot failed: " + describe(error));
                return;
            }
            MemorySegment tiff = ObjC.send(image, ObjC.selector("TIFFRepresentation"));
            MemorySegment bitmap = ObjC.send(ObjC.classNamed("NSBitmapImageRep"),
                    ObjC.selector("imageRepWithData:"), tiff);
            MemorySegment png = ObjC.sendLongObject(bitmap,
                    ObjC.selector("representationUsingType:properties:"),
                    4L, // NSBitmapImageFileTypePNG
                    ObjC.send(ObjC.classNamed("NSDictionary"), ObjC.selector("dictionary")));
            boolean written = ObjC.sendBool(png, ObjC.selector("writeToFile:atomically:"),
                    ObjC.nsString(scope, path), true);
            System.out.println("[panamaview] snapshot " + (written ? "written to " : "FAILED for ") + path);
        } catch (Throwable t) {
            System.err.println("[panamaview] snapshot error: " + t);
            t.printStackTrace(System.err);
        }
    }

    private static String describe(MemorySegment error) {
        return ObjC.isNull(error)
                ? "no error reported"
                : ObjC.toJavaString(ObjC.send(error, ObjC.selector("localizedDescription")));
    }
}
