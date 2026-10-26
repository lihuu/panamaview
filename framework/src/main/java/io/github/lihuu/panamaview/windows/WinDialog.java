package io.github.lihuu.panamaview.windows;

import io.github.lihuu.panamaview.FileDialog;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The Common Item Dialogs — {@code IFileOpenDialog} and {@code IFileSaveDialog} — driven from
 * Java the way the rest of the platform is: hand-counted vtable slots, no glue.
 *
 * <h2>Where the numbers come from</h2>
 * Every slot below was read out of the generated MIDL header for these interfaces
 * (declaration order: IUnknown, then each interface's own methods in order), and the order was
 * cross-checked against a second independent copy of the same declarations. The GUIDs were
 * read from the same headers; the discipline is the one {@code WebView2} states — a slot
 * miscounted by one does not fail loudly, it fails sideways. Note two things the header
 * corrected on the way in: {@code Close} is {@code IFileDialog}'s 24th method (23rd slot), not
 * {@code IModalWindow}'s — which has only {@code Show} — and {@code ERROR_CANCELLED} is
 * 1223, not 122.
 *
 * <h2>Plan, then build</h2>
 * The {@link #plan} half is pure Java and tested without Windows ({@code WinDialogTest}):
 * the model becomes the {@code FOS_*} option flags, the filter spec and the file-name field's
 * default. The native half then only executes. The one guard carried over from
 * {@code WinMenu}: after setting the options they are read back and must contain every flag
 * that was asked for, because a SetOptions call that reached the wrong slot would otherwise
 * leave a dialog that opens with its own defaults and fails nothing.
 *
 * <h2>Threading</h2>
 * {@code Show} runs its own modal loop and returns only when the user decides, so this class
 * is reached on the pump thread from a {@code bindUi} handler — the contract
 * {@code panamaview.DialogHost} states. While the dialog is up its loop pumps the thread's
 * messages, which is what lets a test dismiss it: {@link #cancelActive()} reaches the pump
 * thread's queue through {@code onUiThread}, and the modal loop delivers it.
 */
final class WinDialog {

    private WinDialog() {
    }

    // ---------------------------------------------------------- slots, from the headers

    /** IFileDialog, after IUnknown (0-2) and IModalWindow's one method Show (3). */
    private static final int SHOW = 3;
    private static final int SET_FILE_TYPES = 4;
    private static final int SET_OPTIONS = 9;
    private static final int GET_OPTIONS = 10;
    private static final int SET_FILE_NAME = 15;
    private static final int SET_TITLE = 17;
    private static final int GET_RESULT = 20;
    private static final int CLOSE = 23;

    /** IFileOpenDialog adds its own methods after IFileDialog's last. */
    private static final int GET_RESULTS = 27;

    /** IShellItem, after IUnknown. */
    private static final int GET_DISPLAY_NAME = 5;

    /** IShellItemArray, after IUnknown. */
    private static final int GET_COUNT = 7;
    private static final int GET_ITEM_AT = 8;

    // ---------------------------------------------------------- GUIDs, from the headers

    /** {@code CLSID_FileOpenDialog}. */
    private static final byte[] CLSID_FILE_OPEN = Com.guid("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7");
    /** {@code CLSID_FileSaveDialog}. */
    private static final byte[] CLSID_FILE_SAVE = Com.guid("C0B4E2F3-BA21-4773-8DBA-335EC946EB8B");
    /**
     * {@code IID_IFileDialog} — what a save dialog is asked for, since every method a save
     * needs is on IFileDialog itself.
     */
    private static final byte[] IID_FILE_DIALOG = Com.guid("42F85136-DB7E-439C-85F1-E4075D135FC8");
    /** {@code IID_IFileOpenDialog} — the open dialog is asked for its derived interface, because {@code GetResults} is not on the base. */
    private static final byte[] IID_FILE_OPEN_DIALOG = Com.guid("D57C7288-D4AD-4768-BE02-9D969532D960");

    // ---------------------------------------------------------- option flags, from the headers

    /** {@code FOS_*}, the FILEOPENDIALOGOPTIONS values from the shell headers. */
    private static final int FOS_OVERWRITEPROMPT = 0x2;
    private static final int FOS_PICKFOLDERS = 0x20;
    private static final int FOS_FORCEFILESYSTEM = 0x40;
    private static final int FOS_ALLOWMULTISELECT = 0x200;
    private static final int FOS_PATHMUSTEXIST = 0x800;
    private static final int FOS_FILEMUSTEXIST = 0x1000;

    /** {@code SIGDN_FILESYSPATH} — GetDisplayName's answer as a filesystem path. */
    private static final int SIGDN_FILESYSPATH = 0x80058000;

    /** {@code HRESULT_FROM_WIN32(ERROR_CANCELLED)}, with ERROR_CANCELLED = 1223, not 122. */
    private static final int ERROR_CANCELLED = 0x800704C7;

    /** The dialog whose Show is currently running, for the test-side cancellation. */
    private static volatile MemorySegment active;

    // ------------------------------------------------------------------ plan (pure)

    /** One row of the dialog's filter list: what the dropdown shows, and what it matches. */
    record Filter(String name, String spec) {
    }

    /**
     * What the dialog will be told, decided in pure Java: the {@code FOS_*} flags, the filter
     * row, and the file-name field's default.
     *
     * @param options     the FOS flags, fully specified — SetOptions replaces, not ORs
     * @param filters     one filter row for the extension list, or none when the model had none
     * @param defaultName the save dialog's suggested name, or null
     */
    record Plan(int options, List<Filter> filters, String defaultName) {
    }

    /**
     * Turns the model into what the native calls will say. Pure, and the only place the
     * kinds become flags — so a test can assert that every kind forces the filesystem
     * (these dialogs answer paths, and a non-filesystem item has none) and that only
     * OPEN_FILES asks for multiple selection.
     */
    static Plan plan(FileDialog spec) {
        int options = FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST;
        switch (spec.kind()) {
            case OPEN_FILE -> options |= FOS_FILEMUSTEXIST;
            case OPEN_FILES -> options |= FOS_FILEMUSTEXIST | FOS_ALLOWMULTISELECT;
            case OPEN_DIRECTORY -> options |= FOS_PICKFOLDERS;
            case SAVE_FILE -> options |= FOS_OVERWRITEPROMPT;
        }
        List<Filter> filters = spec.extensions().isEmpty()
                ? List.of()
                : List.of(filterOf(spec.extensions()));
        return new Plan(options, filters, spec.defaultName());
    }

    /** The one filter row the extensions become: the dropdown shows what it matches. */
    private static Filter filterOf(List<String> extensions) {
        StringJoiner patterns = new StringJoiner(";");
        for (String extension : extensions) {
            patterns.add("*." + extension);
        }
        // The dropdown shows what it matches, in Windows' own punctuation: semicolons, no spaces.
        return new Filter("Files (" + patterns + ")", patterns.toString());
    }

    /** Show's answer when the user cancelled — a fact to report, not an error to throw. */
    static boolean cancelled(int hresult) {
        return hresult == ERROR_CANCELLED;
    }

    // ------------------------------------------------------------------ show (native)

    /**
     * Opens the dialog and returns when the user has decided. Pump thread only.
     *
     * @throws IllegalStateException when the window does not exist yet — the dialog is modal
     *                               to it, and an ownerless dialog is modal to the whole desktop
     */
    static FileDialog.Result show(FileDialog spec, MemorySegment hwnd) {
        if (hwnd == null || hwnd.address() == 0) {
            throw new IllegalStateException("the window does not exist yet, so the dialog has "
                    + "nothing to be modal to");
        }
        Plan plan = plan(spec);
        boolean save = spec.kind() == FileDialog.Kind.SAVE_FILE;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dialog = Win32.coCreateInstance(
                    save ? CLSID_FILE_SAVE : CLSID_FILE_OPEN,
                    save ? IID_FILE_DIALOG : IID_FILE_OPEN_DIALOG,
                    save ? "CoCreateInstance(CLSID_FileSaveDialog)" : "CoCreateInstance(CLSID_FileOpenDialog)");
            System.out.println("[panamaview] dialog 0x" + Long.toHexString(dialog.address()) + " created");

            setOptionsWithReadBack(dialog, plan.options());

            if (!plan.filters().isEmpty()) {
                setFileTypes(dialog, plan.filters(), arena);
            }
            if (plan.defaultName() != null) {
                call(dialog, SET_FILE_NAME, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
                        "SetFileName", arena.allocateFrom(plan.defaultName(), StandardCharsets.UTF_16LE));
            }
            if (spec.title() != null) {
                call(dialog, SET_TITLE, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
                        "SetTitle", arena.allocateFrom(spec.title(), StandardCharsets.UTF_16LE));
            }

            active = dialog;
            int shown;
            try {
                System.out.println("[panamaview] Show entering on 0x" + Long.toHexString(dialog.address()));
                shown = (int) Com.bind(dialog, SHOW,
                                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
                        .invoke(dialog, hwnd);
                System.out.println("[panamaview] Show returned 0x" + Integer.toHexString(shown));
            } catch (Throwable t) {
                throw new IllegalStateException("Show could not be called", t);
            } finally {
                active = null;
            }

            if (cancelled(shown)) {
                return new FileDialog.Result(false, List.of());
            }
            Com.check(shown, "Show");

            if (spec.kind() == FileDialog.Kind.OPEN_FILES) {
                return new FileDialog.Result(true, pathsOf(results(dialog), arena));
            }
            MemorySegment item = resultOf(dialog);
            String path = pathOf(item, arena);
            return new FileDialog.Result(true, path == null ? List.of() : List.of(path));
        }
    }

    /**
     * Dismisses the open dialog, if one is.
     *
     * <p>For tests, the way {@code clickMenuItem} is for menus: a self-test has no human to
     * click Cancel, so this is how it dismisses the dialog. {@code Close} is handed
     * {@code ERROR_CANCELLED} so that {@code Show} answers exactly what the Cancel button
     * would have — and the caller sees a cancellation, not a mystery code.
     *
     * <p>Reaches the pump thread through {@code onUiThread} in the caller; the modal loop is
     * what delivers it.
     */
    static boolean cancelActive() {
        // The dialog may not be open yet when the cancellation is asked for — creating it
        // takes longer than the hop that got here. So the wait for it to appear is part of
        // the dismissal, bounded, and the answer says whether there was one to dismiss.
        long deadline = System.nanoTime() + 2_500_000_000L;
        MemorySegment dialog = active;
        while ((dialog == null || dialog.address() == 0) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                return false;
            }
            dialog = active;
        }
        if (dialog == null || dialog.address() == 0) {
            System.out.println("[panamaview] cancelActive: no dialog opened within the wait");
            return false;
        }
        System.out.println("[panamaview] cancelActive: dialog 0x" + Long.toHexString(dialog.address()));
        // The HRESULT argument is what Show's caller receives, so handing it the cancellation
        // makes Show answer exactly what the Cancel button would have.
        int closeResult = callForHresult(dialog, CLOSE,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), "Close", ERROR_CANCELLED);
        System.out.println("[panamaview] Close answered 0x" + Integer.toHexString(closeResult));
        return true;
    }

    /** The raw HRESULT of a vtable call — for the one caller that logs it instead of throwing. */
    private static int callForHresult(MemorySegment object, int slot, FunctionDescriptor descriptor,
                                      String what, Object... args) {
        Object[] all = new Object[args.length + 1];
        all[0] = object;
        System.arraycopy(args, 0, all, 1, args.length);
        try {
            return (int) Com.bind(object, slot, descriptor).invokeWithArguments(all);
        } catch (Throwable t) {
            throw new IllegalStateException(what + " could not be called", t);
        }
    }

    // ------------------------------------------------------------------ native pieces

    /** SetOptions, then GetOptions read back: every flag asked for must still be there. */
    private static void setOptionsWithReadBack(MemorySegment dialog, int options) {
        call(dialog, SET_OPTIONS, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT),
                "SetOptions", options);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_INT);
            call(dialog, GET_OPTIONS, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
                    "GetOptions", out);
            int read = out.get(JAVA_INT, 0);
            System.out.println("[panamaview] options set 0x" + Integer.toHexString(options)
                    + ", read back 0x" + Integer.toHexString(read));
            if ((read & options) != options) {
                throw new IllegalStateException("the dialog kept options 0x"
                        + Integer.toHexString(read) + ", which do not contain the 0x"
                        + Integer.toHexString(options) + " it was given");
            }
        }
    }

    /**
     * The filter list: a {@code COMDLG_FILTERSPEC} array is two {@code LPCWSTR}s per row —
     * what the dropdown shows, and what it matches — with the strings beside it in the arena
     * that dies with the call.
     */
    private static void setFileTypes(MemorySegment dialog, List<Filter> filters, Arena arena) {
        MemorySegment rows = arena.allocate(ADDRESS, filters.size() * 2L);
        for (int i = 0; i < filters.size(); i++) {
            Filter filter = filters.get(i);
            rows.setAtIndex(ADDRESS, i * 2L,
                    arena.allocateFrom(filter.name(), StandardCharsets.UTF_16LE));
            rows.setAtIndex(ADDRESS, i * 2L + 1,
                    arena.allocateFrom(filter.spec(), StandardCharsets.UTF_16LE));
        }
        call(dialog, SET_FILE_TYPES, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
                "SetFileTypes", filters.size(), rows);
    }

    private static MemorySegment resultOf(MemorySegment dialog) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            Com.callGetter(dialog, GET_RESULT, out, "GetResult");
            MemorySegment item = out.getAtIndex(ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            if (item.address() == 0) {
                throw new IllegalStateException("GetResult succeeded but returned no item");
            }
            return item;
        }
    }

    private static MemorySegment results(MemorySegment dialog) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            Com.callGetter(dialog, GET_RESULTS, out, "GetResults");
            MemorySegment array = out.getAtIndex(ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            if (array.address() == 0) {
                throw new IllegalStateException("GetResults succeeded but returned no array");
            }
            return array;
        }
    }

    private static List<String> pathsOf(MemorySegment array, Arena arena) {
        try (Arena counter = Arena.ofConfined()) {
            MemorySegment out = counter.allocate(JAVA_INT);
            Com.callGetter(array, GET_COUNT, out, "GetCount");
            int count = out.get(JAVA_INT, 0);
            java.util.ArrayList<String> paths = new java.util.ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                MemorySegment itemOut = counter.allocate(ADDRESS);
                call(array, GET_ITEM_AT, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
                        "GetItemAt", i, itemOut);
                String path = pathOf(itemOut.getAtIndex(ADDRESS, 0).reinterpret(Long.MAX_VALUE), arena);
                if (path != null) {
                    paths.add(path);
                }
            }
            return paths;
        }
    }

    /**
     * An item's filesystem path: {@code GetDisplayName(SIGDN_FILESYSPATH)} writes a string the
     * COM task allocator made, so it is read and then freed by that same allocator — the only
     * one that may.
     */
    private static String pathOf(MemorySegment item, Arena arena) {
        MemorySegment out = arena.allocate(ADDRESS);
        call(item, GET_DISPLAY_NAME, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
                "GetDisplayName", SIGDN_FILESYSPATH, out);
        MemorySegment value = out.getAtIndex(ADDRESS, 0);
        if (value.address() == 0) {
            return null;
        }
        try {
            return value.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
        } finally {
            try {
                Win32.CO_TASK_MEM_FREE.invoke(value);
            } catch (Throwable ignored) {
                // freeing must never mask the answer
            }
        }
    }

    private static void call(MemorySegment object, int slot, FunctionDescriptor descriptor,
                            String what, Object... args) {
        // The vtable's first argument is the object itself, and the rest follow it —
        // assembled here rather than at each site, where a slot's descriptor and its
        // arguments could drift apart.
        Object[] all = new Object[args.length + 1];
        all[0] = object;
        System.arraycopy(args, 0, all, 1, args.length);
        try {
            int hresult = (int) Com.bind(object, slot, descriptor).invokeWithArguments(all);
            Com.check(hresult, what);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(what + " could not be called", t);
        }
    }
}