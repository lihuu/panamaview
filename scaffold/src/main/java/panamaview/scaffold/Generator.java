package panamaview.scaffold;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Writes a template out as a project. */
final class Generator {

    private Generator() {}

    private static final Set<PosixFilePermission> EXECUTABLE =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE);

    /**
     * Refuses to fill a directory that already has something in it.
     *
     * <p>{@code .git} is the exception: scaffolding into a fresh {@code git init} is ordinary, and
     * refusing because of a directory the user just made would be the tool being pedantic about
     * something that is not its business.
     */
    static void checkTarget(Path target, boolean force) throws IOException {
        if (!Files.isDirectory(target) || force) {
            return;
        }
        try (Stream<Path> entries = Files.list(target)) {
            List<String> occupied = entries
                    .map(p -> p.getFileName().toString())
                    .filter(name -> !name.equals(".git") && !name.equals(".DS_Store"))
                    .sorted()
                    .limit(5)
                    .toList();
            if (occupied.isEmpty()) {
                return;
            }
            throw new IOException(target + " is not empty (" + String.join(", ", occupied)
                    + (occupied.size() == 5 ? ", …" : "") + ").\n"
                    + "  Use --force to write into it anyway, or pick an empty directory.");
        }
    }

    /** Returns the number of files written. */
    static int write(Template template, Path target, Names names) throws IOException {
        List<String> written = new ArrayList<>();

        for (Template.Entry entry : template.files()) {
            byte[] bytes = Catalog.readResourceBytes(
                    Catalog.resourcePath(template.name(), entry.path()));

            Path destination = target.resolve(names.path(entry.path()));
            Files.createDirectories(destination.getParent());

            byte[] out;
            if (isText(bytes)) {
                // Substitute, then encode. Going through a String is what makes the tokens
                // addressable at all; the check below is what keeps that honest.
                out = names.text(new String(bytes, StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                out = bytes;
            }
            Files.write(destination, out);

            if (entry.executable()) {
                // A jar carries no file modes, so this is restored from the manifest rather than
                // from the file next to it. Without it, ./run.sh fails in the generated project.
                //
                // Not every filesystem has POSIX permissions, and asking one that does not is
                // what broke this: on Windows `setPosixFilePermissions` throws
                // UnsupportedOperationException, which killed `create` three files into a
                // ten-file project. Hence setExecutable, which treats that answer as "no such
                // thing here" rather than as a failure — see it for why the distinction holds.
                setExecutable(destination);
            }
            written.add(entry.path());
        }
        return written.size();
    }

    /**
     * Marks a file executable, where the platform has such a thing.
     *
     * <p>POSIX permissions are a property of the filesystem, not of the operating system, so this
     * asks the filesystem rather than testing {@code os.name}: a project generated onto a POSIX
     * mount on Windows is served correctly, and so is one generated onto a FAT stick on macOS.
     * {@code Files.getFileStore} would answer the same question, but it answers it in advance and
     * by a different route; letting the call fail is one code path instead of two.
     *
     * <p>Only {@code UnsupportedOperationException} is swallowed, and it is the one exception that
     * means "this filesystem does not model permissions" rather than "this write did not happen".
     * Anything else — a read-only mount, a missing parent — is still a failure and still reported.
     *
     * <p>Nothing is lost where it does not apply: Windows decides executability by extension
     * ({@code .bat}, {@code .cmd}, {@code .exe}), not by a mode bit, so there is no bit to set.
     */
    private static void setExecutable(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, EXECUTABLE);
        } catch (UnsupportedOperationException e) {
            // The filesystem does not model this. Nothing to do, and nothing wrong.
        }
    }

    /**
     * Whether to treat a file as text.
     *
     * <p>Decided from the bytes, not from the extension: an extension list has to be kept up to
     * date, and the day it is not is the day a template adds a file type nobody thought about.
     *
     * <p>A file that is decoded wrongly is not silently accepted — anything wrongly treated as
     * binary keeps its original tokens, and {@code verify-scaffold.sh} fails if a single one
     * survives in the generated project. So the cost of guessing wrong here is a build failure, not
     * a broken project.
     */
    private static boolean isText(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return false;               // no text format contains NUL
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ the library it carries

    /**
     * Where the carried framework goes inside a generated project.
     *
     * <p>One directory, so that a project says what it is carrying in one look, and so that
     * removing it is one <code>rm -rf</code>.
     */
    static final String CARRIED = ".panamaview";

    /** What the carried library is called in the generated project's .gitignore. */
    private static final String IGNORED = CARRIED + "/";

    /**
     * Wires a generated project to the framework copy the package carries.
     *
     * <p>Three things, all three needed. The copy, because the framework is not on Maven Central.
     * The repository that points at the copy, because without it Maven resolves the framework from
     * wherever the machine happens to have it — which is the failure this whole arrangement exists
     * to remove, and it is silent on a machine that has it. And the ignore entry, because otherwise
     * every generated project commits its own copy of a jar.
     */
    static void bundle(Library library, Path target) throws IOException {
        copyTree(library.root(), target.resolve(CARRIED).resolve("lib"));
        addRepository(target.resolve("pom.xml"));
        ignoreCarried(target.resolve(".gitignore"));
    }

    /**
     * Copies the library into the project.
     *
     * <p>The destination is emptied first rather than written over. A second run with
     * {@code --force} would otherwise merge into what the first one left, and a framework jar
     * whose name changed between versions would sit there for ever with nothing to notice it.
     */
    private static void copyTree(Path source, Path destination) throws IOException {
        // A source inside the destination, or the other way round, would have the delete below
        // destroy what is about to be copied. Nothing sensible asks for that, so it is refused
        // rather than half-handled.
        if (source.startsWith(destination) || destination.startsWith(source)) {
            throw new IOException("refusing to copy the library at " + source + " to " + destination
                    + ": one of them contains the other");
        }
        deleteTree(destination);

        try (Stream<Path> tree = Files.walk(source)) {
            for (Path path : tree.toList()) {
                Path to = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(to);
                    continue;
                }
                Files.createDirectories(to.getParent());
                // Attributes, so the layout arrives as it was, and REPLACE_EXISTING for the case
                // where the destination was emptied but its directories were not.
                Files.copy(path, to, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    /** Removes a directory tree, or does nothing if there is nothing there. */
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Declares the carried library as a repository in the generated pom.
     *
     * <p>Before {@code <dependencies>}, next to the dependency it resolves. The url is built from
     * {@code ${project.basedir}} rather than from an absolute path: a project is generated in one
     * place and built in another often enough — moved, copied, checked out again — that a path
     * frozen where the scaffolder happened to run would be a project that stops building for no
     * visible reason.
     */
    private static void addRepository(Path pom) throws IOException {
        String text = Files.readString(pom, StandardCharsets.UTF_8);
        // A template's pom is written out fresh on every run, so a second run cannot accumulate
        // blocks. This is here for a template that arrives with one of its own.
        if (text.contains("<id>" + Library.REPOSITORY_ID + "</id>")) {
            return;
        }

        // Indented two spaces, like the pom it goes into.
        String block = """
                  <!--
                    The framework travels inside the project: it is not on Maven Central, so the copy
                    this url points at is what makes ./run.sh work with no repository configured.
                    The url is relative to the project rather than fixed at generation time, because
                    projects get moved and copied. The id is what Maven records as the artifact's
                    origin, so a build log naming it is saying this copy was the one used.
                  -->
                  <repositories>
                    <repository>
                      <id>%s</id>
                      <url>file://${project.basedir}/%s/lib</url>
                    </repository>
                  </repositories>

                """.formatted(Library.REPOSITORY_ID, CARRIED);

        int at = lineStartingWith(text, "<dependencies");
        if (at < 0) {
            throw new IOException(pom + " has no <dependencies> for the bundled repository to go"
                    + " before. Every template has one; a template without it has nothing for the"
                    + " carried framework to resolve.");
        }
        Files.writeString(pom, text.substring(0, at) + block + text.substring(at),
                StandardCharsets.UTF_8);
    }

    /** The offset of the first line that begins with a tag, or -1 if there is none. */
    private static int lineStartingWith(String text, String tag) {
        int from = 0;
        while (from <= text.length()) {
            int end = text.indexOf('\n', from);
            // strip() rather than a prefix test on the whole text: the poms are indented, and
            // whatever ending the template carries is not this method's business.
            String line = end < 0 ? text.substring(from) : text.substring(from, end);
            if (line.strip().startsWith(tag)) {
                return from;
            }
            if (end < 0) {
                return -1;
            }
            from = end + 1;
        }
        return -1;
    }

    /**
     * Adds the carried library to the generated project's .gitignore.
     *
     * <p>Appended, never written over: a template's .gitignore is the project's own, and this entry
     * is the only thing the scaffolder has to add to it. A template with no .gitignore gets one,
     * because a carried framework is a directory, not a file, and committing it is not something
     * to leave to chance.
     */
    private static void ignoreCarried(Path gitignore) throws IOException {
        String text = Files.exists(gitignore) ? Files.readString(gitignore, StandardCharsets.UTF_8) : "";
        for (String line : text.split("\n", -1)) {
            if (line.strip().equals(IGNORED)) {
                return;
            }
        }

        StringBuilder out = new StringBuilder(text);
        if (!text.isEmpty() && !text.endsWith("\n")) {
            out.append('\n');
        }
        out.append('\n')
                .append("# The framework this project was generated against. The scaffolder copies\n")
                .append("# it in and can copy a newer one over it, so it is not the project's source.\n")
                .append(IGNORED)
                .append('\n');
        Files.writeString(gitignore, out.toString(), StandardCharsets.UTF_8);
    }
}
