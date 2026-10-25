package io.github.lihuu.panamaview;

/**
 * A host that can open a native file dialog.
 *
 * <p>Optional, and deliberately a separate interface rather than another method on
 * {@link UiHost}, for the same reason {@link MenuHost} is one: {@code UiHost} is a published
 * type, and a new abstract method on it breaks every implementation outside this repository.
 * A host that does not implement this simply cannot open dialogs, and the application still
 * runs.
 *
 * <h2>Threading contract</h2>
 * {@link #open} runs on the UI thread and <em>blocks</em> there until the user decides — that
 * is what a modal dialog is on both platforms. Bind the handler that calls it with
 * {@link WebApp#bindUi}, never {@link WebApp#bind}: the ordinary 30-second deadline would
 * interrupt a handler the user was simply still choosing in, and an off-thread call would be
 * refused by a platform whose window this dialog must be modal to.
 *
 * <p>While the dialog is up there is nothing frozen except the window behind it: both
 * platforms run the dialog as its own event loop, so replies to other in-flight bindings
 * still arrive, and the queue of UI-thread work drains when the dialog closes. A dialog left
 * open is the one honest case of a {@code bindUi} handler that takes minutes, which is why
 * {@code bindUi} deliberately applies no deadline.
 */
public interface DialogHost {

    /**
     * Opens the dialog {@code spec} describes and returns when the user has decided.
     *
     * <p>UI thread only. A cancellation is {@link FileDialog.Result#accepted()} false — an
     * answer, not an error.
     */
    FileDialog.Result open(FileDialog spec);
}