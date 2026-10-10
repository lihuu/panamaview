package panamaview.demo;

import panamaview.Resources;
import panamaview.macos.MacOsHost;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The macOS entry point: the composition root, and the one place allowed to know both the
 * portable application and the platform.
 *
 * <p>It does two things. It pairs the application with a {@link MacOsHost}, and it registers
 * the operations only this platform can offer. Those extras go through {@code bindUi} because
 * they touch the window, and window operations are only legal on the UI thread — a resize or
 * a snapshot performed from a virtual thread is a bug, not a slow handler.
 *
 * <p>A Windows or Linux port adds a sibling class in its own package and changes nothing above
 * it: the application, the bridge protocol and the resource staging are all already portable.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        // Headless mode, for machines with no window server. It proves the two things a
        // consumer gets wrong: that the framework resolves, and that the assets it is owed
        // are actually on the classpath. MacOsHost is never loaded — do not construct it
        // before this check, or this stops working on CI.
        if (Boolean.getBoolean("panamaview.check")) {
            check();
            return;
        }

        MacOsHost host = new MacOsHost();
        App app = new App(host).install();

        app.web()
                .bindUi("geometry", params -> host.geometry())
                .bindUi("resize", params -> host.resize(
                        number(params, "width", 1000),
                        number(params, "height", 700)))
                .bindUi("snapshot", params -> host.snapshot(
                        String.valueOf(params.getOrDefault("path", "/tmp/panamaview.png"))));

        app.web().run();
    }

    /** Stages the assets and confirms the pieces a page cannot work without are there. */
    private static void check() throws Exception {
        Path webRoot = Resources.stage("/web");
        System.out.println("[check] web root: " + webRoot);

        for (String required : new String[]{"index.html", "app.js", "style.css", "blank.html"}) {
            Path file = webRoot.resolve(required);
            if (!Files.exists(file) || Files.size(file) == 0) {
                throw new IllegalStateException("not staged: " + required);
            }
            System.out.println("[check] staged " + required + " (" + Files.size(file) + " bytes)");
        }

        // The bridge comes from the framework, not from our assets. If it is missing, the
        // framework's own jar is not on the classpath and every page would silently have no
        // java.invoke — the failure this check exists to catch.
        Path bridge = webRoot.resolve("panamaview.js");
        if (!Files.exists(bridge) || Files.size(bridge) == 0) {
            throw new IllegalStateException("the framework's client bridge was not staged: " + bridge);
        }
        System.out.println("[check] staged panamaview.js (" + Files.size(bridge) + " bytes)");

        System.out.println("[check] ok");
    }

    private static double number(Map<String, Object> params, String key, double fallback) {
        Object value = params.get(key);
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private Main() {
    }
}
