#!/bin/sh
# Run the app against the frontend dev server, with hot module replacement.
#
# Edit a .jsx file and the window updates without restarting anything. The Java process is not
# reloaded — it does not need to be, because the page is the only thing that changed.
set -eu
cd "$(dirname "$0")"

PORT="${PANAMAVIEW_DEV_PORT:-5199}"

command -v node >/dev/null 2>&1 || {
    echo "dev.sh: node is required for this template." >&2
    exit 1
}

./scripts/build-frontend.sh >/dev/null
[ -d frontend/node_modules ] || (cd frontend && npm install --no-audit --no-fund)

# ------------------------------------------------------------------ dev server
#
# Note the address. The dev server binds to localhost, which on macOS resolves to the IPv6 ::1 —
# so http://127.0.0.1:$PORT does NOT reach it and produces a window that waits forever. The
# hostname is the one that works. (Measured with Vite; Angular's server is checked the same way.)
#
# exec inside the subshell, so the recorded PID is the server itself rather than a shell wrapping
# it. Without that, cleanup kills the wrapper and leaves the server holding the port — which shows
# up as the *next* run failing to start, a confusing distance from the cause.
( cd frontend && exec npx ng serve --port "$PORT" ) &
VITE_PID=$!

cleanup() {
    # The direct child covers the common case; anything it spawned goes with it.
    pkill -P "$VITE_PID" 2>/dev/null || true
    kill "$VITE_PID" 2>/dev/null || true
    wait "$VITE_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "==> waiting for the dev server on http://localhost:$PORT/"
tries=0
until curl -sf -o /dev/null "http://localhost:$PORT/"; do
    tries=$((tries + 1))
    if [ "$tries" -gt 60 ]; then
        echo "dev.sh: the dev server did not come up" >&2
        exit 1
    fi
    sleep 0.5
done

# ------------------------------------------------------------------ the app
mvn -q compile

# The dependency classpath, not just target/classes: panamaview's own jar carries the FFM
# registrations and the client bridge, so leaving it out fails at start-up with
# NoClassDefFoundError rather than at compile time.
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA="${JAVA:-java}"

# Deliberately NOT exec: replacing this shell would discard the trap that stops the dev server,
# and the dev server would outlive the app every time.
#
# -XstartOnFirstThread is not optional: AppKit requires the event loop to own the process' first
# thread, and the java launcher does not give main() that thread.
# shellcheck disable=SC2086
"$JAVA" \
    -XstartOnFirstThread \
    --enable-native-access=ALL-UNNAMED \
    -Dpanamaview.dev="http://localhost:$PORT/" \
    ${JAVA_OPTS:-} \
    -cp "target/classes:$(cat target/classpath.txt)" \
    com.example.Main "$@"
