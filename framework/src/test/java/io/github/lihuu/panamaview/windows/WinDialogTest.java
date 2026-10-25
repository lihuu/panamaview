package io.github.lihuu.panamaview.windows;

import io.github.lihuu.panamaview.FileDialog;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decisions the dialog builder makes, tested without Windows — the same split
 * {@code WinMenu} pioneered. What cannot run here is the COM half ({@code Com}'s static
 * initializer binds ole32, which exists only on Windows); what can is everything the native
 * calls will say, decided in pure Java by {@link WinDialog#plan}.
 *
 * <p>The option flags are the ones that bite. These dialogs answer filesystem paths, so a
 * kind that forgot {@code FOS_FORCEFILESYSTEM} could hand back an item with no path at all —
 * and only OPEN_FILES asks for multiple selection, because the multiple-selection flag on a
 * single-kind dialog is a different dialog wearing the wrong name.
 */
class WinDialogTest {

    private static final int FOS_OVERWRITEPROMPT = 0x2;
    private static final int FOS_PICKFOLDERS = 0x20;
    private static final int FOS_FORCEFILESYSTEM = 0x40;
    private static final int FOS_ALLOWMULTISELECT = 0x200;
    private static final int FOS_PATHMUSTEXIST = 0x800;
    private static final int FOS_FILEMUSTEXIST = 0x1000;

    @Test
    void everyKindForcesTheFilesystemAndExistingPaths() {
        for (FileDialog.Kind kind : FileDialog.Kind.values()) {
            int options = WinDialog.plan(new FileDialog(kind, null, null, null, null)).options();
            assertEquals(FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST,
                    options & (FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST),
                    kind + " must force the filesystem and existing paths: "
                            + "these dialogs answer paths, and an item outside the filesystem "
                            + "has none");
        }
    }

    @Test
    void onlyTheMultipleKindAsksForMultipleSelection() {
        int single = WinDialog.plan(new FileDialog(FileDialog.Kind.OPEN_FILE, null, null, null, null)).options();
        int multiple = WinDialog.plan(new FileDialog(FileDialog.Kind.OPEN_FILES, null, null, null, null)).options();
        int directory = WinDialog.plan(new FileDialog(FileDialog.Kind.OPEN_DIRECTORY, null, null, null, null)).options();
        int save = WinDialog.plan(new FileDialog(FileDialog.Kind.SAVE_FILE, null, null, null, null)).options();

        assertEquals(0, single & FOS_ALLOWMULTISELECT);
        assertEquals(FOS_ALLOWMULTISELECT, multiple & FOS_ALLOWMULTISELECT);
        assertEquals(0, directory & FOS_ALLOWMULTISELECT);
        assertEquals(0, save & FOS_ALLOWMULTISELECT);
    }

    @Test
    void theDirectoryKindAsksForFoldersAndNoFileMustExist() {
        int options = WinDialog.plan(new FileDialog(FileDialog.Kind.OPEN_DIRECTORY, null, null, null, null)).options();
        assertEquals(FOS_PICKFOLDERS, options & FOS_PICKFOLDERS);
        assertEquals(0, options & FOS_FILEMUSTEXIST,
                "a folder picker that demanded an existing file would refuse every folder");
    }

    @Test
    void theSaveKindAsksForTheOverwritePromptAndNoFileMustExist() {
        int options = WinDialog.plan(new FileDialog(FileDialog.Kind.SAVE_FILE, null, null, null, "a.txt")).options();
        assertEquals(FOS_OVERWRITEPROMPT, options & FOS_OVERWRITEPROMPT);
        assertEquals(0, options & FOS_FILEMUSTEXIST,
                "a save dialog demanding an existing file could never save a new one");
    }

    @Test
    void theOpenKindsDemandAnExistingFile() {
        int single = WinDialog.plan(new FileDialog(FileDialog.Kind.OPEN_FILE, null, null, null, null)).options();
        int multiple = WinDialog.plan(new FileDialog(FileDialog.Kind.OPEN_FILES, null, null, null, null)).options();
        assertEquals(FOS_FILEMUSTEXIST, single & FOS_FILEMUSTEXIST);
        assertEquals(FOS_FILEMUSTEXIST, multiple & FOS_FILEMUSTEXIST);
    }

    @Test
    void theFilterIsOneRowShowingWhatItMatches() {
        var single = WinDialog.plan(new FileDialog(
                FileDialog.Kind.OPEN_FILE, null, List.of("txt"), null, null)).filters();
        assertEquals(List.of(new WinDialog.Filter("Files (*.txt)", "*.txt")), single);

        var several = WinDialog.plan(new FileDialog(
                FileDialog.Kind.OPEN_FILE, null, List.of("png", "jpg"), null, null)).filters();
        assertEquals(List.of(new WinDialog.Filter("Files (*.png;*.jpg)", "*.png;*.jpg")), several);

        var none = WinDialog.plan(new FileDialog(
                FileDialog.Kind.OPEN_FILE, null, null, null, null)).filters();
        assertEquals(List.of(), none,
                "no extensions in the model means no filter, which is the platform's own list");
    }

    @Test
    void theSaveNameAndDirectoryTravelWithThePlan() {
        var plan = WinDialog.plan(new FileDialog(
                FileDialog.Kind.SAVE_FILE, "Save as", List.of("txt"),
                Path.of(System.getProperty("java.io.tmpdir")), "untitled.txt"));
        assertEquals("untitled.txt", plan.defaultName());
        assertEquals(1, plan.filters().size());
    }

    @Test
    void theCancelledHresultIsA1223AnswerNotA122One() {
        // HRESULT_FROM_WIN32(ERROR_CANCELLED), with ERROR_CANCELLED = 1223: the number a
        // wrong memory would have written (122) is a different error entirely, and would have
        // surfaced as a crash on the first self-test run of a cancelled dialog.
        assertTrue(WinDialog.cancelled(0x800704C7));
        assertFalse(WinDialog.cancelled(0x8007007A),
                "HRESULT 0x8007007A is ERROR_INVALID_PARAMETER, not a cancellation");
        assertFalse(WinDialog.cancelled(0), "S_OK is an answer, not a cancellation");
    }
}