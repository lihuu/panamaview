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

        // Every asset the built page asks for must actually be staged.
        //
        // This is the check that earns its keep in a native build. The page and its bundle are
        // embedded as resources; the build config that embeds them is a command-line flag that
        // nothing type-checks. Miss it and the app compiles, links, starts, and then shows a
        // blank window — because the HTML loaded and the script did not.
        String html = Files.readString(webRoot.resolve("index.html"));
        // Only real asset references: src= on anything, and href= on <link>. Deliberately not
        // every href — <base href="/"> and <a href="/about"> are not files, and treating them as
        // files fails on the directory case and lies about the anchor case.
        var references = java.util.regex.Pattern
                .compile("src=\"([^\"]+)\"|<link\\b[^>]*?href=\"([^\"]+)\"", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(html);
        int checked = 0;
        while (references.find()) {
            String reference = references.group(1) != null ? references.group(1) : references.group(2);
            if (reference.startsWith("http:") || reference.startsWith("https:")
                    || reference.startsWith("data:") || reference.startsWith("#")
                    || reference.startsWith("mailto:")) {
                continue;
            }
            Path target = webRoot.resolve(reference.startsWith("/")
                    ? reference.substring(1) : reference).normalize();
            if (!Files.isRegularFile(target)) {
                throw new IllegalStateException("index.html references " + reference
                        + " but it was not staged. Add it to the manifest, and make sure the "
                        + "native build embeds it.");
            }
            checked++;
        }
        if (checked == 0) {
            throw new IllegalStateException("index.html references no local assets at all, which "
                    + "means the frontend build produced nothing the page can use");
        }
        System.out.println("[check] all " + checked + " asset reference(s) staged");
        System.out.println("[check] ok");
    }

    private Main() {
    }
}
