package panamaview.scaffold;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The three things a generated project renames, and the rules for doing it.
 *
 * <p>A template carries real values — {@code com.example}, {@code myapp}, {@code My App} — rather
 * than placeholders, so that a template stays a working project on its own: you can copy one out of
 * the repository by hand, and {@code scripts/verify-templates.sh} builds them straight from the
 * working tree. The cost is that a generated project has to rename these three exactly, everywhere
 * they appear, and missing one produces code that compiles and misbehaves.
 *
 * <p>Substitution is a <b>single pass</b> over an alternation, not three sequential replaces. That
 * is not style: with sequential replaces, {@code --package com.example.myapp} would have the
 * package rewritten by the later {@code myapp} rule, silently yielding {@code com.example.<app>}.
 * One pass cannot rewrite text it just inserted.
 */
record Names(String app, String pkg, String title) {

    /*
     * Both spellings of the package, in both places. A package is written with dots in code and
     * with slashes in a path — and a path can appear inside a *text* file: the templates' READMEs
     * name `src/main/java/com/example/Main.java`. Handling only the dot form in text left that
     * one behind, which is the kind of miss this whole single-pass design exists to prevent.
     */
    private static final Pattern IN_TEXT =
            Pattern.compile("com\\.example|com/example|myapp|My App");
    private static final Pattern IN_PATH =
            Pattern.compile("com\\.example|com/example");

    /** Substitutes inside file contents. */
    String text(String value) {
        return replaceAll(value, IN_TEXT);
    }

    /** Substitutes inside a file's path, where a package is a directory tree. */
    String path(String value) {
        return replaceAll(value, IN_PATH);
    }

    private String replaceAll(String value, Pattern pattern) {
        Matcher m = pattern.matcher(value);
        StringBuilder out = new StringBuilder(value.length() + 32);
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(switch (m.group()) {
                case "com.example" -> pkg;
                case "com/example" -> pkg.replace('.', '/');
                case "myapp" -> app;
                default -> title;                       // "My App"
            }));
        }
        return m.appendTail(out).toString();
    }

    /**
     * Derives what a project should be called from the directory it goes into, so the common case
     * needs no options at all.
     */
    static Names forDirectory(String directoryName, String pkgOverride, String titleOverride) {
        String app = directoryName.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (app.isEmpty() || !Character.isLetter(app.charAt(0))) {
            // A Maven artifactId may start with a digit but a Java identifier may not, and one name
            // has to serve both. A directory called "2026-notes" should not produce a project that
            // cannot compile.
            app = "app" + (app.isEmpty() ? "" : "-" + app);
        }

        String pkg = pkgOverride != null ? pkgOverride : "com.example." + app.replace("-", "");
        String title = titleOverride != null ? titleOverride : titleCase(directoryName);
        return new Names(app, pkg, title);
    }

    private static String titleCase(String value) {
        String[] words = value.split("[^A-Za-z0-9]+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                out.append(word.substring(1));
            }
        }
        return out.isEmpty() ? "My App" : out.toString();
    }

    /**
     * Rejects a package that would not compile, before writing anything.
     *
     * <p>A generator that emits code which fails to compile has done worse than refusing: the error
     * surfaces later, in a file the user did not write, about a name they did not type.
     */
    static String checkPackage(String pkg) {
        if (pkg == null || pkg.isBlank()) {
            return "package name is empty";
        }
        for (String segment : pkg.split("\\.", -1)) {
            if (segment.isEmpty()) {
                return "'" + pkg + "' has an empty segment";
            }
            if (!Character.isJavaIdentifierStart(segment.charAt(0))) {
                return "'" + segment + "' does not start with a letter, _ or $";
            }
            for (int i = 1; i < segment.length(); i++) {
                if (!Character.isJavaIdentifierPart(segment.charAt(i))) {
                    return "'" + segment + "' contains '" + segment.charAt(i) + "'";
                }
            }
            if (JavaKeywords.ALL.contains(segment)) {
                return "'" + segment + "' is a Java keyword";
            }
        }
        return null;
    }

    private static final class JavaKeywords {
        static final List<String> ALL = List.of(
                "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
                "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
                "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
                "interface", "long", "native", "new", "package", "private", "protected", "public",
                "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
                "throw", "throws", "transient", "try", "void", "volatile", "while",
                "true", "false", "null", "record", "sealed", "permits", "var", "yield");
    }
}
