#!/bin/sh
# Build the React app into src/main/resources/web/, where the Java build picks it up.
#
# Everything here is generated: the output directory, the client bridge, and the asset manifest.
# None of the three are checked in, because all three are derived from something that is — and a
# copy of a derived file is a copy that goes stale without saying so.
set -eu
cd "$(dirname "$0")/.."

FRONTEND=frontend
OUT=src/main/resources/web

command -v node >/dev/null 2>&1 || {
    echo "build-frontend.sh: node is required for this template." >&2
    echo "build-frontend.sh: for a project with no frontend toolchain, use templates/vanilla." >&2
    exit 1
}

# ------------------------------------------------------------------ dependencies
if [ ! -d "$FRONTEND/node_modules" ]; then
    echo "==> installing frontend dependencies"
    if [ -f "$FRONTEND/package-lock.json" ]; then
        (cd "$FRONTEND" && npm ci --no-audit --no-fund)
    else
        (cd "$FRONTEND" && npm install --no-audit --no-fund)
    fi
fi

# ------------------------------------------------------------------ the client bridge
#
# Taken out of the panamaview jar rather than copied into the repository. The file is half of the
# protocol, so the copy that matters is the one matching the framework on the classpath — and
# extracting it on every build is what guarantees that, where committing a copy would not.
#
# It goes into frontend/public/ so the dev server serves it at /panamaview.js. In a built app the
# framework stages its own copy beside index.html instead; vite.config.js points the page at each.
echo "==> extracting panamaview.js from the framework"
mkdir -p "$FRONTEND/public"
mvn -q dependency:build-classpath -Dmdep.outputFile=target/frontend-classpath.txt -DincludeArtifactIds=panamaview
JAR="$(tr ':' '\n' < target/frontend-classpath.txt | grep 'panamaview' | head -1)"
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
    echo "build-frontend.sh: could not locate the panamaview jar; run 'mvn compile' first" >&2
    exit 1
fi
unzip -p "$JAR" panamaview/panamaview.js > "$FRONTEND/public/panamaview.js"
echo "    $FRONTEND/public/panamaview.js ($(wc -c < "$FRONTEND/public/panamaview.js" | tr -d ' ') bytes)"

# ------------------------------------------------------------------ the bundle
echo "==> building the frontend"
(cd "$FRONTEND" && npm run build --silent)

rm -rf "$OUT"
mkdir -p "$OUT"
(cd "$FRONTEND/dist" && tar cf - .) | (cd "$OUT" && tar xf -)

# Vite copies public/ verbatim into dist/, so the dev-server copy of the client bridge arrives
# here too. It must not stay: the framework stages its own copy of that file into the web root
# and treats the name as reserved, because the copy that matters is the one matching the
# panamaview on the classpath. Leaving this one in place makes the app refuse to start — loudly,
# which is the intent of the reservation.
rm -f "$OUT/panamaview.js"

# ------------------------------------------------------------------ the asset manifest
#
# The Java side cannot enumerate a classpath directory, and not at all inside a native image, so
# it is told which files exist. Generated rather than written by hand because Vite's output names
# are hashed and change on every build.
echo "==> writing the asset manifest"
(cd "$FRONTEND/dist" && find . -type f ! -name panamaview.js | sed 's|^\./||' | sort) > "$OUT/manifest.txt"
sed 's/^/    /' "$OUT/manifest.txt"
echo "==> done: $OUT"
