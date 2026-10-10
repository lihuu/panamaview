/*!
 * panamaview client bridge — the page-side half of the Java ↔ JavaScript bridge.
 *
 * The framework stages this file into your web root at startup, so it always matches the
 * version of panamaview on your classpath. Reference it, don't copy it:
 *
 *     <script src="panamaview.js"></script>
 *
 * Wire protocol — JSON text in both directions:
 *
 *   page → Java   { type: "invoke", id, method, params }
 *                 { type: "cancel", id, reason }
 *   Java → page   window.__pvResolve({ id, ok, result | error })
 *                 window.onJavaMessage(payload)
 *
 * Replies are matched by id, never by arrival order. They have to be: WebKit runs your
 * page in its own operating-system process, so a reply crosses a process boundary and
 * Java may answer from a different thread than the one that accepted the call. The first
 * request answered is routinely not the first one sent.
 *
 * Usage:
 *
 *     const greeting = await java.invoke("hello", { name: "World" });
 *
 *     const run = java.invoke("research", { q: "…" });   // slow work
 *     run.cancel();                                      // ask Java to stop
 *
 *     window.onJavaMessage = (payload) => { … };         // Java → page
 *
 *     panamaview.configure({ fuseMs: 120000 });          // if your handlers run long
 */
(() => {
  "use strict";

  if (window.java) {
    console.warn("[panamaview] a bridge is already defined; leaving it alone");
    return;
  }

  const HANDLER = "java";

  // Java applies its own deadline (30 s by default, set with WebApp.timeout). This fuse
  // sits *behind* it so the more precise error — the one naming the binding's own limit —
  // normally wins the race, and this only catches a reply that never arrives at all.
  let fuseMs = 35000;

  const pending = new Map();
  let seq = 0;

  const post = (message) => window.webkit.messageHandlers[HANDLER].postMessage(JSON.stringify(message));

  /** Rejects everything in flight. For a page that is going away. */
  function cancelAll(reason) {
    for (const entry of [...pending.values()]) {
      post({ type: "cancel", id: entry.id, reason, notify: false });
      entry.settle(entry.reject, new Error(reason));
    }
  }

  /**
   * Calls a Java binding.
   *
   * @param {string} method  a name registered with WebApp.bind / bindUi
   * @param {object} [params] JSON-serialisable arguments
   * @param {{deadlineMs?: number|null}} [options] override the fuse; null disables it
   * @returns {Promise<any>} the handler's return value, with a .cancel() method
   */
  function invoke(method, params, options = {}) {
    const id = ++seq;
    const deadline = options.deadlineMs === undefined ? fuseMs : options.deadlineMs;

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

    if (deadline !== null && deadline !== undefined) {
      entry.timer = setTimeout(() => {
        // Tell Java to stop working too. The reply is already moot, but the work is not.
        post({ type: "cancel", id, reason: `no reply within ${deadline} ms` });
        entry.settle(entry.reject, new Error(`no reply within ${deadline} ms`));
      }, deadline);
    }

    pending.set(id, entry);
    post({ type: "invoke", id, method, params: params ?? null });

    // Cancellation is a request, not a command: Java interrupts the handler, and a handler
    // that never checks for interruption keeps running. The page stops waiting either way.
    promise.cancel = () => {
      post({ type: "cancel", id, reason: "cancelled by the page" });
      entry.settle(entry.reject, new Error("cancelled"));
    };

    return promise;
  }

  /** Called by Java. Not part of the public surface. */
  window.__pvResolve = (reply) => {
    const entry = pending.get(reply.id);
    if (!entry) return;                       // already settled, or never ours
    if (reply.ok) entry.settle(entry.resolve, reply.result);
    else entry.settle(entry.reject, new Error(reply.error));
  };

  window.java = { invoke };

  window.panamaview = {
    configure({ fuseMs: value }) {
      if (typeof value === "number") fuseMs = value;
    },
    inFlight: () => pending.size,
    cancelAll,
  };

  // A page that never defines the Java→page hook would otherwise fail deep inside the
  // framework with a TypeError naming an internal function. Say what is wrong instead.
  window.addEventListener("DOMContentLoaded", () => {
    if (typeof window.onJavaMessage !== "function") {
      console.warn(
        "[panamaview] window.onJavaMessage is not defined — messages pushed from Java " +
          "have nowhere to go. Define it to receive them."
      );
    }
  });

  // Requests belong to the page that made them. Java abandons them on navigation too;
  // this just stops this page's promises from hanging on the way out.
  window.addEventListener("pagehide", () => cancelAll("the page navigated away"));
})();
