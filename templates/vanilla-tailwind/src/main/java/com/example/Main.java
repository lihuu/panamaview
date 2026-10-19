package com.example;

import io.github.lihuu.panamaview.Resources;
import io.github.lihuu.panamaview.UiHost;
import io.github.lihuu.panamaview.WebApp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * A panamaview application.
 *
 * <p>Two halves: this Java process, and the page it puts in a window. They talk over a JSON
 * bridge — {@code java.invoke(…)} from the page, {@link WebApp#postToJs} from here.
 *
 * <p>The page is styled with Tailwind, compiled by {@code scripts/build-css.sh} into a stylesheet
 * that is generated rather than checked in — so run it with {@code ./run.sh}, not
 * {@code mvn compile exec:exec} directly, because run.sh is what builds the CSS first.
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

        UiHost host = host();


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


    /**
     * The window implementation for the platform this is running on.
     *
     * <p>Chosen at run time rather than at generation time, so one project runs on every platform
     * the framework supports. The alternative — a macOS class name baked into the source — makes
     * the project unbuildable anywhere else, which is why this exists.
     *
     * <p>Both classes are only <em>referenced</em> here, never loaded until one branch is taken, so
     * the platform that is not in use contributes nothing at run time: on Windows the macOS class
     * is never initialised, and {@code ObjC}'s dlopen of the Objective-C runtime never happens.
     * That matters — it is a static initialiser, so loading it where the library is absent would
     * fail before a single line of this method ran.
     */
    private static UiHost host() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new io.github.lihuu.panamaview.windows.WindowsHost();
        }
        if (os.contains("mac")) {
            return new io.github.lihuu.panamaview.macos.MacOsHost();
        }
        throw new IllegalStateException("panamaview has no window implementation for "
                + System.getProperty("os.name")
                + ". It builds on macOS and Windows today; the portable half of the framework is"
                + " already platform-free, so a Linux implementation is an addition here rather"
                + " than a redesign — see the repository's NOTES.");
    }

    /** Stages the assets and confirms the pieces a page cannot work without are there. */
    private static void check() throws Exception {
        Path webRoot = Resources.stage("/web");
        System.out.println("[check] web root: " + webRoot);

        // tailwind.css is on the list because it is *generated*: it does not exist in a fresh
        // checkout until scripts/build-css.sh has run, and a missing stylesheet reads as a styling
        // bug rather than as a build that never happened.
        for (String required : new String[]{"index.html", "panamaview.js", "tailwind.css"}) {
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
