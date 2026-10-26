package io.github.lihuu.panamaview;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a native file dialog is asked to do, in a shape both platforms can honour.
 *
 * <p>The kinds are the four a desktop application actually needs — pick a file, pick several,
 * pick a folder, save under a name — and the platform decides everything else the dialog
 * shows. The parts the model refuses rather than ignores are the combinations that would
 * quietly mean nothing: a name field on an open dialog, a file-type filter on one that cannot
 * choose files. The two platforms would otherwise handle the same mistake differently — one
 * greyed out, one silently unused — and the moment to catch that is here, where both
 * implementations read the same spec.
 *
 * <p>Build one by hand for a Java caller, or read one straight out of the page with
 * {@link #of(Map)}: the map is the {@code params} of a bridge invocation, so a binding is one
 * line —
 *
 * <pre>{@code
 * web.bindUi("pickFile", params -> host.open(FileDialog.of(params)).toMap());
 * }</pre>
 *
 * <p>Bind it with {@code bindUi}, not {@code bind}. Opening the dialog blocks the thread it
 * runs on until the user decides, which on the UI thread is what a modal dialog is — and the
 * default 30-second deadline would kill a handler the user was simply still thinking about.
 * While the dialog is up, both platforms' event pumps keep running inside the modal loop, so
 * replies to other in-flight calls still arrive.
 *
 * @param kind        which of the four dialogs this is
 * @param title       the panel's title, or null for the platform's own default. A blank title is
 *                    refused: the choice is between saying something and saying nothing.
 * @param extensions  file-type filter as bare extensions, no dots — {@code png}, not
 *                    {@code .png}; normalised to lower case. Empty means no filter.
 * @param directory   where the dialog starts, or null for the platform's remembered default.
 * @param defaultName the save dialog's suggested file name, or null. Only meaningful for
 *                    {@link Kind#SAVE_FILE}, and refused everywhere else.
 */
public record FileDialog(Kind kind, String title, List<String> extensions,
                         Path directory, String defaultName) {

    /** The four dialogs. {@code OPEN_FILES} is the multiple-selection one; the rest pick one. */
    public enum Kind { OPEN_FILE, OPEN_FILES, OPEN_DIRECTORY, SAVE_FILE }

    public FileDialog {
        if (kind == null) {
            throw new IllegalArgumentException("A file dialog must have a kind: which of "
                    + List.of(Kind.values()) + " is it?");
        }
        if (title != null && title.isBlank()) {
            throw new IllegalArgumentException("A file dialog's title must say something or be "
                    + "absent: a blank title is the platform default wearing a disguise");
        }
        extensions = normalized(extensions);
        if (kind == Kind.OPEN_DIRECTORY && !extensions.isEmpty()) {
            throw new IllegalArgumentException("Extensions filter files, and this dialog chooses a "
                    + "directory: \"" + extensions + "\" would be quietly unused, so it is refused");
        }
        if (kind != Kind.SAVE_FILE && defaultName != null) {
            throw new IllegalArgumentException("A name field suggests a file to save, and this "
                    + "dialog does not save one: \"" + defaultName + "\" is refused");
        }
    }

    /**
     * Reads a dialog out of the {@code params} of a bridge invocation.
     *
     * <p>Everything is optional: an empty map is a plain single-file picker. The page-side
     * vocabulary is {@code kind} ({@code "file"}, {@code "files"}, {@code "directory"},
     * {@code "save"}), {@code title}, {@code extensions} (an array of bare extensions),
     * {@code directory} (a path), and {@code name} (the save dialog's suggestion).
     *
     * <p>Fail-fast, like the model itself: a wrong kind, a blank extension or a misplaced name
     * is an {@link IllegalArgumentException} here, which the bridge reports to the page as the
     * invocation's error — rather than as a dialog that opened and did not do what was meant.
     */
    public static FileDialog of(Map<String, Object> params) {
        Map<String, Object> p = params == null ? Map.of() : params;
        Kind kind = kindOf(string(p.get("kind"), "file"));
        String title = p.containsKey("title") ? string(p.get("title"), null) : null;
        if (title != null && title.isBlank()) {
            throw new IllegalArgumentException("A file dialog's title must say something or be "
                    + "absent: a blank title is the platform default wearing a disguise");
        }
        Path directory = p.get("directory") == null
                ? null
                : Path.of(requireNonBlank(string(p.get("directory"), null), "directory"));
        String name = p.containsKey("name") ? string(p.get("name"), null) : null;
        if (name != null && name.isBlank()) {
            throw new IllegalArgumentException("A save dialog's suggested name must say something "
                    + "or be absent: a blank name is an empty field wearing a disguise");
        }
        return new FileDialog(kind, title, extensionsOf(p.get("extensions")), directory, name);
    }

    private static Kind kindOf(String name) {
        return switch (name) {
            case "file" -> Kind.OPEN_FILE;
            case "files" -> Kind.OPEN_FILES;
            case "directory" -> Kind.OPEN_DIRECTORY;
            case "save" -> Kind.SAVE_FILE;
            default -> throw new IllegalArgumentException("A file dialog's kind must be one of "
                    + "\"file\", \"files\", \"directory\" or \"save\": \"" + name + "\" names none");
        };
    }

    private static List<String> extensionsOf(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("A file dialog's extensions are a list of bare "
                    + "extensions: an array, not the single value \"" + value + "\"");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element == null || String.valueOf(element).isBlank()) {
                throw new IllegalArgumentException("A file dialog's extensions are a list of bare "
                        + "extensions: a blank one filters nothing and is refused");
            }
            String extension = String.valueOf(element).toLowerCase(Locale.ROOT);
            out.add(extension.startsWith(".") ? extension.substring(1) : extension);
        }
        return out;
    }

    /** Lower-case, dot-free, order-preserving, and never null. */
    private static List<String> normalized(List<String> extensions) {
        if (extensions == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(extensions.size());
        for (String extension : extensions) {
            if (extension == null || extension.isBlank()) {
                throw new IllegalArgumentException("A file dialog's extensions are a list of bare "
                        + "extensions: a blank one filters nothing and is refused");
            }
            String normalized = extension.trim().toLowerCase(Locale.ROOT);
            out.add(normalized.startsWith(".") ? normalized.substring(1) : normalized);
        }
        return List.copyOf(out);
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String requireNonBlank(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A file dialog's " + what + " is a path: a blank "
                    + "one points nowhere and is refused");
        }
        return value;
    }

    /**
     * What came back from the dialog.
     *
     * <p>{@code accepted} is the user's decision — false is a cancellation, not an error, and
     * carries no paths. The paths are absolute and platform-shaped; one for every kind but
     * {@link Kind#OPEN_FILES}, which is the multiple-selection one.
     *
     * @param accepted whether the user confirmed a choice
     * @param paths    what was chosen, in the order the dialog reported it
     */
    public record Result(boolean accepted, List<String> paths) {

        public Result {
            paths = paths == null ? List.of() : List.copyOf(paths);
            if (accepted && paths.isEmpty()) {
                throw new IllegalArgumentException("An accepted dialog always names at least one "
                        + "path: an empty acceptance is a lie about what the user did");
            }
            if (!accepted && !paths.isEmpty()) {
                throw new IllegalArgumentException("A cancelled dialog names no paths: paths "
                        + "without acceptance are a lie about what the user did");
            }
        }

        /** The bridge-shaped answer: {@code {ok, paths}}. */
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", accepted);
            out.put("paths", paths);
            return out;
        }
    }
}