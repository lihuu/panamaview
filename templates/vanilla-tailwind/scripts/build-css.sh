#!/bin/sh
# Compile Tailwind CSS into src/main/resources/web/, where the Java build picks it up.
#
# This template exists for people who want a plain HTML page and no frontend toolchain, so the CSS
# build must not drag Node in behind their back. Tailwind publishes a standalone binary per
# platform, which is what this uses: it is fetched once, checksum-verified against the release's
# own sha256sums.txt, and cached under .tailwind/ (gitignored).
#
# Both outputs are generated — tailwind.css and manifest.txt — because both are derived. A
# committed copy of a derived file goes stale without saying so, and the whole point of the
# manifest is to be an accurate list of what exists.
set -eu
cd "$(dirname "$0")/.."

WEB=src/main/resources/web
TAILWIND_VERSION="${TAILWIND_VERSION:-4.3.3}"

# The binary is a build tool, not project content, so it is cached where build tools belong —
# outside the project. That also makes the cache shared: the verification script copies the
# template somewhere else to build it, and a per-project cache would mean an 80 MB download per
# copy. Override with TAILWIND_CACHE.
CACHE="${TAILWIND_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/panamaview-tailwind}"

# ------------------------------------------------------------------ pick a binary
os="$(uname -s)"
arch="$(uname -m)"
case "$os/$arch" in
    Darwin/arm64)              asset=tailwindcss-macos-arm64 ;;
    Darwin/x86_64)             asset=tailwindcss-macos-x64 ;;
    Linux/aarch64|Linux/arm64) asset=tailwindcss-linux-arm64 ;;
    Linux/x86_64)              asset=tailwindcss-linux-x64 ;;
    *)
        echo "build-css.sh: no Tailwind standalone binary for $os/$arch" >&2
        echo "build-css.sh: install and use the CLI instead: npx @tailwindcss/cli -i $WEB/input.css -o $WEB/tailwind.css" >&2
        exit 1
        ;;
esac

base="https://github.com/tailwindlabs/tailwindcss/releases/download/v$TAILWIND_VERSION"
binary="$CACHE/$asset"
mkdir -p "$CACHE"

# ------------------------------------------------------------------ fetch, once
if [ ! -x "$binary" ]; then
    echo "==> fetching Tailwind $TAILWIND_VERSION ($asset, ~80 MB, once)"
    tmp="$CACHE/$asset.download"
    if ! curl -fsSL --retry 3 -o "$tmp" "$base/$asset"; then
        rm -f "$tmp"
        echo "build-css.sh: download failed. If you are offline, run this where you have network" >&2
        echo "build-css.sh: once — the binary is cached in $CACHE." >&2
        exit 1
    fi

    # Verify against the release's own checksum list. A downloaded executable that nobody
    # verified is exactly the thing this is worth doing for.
    echo "==> verifying checksum"
    if ! curl -fsSL --retry 3 -o "$CACHE/sha256sums.txt" "$base/sha256sums.txt"; then
        rm -f "$tmp" "$CACHE/sha256sums.txt"
        echo "build-css.sh: could not fetch sha256sums.txt; refusing an unverified binary" >&2
        exit 1
    fi
    expected="$(grep -E "(\./)?$asset\$" "$CACHE/sha256sums.txt" | awk '{print $1}' | head -1)"
    if [ -z "$expected" ]; then
        rm -f "$tmp"
        echo "build-css.sh: $asset is not listed in sha256sums.txt; refusing an unverified binary" >&2
        exit 1
    fi
    actual="$(shasum -a 256 "$tmp" | awk '{print $1}')"
    if [ "$expected" != "$actual" ]; then
        rm -f "$tmp"
        echo "build-css.sh: checksum mismatch for $asset" >&2
        echo "  expected $expected" >&2
        echo "  actual   $actual" >&2
        exit 1
    fi

    chmod +x "$tmp"
    mv "$tmp" "$binary"
    echo "    checksum ok"
fi

# ------------------------------------------------------------------ compile
echo "==> compiling Tailwind CSS"
"$binary" -i "$WEB/input.css" -o "$WEB/tailwind.css" --minify

# ------------------------------------------------------------------ manifest
#
# The Java side cannot enumerate a classpath directory, and not at all inside a native image, so it
# is told which files exist. Generated, so it cannot disagree with what is on disk.
echo "==> writing the asset manifest"
(cd "$WEB" && find . -type f ! -name manifest.txt ! -name input.css | sed 's|^\./||' | sort) > "$WEB/manifest.txt"
sed 's/^/    /' "$WEB/manifest.txt"
echo "==> done: $WEB/tailwind.css ($(wc -c < "$WEB/tailwind.css" | tr -d ' ') bytes)"
