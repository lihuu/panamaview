package io.github.lihuu.panamaview.windows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The loader is found beside the executable, not only in the working directory.
 *
 * <p>A packaged application is started by a shortcut or by the shell, and the working directory
 * it inherits is whatever the launcher gives it — not necessarily the install directory. Before
 * this rule existed, an installed app found {@code WebView2Loader.dll} only if it happened to be
 * launched from its own folder, and failed with "not found" otherwise. The search is ordinary
 * path arithmetic, so it is tested here without Windows, a window server, or the DLL itself.
 */
class LoaderLookupTest {

    private static final String DLL = "WebView2Loader.dll";

    @TempDir
    Path beside;

    @TempDir
    Path working;

    @Test
    void findsTheDllBesideTheExecutable() throws Exception {
        Path dll = Files.createFile(beside.resolve(DLL));

        assertEquals(dll, WindowsHost.locateLoader(beside, working));
    }

    @Test
    void findsTheDllInALoaderSubdirectoryBesideTheExecutable() throws Exception {
        Path dll = Files.createFile(Files.createDirectories(beside.resolve("loader")).resolve(DLL));

        assertEquals(dll, WindowsHost.locateLoader(beside, working));
    }

    @Test
    void fallsBackToTheWorkingDirectory() throws Exception {
        Path dll = Files.createFile(Files.createDirectories(working.resolve("loader")).resolve(DLL));

        assertEquals(dll, WindowsHost.locateLoader(beside, working));
    }

    @Test
    void prefersTheExecutablesDirectoryOverTheWorkingDirectory() throws Exception {
        Path nextToTheExe = Files.createFile(beside.resolve(DLL));
        Files.createFile(working.resolve(DLL));

        assertEquals(nextToTheExe, WindowsHost.locateLoader(beside, working));
    }

    @Test
    void findsNothingWhenTheDllIsNowhere() {
        assertNull(WindowsHost.locateLoader(beside, working));
    }

    @Test
    void toleratesAnUnknownExecutableDirectory() throws Exception {
        Path dll = Files.createFile(working.resolve(DLL));

        assertEquals(dll, WindowsHost.locateLoader(null, working));
    }
}
