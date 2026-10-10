package panamaview;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stages the assets a web view needs onto disk before the window opens.
 *
 * <p>WebKit's {@code loadFileURL:allowingReadAccessToURL:} needs real files, and a classpath
 * is not a filesystem — in a GraalVM native executable there is no {@code target/classes}
 * directory at all. Extracting to a temp directory is the one approach that behaves
 * identically in both modes, and it costs a few milliseconds.
 *
 * <h2>Directory listing is the awkward part</h2>
 * You cannot enumerate a classpath directory portably, and not at all in a native image. So
 * the build ships a {@code manifest.txt} next to your assets, and this class trusts it.
 *
 * <h2>The framework's own asset</h2>
 * {@link #stage} also writes {@code panamaview.js} — the client half of the bridge — into
 * the web root, taken from the framework's own jar. That is deliberate: the file is part of
 * the protocol, so it has to be the copy that matches the framework you are running. Copying
 * it into your project instead would work right up until it silently fell out of date.
 *
 * <p>{@code panamaview.js} is therefore a reserved name in the web root.
 */
public final class Resources {

    /** The client bridge, staged into the web root under this name. Reserved. */
    public static final String BRIDGE_FILE = "panamaview.js";

    private static final String BRIDGE_RESOURCE = "/panamaview/" + BRIDGE_FILE;

    private Resources() {
    }

    /**
     * Copies the assets listed in {@code /<resourceDir>/manifest.txt} into a fresh temp
     * directory, and adds the framework's client bridge alongside them.
     *
     * <p>Exposed rather than internal so an application can confirm its assets really made
     * it into a native executable without opening a window — the failure this prevents
     * ({@code IOException: missing resource: …}) happens at run time, so a successful
     * compile proves nothing about it.
     *
     * @param resourceDir classpath directory, e.g. {@code "web"}
     * @return the staged web root, to hand to {@link WebApp#load}
     */
    public static Path stage(String resourceDir) throws IOException {
        String dir = resourceDir.startsWith("/") ? resourceDir.substring(1) : resourceDir;
        if (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }

        List<String> names = readManifest(dir);
        if (names.contains(BRIDGE_FILE)) {
            throw new IOException("'" + BRIDGE_FILE + "' in /" + dir + "/manifest.txt is a "
                    + "reserved name: the framework stages its own copy of the client bridge "
                    + "there. Rename your file.");
        }

        Path target = Files.createTempDirectory("panamaview-web-");
        for (String name : names) {
            String resource = "/" + dir + "/" + name;
            Path out = target.resolve(name).normalize();
            if (!out.startsWith(target)) {
                throw new IOException("manifest entry escapes the target directory: " + name);
            }
            try (InputStream in = Resources.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("missing resource: " + resource);
                }
                Files.createDirectories(out.getParent());
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        stageBridge(target);
        deleteTreeOnExit(target);
        return target;
    }

    /**
     * Writes the framework's copy of the client bridge into the web root.
     *
     * <p>Missing means the framework itself is broken — most likely a native image built
     * without {@code panamaview.js} registered as a resource — so this fails loudly rather
     * than letting the page load and then fail to reach Java at all.
     */
    private static void stageBridge(Path webRoot) throws IOException {
        try (InputStream in = Resources.class.getResourceAsStream(BRIDGE_RESOURCE)) {
            if (in == null) {
                throw new IOException("the framework's client bridge is missing from the "
                        + "classpath (" + BRIDGE_RESOURCE + "). In a native image this means "
                        + "it was not registered as a resource; see the framework's "
                        + "reachability-metadata.json.");
            }
            Files.copy(in, webRoot.resolve(BRIDGE_FILE), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<String> readManifest(String dir) throws IOException {
        String manifest = "/" + dir + "/manifest.txt";
        List<String> names = new ArrayList<>();
        try (InputStream in = Resources.class.getResourceAsStream(manifest)) {
            if (in == null) {
                throw new IOException("missing resource manifest: " + manifest);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    names.add(line);
                }
            }
        }
        return names;
    }

    private static void deleteTreeOnExit(Path dir) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
            } catch (IOException ignored) {
                // best effort
            }
        }, "panamaview-resource-cleanup"));
    }
}
