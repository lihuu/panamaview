package panamaview.macos;

import panamaview.App;

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

    private static double number(Map<String, Object> params, String key, double fallback) {
        Object value = params.get(key);
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private Main() {
    }
}
