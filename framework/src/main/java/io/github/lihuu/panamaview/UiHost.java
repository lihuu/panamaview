package io.github.lihuu.panamaview;

/**
 * The seam between portable application code and whatever platform actually draws a window
 * and runs a web view.
 *
 * <p>Everything here is expressed in ordinary Java types — {@code String}, {@code Runnable},
 * primitives. No {@code java.lang.foreign}, no Objective-C, no window handles. That is the
 * point: this is the only surface the application layer is allowed to depend on, so a second
 * platform implements this interface and nothing above it changes.
 *
 * <h2>What is deliberately not here</h2>
 * There is no {@code WebViewBackend} hierarchy and no Windows or Linux placeholder. An
 * interface extracted from one implementation is a description of that implementation, while
 * an interface invented for three is a guess. This one was derived from a working macOS
 * implementation, and it should grow only when a second real implementation shows what is
 * genuinely shared. Platform-only operations (snapshot, geometry, resize) are therefore not
 * methods here — they are ordinary bindings registered by the platform's entry point.
 *
 * <h2>Threading contract</h2>
 * Every platform here has a UI thread that owns the event loop, and AppKit-like toolkits insist
 * that window operations happen on it. So:
 * <ul>
 *   <li>{@link #start} is called on, and blocks, the thread that will own the event loop.</li>
 *   <li>{@link #evaluate} must be called on that thread.</li>
 *   <li>{@link #onUiThread} may be called from any thread and must route the action to it.</li>
 *   <li>The incoming-message callback arrives <em>on</em> the UI thread — a property of the
 *       platforms, not a choice — so it must return fast.</li>
 * </ul>
 */
public interface UiHost {

    /**
     * Creates the window, loads the page and runs the platform event loop. Blocks until the
     * application quits, so callbacks are how anything useful happens afterwards.
     */
    void start(Callbacks callbacks, WindowSpec spec);

    /** Runs {@code script} in the page. UI thread only. */
    void evaluate(String script);

    /** Schedules {@code action} on the UI thread. Safe to call from any thread. */
    void onUiThread(Runnable action);

    /** True when the calling thread already is the UI thread. */
    boolean isUiThread();

    /** Callbacks the host makes into the application. */
    interface Callbacks {

        /**
         * A message arrived from the page (the raw JSON body of a script message).
         *
         * <p>Called on the UI thread. Any handler that takes real time must be moved off it —
         * that is {@code Bridge}'s job, not the host's.
         */
        void onMessage(String json);

        /** The page finished loading; the first moment it is safe to call into it. */
        void onPageLoaded(String url, String title);

        /** The page failed to load, with the platform's description of why. */
        void onPageFailed(String reason);

        /**
         * The page began navigating away, so anything in flight belongs to a page that is
         * about to be gone.
         *
         * <p>Optional: it has a default implementation because a host that cannot detect
         * navigation should still be usable, just with requests left to expire on their own.
         */
        default void onPageUnloading() {
        }

        /** Whether closing the last window should end the process. */
        boolean shouldQuitWhenLastWindowCloses();
    }
}
