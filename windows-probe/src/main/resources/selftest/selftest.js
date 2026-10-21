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

    // 3b. The control. Same work, same duration, on the UI thread: it *should* delay.
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

    // 4. Cancellation, tested honestly: the promise rejecting locally proves nothing about
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

    // 5. Java's own deadline, independent of the page's fuse. The page allows 10 s here and
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

    // 6. A binding with no deadline must still work.
    try {
      const result = await java.invoke("unbounded", { ms: 200 });
      check("deadline can be disabled", /no deadline/.test(result), result);
    } catch (error) {
      check("deadline can be disabled", false, String(error.message));
    }

    // 7. The platform extra runs on the UI thread; ordinary handlers must not.
    try {
      const info = await java.invoke("windowInfo", {});
      check("platform extra runs on UI thread", info.onUiThread === true,
            "window " + info.window + ", onUiThread=" + info.onUiThread);
    } catch (error) {
      check("windowInfo", false, String(error.message));
    }

    // 8. Runtime facts only Java can supply, including which thread answered.
    try {
      const info = await java.invoke("runtime", {});
      check("ordinary handler runs off the UI thread",
            info.handlerIsVirtual === true && info.handlerOnUiThread === false,
            info.vmName + ", handler on " + info.handlerThread);
    } catch (error) {
      check("runtime", false, String(error.message));
    }

    // 9. Two hundred replies, correlated by id rather than by arrival order.
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

    // The verdict goes to Java so it lands in stdout.
    const passed = results.filter((r) => r.ok).length;
    try {
      await java.invoke("report", { passed, failed: results.length - passed, checks: results });
    } catch (error) {
      log("error  report: " + error.message);
    }
  }
})();
