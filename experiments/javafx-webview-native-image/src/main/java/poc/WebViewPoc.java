package poc;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Timer;
import java.util.TimerTask;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import netscape.javascript.JSObject;

public final class WebViewPoc extends Application {
    static volatile int exitCode = 1;
    private final Bridge bridge = new Bridge(); // JSObject bindings are weak: own this object.
    private final Timer watchdog = new Timer("poc-watchdog", true);
    private Path assets;
    private WebEngine engine;
    private JSObject window;
    private Stage stage;
    private boolean closeRequested;

    public static final class Bridge {
        private int calls;
        public String echo(String message) {
            if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Wrong callback thread");
            calls++;
            System.out.println("[poc] javascript-to-java message=" + message);
            return "java:" + message;
        }
    }

    @Override public void start(Stage primaryStage) throws Exception {
        System.out.println("[poc] environment java=" + System.getProperty("java.version")
                + " javafx=" + System.getProperty("javafx.runtime.version")
                + " image=" + System.getProperty("org.graalvm.nativeimage.imagecode", "jvm")
                + " os=" + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
        watchdog.schedule(new TimerTask() {
            @Override public void run() {
                System.err.println("[poc] FAIL timeout after 30s");
                System.exit(2);
            }
        }, 30_000);
        assets = Files.createTempDirectory("javafx-webview-poc-");
        for (String name : new String[]{"index.html", "style.css", "app.js"}) {
            try (var input = WebViewPoc.class.getResourceAsStream("/web/" + name)) {
                if (input == null) throw new IOException("Missing resource: " + name);
                Files.copy(input, assets.resolve(name));
            }
        }
        System.out.println("[poc] assets=" + assets);
        stage = primaryStage;
        WebView view = new WebView();
        engine = view.getEngine();
        stage.setScene(new Scene(view, 780, 480));
        stage.setTitle("JavaFX WebView Native Image PoC");
        stage.setOnCloseRequest(event -> {
            closeRequested = true;
            System.out.println("[poc] close-request");
            Platform.exit();
        });
        engine.getLoadWorker().stateProperty().addListener((observable, before, after) -> {
            if (after == Worker.State.SUCCEEDED && engine.getLocation().endsWith("index.html")) {
                try { verify(); } catch (Throwable failure) { fail(failure); }
            } else if (after == Worker.State.FAILED) {
                fail(new IllegalStateException("Page failed", engine.getLoadWorker().getException()));
            }
        });
        stage.show();
        System.out.println("[poc] PASS stage-showing=" + stage.isShowing());
        engine.load(assets.resolve("index.html").toUri().toString());
    }

    private void verify() {
        check("local-html", "LOCAL_HTML_OK", engine.executeScript("document.getElementById('marker').textContent"));
        check("external-script", "loaded", engine.executeScript("window.scriptLoaded"));
        check("external-css", "rgb(18, 32, 47)", engine.executeScript("getComputedStyle(document.body).backgroundColor"));
        window = (JSObject) engine.executeScript("window");
        window.setMember("javaBridge", bridge);
        check("java-to-javascript", "js:java-nonce-42", engine.executeScript("window.fromJava('java-nonce-42')"));
        check("javascript-to-java-return", "java:js-nonce-73", engine.executeScript("window.toJava('js-nonce-73')"));
        check("java-callback-count", 1, bridge.calls);
        PauseTransition settle = new PauseTransition(Duration.millis(Long.getLong("poc.closeDelayMillis", 1000)));
        settle.setOnFinished(event -> {
            try {
                check("rendered-page-state", "java:js-nonce-73", engine.executeScript("document.getElementById('bridge').textContent"));
                saveSnapshot(stage.getScene().snapshot(null));
                exitCode = 0;
                if (Boolean.getBoolean("poc.keepOpen")) {
                    System.out.println("[poc] ready-for-manual-close");
                    watchdog.cancel();
                } else {
                    stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
                    stage.close();
                }
            } catch (Throwable failure) { fail(failure); }
        });
        settle.play();
    }

    private static void check(String name, Object expected, Object actual) {
        if (!expected.equals(actual)) throw new IllegalStateException(name + ": expected=" + expected + " actual=" + actual);
        System.out.println("[poc] PASS " + name + "=" + actual);
    }

    private void fail(Throwable failure) {
        exitCode = 1;
        failure.printStackTrace();
        Platform.exit();
    }

    private static void saveSnapshot(WritableImage image) throws IOException {
        String output = System.getProperty("poc.snapshot");
        if (output == null) return;
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        try (var stream = new BufferedOutputStream(Files.newOutputStream(Path.of(output)))) {
            stream.write(("P6\n" + width + " " + height + "\n255\n").getBytes(StandardCharsets.US_ASCII));
            var pixels = image.getPixelReader();
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int argb = pixels.getArgb(x, y);
                stream.write((argb >> 16) & 255);
                stream.write((argb >> 8) & 255);
                stream.write(argb & 255);
            }
        }
        System.out.println("[poc] PASS snapshot=" + output + " pixels=" + width + "x" + height);
    }

    @Override public void stop() throws Exception {
        watchdog.cancel();
        if (window != null) window.removeMember("javaBridge");
        window = null;
        if (engine != null) {
            engine.getLoadWorker().cancel();
            engine.load("about:blank");
        }
        if (assets != null) {
            try (var paths = Files.walk(assets)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
            System.out.println("[poc] PASS assets-removed=" + !Files.exists(assets));
        }
        if (exitCode == 0 && !closeRequested) exitCode = 1;
        System.out.println("[poc] stop closeRequested=" + closeRequested + " exit=" + exitCode);
    }
}
