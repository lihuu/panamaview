"use strict";

/*
 * The demo application — and deliberately *application* code.
 *
 * The bridge itself is not here. It lives in panamaview.js, which the framework stages
 * into the web root next to this file, so it always matches the panamaview on the
 * classpath. An app written with panamaview is wiring, not plumbing:
 *
 *     const greeting = await java.invoke("hello", { name: "World" });
 *     window.onJavaMessage = (payload) => { … };
 *
 * Replies are matched by id and may arrive out of order; nothing here assumes otherwise.
 */

(() => {
  const $ = (id) => document.getElementById(id);

  const log = (line) => {
    const el = $("log");
    el.textContent += `${new Date().toLocaleTimeString()}  ${line}\n`;
    el.scrollTop = el.scrollHeight;
  };

  // ------------------------------------------------------------- Java → page

  window.onJavaMessage = async (payload) => {
    $("inbox").textContent = JSON.stringify(payload, null, 2);
    log(`java → js  ${JSON.stringify(payload)}`);
    if (payload.autotest) {
      await selfTest(payload.snapshot, payload.navigateAway === true);
    }
    if (payload.menuChosen) {
      markChosen(payload.menuChosen, payload.clicks);
    }
  };

  // ------------------------------------------------------------- the self test

  const results = [];
  const check = (name, ok, detail) => {
    results.push({ name, ok, detail });
    log(`${ok ? "PASS" : "FAIL"}     ${name}${detail ? " — " + detail : ""}`);
    return ok;
  };

  async function selfTest(snapshotPath, handOff) {
    // 1. A plain call, the shape the brief asks for.
    try {
      const greeting = await java.invoke("hello", { name: "World" });
      $("response").textContent = `Response: ${greeting}`;
      check("hello", greeting === "Hello World", greeting);
    } catch (error) {
      check("hello", false, String(error.message));
    }

    // 2. Structured values survive the trip, so this is a codec test not a string test.
    try {
      const sent = { a: 1, b: "two", c: [3, 4], d: { nested: true }, e: null };
      const echoed = await java.invoke("echo", sent);
      check("json round trip", JSON.stringify(sent) === JSON.stringify(echoed),
            JSON.stringify(echoed));
    } catch (error) {
      check("json round trip", false, String(error.message));
    }

    // 3. The threading fix. Hold a slow call open, then see whether anything else gets
    //    answered while it is in flight. Replies are delivered *by the UI thread*, so if a
    //    slow handler still occupied the UI thread, the fast call could not return until the
    //    slow one finished — and the elapsed time below would be ~600 ms, not a few.
    //
    //    The elapsed time is kept, because the control below quotes it: the two numbers are
    //    the whole point of the pair, and a typed one would be a number nobody measured.
    let fastElapsed = null;
    try {
      const slow = java.invoke("slow", { ms: 600 });
      const started = performance.now();
      await java.invoke("hello", { name: "overtake" });
      const elapsed = performance.now() - started;
      fastElapsed = elapsed;
      check("slow handler does not block", elapsed < 150,
            `hello answered in ${elapsed.toFixed(0)} ms while slow(600 ms) was in flight`);
      await slow;
    } catch (error) {
      check("slow handler does not block", false, String(error.message));
    }

    // 3b. The control for the check above. Every reply is delivered by the UI thread, so a
    //     handler that occupies the UI thread should delay even an unrelated, instant call.
    //     Without this, check 3 could be passing for reasons that have nothing to do with
    //     scheduling. Same work, same duration, different thread.
    try {
      const blocking = java.invoke("slowUi", { ms: 400 });
      const started = performance.now();
      await java.invoke("hello", { name: "blocked" });
      const elapsed = performance.now() - started;
      const contrast = fastElapsed === null ? "the fast call above" : `${fastElapsed.toFixed(0)} ms above`;
      check("control: UI-thread handler does block", elapsed >= 300,
            `hello delayed ${elapsed.toFixed(0)} ms behind slowUi(400 ms) — contrast with ${contrast}`);
      await blocking;
    } catch (error) {
      check("control: UI-thread handler does block", false, String(error.message));
    }

    // 4. Cancellation, and the honest way to test it.
    //
    //    Comparing how fast the promise rejected would prove nothing: `cancel()` rejects it
    //    locally, so that number measures JavaScript, not the bridge. What has to be shown is
    //    that the handler on the Java side was *actually stopped*. So the handler counts its
    //    own interruptions and the page reads that counter back.
    try {
      const before = await java.invoke("interrupts", {});

      const doomed = java.invoke("interruptible", { ms: 5000 });
      await new Promise((resolve) => setTimeout(resolve, 200));   // let it get into the sleep

      const started = performance.now();
      doomed.cancel();
      const localReject = performance.now() - started;            // the page stops waiting at once

      // Give Java time to interrupt the handler and for the counter to move.
      await new Promise((resolve) => setTimeout(resolve, 400));
      const after = await java.invoke("interrupts", {});

      check("cancel actually stops the handler", after === before + 1,
            `handler interrupted (count ${before} → ${after}); page stopped waiting in ` +
            `${localReject.toFixed(0)} ms`);
    } catch (error) {
      check("cancel actually stops the handler", false, String(error.message));
    }

    // 5. Java's own deadline, independent of the page's fuse. The page allows 10 s here but the
    //    handler's deadline is 300 ms, so a ~300 ms rejection proves the Java-side timer fired
    //    rather than the page's own backstop.
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
            `rejected after ${elapsed.toFixed(0)} ms with "${message}"`);
    } catch (error) {
      check("java-side deadline fires on its own", false, String(error.message));
    }

    // 6. A binding with no deadline must still work — the escape hatch for work that is
    //    honestly unbounded.
    try {
      const result = await java.invoke("unbounded", { ms: 200 });
      check("deadline can be disabled", /no deadline/.test(result), result);
    } catch (error) {
      check("deadline can be disabled", false, String(error.message));
    }

    // 7. Platform extras must run on the UI thread; ordinary handlers must not.
    try {
      const geometry = await java.invoke("geometry", {});
      check("by-value NSRect round trip", geometry.roundTripStable === true,
            `${geometry.width}×${geometry.height} window #${geometry.windowNumber}`);
      check("platform extra runs on UI thread", geometry.onUiThread === true,
            `onUiThread=${geometry.onUiThread}`);
    } catch (error) {
      check("geometry", false, String(error.message));
    }

    try {
      const shrunk = await java.invoke("resize", { width: 900, height: 620 });
      const restored = await java.invoke("resize", { width: 1000, height: 700 });
      check("web view follows the window", shrunk.followed && restored.followed,
            `${shrunk.webViewFrame} → ${restored.webViewFrame}`);
    } catch (error) {
      check("resize", false, String(error.message));
    }

    // 8. Runtime facts only Java can supply, including which thread answered.
    try {
      const info = await java.invoke("runtime", {});
      const list = $("runtime");
      list.textContent = "";
      for (const [key, value] of Object.entries(info)) {
        const dt = document.createElement("dt");
        dt.textContent = key;
        const dd = document.createElement("dd");
        dd.textContent = String(value);
        list.append(dt, dd);
      }
      check("ordinary handler runs off the UI thread",
            info.handlerIsVirtual === true && info.handlerOnUiThread === false,
            `${info.vmName}, imageKind=${info.imageKind}, handler on ${info.handlerThread}`);
    } catch (error) {
      check("runtime", false, String(error.message));
    }

    // 9. 200 invocations with no awaiting in between: replies have to be correlated out of
    //    order rather than surviving as a single request/response pair.
    try {
      const count = 200;
      const started = performance.now();
      const replies = await Promise.all(
        Array.from({ length: count }, (_, i) => java.invoke("hello", { name: `#${i}` }))
      );
      const ordered = replies.every((r, i) => r === `Hello #${i}`);
      const ms = performance.now() - started;
      check("200 concurrent invocations", ordered, `${count} round trips in ${ms.toFixed(0)} ms`);
    } catch (error) {
      check("200 concurrent invocations", false, String(error.message));
    }

    // 10. WebKit rendering itself, through an Objective-C block whose body is Java.
    if (snapshotPath) {
      try {
        await java.invoke("snapshot", { path: snapshotPath });
        check("webkit self-snapshot", true, snapshotPath);
      } catch (error) {
        check("webkit self-snapshot", false, String(error.message));
      }
    }

    // 11. A menu item, chosen.
    //
    // The check asserts the effect, not the absence of an exception: a menu item that
    // reaches nobody does not throw — it does nothing, silently, which is the failure this
    // whole feature is exposed to. The count is the only evidence that the click reached
    // the binding, and the binding waits for the effect so there is no race here.
    //
    // Two items, not one, and the second one is the one after the separator. An item's
    // position in a menu is not the number of the items before it, so a click that counted
    // only the items would land on the separator and reach nothing — which is exactly the
    // failure this check would then report.
    try {
      const first = await java.invoke("menuClick", {});
      const second = await java.invoke("menuClick", { item: "menuProbe2" });
      check("menu item reaches its binding", first >= 1 && second >= 1,
            "the menu item ran the binding " + first + " time(s), and the one after the "
            + "separator " + second);
    } catch (error) {
      check("menu item reaches its binding", false, String(error.message));
    }

    // 12. A native file dialog, opened and dismissed without a human.
    //
    // A self-test cannot choose a file — choosing is what a person is for — so the check
    // proves the machinery around that choice: the dialog opens on the UI thread, a
    // programmatic dismissal closes it, the answer is an honest "not accepted", and a round
    // trip afterwards proves the modal loop left nothing wedged behind it. The dialog really
    // was open: the dismissal is delivered through the same modal loop the real Cancel button
    // uses, so a panel that never opened cannot pass this.
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

    // Hand the verdict to Java so it lands in stdout. A test whose result can only be read by
    // looking at a screenshot is not a test you can run in CI.
    const passed = results.filter((r) => r.ok).length;
    try {
      await java.invoke("report", { passed, failed: results.length - passed, checks: results });
    } catch (error) {
      log(`error    report: ${error.message}`);
    }
    log(`self-test complete: ${passed}/${results.length} checks passed`);

    // Last, deliberately: leave a request in flight and replace the document.
    //
    // Requests belong to the page that made them, so the bridge should abandon them when that
    // page goes away rather than let them run to completion for nobody and then expire. This
    // is the one part of the cancellation story the page cannot assert about itself — it is
    // navigating away — so the evidence is the line Java writes to its log.
    if (handOff) {
      java.invoke("slow", { ms: 3000 }).catch(() => {});
      setTimeout(() => {
        location.href = "blank.html";
      }, 150);
    }
  }

  // ------------------------------------------------------------- the menu bar
  //
  // The bar the application declared, and how many times each of its items has been chosen.
  // A person clicking an item in the real menu bar sees the count move — the other half of
  // what a menu is for, and the half a check cannot show. The count arrives as a message
  // from Java, because a menu click does not go through the page at all.

  const menuCounts = new Map();
  const announced = new Map();

  const renderMenu = (state) => {
    const list = $("menu-items");
    list.textContent = "";
    menuCounts.clear();
    for (const menu of state.menus) {
      for (const item of menu.items) {
        const title = document.createElement("dt");
        title.textContent = `${menu.title} › ${item.kind === "separator" ? "—" : item.title}`;
        const detail = document.createElement("dd");
        detail.textContent = item.kind === "binding"
          ? `${announced.get(item.binding) ?? item.clicks} time(s) chosen`
          : item.kind;
        list.append(title, detail);
        if (item.binding) {
          menuCounts.set(item.binding, detail);
        }
      }
    }
  };

  const markChosen = (binding, clicks) => {
    // The announcement wins over the count the state carried: a click that lands between the
    // two would otherwise leave the page showing a count that is already out of date.
    announced.set(binding, clicks);
    const detail = menuCounts.get(binding);
    if (detail) {
      detail.textContent = `${clicks} time(s) chosen`;
    }
  };

  const refreshMenu = async () => {
    try {
      renderMenu(await java.invoke("menuState", {}));
    } catch (error) {
      log(`error    menuState: ${error.message}`);
    }
  };

  refreshMenu();

  // ------------------------------------------------------------- interactions

  $("call").addEventListener("click", async () => {
    const name = $("name").value || "world";
    log(`js → java  hello(name=${JSON.stringify(name)})`);
    $("response").textContent = "Response: …";
    try {
      const result = await java.invoke("hello", { name });
      $("response").textContent = `Response: ${result}`;
    } catch (error) {
      $("response").textContent = `Error: ${error.message}`;
    }
  });

  let cancellable = null;
  $("slowcall").addEventListener("click", async () => {
    if (cancellable) return;
    cancellable = java.invoke("slow", { ms: 10000 });
    log("js → java  slow(10000 ms) — click Cancel to stop it");
    try {
      log(`         ${await cancellable}`);
    } catch (error) {
      log(`         ${error.message}`);
    } finally {
      cancellable = null;
    }
  });

  $("cancel").addEventListener("click", () => {
    if (!cancellable) {
      log("cancel   nothing in flight");
      return;
    }
    cancellable.cancel();
  });

  $("stress").addEventListener("click", async () => {
    try {
      const started = performance.now();
      const replies = await Promise.all(
        Array.from({ length: 200 }, (_, i) => java.invoke("hello", { name: `#${i}` }))
      );
      const ordered = replies.every((r, i) => r === `Hello #${i}`);
      log(`stress   200 round trips in ${(performance.now() - started).toFixed(0)} ms — ${
        ordered ? "all replies matched" : "MISMATCH"}`);
    } catch (error) {
      log(`error    stress: ${error.message}`);
    }
  });

  $("snapshot").addEventListener("click", async () => {
    try {
      const path = await java.invoke("snapshot", { path: "/tmp/panamaview-poc.png" });
      log(`js → java  snapshot → ${path}`);
    } catch (error) {
      log(`error    snapshot: ${error.message}`);
    }
  });

  // ------------------------------------------------------------------ clipboard
  //
  // This is the half navigator.clipboard cannot do reliably: by the time an async handler has
  // its text, the click that started it is no longer "fresh", and WebKit refuses. Java has no
  // such rule, so the page asks Java.

  $("copy").addEventListener("click", async () => {
    const text = `panamaview copied this at ${new Date().toLocaleTimeString()}`;
    try {
      const result = await java.invoke("copyText", { text });
      $("clip-state").textContent = `copied ${result.copied} chars (changeCount ${result.changeCount})`;
      log(`js → java  copyText → ${JSON.stringify(result)}`);
    } catch (error) {
      $("clip-state").textContent = `copy failed — ${error.message}`;
    }
  });

  $("readclip").addEventListener("click", async () => {
    try {
      const result = await java.invoke("clipboard", {});
      $("clip-state").textContent = result.text === null
        ? `no text on the clipboard (changeCount ${result.changeCount})`
        : JSON.stringify(result.text);
    } catch (error) {
      $("clip-state").textContent = `read failed — ${error.message}`;
    }
  });

  // ------------------------------------------------------ system notifications
  //
  // The page never touches the Web Notification API. WebKit's permission for it lives behind a
  // private WKUIDelegate SPI, and delivery would still be made under the app's identity — so the
  // page asks Java, and Java asks the system.

  (async () => {
    try {
      const info = await java.invoke("notificationInfo", {});
      $("notify").disabled = !info.available;
      $("notify-state").textContent = info.available
        ? `bundle ${info.bundleIdentifier}`
        : "this process is not a .app — run scripts/make-dev-app.sh";
    } catch (error) {
      $("notify").disabled = true;
      $("notify-state").textContent = `unavailable — ${error.message}`;
    }
  })();

  $("notify").addEventListener("click", async () => {
    $("notify-state").textContent = "asking the system…";
    try {
      const result = await java.invoke("notify", {
        title: "panamaview",
        body: "Hello from the Java side",
      });
      $("notify-state").textContent = result.granted ? "posted" : "permission denied";
    } catch (error) {
      $("notify-state").textContent = `failed — ${error.message}`;
    }
  });
})();
