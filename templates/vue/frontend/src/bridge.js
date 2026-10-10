import { ref, onMounted, onUnmounted } from "vue";

/*
 * A thin Vue adapter over the framework's Java → page channel.
 *
 * The bridge gives you one callback, and it can fire at a moment when nothing is listening.
 * Java's natural first push is on *page ready*, which is before this app has mounted anything: a
 * module script is deferred, and mounting happens after that. Subscribe with a plain onMounted
 * and that first message is simply gone — the window shows "waiting for Java…" forever, with a
 * clean console and nothing to go on.
 *
 * So the last message is kept, and a component that subscribes later still receives it. That is
 * the whole reason this file exists rather than three lines in a component. The React template
 * needs the same thing, for the same reason.
 */

/** The most recent payload Java pushed. Read by late subscribers, never by listeners. */
let latest = null;

const listeners = new Set();

// Installed at import time, so it exists before anything renders. The framework warns at load
// time if nothing has defined it.
window.onJavaMessage = (payload) => {
  latest = payload;
  for (const listener of listeners) {
    listener(payload);
  }
};

/**
 * A ref holding the most recent payload from Java, or null if none has arrived yet.
 *
 *     const fromJava = useJavaMessage();
 *     <pre>{{ fromJava ? JSON.stringify(fromJava, null, 2) : "waiting for Java…" }}</pre>
 */
export function useJavaMessage() {
  const received = ref(latest);
  const listener = (payload) => {
    received.value = payload;
  };

  onMounted(() => {
    // Picks up anything that arrived between setup and mount, which is the common case for
    // Java's first push.
    received.value = latest;
    listeners.add(listener);
  });

  onUnmounted(() => {
    listeners.delete(listener);
  });

  return received;
}
