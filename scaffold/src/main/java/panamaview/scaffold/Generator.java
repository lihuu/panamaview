package panamaview.scaffold;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                Files.setPosixFilePermissions(destination, EXECUTABLE);
            }
            written.add(entry.path());
        }
        return written.size();
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
}
