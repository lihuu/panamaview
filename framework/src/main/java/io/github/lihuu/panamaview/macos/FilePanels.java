package io.github.lihuu.panamaview.macos;

import io.github.lihuu.panamaview.FileDialog;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code NSOpenPanel} and {@code NSSavePanel}, driven from Java the way the rest of AppKit is:
 * one {@code objc_msgSend} per call site, no glue. Every message shape this class needs was
 * already in the reachability metadata before it was written — the shapes are shared with the
 * rest of AppKit — so a native build works the first time, and the CI check on the metadata
 * keeps it that way.
 *
 * <h2>The modal loop</h2>
 * {@code runModal} runs AppKit's own nested event loop and returns only when the user
 * decides, so this class is only ever reached on the UI thread from a {@code bindUi} handler
 * — that is the contract {@code panamaview.DialogHost} states. While the loop runs, the main
 * dispatch queue keeps draining, which is what lets a test dismiss the panel from a worker
 * thread: {@link #cancelActive()} hops onto the UI thread with {@code dispatch_async}, and
 * the modal loop delivers it.
 *
 * <h2>Lifetime</h2>
 * The panels come from the autoreleased convenience constructors ({@code openPanel},
 * {@code savePanel}), so all reading of their answers happens inside one {@link ObjC.Scope}
 * and nothing is retained beyond the user's decision.
 */
final class FilePanels {

    private FilePanels() {
    }

    /** {@code NSModalResponseOK} — every other response, including cancel, is "not accepted". */
    private static final long MODAL_RESPONSE_OK = 1L;

    /** The panel whose {@code runModal} is currently running, for the test-side cancellation. */
    private static volatile MemorySegment active;

    /** Opens the dialog and returns when the user has decided. UI thread only. */
    static FileDialog.Result open(FileDialog spec) {
        try (ObjC.Scope scope = ObjC.scope()) {
            MemorySegment panel = spec.kind() == FileDialog.Kind.SAVE_FILE
                    ? ObjC.send(ObjC.classNamed("NSSavePanel"), ObjC.selector("savePanel"))
                    : ObjC.send(ObjC.classNamed("NSOpenPanel"), ObjC.selector("openPanel"));

            if (spec.title() != null) {
                ObjC.sendVoid(panel, ObjC.selector("setTitle:"), ObjC.nsString(scope, spec.title()));
            }
            if (!spec.extensions().isEmpty()) {
                ObjC.sendVoid(panel, ObjC.selector("setAllowedFileTypes:"),
                        allowedTypes(scope, spec.extensions()));
            }
            if (spec.directory() != null) {
                ObjC.sendVoid(panel, ObjC.selector("setDirectoryURL:"), ObjC.send(
                        ObjC.classNamed("NSURL"), ObjC.selector("fileURLWithPath:"),
                        ObjC.nsString(scope, spec.directory().toString())));
            }

            switch (spec.kind()) {
                case OPEN_FILE -> configureOpenPanel(panel, true, false, false);
                case OPEN_FILES -> configureOpenPanel(panel, true, false, true);
                case OPEN_DIRECTORY -> configureOpenPanel(panel, false, true, false);
                case SAVE_FILE -> {
                    if (spec.defaultName() != null) {
                        ObjC.sendVoid(panel, ObjC.selector("setNameFieldStringValue:"),
                                ObjC.nsString(scope, spec.defaultName()));
                    }
                }
            }

            active = panel;
            long response;
            try {
                response = ObjC.sendLong(panel, ObjC.selector("runModal"));
            } finally {
                active = null;
            }

            if (response != MODAL_RESPONSE_OK) {
                return new FileDialog.Result(false, List.of());
            }
            if (spec.kind() == FileDialog.Kind.SAVE_FILE) {
                MemorySegment url = ObjC.send(panel, ObjC.selector("URL"));
                String path = ObjC.toJavaString(ObjC.send(url, ObjC.selector("path")));
                return new FileDialog.Result(true, path == null ? List.of() : List.of(path));
            }
            return new FileDialog.Result(true, pathsOf(ObjC.send(panel, ObjC.selector("URLs"))));
        }
    }

    private static void configureOpenPanel(MemorySegment panel, boolean files,
                                          boolean directories, boolean multiple) {
        ObjC.sendVoidBool(panel, ObjC.selector("setCanChooseFiles:"), files);
        ObjC.sendVoidBool(panel, ObjC.selector("setCanChooseDirectories:"), directories);
        ObjC.sendVoidBool(panel, ObjC.selector("setAllowsMultipleSelection:"), multiple);
        ObjC.sendVoidBool(panel, ObjC.selector("setCanCreateDirectories:"), true);
    }

    /**
     * The filter as an {@code NSArray} of bare extensions, built the way Cocoa wants a growing
     * collection built. The array and its strings are autoreleased — valid until the scope
     * closes, which outlives the panel by exactly as long as this method's caller.
     */
    private static MemorySegment allowedTypes(ObjC.Scope scope, List<String> extensions) {
        MemorySegment array = ObjC.send(ObjC.classNamed("NSMutableArray"),
                ObjC.selector("arrayWithCapacity:"), (long) extensions.size());
        for (String extension : extensions) {
            ObjC.sendVoid(array, ObjC.selector("addObject:"), ObjC.nsString(scope, extension));
        }
        return array;
    }

    /** Reads an {@code NSArray} of file URLs into paths: a count, then {@code objectAtIndex:}. */
    private static List<String> pathsOf(MemorySegment urls) {
        long count = ObjC.sendLong(urls, ObjC.selector("count"));
        List<String> paths = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            MemorySegment url = ObjC.send(urls, ObjC.selector("objectAtIndex:"), i);
            String path = ObjC.toJavaString(ObjC.send(url, ObjC.selector("path")));
            if (path != null) {
                paths.add(path);
            }
        }
        return paths;
    }

    /**
     * Dismisses the open dialog, if one is.
     *
     * <p>For tests, the way {@code MacOsHost.clickMenuItem} is for menus: there is no way to
     * dismiss a panel without a human, so this is how a self-test does it. {@code cancel:} is
     * the panel's own Cancel-button action, so {@code runModal} answers exactly what a click
     * on Cancel would have.
     *
     * <p>Must be called on the UI thread — the same thread {@code runModal} is blocking —
     * which a caller reaches through {@code UiThread.run}; the modal loop delivers it.
     */
    static boolean cancelActive() {
        // The panel may not be open yet when the cancellation is asked for — opening it
        // takes longer than the hop that got here. So the wait for it to appear is part of
        // the dismissal, bounded, and the answer says whether there was one to dismiss.
        long deadline = System.nanoTime() + 2_500_000_000L;
        MemorySegment panel = active;
        while ((panel == null || ObjC.isNull(panel)) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                return false;
            }
            panel = active;
        }
        if (panel == null || ObjC.isNull(panel)) {
            return false;
        }
        // `cancel:` is the panel's own Cancel-button action, so `runModal` answers exactly
        // what a click on Cancel would have.
        ObjC.sendVoid(panel, ObjC.selector("cancel:"));
        return true;
    }
}