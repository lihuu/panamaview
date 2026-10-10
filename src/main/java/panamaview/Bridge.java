package panamaview;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The JavaScript side of the bridge: request framing, handler dispatch, reply delivery, and
 * making sure every request reaches exactly one outcome.
 *
 * <p>This class is where the threading and lifetime model lives, and the shape of it is forced
 * by the platforms rather than chosen:
 *
 * <ol>
 *   <li>A message arrives on the UI thread, inside the platform's own callback. AppKit — and
 *       the equivalents elsewhere — will not service anything else while that callback runs,
 *       so returning quickly is not an optimisation, it is a requirement.</li>
 *   <li>So a handler that is allowed to take time runs on a <em>virtual thread</em> and the
 *       callback returns immediately. A burst of requests costs a virtual thread each, which is
 *       cheap by design.</li>
 *   <li>The reply then has to be delivered back on the UI thread, because that is where
 *       {@code evaluateJavaScript} is legal. {@link UiHost#onUiThread} does that hop.</li>
 * </ol>
 *
 * <p>Ordering consequence: replies are not necessarily in request order. That is fine because
 * the page correlates them by {@code id} — which it had to do anyway, since a reply can never be
 * synchronous across a process boundary.
 *
 * <h2>Exactly one outcome per request</h2>
 * A request can end four ways, and they race: the handler returns, the handler throws, the
 * deadline passes, or the page cancels. Whichever gets there first wins and reports; the rest
 * become no-ops. Without that rule, a request that timed out and <em>then</em> finished would
 * report twice — and the second report would be a lie about a request that is already over.
 *
 * <h2>What a timeout can and cannot do</h2>
 * A deadline here does two things: it stops the request hanging forever from the page's point of
 * view, and it <em>interrupts</em> the worker, which genuinely stops sleeping, waiting, and
 * blocking {@code java.nio} work. It cannot stop a handler that never checks for interruption —
 * a CPU-bound loop, or a native call that went into Apple's libraries and has not come back.
 * Cancellation is cooperative: this framework can deliver the request, not enforce it.
 */
final class Bridge {

    /** What a request is allowed to do when nobody answered it in time. */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** A Java method JavaScript can call. */
    @FunctionalInterface
    interface Handler {
        Object apply(Map<String, Object> params) throws Exception;
    }

    /** A handler plus everything the bridge needs in order to run and bound it. */
    private record Binding(Handler handler, boolean uiThread, Duration timeout) {
    }

    /**
     * One in-flight request, and the machinery to settle it exactly once.
     *
     * <p>{@link #claim} is the whole trick: a compare-and-set that only one of the four racing
     * outcomes can win.
     *
     * <p>{@code worker} is filled in by the task itself rather than by the caller — the bridge
     * cannot know the virtual thread's identity until that thread is running, and the deadline
     * needs something to interrupt.
     */
    private static final class Request {
        final Object id;
        final AtomicBoolean pending = new AtomicBoolean(true);
        volatile Thread worker;
        volatile ScheduledFuture<?> timer;
        volatile String outcome;

        Request(Object id) {
            this.id = id;
        }

        /** Claims the single terminal outcome. False means somebody already reported this. */
        boolean claim(String why) {
            if (!pending.compareAndSet(true, false)) {
                return false;
            }
            outcome = why;
            ScheduledFuture<?> t = timer;
            if (t != null) {
                t.cancel(false);
            }
            return true;
        }
    }

    private final UiHost host;
    private final Map<String, Binding> bindings = new ConcurrentHashMap<>();
    private final Map<Object, Request> inFlight = new ConcurrentHashMap<>();

    /** Virtual threads: one per request, because a request is exactly the blocking-shaped work. */
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    /** One daemon thread firing deadlines. Not virtual — delayed tasks want a real thread. */
    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "panamaview-watchdog");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean verbose = true;

    Bridge(UiHost host) {
        this.host = host;
    }

    /**
     * Registers a handler. Package-private: {@link WebApp#bind} is the public door.
     *
     * <p>A UI-thread handler is given no deadline, deliberately. Deadlines are enforced by the
     * watchdog but <em>reported</em> on the UI thread — which, for a stalled UI-thread handler,
     * is exactly the thread that cannot answer. A timer there would fire into a queue nobody
     * can drain: it would look like protection without being any. The honest statement is that
     * a slow UI-thread handler freezes the app; that is why so few things may be one.
     */
    void bind(String method, Handler handler, boolean uiThread, Duration timeout) {
        bindings.put(method, new Binding(handler, uiThread, uiThread ? null : timeout));
    }

    void verbose(boolean value) {
        this.verbose = value;
    }

    // ------------------------------------------------------------------ incoming

    /** Entry point for a message from the page. Called on the UI thread — must return fast. */
    void onIncoming(String json) {
        Map<String, Object> message;
        try {
            message = asMap(Json.parse(json));
        } catch (RuntimeException e) {
            fail("malformed bridge message", e);
            return;
        }

        // Pages that predate cancellation send no `type`; treating that as an invocation keeps
        // the field additive rather than a breaking change.
        String type = message.get("type") == null ? "invoke" : String.valueOf(message.get("type"));
        switch (type) {
            case "invoke" -> onInvoke(message);
            case "cancel" -> onCancel(message);
            default -> fail("unknown bridge message type '" + type + "'", null);
        }
    }

    private void onInvoke(Map<String, Object> message) {
        Object id = message.get("id");
        String method = String.valueOf(message.get("method"));
        Map<String, Object> params = asMap(message.get("params"));
        Binding binding = bindings.get(method);

        if (binding == null) {
            reply(id, false, null, "no Java binding named '" + method + "'");
            return;
        }

        Request request = new Request(id);
        inFlight.put(id, request);
        armDeadline(request, binding);

        if (binding.uiThread()) {
            // Already on the UI thread, and this handler has to stay there.
            run(request, method, params, binding.handler());
        } else {
            workers.execute(() -> run(request, method, params, binding.handler()));
        }
    }

    private void armDeadline(Request request, Binding binding) {
        Duration timeout = binding.timeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return;
        }
        long millis = timeout.toMillis();
        request.timer = watchdog.schedule(() -> expire(request, millis), millis, TimeUnit.MILLISECONDS);
    }

    /** The page withdrew the request, or the page is going away. */
    private void onCancel(Map<String, Object> message) {
        Object id = message.get("id");
        Object reason = message.get("reason");
        boolean notify = !Boolean.FALSE.equals(message.get("notify"));
        cancel(id, reason == null ? "cancelled by the page" : String.valueOf(reason), notify);
    }

    /** Stops one request. Returns true when this call is the one that ended it. */
    boolean cancel(Object id, String reason, boolean notify) {
        Request request = inFlight.get(id);
        if (request == null || !request.claim(reason)) {
            return false;
        }
        inFlight.remove(id);
        Thread worker = request.worker;
        if (worker != null) {
            worker.interrupt();
        }
        if (verbose) {
            System.out.println("[panamaview] cancel   " + reason + "  (id " + id + ")");
        }
        if (notify) {
            reply(id, false, null, reason);
        }
        return true;
    }

    /**
     * Abandons everything in flight.
     *
     * <p>Called when the page navigates away: requests belong to the page that made them, so
     * they are meaningless the moment it is gone. No replies are sent — there is nothing left
     * to receive them, and asking a departing web view to evaluate a script is worse than
     * useless.
     */
    void cancelAll(String reason) {
        List<Object> ids = new ArrayList<>(inFlight.keySet());
        if (ids.isEmpty()) {
            return;
        }
        if (verbose) {
            System.out.println("[panamaview] cancel   " + ids.size() + " request(s): " + reason);
        }
        for (Object id : ids) {
            cancel(id, reason, false);
        }
    }

    /** Pushes a payload into the page. Safe to call from any thread. */
    void push(Object payload) {
        evaluate("window.onJavaMessage(" + Json.write(payload) + ")");
    }

    /** Creates a thread-safe view of what is outstanding, for diagnostics. */
    int inFlightCount() {
        return inFlight.size();
    }

    // ------------------------------------------------------------------ outcomes

    private void run(Request request, String method, Map<String, Object> params, Handler handler) {
        request.worker = Thread.currentThread();
        try {
            Object result = handler.apply(params);
            if (request.claim(null)) {
                inFlight.remove(request.id);
                if (verbose) {
                    System.out.println("[panamaview] js → java  " + method + " → " + Json.write(result)
                            + "   [" + Thread.currentThread() + "]");
                }
                reply(request.id, true, result, null);
            } else {
                logAbandoned(method, request.outcome);
            }
        } catch (Exception e) {
            // Losing the race means the outcome is already reported; the exception is just the
            // interrupt arriving, and saying anything about it would contradict the reply.
            if (!request.claim("handler failed")) {
                logAbandoned(method, request.outcome);
                return;
            }
            inFlight.remove(request.id);
            if (Thread.interrupted()) {
                // Clearing the flag here keeps it from leaking onto a pooled carrier thread.
                reply(request.id, false, null, request.outcome);
                return;
            }
            fail("binding '" + method + "' failed", e);
            reply(request.id, false, null, String.valueOf(e.getMessage()));
        }
    }

    private void expire(Request request, long millis) {
        String reason = "no answer within " + millis + " ms";
        if (!request.claim(reason)) {
            return;
        }
        inFlight.remove(request.id);
        Thread worker = request.worker;
        if (worker != null) {
            worker.interrupt();
        }
        if (verbose) {
            System.out.println("[panamaview] timeout  " + reason + "  (id " + request.id + ")");
        }
        reply(request.id, false, null, reason);
    }

    private void logAbandoned(String method, String outcome) {
        if (verbose && outcome != null) {
            System.out.println("[panamaview] abandoned " + method + " → " + outcome);
        }
    }

    private void reply(Object id, boolean ok, Object result, String error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id == null ? -1 : id);
        out.put("ok", ok);
        if (ok) {
            out.put("result", result);
        } else {
            out.put("error", error);
        }
        // A JSON document is already a valid JavaScript expression, so the reply needs no
        // escaping on the way into the page.
        evaluate("window.__pvResolve(" + Json.write(out) + ")");
    }

    private void evaluate(String script) {
        // Whatever thread produced this, delivery happens on the UI thread.
        host.onUiThread(() -> host.evaluate(script));
    }

    private void fail(String what, Throwable t) {
        System.err.println("[panamaview] " + what + (t == null ? "" : ": " + t));
        if (t != null) {
            t.printStackTrace(System.err);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
