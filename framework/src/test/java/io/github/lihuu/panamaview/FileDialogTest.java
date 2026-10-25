package io.github.lihuu.panamaview;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the file-dialog model accepts, what it refuses, and what it makes of the page's own
 * vocabulary.
 *
 * <p>The refusals are the point. The model is the one place both platforms read the same
 * spec, so a combination that would quietly mean nothing — a name field on an open dialog, a
 * file-type filter on one that cannot choose files — is caught here, where the message can
 * name the mistake, rather than on a platform where the same mistake greys out a control or
 * is silently unused. Both platforms would handle it differently, and "differently" is the
 * last thing a cross-platform dialog wants.
 */
class FileDialogTest {

    @Test
    void anEmptyMapIsAPlainFilePicker() {
        var spec = FileDialog.of(Map.of());
        assertEquals(FileDialog.Kind.OPEN_FILE, spec.kind());
        assertNull(spec.title());
        assertEquals(List.of(), spec.extensions());
        assertNull(spec.directory());
        assertNull(spec.defaultName());
    }

    @Test
    void thePageVocabularyNamesAllFourKinds() {
        assertEquals(FileDialog.Kind.OPEN_FILE, FileDialog.of(Map.of("kind", "file")).kind());
        assertEquals(FileDialog.Kind.OPEN_FILES, FileDialog.of(Map.of("kind", "files")).kind());
        assertEquals(FileDialog.Kind.OPEN_DIRECTORY,
                FileDialog.of(Map.of("kind", "directory")).kind());
        assertEquals(FileDialog.Kind.SAVE_FILE, FileDialog.of(Map.of("kind", "save")).kind());
    }

    @Test
    void anUnknownKindIsRefusedWithTheVocabularyNamed() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> FileDialog.of(Map.of("kind", "folder")));
        assertTrue(e.getMessage().contains("\"folder\""),
                "the message should name what was offered: " + e.getMessage());
        assertTrue(e.getMessage().contains("\"directory\""),
                "the message should name the right spelling: " + e.getMessage());
    }

    @Test
    void extensionsAreNormalisedToBareLowerCase() {
        var spec = FileDialog.of(Map.of("extensions", List.of("PNG", ".Jpg", "txt")));
        assertEquals(List.of("png", "jpg", "txt"), spec.extensions());
    }

    @Test
    void aHandBuiltSpecNormalisesTheSameWay() {
        var spec = new FileDialog(FileDialog.Kind.SAVE_FILE, "Save", List.of(".PNG"), null, "a.png");
        assertEquals(List.of("png"), spec.extensions());
    }

    @Test
    void aBlankExtensionIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> FileDialog.of(Map.of("extensions", List.of("png", " "))));
        assertThrows(IllegalArgumentException.class,
                () -> new FileDialog(FileDialog.Kind.OPEN_FILE, null, List.of(""), null, null));
    }

    @Test
    void extensionsOnADirectoryDialogAreRefused() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> FileDialog.of(Map.of("kind", "directory", "extensions", List.of("txt"))));
        assertTrue(e.getMessage().contains("chooses a directory"),
                "the message should say what the combination means: " + e.getMessage());
    }

    @Test
    void aNameOnAnOpenDialogIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> FileDialog.of(Map.of("kind", "file", "name", "a.txt")));
        // The same name, on the one kind it belongs to, is fine.
        assertEquals("a.txt",
                FileDialog.of(Map.of("kind", "save", "name", "a.txt")).defaultName());
    }

    @Test
    void aBlankTitleIsRefusedBecauseItWearsTheDefaultAsADisguise() {
        assertThrows(IllegalArgumentException.class,
                () -> FileDialog.of(Map.of("title", "  ")));
        assertNull(FileDialog.of(Map.of()).title(),
                "an absent title is the platform's own default, and is fine");
    }

    @Test
    void aDirectoryIsKeptAsAPath() {
        assertEquals(java.nio.file.Path.of("/tmp"),
                FileDialog.of(Map.of("directory", "/tmp")).directory());
        assertThrows(IllegalArgumentException.class,
                () -> FileDialog.of(Map.of("directory", " ")));
    }

    @Test
    void aResultTellsTheTruthInBothDirections() {
        assertEquals(Map.of("ok", false, "paths", List.of()),
                new FileDialog.Result(false, List.of()).toMap());
        assertEquals(Map.of("ok", true, "paths", List.of("/a", "/b")),
                new FileDialog.Result(true, List.of("/a", "/b")).toMap());
        assertThrows(IllegalArgumentException.class,
                () -> new FileDialog.Result(true, List.of()),
                "an accepted dialog with no paths claims the user chose nothing");
        assertThrows(IllegalArgumentException.class,
                () -> new FileDialog.Result(false, List.of("/a")),
                "a cancelled dialog with paths claims the user chose something");
        // The paths cannot be mutated through the result, and null reads as empty.
        assertEquals(List.of(), new FileDialog.Result(false, null).paths());
    }
}