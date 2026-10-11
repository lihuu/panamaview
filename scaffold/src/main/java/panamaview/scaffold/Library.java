package panamaview.scaffold;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The copy of the framework that travels with a packaged scaffolder.
 *
 * <p>A generated project depends on {@code io.github.lihuu:panamaview}, which is not on Maven
 * Central. A scaffolder handed to someone through npm or Homebrew therefore has to carry the
 * framework with it, or it generates a project whose one dependency cannot be resolved — an error
 * that lands in a file the user did not write, about an artifact they never heard of. So the
 * package ships the framework laid out as a Maven repository, and the generated project is pointed
 * at it with a {@code file://} repository; see {@link Generator#bundle}.
 *
 * <p><b>Nothing is searched for.</b> There are exactly four rules, in this order:
 *
 * <ol>
 *   <li>{@code PANAMAVIEW_LIBRARY}, if it is set and not empty. Set but not a library is a hard
 *       error, not a fall-through: someone who names a directory meant that directory.</li>
 *   <li>{@code <dir of this executable>/lib} — a Homebrew package, binary in {@code bin/} and
 *       library in {@code lib/}.</li>
 *   <li>{@code <dir of this executable>/../lib} — an npm package, binary in {@code bin/} and
 *       library in {@code ../lib/}.</li>
 *   <li>The same two directories, but computed from where the executable really is rather than
 *       where it was invoked from. This exists for Homebrew: it links the binary into
 *       {@code <prefix>/bin} and {@code ProcessHandle} reports the path as invoked, so rules 2
 *       and 3 would look beside the link, where nothing is. Every candidate, tried in order.</li>
 *   <li>Nothing. No wiring, no complaint: this is a jar run out of a checkout, where the framework
 *       comes from a repository or from {@code mvn install}, and that is a supported way to work.
 *       The verification scripts depend on it staying quiet.</li>
 * </ol>
 *
 * <p>Adding candidates to that list is how a "where is it?" question turns into a guess that
 * sometimes finds the wrong thing. A library is a directory with the framework's jar and pom
 * inside it at the version this scaffolder generates for — not merely a directory with the right
 * name, which is what a wrong guess would look like.
 */
final class Library {

    /**
     * Overrides detection. The variable exists because the two layouts below cover the ways this
     * is installed and nothing else: a test, a tarball unpacked somewhere odd, or a packaging
     * layout not thought of yet needs a way to say so rather than a new rule.
     */
    static final String ENV = "PANAMAVIEW_LIBRARY";

    /**
     * The repository id a generated project gives it.
     *
     * <p>Maven writes this id beside the artifact it resolved from, in {@code _remote.repositories},
     * which is what turns "the bundled copy was used rather than one from this machine" into
     * something that can be checked instead of assumed.
     * {@code scripts/verify-scaffold-library.sh} checks it against an empty local repository.
     */
    static final String REPOSITORY_ID = "panamaview-bundled";

    /** Where the framework sits inside a Maven repository root. */
    private static final String GROUP_PATH = "io/github/lihuu/panamaview/";

    private final Path root;
    private final String version;

    private Library(Path root, String version) {
        this.root = root;
        this.version = version;
    }

    /** The repository root, which is what gets copied into a generated project. */
    Path root() {
        return root;
    }

    String version() {
        return version;
    }

    /**
     * Finds the library this scaffolder carries, or {@code null} if it carries none.
     *
     * @param version the framework version this scaffolder generates projects for
     */
    static Library find(String version) throws IOException {
        String named = System.getenv(ENV);
        if (named != null && !named.isBlank()) {
            Path root = Path.of(named).toAbsolutePath().normalize();
            if (!holds(root, version)) {
                throw new IOException(ENV + " is set to " + named
                        + ", which is not a panamaview library. Expected a Maven repository root"
                        + " holding\n  " + artifacts(root, version) + "/panamaview-" + version
                        + ".jar\n  " + artifacts(root, version) + "/panamaview-" + version
                        + ".pom\n"
                        + "  Unset " + ENV + " to generate a project that finds the framework the"
                        + " way this checkout does.");
            }
            return new Library(root, version);
        }

        for (Path candidate : besideExecutable()) {
            if (holds(candidate, version)) {
                return new Library(candidate, version);
            }
        }
        return null;
    }

    /**
     * Where a package puts its library, relative to the executable.
     *
     * <p>{@link ProcessHandle} rather than a system property: a native image is not a jar and has
     * no {@code java.class.path} to read, and the file the user ran is exactly what knows where its
     * neighbours are. It can be empty — some launch modes do not report it — and under
     * {@code mvn exec:exec} or a plain {@code java -jar} it names the launcher rather than a
     * packaged layout. Both of those mean "not found", which is the right answer there.
     *
     * <p>The path is used as invoked, without {@code toRealPath()}. Homebrew's {@code bin} is a
     * symlink into the Cellar, and the {@code lib} beside that symlink is precisely where the
     * library is expected to be; resolving the link would look inside the Cellar instead and miss
     * it. The same goes for a package manager that links a launcher into a shared {@code bin}.
     */
    private static List<Path> besideExecutable() {
        Optional<String> command = ProcessHandle.current().info().command();
        if (command.isEmpty() || command.get().isBlank()) {
            return List.of();
        }
        // Relative when the user ran "./panamaview"; resolved against the working directory, which
        // is what a relative path means.
        Path invoked = Path.of(command.get()).toAbsolutePath().normalize();
        Path directory = invoked.getParent();
        if (directory == null) {
            return List.of();
        }

        List<Path> candidates = new ArrayList<>(candidatesUnder(directory));

        // A package manager usually links the binary rather than copying it, and ProcessHandle
        // reports the path it was invoked by — the link. Homebrew does exactly this: the formula
        // puts the library in the keg and links bin/panamaview to <prefix>/bin, so looking beside
        // the link finds nothing and the generated project would silently resolve no framework.
        // Follow the link and offer the same candidates again. This is not a search: it is the
        // same two rules, applied to the file's real home.
        try {
            Path real = invoked.toRealPath();
            Path realDirectory = real.getParent();
            if (realDirectory != null && !realDirectory.equals(directory)) {
                candidates.addAll(candidatesUnder(realDirectory));
            }
        } catch (IOException e) {
            // A dangling link or a path that cannot be resolved: the candidates above stand, and
            // "not found" is the honest answer.
        }

        return candidates;
    }

    private static List<Path> candidatesUnder(Path directory) {
        return List.of(directory.resolve("lib"), directory.resolve("..").resolve("lib").normalize());
    }

    /** Whether a directory is a Maven repository root holding this version of the framework. */
    private static boolean holds(Path root, String version) {
        Path artifacts = artifacts(root, version);
        return Files.isRegularFile(artifacts.resolve("panamaview-" + version + ".jar"))
                && Files.isRegularFile(artifacts.resolve("panamaview-" + version + ".pom"));
    }

    private static Path artifacts(Path root, String version) {
        return root.resolve(GROUP_PATH + version);
    }
}
