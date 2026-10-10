import { Injectable, signal, type Signal } from "@angular/core";

/*
 * An Angular wrapper over the framework's Java → page channel.
 *
 * The bridge hands you a single callback, and it can fire at a moment when nothing is listening.
 * Java's natural first push is on *page ready*, which is earlier than it looks: the module script
 * is deferred, but `bootstrapApplication` is itself asynchronous, and a root service is created
 * lazily on first injection. Subscribe with a bare ngOnInit and that first message is gone — the
 * window shows "waiting for Java…" forever, with a clean console and nothing to go on.
 *
 * So the last payload is kept at module scope, and reading it is what a late subscriber does.
 * That is the whole reason this file exists rather than three lines in a component. The React and
 * Vue templates need the same thing, for the same reason.
 */

/** Declared here so the rest of the app gets types instead of `any`. */
declare global {
  interface Window {
    java: {
      invoke(method: string, params?: Record<string, unknown>): Promise<unknown>;
    };
    onJavaMessage?: (payload: unknown) => void;
  }
}

/** The most recent payload Java pushed. Read by late subscribers, never by listeners. */
let latest: unknown = null;

const listeners = new Set<(payload: unknown) => void>();

// Installed at import time, which is why main.ts imports this module before bootstrapping.
window.onJavaMessage = (payload: unknown) => {
  latest = payload;
  for (const listener of listeners) {
    listener(payload);
  }
};

@Injectable({ providedIn: "root" })
export class Bridge {
  private readonly received = signal<unknown>(latest);

  /** The most recent payload from Java, or null if none has arrived yet. */
  readonly fromJava: Signal<unknown> = this.received.asReadonly();

  constructor() {
    // Anything that arrived before this service existed, which is the common case for Java's
    // first push.
    this.received.set(latest);
    listeners.add((payload) => this.received.set(payload));
  }

  /** Calls a Java binding. The handler runs off the UI thread, so it may take its time. */
  invoke(method: string, params?: Record<string, unknown>): Promise<unknown> {
    return window.java.invoke(method, params);
  }
}
