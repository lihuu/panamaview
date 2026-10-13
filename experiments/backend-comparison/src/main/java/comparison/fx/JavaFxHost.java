package comparison.fx;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import netscape.javascript.JSObject;
import panamaview.PageSource;
import panamaview.UiHost;
import panamaview.WindowSpec;

/** Experimental optional UiHost. No changes to core WebApp, Bridge or client protocol. */
public final class JavaFxHost implements UiHost {
    private static JavaFxHost active;
    private Callbacks callbacks;
    private WindowSpec spec;
    private WebEngine engine;
    private Stage stage;
    private HttpServer server;
    private ExecutorService serving;
    private Transport transport; // JSObject.setMember uses weak references.
    private long generation;

    // Expose the existing WK-style message-handler shape, with an early-message queue.
    private static final String BOOTSTRAP = """
            <script>
            window.__pvFxPending=[];
            window.webkit={messageHandlers:{java:{postMessage:function(body){
              if(window.__pvFxTransport) window.__pvFxTransport.postMessage(body);
              else window.__pvFxPending.push(body);
            }}}};
            </script>
            """;

    @Override public void start(Callbacks callbacks, WindowSpec spec) {
        if (active != null) throw new IllegalStateException("One JavaFX application per process");
        this.callbacks = callbacks;
        this.spec = spec;
        active = this;
        try {
            Runnable launch = () -> Application.launch(HostApplication.class);
            if ("runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
                MacNativeBootstrap.run(launch);
            } else launch.run();
        } catch (Throwable failure) { throw new IllegalStateException("JavaFX host failed", failure); }
        finally { cleanup(); }
    }

    public static final class HostApplication extends Application {
        @Override public void start(Stage stage) throws Exception { active.open(stage); }
        @Override public void stop() { active.cleanup(); }
    }

    private void open(Stage window) throws IOException {
        if (!(spec.page() instanceof PageSource.FromAssets assets)) {
            throw new UnsupportedOperationException("Experimental host currently supports staged assets only");
        }
        stage = window;
        String url = serve(assets);
        WebView view = new WebView();
        engine = view.getEngine();
        stage.setTitle(spec.title());
        stage.setScene(new Scene(view, spec.width(), spec.height()));
        Platform.setImplicitExit(callbacks.shouldQuitWhenLastWindowCloses());
        stage.setOnCloseRequest(event -> { callbacks.onPageUnloading(); Platform.exit(); });
        engine.getLoadWorker().stateProperty().addListener((observable, before, after) -> {
            if (after == Worker.State.SCHEDULED) {
                generation++;
                transport = null;
                callbacks.onPageUnloading();
            } else if (after == Worker.State.SUCCEEDED) {
                transport = new Transport(this, generation);
                JSObject windowObject = (JSObject) engine.executeScript("window");
                windowObject.setMember("__pvFxTransport", transport);
                engine.executeScript("(window.__pvFxPending||[]).splice(0).forEach(function(body){window.__pvFxTransport.postMessage(body);})");
                callbacks.onPageLoaded(engine.getLocation(), engine.getTitle());
            } else if (after == Worker.State.FAILED) {
                callbacks.onPageFailed(String.valueOf(engine.getLoadWorker().getException()));
                close();
            }
        });
        stage.show();
        engine.load(url);
    }

    public static final class Transport {
        private final JavaFxHost owner;
        private final long generation;
        Transport(JavaFxHost owner, long generation) { this.owner = owner; this.generation = generation; }
        public void postMessage(String json) {
            // Defer even UI-thread calls: retain the asynchronous WK/WebView2 transport semantics.
            Platform.runLater(() -> {
                if (owner.transport == this && owner.generation == generation) owner.callbacks.onMessage(json);
            });
        }
    }

    @Override public void evaluate(String script) { engine.executeScript(script); }
    @Override public void onUiThread(Runnable action) { Platform.runLater(action); }
    @Override public boolean isUiThread() { return Platform.isFxApplicationThread(); }

    public void close() {
        stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
        stage.close();
    }

    private String serve(PageSource.FromAssets assets) throws IOException {
        Path root = assets.root().toRealPath();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serving = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serving);
        server.createContext("/", exchange -> {
            try (exchange) {
                String method = exchange.getRequestMethod();
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    exchange.sendResponseHeaders(405, -1); return;
                }
                String path = exchange.getRequestURI().getPath();
                Path file = root.resolve(path.substring(1)).normalize();
                if (!file.startsWith(root)) { exchange.sendResponseHeaders(403, -1); return; }
                if (!Files.isRegularFile(file) && assets.spaFallback()
                        && !file.getFileName().toString().contains(".")) file = root.resolve(assets.entry());
                if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(root)) {
                    exchange.sendResponseHeaders(404, -1); return;
                }
                byte[] bytes = Files.readAllBytes(file);
                String type = mime(file);
                if (type.startsWith("text/html")) {
                    String html = new String(bytes, StandardCharsets.UTF_8);
                    int head = html.toLowerCase(Locale.ROOT).indexOf("<head>");
                    html = head >= 0 ? html.substring(0, head + 6) + BOOTSTRAP + html.substring(head + 6)
                            : BOOTSTRAP + html;
                    bytes = html.getBytes(StandardCharsets.UTF_8);
                }
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(200, method.equals("HEAD") ? -1 : bytes.length);
                if (!method.equals("HEAD")) exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        System.out.println("[javafx-host] assets served over loopback; core protocol unchanged");
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + assets.entry();
    }

    private static String mime(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js") || name.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    private void cleanup() {
        transport = null;
        if (callbacks != null) callbacks.onPageUnloading();
        if (server != null) { server.stop(0); server = null; }
        if (serving != null) { serving.close(); serving = null; }
    }
}
