"use strict";

/*
 * The JavaScript half of the bridge.
 *
 * WebKit gives the page exactly one primitive: `postMessage` into a named script message
 * handler. Everything else — request/reply correlation, promises, cancellation, Java→JS
 * delivery — is built on top of it here.
 *
 * Protocols, both directions, JSON text:
 *   page → Java   { type: "invoke", id, method, params }
 *                 { type: "cancel", id, reason, notify }
 *   Java → page   window.__pvResolve({ id, ok, result | error })
 *                 window.onJavaMessage(payload)
 *
 * Replies are correlated by id, never by order. They have to be: the reply crosses a process
 * boundary and Java may answer from a different thread than the one that took the call, so the
 * first request answered is routinely not the first one sent.
 */

(() => {
  const $ = (id) => document.getElementById(id);
  const pending = new Map();
  let seq = 0;

  // Java's own deadline defaults to 30 s. This fuse sits just behind it so that the more
  // precise error ("timed out in the handler") normally arrives first, and this only catches
  // the case where the reply itself never made it back.
  const JAVA_DEADLINE_MS = 30000;
  const FUSE_MS = JAVA_DEADLINE_MS + 5000;

  const log = (line) => {
    const el = $("log");
    el.textContent += `${new Date().toLocaleTimeString()}  ${line}\n`;
    el.scrollTop = el.scrollHeight;
  };

  // ---------------------------------------------------------------- the bridge

  const post = (message) => window.webkit.messageHandlers.java.postMessage(JSON.stringify(message));

  function invoke(method, params, options = {}) {
    const id = ++seq;
    const deadline = options.deadlineMs === undefined ? FUSE_MS : options.deadlineMs;

    const entry = { id, timer: null, settled: false };

    const promise = new Promise((resolve, reject) => {
      entry.resolve = resolve;
      entry.reject = reject;
    });

    entry.settle = (settle, value) => {
      if (entry.settled) return false;
      entry.settled = true;
      clearTimeout(entry.timer);
      pending.delete(id);
      settle(value);
      return true;
    };

    if (deadline !== null) {
      entry.timer = setTimeout(() => {
        // Tell Java to stop working as well; the reply is already moot, but the work is not.
        post({ type: "cancel", id, reason: `no reply within ${deadline} ms` });
        entry.settle(entry.reject, new Error(`no reply within ${deadline} ms`));
      }, deadline);
    }

    pending.set(id, entry);
    post({ type: "invoke", id, method, params: params ?? null });

    // Cancellation is a request, not a command — Java interrupts the handler, and a handler
    // that never checks for interruption will keep going. See the note in Bridge.java.
    promise.cancel = () => {
      post({ type: "cancel", id, reason: "cancelled by the page" });
      entry.settle(entry.reject, new Error("cancelled"));
    };

    return promise;
  }

  window.java = { invoke };

  window.__pvResolve = (reply) => {
    const entry = pending.get(reply.id);
    if (!entry) return;
    if (reply.ok) entry.settle(entry.resolve, reply.result);
    else entry.settle(entry.reject, new Error(reply.error));
  };

  // ------------------------------------------------------- Java → JavaScript

  // Called by Java via evaluateJavaScript.
  window.onJavaMessage = async (payload) => {
    $("inbox").textContent = JSON.stringify(payload, null, 2);
    log(`java → js  ${JSON.stringify(payload)}`);
    if (payload.autotest) {
      await selfTest(payload.snapshot, payload.navigateAway === true);
    }
  };

  // ------------------------------------------------------------- the self test

  const results = [];
  const check = (name, ok, detail) => {
    results.push({ name, ok, detail });
    log(`${ok ? "PASS" : "FAIL"}     ${name}${detail ? " — " + detail : ""}`);
    return ok;
  };

  /** Milliseconds until a promise settles, regardless of which way. */
  async function settleTime(promise) {
    const started = performance.now();
    try {
      await promise;
    } catch (ignored) {
      // the outcome is not what is being measured here
    }
    return performance.now() - started;
  }

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
    //    answered while it is in flight. Replies are delivered *by the UI thread*, so if a slow
    //    handler still occupied the UI thread, the fast call could not return until the slow
    //    one finished — and the elapsed time below would be ~600 ms, not a few.
    try {
      const slow = java.invoke("slow", { ms: 600 });
      const started = performance.now();
      await java.invoke("hello", { name: "overtake" });
      const elapsed = performance.now() - started;
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
      check("control: UI-thread handler does block", elapsed >= 300,
            `hello delayed ${elapsed.toFixed(0)} ms behind slowUi(400 ms) — contrast with 1 ms above`);
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
      check("java-side deadline fires on its own", elapsed < 2000 && /no answer within 300 ms/.test(message),
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
      const path = await java.invoke("snapshot", { path: "/tmp/panamaview.png" });
      log(`js → java  snapshot → ${path}`);
    } catch (error) {
      log(`error    snapshot: ${error.message}`);
    }
  });
})();
