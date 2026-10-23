package io.github.lihuu.panamaview;

/**
 * A host that can take a menu.
 *
 * <p>Optional, and deliberately a separate interface rather than another method on
 * {@link UiHost}: {@code UiHost} is a published type, and a new abstract method on it breaks
 * every implementation outside this repository. A host that does not implement this simply has
 * no menu, and the application still runs — a host that cannot take a menu is still usable.
 * The same reasoning put {@link UiHost.Callbacks#onPageUnloading()} behind a default method.
 *
 * <p>The bar is handed over before the platform starts, because a platform builds its native
 * menu during start-up: a bar handed over afterwards would arrive too late to be built into
 * either menu.
 */
public interface MenuHost {

    /**
     * Takes the application's menu bar.
     *
     * <p>Called once, before {@link UiHost#start}. The bar is already assembled: the ids the
     * platform puts on its own items are the ones the framework assigned while it built the
     * model, and a click comes back as one of them.
     */
    void setMenu(MenuBar bar);
}
