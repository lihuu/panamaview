import { useEffect, useState } from "react";

/*
 * A thin React adapter over the framework's Java → page channel.
 *
 * The bridge gives you one callback, and it can fire at a moment when nothing is listening.
 * Java's natural first push is on *page ready*, which is before this app has mounted anything:
 * a module script is deferred, and `createRoot().render()` schedules its work rather than
 * finishing it. Subscribe with a plain useEffect and that first message is simply gone — the
 * window renders "waiting for Java…" forever, with a clean console and nothing to go on.
 *
 * So the last message is kept, and a component that subscribes later still receives it. That is
 * the whole reason this file exists rather than three lines in a component.
 */

let latest = null;
const listeners = new Set();

window.onJavaMessage = (payload) => {
  latest = payload;
  for (const listener of listeners) {
    listener(payload);
  }
};

/** The most recent payload Java pushed, or null if none has arrived yet. */
export function useJavaMessage() {
  const [message, setMessage] = useState(latest);

  useEffect(() => {
    // Catches anything that arrived between the first render and this effect running.
    setMessage(latest);
    listeners.add(setMessage);
    return () => listeners.delete(setMessage);
  }, []);

  return message;
}
