package com.example;

import panamaview.Resources;
import panamaview.WebApp;
import panamaview.macos.MacOsHost;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A panamaview application with a React frontend.
 *
 * <p>Two halves that are built separately: the React app under {@code frontend/}, compiled by
 * Vite into {@code src/main/resources/web/}, and this Java program, which puts the result in a
 * window and answers calls from it.
 *
 * <ul>
 *   <li>{@code ./dev.sh} — the frontend dev server, with hot module replacement.</li>
 *   <li>{@code ./run.sh} — a production build, loaded from disk.</li>
 * </ul>
 */
public final class Main {

    /** Set by dev.sh to the dev server's address; unset in every other mode. */
    private static final String DEV_PROPERTY = "panamaview.dev";

    public static void main(String[] args) throws Exception {
        // CI has no window server. This stages the assets and reports what it found, without
        // opening anything — worth keeping, because "an asset is missing" is a *runtime*
        // failure, so a successful compile says nothing about it.
        if (Boolean.getBoolean("panamaview.check")) {
            check();
            return;
        }

        MacOsHost host = new MacOsHost();

        WebApp app = new WebApp(host)
                .title("My App")
                .size(900, 620)
                .load("/web/index.html")

                // Handlers run on a virtual thread: they may take their time without freezing
                // the window. A 30 s deadline applies by default.
                .bind("greet", params -> "Hello " + params.getOrDefault("name", "world"))

                .onReady(a -> a.postToJs(Map.of("message", "ready", "from", "Java")));

        // One flag switches between the dev server and the built assets. Nothing else changes:
        // the same code, the same handlers, the same bridge — only what the page is.
        String dev = System.getProperty(DEV_PROPERTY);
        if (dev != null) {
            app.dev(URI.create(dev));
        }

        app.run();
    }

    /** Stages the assets and confirms the pieces a page cannot work without are there. */
    private static void check() throws Exception {
        Path webRoot = Resources.stage("/web");
        System.out.println("[check] web root: " + webRoot);

        for (String required : new String[]{"index.html", "panamaview.js"}) {
            Path file = webRoot.resolve(required);
            if (!Files.exists(file) || Files.size(file) == 0) {
                throw new IllegalStateException("not staged: " + required);
            }
            System.out.println("[check] staged " + required + " (" + Files.size(file) + " bytes)");
        }

        // The bundle is inlined into the HTML, so the page must not be asking for any asset the
        // build did not produce. A reference to an absolute path here would work on the dev
        // server and silently fail from disk.
        String html = Files.readString(webRoot.resolve("index.html"));
        if (html.contains("src=\"/")) {
            throw new IllegalStateException(
                    "index.html references an absolute path, which will not resolve when the page "
                    + "is loaded from disk. Check base: \"./\" in vite.config.js.");
        }
        System.out.println("[check] index.html has no absolute asset references");
        System.out.println("[check] ok");
    }

    private Main() {
    }
}
