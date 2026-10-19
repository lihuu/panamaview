package io.github.lihuu.panamaview.macos;

import java.lang.foreign.MemorySegment;

/**
 * The system pasteboard: read text, write text, notice that it changed.
 *
 * <p>This is the half of "clipboard support" the keyboard does not cover. The other half — ⌘C and
 * ⌘V inside the page — is AppKit's own business: {@link Cocoa#installMenuBar} gives the responder
 * chain the actions, and {@code WKContentView} performs them on whatever WebKit has selected. That
 * path needs no binding because no Java runs in it.
 *
 * <p>What needs a binding is the program calling the clipboard: a Java handler behind a "copy"
 * button, an app that reads the clipboard at start-up, a page that wants to copy something it
 * fetched asynchronously (where the browser's transient user activation has already expired). The
 * {@code NSPasteboard} calls are three messages long, which is why the alternative — starting AWT
 * inside a process that is otherwise AppKit-only — is hard to justify.
 *
 * <h2>Threading</h2>
 * Unlike most of AppKit, the general pasteboard is safe to talk to from any thread, so these
 * methods do not require the UI thread and can be called from an ordinary binding handler. The
 * change counter is the platform's own: every write — by this app or any other — bumps it.
 */
final class Pasteboard {

    /** {@code NSPasteboardTypeString}: the plain-text type, as a string. */
    private static final String TYPE_STRING = "public.utf8-plain-text";

    private Pasteboard() {
    }

    /** The clipboard's text, or {@code null} when it holds no string. */
    static String readText() {
        try (ObjC.Scope scope = ObjC.scope()) {
            MemorySegment value = ObjC.send(general(),
                    ObjC.selector("stringForType:"), ObjC.nsString(scope, TYPE_STRING));
            return ObjC.toJavaString(value);
        }
    }

    /**
     * Replaces the clipboard with {@code text}.
     *
     * <p>{@code clearContents} first is what makes this a replacement rather than an addition: it
     * bumps the change counter and drops every other representation the pasteboard was holding.
     */
    static void writeText(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        try (ObjC.Scope scope = ObjC.scope()) {
            MemorySegment pasteboard = general();
            ObjC.sendLong(pasteboard, ObjC.selector("clearContents"));
            boolean written = ObjC.sendBool(pasteboard, ObjC.selector("setString:forType:"),
                    ObjC.nsString(scope, text), ObjC.nsString(scope, TYPE_STRING));
            if (!written) {
                throw new IllegalStateException("the pasteboard refused the string");
            }
        }
    }

    /** The pasteboard's change counter — equal values mean nothing was copied since last time. */
    static long changeCount() {
        return ObjC.sendLong(general(), ObjC.selector("changeCount"));
    }

    private static MemorySegment general() {
        return ObjC.send(ObjC.classNamed("NSPasteboard"), ObjC.selector("generalPasteboard"));
    }
}
