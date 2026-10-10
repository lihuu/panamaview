package com.example;

import panamaview.Resources;
import panamaview.WebApp;
import panamaview.macos.MacOsHost;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A panamaview application.
 *
 * <p>Two halves: this Java process, and the page it puts in a window. They talk over a JSON
 * bridge — {@code java.invoke(…)} from the page, {@link WebApp#postToJs} from here.
 *
 * <p>Run it with {@code ./run.sh} (or {@code mvn compile exec:exec}).
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        // CI has no window server. This stages the assets and reports what it found, without
        // opening anything — worth keeping, because "an asset is missing" is a *runtime*
        // failure, so a successful compile says nothing about it.
        if (Boolean.getBoolean("panamaview.check")) {
            check();
            return;
        }

        MacOsHost host = new MacOsHost();

        new WebApp(host)
                .title("My App")
                .size(900, 620)
                .load("/web/index.html")

                // Handlers run on a virtual thread: they may take their time without freezing
                // the window. A 30 s deadline applies by default; give a handler its own with
                // bind(name, fn, Duration.ofMinutes(10)), or null for none at all.
                .bind("greet", params -> "Hello " + params.getOrDefault("name", "world"))

                // Fired once the page is ready to receive it — calling into the page before
                // then is silently lost.
                .onReady(app -> app.postToJs(Map.of("message", "ready", "from", "Java")))

                .run();
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
        System.out.println("[check] ok");
    }

    private Main() {
    }
}
