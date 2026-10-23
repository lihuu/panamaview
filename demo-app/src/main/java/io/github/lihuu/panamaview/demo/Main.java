package io.github.lihuu.panamaview.demo;

import io.github.lihuu.panamaview.Resources;
import io.github.lihuu.panamaview.macos.MacOsHost;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
        // The menu's activation is the platform's own call, so the portable half of the demo
        // takes it as a port and this is where it is supplied.
        App app = new App(host, id -> host.clickMenuItem(id)).install();

        app.web()
                .bindUi("geometry", params -> host.geometry())
                .bindUi("resize", params -> host.resize(
                        number(params, "width", 1000),
                        number(params, "height", 700)))
                .bindUi("snapshot", params -> host.snapshot(
                        String.valueOf(params.getOrDefault("path", "/tmp/panamaview.png"))))
                // Both of these run on a virtual thread, like every ordinary binding, and both
                // answer with facts the page cannot know on its own: whether this process is a
                // .app at all, and what the user decided when the system asked.
                .bind("notificationInfo", params -> notificationInfo(host))
                .bind("notify", params -> notify(host, params))
                // The clipboard, from the program's side. The page can also use
                // navigator.clipboard, but only while the user's click is still fresh; behind an
                // await it is refused. This binding has no such rule.
                .bind("copyText", params -> copyText(host, params))
                .bind("clipboard", params -> clipboard(host));

        app.web().run();
    }

    /** What the page needs to know before offering the affordance. */
    private static Map<String, Object> notificationInfo(MacOsHost host) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("available", host.canNotify());
        info.put("bundleIdentifier", host.bundleIdentifier());
        return info;
    }

    /**
     * Asks the system, waits for the answer on a virtual thread, and posts if the user agreed.
     *
     * <p>The wait is the interesting part: the permission callback arrives on the UI thread, so
     * this handler blocks a virtual thread rather than the window.
     */
    private static Map<String, Object> notify(MacOsHost host, Map<String, Object> params) throws Exception {
        String title = String.valueOf(params.getOrDefault("title", "panamaview"));
        String body = String.valueOf(params.getOrDefault("body", "Hello from the Java side"));

        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        host.requestNotificationPermission(answer::complete);
        boolean granted = answer.get(30, TimeUnit.SECONDS);

        if (granted) {
            host.notify(title, body);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("granted", granted);
        result.put("posted", granted);
        return result;
    }

    private static Map<String, Object> copyText(MacOsHost host, Map<String, Object> params) {
        String text = String.valueOf(params.getOrDefault("text", ""));
        host.writeClipboard(text);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("copied", text.length());
        result.put("changeCount", host.clipboardChangeCount());
        return result;
    }

    private static Map<String, Object> clipboard(MacOsHost host) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("text", host.readClipboard());
        result.put("changeCount", host.clipboardChangeCount());
        return result;
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
