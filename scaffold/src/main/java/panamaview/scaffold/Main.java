package panamaview.scaffold;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The command line.
 *
 * <p>Hand-rolled argument parsing, because the whole surface is five flags and a dependency here
 * would be the largest thing in the native image. The first argument is the command; everything
 * after it is parsed as options.
 */
public final class Main {

    private static final int OK = 0;
    private static final int FAILED = 1;
    private static final int USAGE = 2;

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        try {
            if (args.length == 0) {
                printHelp(null);
                return USAGE;
            }
            return switch (args[0]) {
                case "create", "new" -> create(List.of(args).subList(1, args.length));
                case "templates", "list" -> templates();
                case "--selftest" -> selftest();
                case "--version", "-V", "version" -> version();
                case "--help", "-h", "help" -> {
                    printHelp(null);
                    yield OK;
                }
                default -> {
                    printHelp("unknown command: " + args[0]);
                    yield USAGE;
                }
            };
        } catch (UsageException e) {
            System.err.println("panamaview: " + e.getMessage());
            System.err.println("  run 'panamaview help' for usage");
            return USAGE;
        } catch (IOException e) {
            System.err.println("panamaview: " + e.getMessage());
            return FAILED;
        }
    }

    // ------------------------------------------------------------------ commands

    private static int version() throws IOException {
        Catalog catalog = Catalog.load();
        System.out.println("panamaview " + catalog.version());
        return OK;
    }

    private static int templates() throws IOException {
        Catalog catalog = Catalog.load();
        System.out.println("panamaview " + catalog.version());
        System.out.println();
        for (Template template : catalog.templates()) {
            System.out.println("  " + template.describe());
        }
        System.out.println();
        System.out.println("  'needs Node' is about editing and building, not running: a released app");
        System.out.println("  is a native executable plus static files, with no Node anywhere.");
        return OK;
    }

    private static int create(List<String> args) throws IOException {
        if (args.isEmpty()) {
            throw new UsageException("create needs a directory: panamaview create <dir>");
        }

        String directory = args.get(0);
        String templateName = null;
        String pkgOverride = null;
        String titleOverride = null;
        boolean force = false;

        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "-t", "--template" -> templateName = value(args, ++i, arg);
                case "-p", "--package" -> pkgOverride = value(args, ++i, arg);
                case "--title" -> titleOverride = value(args, ++i, arg);
                case "-f", "--force" -> force = true;
                default -> throw new UsageException("unknown option: " + arg);
            }
        }

        Catalog catalog = Catalog.load();

        // Before anything is written. A PANAMAVIEW_LIBRARY that is set to something that is not a
        // library is a hard error, and it should not leave half a generated project behind — and a
        // library that is found is worth knowing about before the project exists, not after.
        Library library = Library.find(catalog.version());

        Path target = Path.of(directory).toAbsolutePath().normalize();
        if (Files.exists(target) && !Files.isDirectory(target)) {
            throw new IOException(target + " exists and is not a directory");
        }

        Template template = templateName != null
                ? requireTemplate(catalog, templateName)
                : chooseTemplate(catalog);

        // Defaults come from the directory name, so the common case needs no options. For "create ."
        // that is the directory the user is standing in, which is what they mean by "here".
        String stem = target.getFileName().toString();
        Names names = Names.forDirectory(stem, pkgOverride, titleOverride);

        String problem = Names.checkPackage(names.pkg());
        if (problem != null) {
            throw new UsageException("bad package name: " + problem);
        }

        Generator.checkTarget(target, force);
        int written = Generator.write(template, target, names);
        if (library != null) {
            Generator.bundle(library, target);
        }

        report(target, template, names, written, library);
        return OK;
    }

    private static void report(
            Path target, Template template, Names names, int written, Library library) {
        System.out.println("creating " + target);
        System.out.printf("  template  %-17s %s%n", template.name(), template.summary());
        System.out.printf("  package   %s%n", names.pkg());
        System.out.printf("  title     %s%n", names.title());
        System.out.printf("  %d files written%n", written);
        if (library != null) {
            // One line, always, when there is a library: the alternative is a project that does not
            // build for a reason nothing in its output mentions.
            System.out.printf("  library   %s%n", library.root());
        }
        System.out.println();
        System.out.println("next");
        System.out.println("  cd " + target);
        // Built from the template's own file list, so a template that adds or drops a script cannot
        // leave this advice describing something that is not there.
        if (template.has("dev.sh")) {
            System.out.printf("  %-17s %s%n", "./dev.sh", "dev server with hot reload"
                    + (template.needsNode() ? "   (needs Node)" : ""));
        }
        System.out.printf("  %-17s %s%n", "./run.sh", "production build, served from the app's own scheme");
        System.out.printf("  %-17s %s%n", "./build-native.sh", "→ target/" + names.app());
        if (library != null) {
            System.out.println();
            System.out.println("  The project carries its own copy of panamaview, in " + Generator.CARRIED
                    + "/lib, so it");
            System.out.println("  builds with nothing configured: ./run.sh is all it needs.");
        }
        if (template.has("README.md")) {
            System.out.println();
            System.out.println("  README.md has the rest.");
        }
    }

    private static Template requireTemplate(Catalog catalog, String name) throws IOException {
        Template template = catalog.byName(name);
        if (template == null) {
            var known = new ArrayList<String>();
            catalog.templates().forEach(t -> known.add(t.name()));
            throw new UsageException("no template named '" + name + "'; try one of " + known);
        }
        return template;
    }

    /**
     * Asks, if there is anyone to ask.
     *
     * <p>Without a terminal there is no question to put: reading from a pipe would consume whatever
     * is there and answer with it. So a non-interactive run gets the list of names and an error,
     * which is the only thing that can be acted on — and it is what CI exercises.
     */
    private static Template chooseTemplate(Catalog catalog) throws IOException {
        if (System.console() == null) {
            var known = new ArrayList<String>();
            catalog.templates().forEach(t -> known.add(t.name()));
            throw new UsageException("no --template given and no terminal to ask on; "
                    + "pass --template <" + String.join("|", known) + ">");
        }

        System.out.println("choose a template");
        System.out.println();
        List<Template> offered = catalog.templates();
        for (int i = 0; i < offered.size(); i++) {
            System.out.printf("  %d) %s%n", i + 1, offered.get(i).describe());
        }
        System.out.println();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null) {
                throw new UsageException("no template chosen");
            }
            line = line.strip();
            for (Template template : offered) {
                if (template.name().equals(line)) {
                    return template;
                }
            }
            try {
                int index = Integer.parseInt(line);
                if (index >= 1 && index <= offered.size()) {
                    return offered.get(index - 1);
                }
            } catch (NumberFormatException ignored) {
                // fall through to the retry below
            }
            System.out.println("  not one of the listed templates — a name or a number");
        }
    }

    /**
     * Checks that everything the manifest promises is actually present.
     *
     * <p>This is the check for the failure that has no other symptom: a native image built without
     * matching {@code -H:IncludeResources} still starts and still lists templates, and only fails
     * when someone runs {@code create}. The files most likely to go missing are the ones whose
     * names begin with a dot, because they look like build noise. So the selftest does not trust
     * the manifest — it opens every file in it.
     */
    private static int selftest() throws IOException {
        Catalog catalog = Catalog.load();
        int files = 0;
        int executable = 0;
        for (Template template : catalog.templates()) {
            for (Template.Entry entry : template.files()) {
                byte[] bytes = Catalog.readResourceBytes(
                        Catalog.resourcePath(template.name(), entry.path()));
                if (bytes.length == 0) {
                    throw new IOException("empty resource: " + template.name() + "/" + entry.path());
                }
                if (entry.path().startsWith(".")) {
                    System.out.println("  dotfile present: " + template.name() + "/" + entry.path()
                            + " (" + bytes.length + " bytes)");
                }
                files++;
                if (entry.executable()) {
                    executable++;
                }
            }
        }
        System.out.println("panamaview " + catalog.version() + ": " + catalog.templates().size()
                + " template(s), " + files + " file(s), " + executable + " executable");
        return OK;
    }

    // ------------------------------------------------------------------ helpers

    private static String value(List<String> args, int index, String option) {
        if (index >= args.size()) {
            throw new UsageException(option + " needs a value");
        }
        return args.get(index);
    }

    private static void printHelp(String problem) {
        if (problem != null) {
            System.out.println("panamaview: " + problem);
            System.out.println();
        }
        System.out.println("""
                panamaview — create a panamaview project

                usage
                  panamaview create <dir> [options]
                  panamaview templates
                  panamaview version | help

                create options
                  -t, --template <name>   which template to use; without it you are asked,
                                          unless there is no terminal, in which case it is required
                  -p, --package <pkg>     Java package for the app   [default: com.example.<dir>]
                      --title <text>      window and page title      [default: from the directory]
                  -f, --force             write into a directory that is not empty

                examples
                  panamaview templates
                  panamaview create my-chat-app --template vue
                  panamaview create . --template vanilla

                A generated project builds with Maven and runs on the JVM:

                  cd my-chat-app && ./run.sh

                and produces a standalone executable with GraalVM:

                  ./build-native.sh

                Nothing generated at run time needs Node. Three of the five templates need it to
                build, which 'panamaview templates' marks.
                """);
    }

    /** Thrown for anything the caller got wrong, as opposed to anything the filesystem did. */
    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }
}
