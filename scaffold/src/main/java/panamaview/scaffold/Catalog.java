package panamaview.scaffold;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * What the scaffolder ships: the embedded manifest, parsed.
 *
 * <p>Loading is also the consistency check. The manifest says which templates exist and which files
 * they contain; this class says what each one is for; a mismatch between the two is a build
 * mistake, and it is caught here rather than at the moment someone tries to generate a project. So
 * every entry point that touches templates goes through {@link #load()}, including
 * {@code --selftest}.
 */
final class Catalog {

    static final String MANIFEST = "templates-manifest.txt";

    /**
     * Where the templates sit inside the jar.
     *
     * <p>Manifest paths are relative to a template ({@code vanilla/README.md}), because that is
     * what they mean and what the generated project tree mirrors. The resource they name lives
     * under this prefix. Keeping the two apart is why this method exists rather than each caller
     * concatenating: doing it by hand in two places got it wrong in both.
     */
    private static final String RESOURCE_ROOT = "templates/";

    static String resourcePath(String template, String path) {
        return RESOURCE_ROOT + template + "/" + path;
    }

    /**
     * What each template is, in the order they should be offered.
     *
     * <p>Descriptions live in code rather than in the templates because they have to be short
     * enough to read in a picker, and a template's README is the wrong shape for that. The keys,
     * though, must match the manifest exactly — {@link #load()} fails loudly on either a template
     * with no description or a description with no template.
     */
    private static final Map<String, String[]> REGISTRY = new LinkedHashMap<>();

    static {
        REGISTRY.put("vanilla", new String[]{
                "plain HTML, CSS and JS — no build step", "no"});
        REGISTRY.put("vanilla-tailwind", new String[]{
                "plain HTML styled with Tailwind", "no"});
        REGISTRY.put("react", new String[]{
                "React, built by Vite", "yes"});
        REGISTRY.put("vue", new String[]{
                "Vue 3 with a history-mode router, built by Vite", "yes"});
        REGISTRY.put("angular", new String[]{
                "Angular with a history-mode router", "yes"});
    }

    private final String version;
    private final List<Template> templates;

    private Catalog(String version, List<Template> templates) {
        this.version = version;
        this.templates = templates;
    }

    String version() {
        return version;
    }

    List<Template> templates() {
        return templates;
    }

    Template byName(String name) {
        return templates.stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    static Catalog load() throws IOException {
        String text = readResource(MANIFEST);

        String version = null;
        // Per template, the files in manifest order.
        Map<String, List<Template.Entry>> byTemplate = new LinkedHashMap<>();

        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("version ")) {
                version = line.substring("version ".length()).strip();
                continue;
            }
            // "<path>" or "<path> *" — the star means executable, the same convention tar uses.
            boolean executable = line.endsWith(" *");
            String path = executable ? line.substring(0, line.length() - 2).strip() : line;

            int slash = path.indexOf('/');
            if (slash <= 0) {
                throw new IOException("manifest entry is not <template>/<path>: " + path);
            }
            String template = path.substring(0, slash);
            String inside = path.substring(slash + 1);
            byTemplate.computeIfAbsent(template, k -> new ArrayList<>())
                    .add(new Template.Entry(inside, executable));
        }

        if (version == null) {
            throw new IOException(MANIFEST + " has no 'version' line; the scaffolder cannot report "
                    + "which framework version it generates projects for");
        }
        if (byTemplate.isEmpty()) {
            throw new IOException(MANIFEST + " lists no templates");
        }

        // A template in the manifest with no description, or a description with no template, means
        // the two sources have drifted. Both are silent-failure material: the first would offer a
        // template nobody can explain, the second would show a menu entry that generates nothing.
        var undescribed = new TreeSet<String>();
        for (String name : byTemplate.keySet()) {
            if (!REGISTRY.containsKey(name)) {
                undescribed.add(name);
            }
        }
        var missing = new TreeSet<String>();
        for (String name : REGISTRY.keySet()) {
            if (!byTemplate.containsKey(name)) {
                missing.add(name);
            }
        }
        if (!undescribed.isEmpty() || !missing.isEmpty()) {
            throw new IOException("templates and descriptions disagree"
                    + (undescribed.isEmpty() ? "" : "; templates with no description: " + undescribed)
                    + (missing.isEmpty() ? "" : "; descriptions with no template: " + missing));
        }

        List<Template> templates = new ArrayList<>();
        for (var e : REGISTRY.entrySet()) {
            String[] meta = e.getValue();
            templates.add(new Template(
                    e.getKey(), meta[0], meta[1].equals("yes"), List.copyOf(byTemplate.get(e.getKey()))));
        }
        return new Catalog(version, List.copyOf(templates));
    }

    /**
     * Reads a classpath resource, which in a native image means one that {@code -H:IncludeResources}
     * matched at build time.
     */
    static String readResource(String name) throws IOException {
        try (InputStream in = openResource(name)) {
            if (in == null) {
                throw new IOException("missing resource: " + name
                        + "  (for the template files this almost always means -H:IncludeResources "
                        + "does not match them; note that a resource whose name starts with a dot "
                        + "is easy to leave out by accident)");
            }
            var out = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                int c;
                char[] buf = new char[8192];
                while ((c = reader.read(buf)) != -1) {
                    out.append(buf, 0, c);
                }
            }
            return out.toString();
        }
    }

    static byte[] readResourceBytes(String name) throws IOException {
        try (InputStream in = openResource(name)) {
            if (in == null) {
                throw new IOException("missing resource: " + name
                        + "  (for the template files this almost always means -H:IncludeResources "
                        + "does not match them; note that a resource whose name starts with a dot "
                        + "is easy to leave out by accident)");
            }
            return in.readAllBytes();
        }
    }

    /**
     * The leading slash is required, not cosmetic: without it {@code getResourceAsStream} resolves
     * against this class's own package, so it would look for
     * {@code panamaview/scaffold/templates-manifest.txt} and find nothing. Resources here live at
     * the root of the jar.
     */
    private static InputStream openResource(String name) {
        return Catalog.class.getResourceAsStream("/" + name);
    }
}
