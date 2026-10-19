package panamaview.macos;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.util.UUID;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * The system notification centre: ask for permission, then post.
 *
 * <h2>The one hard precondition</h2>
 * {@code UNUserNotificationCenter} does not ask the process for an identifier; it asks
 * LaunchServices for the bundle proxy of the running process. A process that is not inside a
 * {@code .app} has no proxy, and {@code +currentNotificationCenter} does not fail politely —
 * it raises {@code NSInternalInconsistencyException} ("bundleProxyForCurrentProcess is nil"),
 * which is a process-level abort, not an exception Java can catch.
 *
 * <p>What makes this subtle is that the obvious pre-flight check is wrong. A bare
 * {@code java -cp} process <em>does</em> report a bundle identifier — the JVM's own
 * ({@code net.java.openjdk.java}, synthesised from {@code .../Contents/Home/bin/java}) — and is
 * still not an app. So the question below is not "is there an identifier" but "is this process
 * running from an application bundle": the main bundle's URL ends in {@code .app}. That is the
 * one value observed to distinguish the two cases, and it is why the check cannot be skipped.
 *
 * <p>Which means notifications are not a framework-shape question. panamaview does not become an
 * app; the <em>running process</em> has to be in a bundle, and that is decided by packaging —
 * {@code jpackage}, a native-image binary placed in {@code Contents/MacOS/}, or the development
 * shell in {@code scripts/make-dev-app.sh}. Everything here works the moment that is true, and
 * degrades to {@link #canNotify()} returning false when it is not.
 *
 * <h2>Why not the Web Notification API</h2>
 * WebKit's notification permission is decided by a private {@code WKUIDelegate} SPI
 * ({@code _webView:requestNotificationPermissionForSecurityOrigin:decisionHandler:}) — the public
 * delegate (checked against the macOS 27 SDK) has media capture and geolocation only. Even if the
 * SPI were implemented, delivery still happens under the app's identity, so it does not lift the
 * bundle requirement. Pages therefore get notifications the same way they get everything else
 * here: {@code java.invoke("notify", …)}, through the bridge.
 */
final class Notifications {

    /** {@code UNAuthorizationOptionAlert | Sound | Badge} — the three ordinary things a banner does. */
    private static final long ALERT_SOUND_BADGE = (1L << 2) | (1L << 1) | (1L << 0);

    /** {@code void (^)(BOOL granted, NSError *error)} — requestAuthorization's completion. */
    private static final FunctionDescriptor AUTHORIZATION_CALLBACK =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_BYTE, ADDRESS);

    /** {@code void (^)(NSError *error)} — addNotificationRequest's completion. */
    private static final FunctionDescriptor POST_CALLBACK = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

    static final String NO_BUNDLE =
            "notifications need the process to run from an application bundle (.app): without one the "
                    + "system notification centre cannot identify the process and aborts it. Run the app "
                    + "from a .app (for development: scripts/make-dev-app.sh) or package it.";

    private static boolean loaded;

    /** The request currently waiting for an answer. One at a time: the system asks once. */
    private static volatile Consumer<Boolean> pendingAuthorization;

    private Notifications() {
    }

    /**
     * Loads UserNotifications on first use rather than at class-initialisation time: an app that
     * never notifies should not pay for the framework, and {@code objc_getClass} only sees
     * classes from frameworks that have been opened.
     */
    static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        ObjC.loadLibrary("/System/Library/Frameworks/UserNotifications.framework/UserNotifications");
        loaded = true;
    }

    /**
     * The path of the {@code .app} this process runs from, or {@code null} when it runs from no
     * bundle at all.
     *
     * <p>Checked on the URL rather than on the identifier on purpose: an unbundled JVM hands back
     * a perfectly plausible identifier belonging to the JDK, and that answer is useless here.
     */
    static String applicationBundlePath() {
        try (ObjC.Scope scope = ObjC.scope()) {
            MemorySegment bundle = ObjC.send(ObjC.classNamed("NSBundle"), ObjC.selector("mainBundle"));
            MemorySegment url = ObjC.send(bundle, ObjC.selector("bundleURL"));
            String path = ObjC.toJavaString(ObjC.send(url, ObjC.selector("path")));
            return path != null && path.endsWith(".app") ? path : null;
        }
    }

    /** The application's bundle identifier, or {@code null} when this process is not an app. */
    static String bundleIdentifier() {
        if (applicationBundlePath() == null) {
            return null;
        }
        try (ObjC.Scope scope = ObjC.scope()) {
            MemorySegment bundle = ObjC.send(ObjC.classNamed("NSBundle"), ObjC.selector("mainBundle"));
            return ObjC.toJavaString(ObjC.send(bundle, ObjC.selector("bundleIdentifier")));
        }
    }

    /** True when this process can address the notification centre at all. */
    static boolean canNotify() {
        return applicationBundlePath() != null;
    }

    /** Refuses to reach the notification centre from a process that would abort on the way. */
    private static void requireBundle() {
        if (applicationBundlePath() != null) {
            return;
        }
        throw new IllegalStateException(NO_BUNDLE + " (main bundle: " + mainBundlePath() + ")");
    }

    private static String mainBundlePath() {
        try (ObjC.Scope scope = ObjC.scope()) {
            MemorySegment bundle = ObjC.send(ObjC.classNamed("NSBundle"), ObjC.selector("mainBundle"));
            MemorySegment url = ObjC.send(bundle, ObjC.selector("bundleURL"));
            return ObjC.toJavaString(ObjC.send(url, ObjC.selector("path")));
        }
    }

    /**
     * Asks once, and answers through the callback on the UI thread.
     *
     * <p>The system remembers the answer per app, so calling this again after a decision returns
     * that same decision without a prompt — which is why the caller should ask where a prompt
     * makes sense (a button), not at start-up.
     */
    static void requestAuthorization(Consumer<Boolean> onResult) {
        ensureLoaded();
        requireBundle();
        pendingAuthorization = onResult;
        ObjC.sendVoidLongObject(center(),
                ObjC.selector("requestAuthorizationWithOptions:completionHandler:"),
                ALERT_SOUND_BADGE,
                NativeBlock.of(Notifications.class, "handleAuthorization", AUTHORIZATION_CALLBACK));
    }

    /** Posts a notification immediately — no trigger, so the centre delivers it now. */
    static void post(String title, String body) {
        ensureLoaded();
        requireBundle();
        try (ObjC.Scope scope = ObjC.scope()) {
            MemorySegment content = ObjC.newObject("UNMutableNotificationContent");
            ObjC.sendVoid(content, ObjC.selector("setTitle:"), ObjC.nsString(scope, title));
            ObjC.sendVoid(content, ObjC.selector("setBody:"), ObjC.nsString(scope, body));

            MemorySegment request = ObjC.send(ObjC.classNamed("UNNotificationRequest"),
                    ObjC.selector("requestWithIdentifier:content:trigger:"),
                    ObjC.nsString(scope, "panamaview-" + UUID.randomUUID()),
                    content,
                    // A nil trigger is what makes it "now" rather than scheduled.
                    MemorySegment.NULL);

            ObjC.sendVoid(center(), ObjC.selector("addNotificationRequest:withCompletionHandler:"),
                    request,
                    NativeBlock.of(Notifications.class, "handlePost", POST_CALLBACK));
        }
    }

    // ---------------------------------------------------------------- callbacks

    /**
     * The authorization answer, arriving from UserNotifications' own queue.
     *
     * <p>Must be public and must not throw: this is reached from native code through a reflective
     * lookup, and an exception escaping an upcall aborts the process.
     */
    public static void handleAuthorization(MemorySegment block, byte granted, MemorySegment error) {
        try {
            Consumer<Boolean> callback = pendingAuthorization;
            pendingAuthorization = null;
            boolean allowed = granted != 0;
            if (!allowed && !ObjC.isNull(error)) {
                System.err.println("[panamaview] notification permission failed: " + describe(error));
            }
            if (callback != null) {
                boolean answer = allowed;
                UiThread.run(() -> callback.accept(answer));
            }
        } catch (Throwable t) {
            System.err.println("[panamaview] notification permission callback failed: " + t);
        }
    }

    public static void handlePost(MemorySegment block, MemorySegment error) {
        try {
            if (!ObjC.isNull(error)) {
                System.err.println("[panamaview] notification failed: " + describe(error));
            }
        } catch (Throwable t) {
            System.err.println("[panamaview] notification callback failed: " + t);
        }
    }

    private static MemorySegment center() {
        return ObjC.send(ObjC.classNamed("UNUserNotificationCenter"),
                ObjC.selector("currentNotificationCenter"));
    }

    private static String describe(MemorySegment error) {
        return ObjC.toJavaString(ObjC.send(error, ObjC.selector("localizedDescription")));
    }
}
