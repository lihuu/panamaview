package io.github.lihuu.panamaview;

/**
 * A host that can show a tray icon.
 *
 * <p>Optional, and deliberately a separate interface rather than another method on
 * {@link UiHost} — the same reasoning {@link MenuHost} records: {@code UiHost} is a published
 * type, and a new abstract method on it breaks every implementation outside this repository. A
 * host that does not implement this simply has no tray, and the application still runs. That is
 * also what a platform without a host — Linux, today — relies on.
 *
 * <p>The spec is handed over before {@link UiHost#start}, because both platforms build the icon
 * while they are starting: macOS needs the status item on the main thread before the run loop,
 * and Windows needs the window whose callback message the icon reports to.
 */
public interface TrayHost {

    /**
     * Takes the application's tray.
     *
     * <p>Called once, before {@link UiHost#start}. The items are already numbered: the ids the
     * platform puts on its own items are the ones the framework assigned while it numbered the
     * model, and a click comes back as one of them — through
     * {@link UiHost.Callbacks#onTrayMenuItem(int)}, which is a different callback from the menu
     * bar's because the two surfaces number their items independently.
     */
    void setTray(TraySpec tray);
}
