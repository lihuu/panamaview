"use strict";

/*
 * Application code. There is no bridge plumbing here — panamaview.js provides
 * window.java and window.__pvResolve, and the framework stages it into this directory.
 *
 *   java.invoke(method, params)  →  Promise of the Java handler's return value
 *   window.onJavaMessage         ←  define this to receive what Java pushes
 */

const $ = (id) => document.getElementById(id);

// Java → page. Without this the framework warns at load time, because messages pushed
// from Java would have nowhere to go.
window.onJavaMessage = (payload) => {
  $("from-java").textContent = JSON.stringify(payload, null, 2);
};

// Page → Java. The handler runs off the UI thread, so it may take its time.
$("call").addEventListener("click", async () => {
  $("result").textContent = "…";
  try {
    $("result").textContent = await java.invoke("greet", { name: "World" });
  } catch (error) {
    // A timeout, a cancellation and a thrown exception all arrive here as a message.
    $("result").textContent = `Error: ${error.message}`;
  }
});
