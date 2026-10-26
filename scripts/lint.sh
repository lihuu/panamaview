#!/bin/sh
# Lint every shell script, with the same version of shellcheck CI uses.
#
# The point is the version, not the lint. CI pins shellcheck to $VERSION below and downloads it,
# because the one a runner happens to carry gave a different verdict than the one here: 0.11
# dropped SC2002 and SC2015, so two findings passed locally and failed in CI. That pin makes
# "clean locally" and "clean in CI" the same claim -- but only if this script uses the same
# version, which is why it downloads that build rather than invoking whatever `shellcheck` is on
# PATH.
#
# What went wrong without it: a change to scripts/verify-scaffold.sh had an unquoted $TEMPLATES
# (SC2086), CI failed six runs in a row, and every one of them was found by reading the CI log
# rather than by running this on the machine where the change was written. The lint existed and
# was correct; nothing local ever ran it.
#
# Usage:
#   ./scripts/lint.sh              # download if needed, then lint
#   SHELLCHECK=/path/to/shellcheck ./scripts/lint.sh    # use a build you already have
set -eu
cd "$(dirname "$0")/.."

VERSION=0.11.0

# Which files CI lints. Kept in one place so this script and the workflow cannot drift: if a glob
# is added to the workflow, add it here too, or the local run is a smaller claim than the CI run.
FILES="scripts/*.sh scripts/packaging/*.sh npm/scripts/*.sh templates/*/*.sh templates/*/scripts/*.sh"

if [ -n "${SHELLCHECK:-}" ]; then
    BIN="$SHELLCHECK"
    DIR=""
else
    # Cached outside the checkout, so a lint leaves no untracked directory behind. Re-downloaded
    # only when the version changes.
    DIR="${TMPDIR:-/tmp}/panamaview-shellcheck-$VERSION"
    BIN="$DIR/shellcheck"
fi

if [ ! -x "$BIN" ]; then
    case "$(uname -s)" in
        Darwin) os=darwin ;;
        Linux)  os=linux ;;
        *)      echo "lint.sh: no shellcheck build for $(uname -s); set SHELLCHECK=" >&2; exit 2 ;;
    esac
    # Upstream publishes exactly one Linux target, so on Linux the machine's own arch is not what
    # to ask for -- and writing that as `[ ... ] && arch=...` would make the whole script's exit
    # status depend on a test that fails on every platform but one.
    if [ "$os" = linux ]; then
        arch=x86_64
    else
        case "$(uname -m)" in
            arm64|aarch64) arch=aarch64 ;;
            x86_64)        arch=x86_64 ;;
            *)             echo "lint.sh: no shellcheck build for $(uname -m); set SHELLCHECK=" >&2; exit 2 ;;
        esac
    fi

    url="https://github.com/koalaman/shellcheck/releases/download/v$VERSION/shellcheck-v$VERSION.$os.$arch.tar.xz"
    echo "downloading shellcheck $VERSION ($os/$arch)"
    rm -rf "$DIR"
    mkdir -p "$DIR"
    curl -fsSL "$url" | tar -xJ -C "$DIR" --strip-components=1
fi

"$BIN" --version | sed -n '2p' | sed 's/^/  /'

# Unquoted $FILES on purpose: it is a list of globs, and each has to be expanded by the shell.
# shellcheck disable=SC2086
"$BIN" $FILES

echo "  all shell scripts clean"
