package panamaview.scaffold;

import java.util.List;

/**
 * One template, as declared by the embedded manifest.
 *
 * <p>{@code files} are paths relative to the template's own directory, in the template's own
 * layout — {@code src/main/java/com/example/Main.java}, not the layout of a generated project.
 * Renaming the package happens on the way out; see {@link Names}.
 */
record Template(String name, String summary, boolean needsNode, List<Entry> files) {

    /** A file to write out, and whether it keeps its executable bit. */
    record Entry(String path, boolean executable) {}

    /** One line for the picker. The Node column is the thing that actually decides between these. */
    String describe() {
        return String.format("%-17s %-13s %s", name, needsNode ? "needs Node" : "no Node", summary);
    }

    boolean has(String file) {
        return files.stream().anyMatch(e -> e.path().equals(file));
    }
}
