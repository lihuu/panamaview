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
 * Stages the bundled web assets on disk so {@code WKWebView} can load them.
 *
 * <p>WebKit's {@code loadFileURL:allowingReadAccessToURL:} needs real files, and a
 * classpath is not a filesystem — in a GraalVM native executable there is no
 * {@code target/classes} directory at all. Extracting to a temp directory is the one
 * approach that behaves identically in both modes, and it costs a few milliseconds.
 *
 * <p>Directory listing is the awkward part: you cannot enumerate a classpath directory
 * portably (and not at all in a native image). So the build ships a {@code manifest.txt}
 * next to the assets, and this class trusts it.
 */
public final class Resources {

    private Resources() {
    }

    /**
     * Copies every file listed in {@code /<resourceDir>/manifest.txt} into a fresh
     * temp directory.
     *
     * @param resourceDir classpath directory, e.g. {@code "web"}
     * @return the directory holding the extracted assets
     */
    public static Path extract(String resourceDir) throws IOException {
        String dir = resourceDir.startsWith("/") ? resourceDir.substring(1) : resourceDir;
        if (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }

        List<String> names = new ArrayList<>();
        String manifest = "/" + dir + "/manifest.txt";
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

        deleteTreeOnExit(target);
        return target;
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
