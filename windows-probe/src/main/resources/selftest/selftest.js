"use strict";

/*
 * The bridge self-test, on Windows.
 *
 * A port of the macOS demo's self-test: the same checks, the same reasoning, minus the two
 * that are macOS-only (a by-value NSRect round trip and a programmatic resize — Windows has
 * neither bound yet). What is left is the portable half of the contract, which is the half
 * the two platforms have to agree on.
 *
 * The verdict is handed to Java so it lands in stdout, where a machine can read it. A test
 * whose result can only be read by looking at a screen is not a test anyone can run.
 */

(() => {
  const log = (line) => {
    document.getElementById("log").textContent += line + "\n";
  };

  const results = [];
  const check = (name, ok, detail) => {
    results.push({ name, ok, detail });
    log((ok ? "PASS" : "FAIL") + "  " + name + (detail ? " - " + detail : ""));
    return ok;
  };

  window.onJavaMessage = async (payload) => {
    if (payload.autotest) {
      await selfTest();
    }
  };

  async function selfTest() {
    // 1. A plain call.
    try {
      const greeting = await java.invoke("hello", { name: "World" });
      check("hello", greeting === "Hello World", greeting);
    } catch (error) {
      check("hello", false, String(error.message));
    }

    // 2. Structured values survive the trip.
    try {
      const sent = { a: 1, b: "two", c: [3, 4], d: { nested: true }, e: null };
      const echoed = await java.invoke("echo", sent);
      check("json round trip", JSON.stringify(sent) === JSON.stringify(echoed),
            JSON.stringify(echoed));
    } catch (error) {
      check("json round trip", false, String(error.message));
    }

    // 3. A slow handler must not block the window. Replies are delivered by the UI thread, so
    //    a slow handler still occupying that thread would delay this call by its whole duration.
    try {
      const slow = java.invoke("slow", { ms: 600 });
      const started = performance.now();
      await java.invoke("hello", { name: "overtake" });
      const elapsed = performance.now() - started;
      check("slow handler does not block", elapsed < 150,
            "hello answered in " + elapsed.toFixed(0) + " ms while slow(600 ms) was in flight");
      await slow;
    } catch (error) {
      check("slow handler does not block", false, String(error.message));
    }

    // 4. The control. Same work, same duration, on the UI thread: it *should* delay.
    //     Without this, the check above could pass for reasons unrelated to scheduling.
    try {
      const blocking = java.invoke("slowUi", { ms: 400 });
      const started = performance.now();
      await java.invoke("hello", { name: "blocked" });
      const elapsed = performance.now() - started;
      check("control: UI-thread handler does block", elapsed >= 300,
            "hello delayed " + elapsed.toFixed(0) + " ms behind slowUi(400 ms)");
      await blocking;
    } catch (error) {
      check("control: UI-thread handler does block", false, String(error.message));
    }

    // 5. Cancellation, tested honestly: the promise rejecting locally proves nothing about
    //    Java. What has to be shown is that the handler was really stopped, so the handler
    //    counts its own interruptions and the page reads the counter back.
    try {
      const before = await java.invoke("interrupts", {});
      const doomed = java.invoke("interruptible", { ms: 5000 });
      await new Promise((resolve) => setTimeout(resolve, 200));
      const started = performance.now();
      doomed.cancel();
      const localReject = performance.now() - started;
      await new Promise((resolve) => setTimeout(resolve, 400));
      const after = await java.invoke("interrupts", {});
      check("cancel actually stops the handler", after === before + 1,
            "handler interrupted (count " + before + " -> " + after + "); page stopped waiting in "
            + localReject.toFixed(0) + " ms");
    } catch (error) {
      check("cancel actually stops the handler", false, String(error.message));
    }

    // 6. Java's own deadline, independent of the page's fuse. The page allows 10 s here and
    //    the handler's deadline is 300 ms, so a ~300 ms rejection proves Java's timer fired.
    try {
      const started = performance.now();
      let message = "no rejection?";
      try {
        await java.invoke("deadlineProbe", {}, { deadlineMs: 10000 });
      } catch (error) {
        message = String(error.message);
      }
      const elapsed = performance.now() - started;
      check("java-side deadline fires on its own",
            elapsed < 2000 && /no answer within 300 ms/.test(message),
            "rejected after " + elapsed.toFixed(0) + " ms with \"" + message + "\"");
    } catch (error) {
      check("java-side deadline fires on its own", false, String(error.message));
    }

    // 7. A binding with no deadline must still work.
    try {
      const result = await java.invoke("unbounded", { ms: 200 });
      check("deadline can be disabled", /no deadline/.test(result), result);
    } catch (error) {
      check("deadline can be disabled", false, String(error.message));
    }

    // 8. The platform extra runs on the UI thread; ordinary handlers must not.
    try {
      const info = await java.invoke("windowInfo", {});
      check("platform extra runs on UI thread", info.onUiThread === true,
            "window " + info.window + ", onUiThread=" + info.onUiThread);
    } catch (error) {
      check("windowInfo", false, String(error.message));
    }

    // 9. Runtime facts only Java can supply, including which thread answered.
    try {
      const info = await java.invoke("runtime", {});
      check("ordinary handler runs off the UI thread",
            info.handlerIsVirtual === true && info.handlerOnUiThread === false,
            info.vmName + ", handler on " + info.handlerThread);
    } catch (error) {
      check("runtime", false, String(error.message));
    }

    // 10. Two hundred replies, correlated by id rather than by arrival order.
    try {
      const count = 200;
      const started = performance.now();
      const replies = await Promise.all(
        Array.from({ length: count }, (_, i) => java.invoke("hello", { name: "#" + i }))
      );
      const ordered = replies.every((r, i) => r === "Hello #" + i);
      const ms = performance.now() - started;
      check("200 concurrent invocations", ordered, count + " round trips in " + ms.toFixed(0) + " ms");
    } catch (error) {
      check("200 concurrent invocations", false, String(error.message));
    }

    // 11. A menu item, chosen.
    //
    // The check asserts the effect — that the click reached the binding — and not the
    // absence of an exception: a click that goes nowhere does not throw, it does nothing,
    // silently. The count is the only evidence that the dispatch path ran.
    //
    // The wait for the effect lives in Java, inside the click, because the click crosses
    // three hops: the message to the window, the pump thread's WM_COMMAND handling, and the
    // binding on a virtual thread. The answer therefore arrives with the result, in one
    // round trip, and the page never has to poll.
    //
    // Two items, not one, and the second one is the one after the separator. The ids here
    // are explicit rather than positional, so the trap is not a count but the separator
    // itself: the model numbers the separator too, so the item after it does not shift,
    // and the native menu reports 0 for a separator. An off-by-one in the id mapping would
    // send the second click to the wrong item — silently, which is what this check exists
    // to rule out.
    try {
      const first = await java.invoke("menuClick", {});
      const second = await java.invoke("menuClick", { item: "menuProbe2" });
      check("menu item reaches its binding", first >= 1 && second >= 1,
            "the menu item ran the binding " + first + " time(s), and the one after the "
            + "separator " + second);
    } catch (error) {
      check("menu item reaches its binding", false, String(error.message));
    }

    // 11b. The tray: the third surface, its own id table, its own callback.
    //
    // Three assertions, in the order a broken tray would fail them. The icon was added at
    // all — Shell_NotifyIconW's answer is the only evidence a session-0 run can honestly
    // hold, and a run whose icon was refused has nothing else to say. A callback message
    // posted the way a mouse event posts reaches the host's procedure. And a dispatched
    // command reaches its binding — the effect, not the absence of an exception, exactly
    // as the menu check above says.
    //
    // What no run here can assert is the icon a person sees and clicks: GitHub's Windows
    // runners are in session 0, with no Explorer and no notification area. That part is the
    // interactive run on a real desktop, and saying so is part of the result.
    try {
      const info = await java.invoke("trayInfo", {});
      check("tray icon accepted and added", info.accepted === true && info.added === true,
            "accepted=" + info.accepted + ", added=" + info.added
            + ", built=" + info.builtItems + "/" + info.declaredItems
            + ', tooltip="' + info.tooltip + '"');
    } catch (error) {
      check("tray icon accepted and added", false, String(error.message));
    }
    try {
      const after = await java.invoke("probeTrayCallback", {});
      check("tray callback reaches the host", after.callbackSeen === true,
            "callbackSeen=" + after.callbackSeen);
    } catch (error) {
      check("tray callback reaches the host", false, String(error.message));
    }
    try {
      const clicked = await java.invoke("trayClick", {});
      check("tray item reaches its binding", clicked >= 1,
            "the tray item ran the binding " + clicked + " time(s)");
    } catch (error) {
      check("tray item reaches its binding", false, String(error.message));
    }

    // 12. A native file dialog, opened and dismissed without a human.
    //
    // A self-test cannot choose a file — choosing is what a person is for — so the check
    // proves the machinery around that choice: the dialog opens on the pump thread, a
    // programmatic dismissal closes it, the answer is an honest "not accepted", and a round
    // trip afterwards proves the modal loop left nothing wedged behind it. The dismissal
    // travels through the queue the modal loop keeps pumping, so a dialog that never opened,
    // or a loop that does not pump, cannot pass this.
    try {
      const armed = await java.invoke("cancelDialogSoon", {});
      const picked = await java.invoke("pickFile",
            { kind: "file", title: "panamaview self-test", extensions: ["txt"] });
      const after = await java.invoke("hello", { name: "after the dialog" });
      check("file dialog opens, cancels and returns",
            armed === "armed" && picked.ok === false
                  && after === "Hello after the dialog",
            `armed=${armed}, picked=${JSON.stringify(picked)}, hello after="${after}"`);
    } catch (error) {
      check("file dialog opens, cancels and returns", false, String(error.message));
    }

    // The verdict goes to Java so it lands in stdout.
    const passed = results.filter((r) => r.ok).length;
    try {
      await java.invoke("report", { passed, failed: results.length - passed, checks: results });
    } catch (error) {
      log("error  report: " + error.message);
    }
  }
})();
