#!/bin/sh
# Verify the templates the way a user receives them.
#
# Installs the framework, copies each template somewhere unrelated to this checkout, and runs it via
# ./run.sh — the script the README tells people to run, not a hand-rolled command. That detail
# matters: an earlier version of this script assembled its own java invocation, and so it never
# noticed that ./run.sh put only target/classes on the classpath and died with NoClassDefFoundError
# the moment it was used for real.
#
# The native build happens in verify-scaffold.sh, once per template instead of twice. See the note
# above the loop.
#
# Runs headless, so it works in CI — which is the point. A template nobody builds is a template
# that quietly rots.
#
# The templates build in parallel. Each one ends in a native-image compile, and those are the whole
# cost of this script — about a minute each on this machine, times five. Run in turn it was five
# minutes of a fourteen-core box sitting at one core. See the note above the loop for why the
# parallelism is capped rather than left to native-image.
set -eu
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

# Which templates to check; defaults to all of them.
if [ "$#" -gt 0 ]; then
    TEMPLATES="$*"
else
    TEMPLATES="$(cd templates && for d in */; do printf '%s ' "${d%/}"; done)"
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT INT TERM

say() { printf '\n=== %s\n' "$1"; }

# ---------------------------------------------------------------- shared setup
say "installing the framework into the local repository"
(cd "$ROOT" && mvn -q -B install -DskipTests)

# The framework's version, from the framework's own pom. The root pom is the reactor's parent since
# the build became a module build, so it is no longer the file that says what the framework is.
# The script exports FRAMEWORK_VERSION itself; the name is kept because everything below reads it.
. "$ROOT/scripts/framework-coordinates.sh"
echo "framework version: $FRAMEWORK_VERSION"

# ---------------------------------------------------------------- each template
#
# Why there is no native build here. There used to be one per template, five in total, and
# verify-scaffold.sh built the same five again — from the projects it generated, which are copies of
# these directories. A template that cannot compile natively cannot be generated into one that can,
# so the second run is the one that proves it, and the first was five minutes of a fourteen-core
# machine spent re-deriving a result that was about to be derived again.
#
# What this script still covers, and what verify-scaffold.sh does not: the template as checked out
# here — the pinned framework version, and run.sh working from an unmodified copy. verify-scaffold.sh
# only ever sees the generated project.
#
# Each template gets its own subshell and its own log, and they run at the same time. That is safe
# because nothing below uses `cd` on this shell any more: each block does its work inside a
# subshell, so a template cannot change the directory another one is working in.
: > "$WORK/pids"
for name in $TEMPLATES; do
    TEMPLATE="$ROOT/templates/$name"
    [ -d "$TEMPLATE" ] || { echo "no such template: $name" >&2; exit 1; }

    (
    say "[$name] checking the packaging scripts are the shared copies"
    # The packager is one script copied into every template, because a generated project has to
    # carry its own copy. Copies drift silently -- the one that is edited works, the others do
    # not -- so the copies are compared here, not trusted.
    for script in package.sh package.ps1 build-native.ps1; do
        [ -f "$TEMPLATE/$script" ] || continue
        if ! cmp -s "$TEMPLATE/$script" "$ROOT/scripts/packaging/$script"; then
            echo "templates/$name/$script differs from scripts/packaging/$script" >&2
            echo "  Fix: cp scripts/packaging/$script templates/$name/$script" >&2
            exit 1
        fi
    done
    if [ ! -x "$TEMPLATE/package.sh" ]; then
        echo "templates/$name/package.sh is not executable" >&2
        exit 1
    fi
    echo "  package.sh (and the Windows scripts where present) match scripts/packaging/"

    say "[$name] checking the pinned framework version"
    python3 - "$FRAMEWORK_VERSION" "$TEMPLATE/pom.xml" "$name" <<'PY'
import re, sys

expected, pom, name = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(pom).read()
m = re.search(r"<panamaview\.version>([^<]+)</panamaview\.version>", text)
if not m:
    sys.exit(f"[{name}] {pom} does not pin <panamaview.version>")
if m.group(1) != expected:
    sys.exit(f"\nMISMATCH in templates/{name}/pom.xml: pins {m.group(1)}, "
             f"framework is {expected}\n"
             f"Fix: set <panamaview.version>{expected}</panamaview.version>")
print(f"  pins {expected}, matches the framework")
PY

    say "[$name] copying into a clean directory"
    cp -R "$TEMPLATE" "$WORK/$name"
    cd "$WORK/$name"
    rm -rf target
    ls -1

    # Some templates have a lockfile and some do not; either way a clean checkout must build.
    if [ -f package-lock.json ]; then
        echo "  (has a lockfile)"
    fi

    say "[$name] ./run.sh — the JVM path, with the check that needs no window"
    #
    # JAVA_OPTS carries the check flag, so this exercises the real script: its compile step, its
    # classpath assembly and its two required JVM flags. Without a window server the app cannot
    # open a window, and this is what is left — staging the assets and reporting what it found.
    JAVA_OPTS=-Dpanamaview.check=true ./run.sh
    ) > "$WORK/$name.log" 2>&1 &
    # Interleaved output from five builds is unreadable, so each one is held in its own file and
    # printed when it finishes. Failing loudly is still the point: a non-zero exit here fails the
    # whole run, which is what the sequential version did with `set -e`.
    #
    # The pid is recorded as "<name> <pid>" in a flat file rather than in a variable named after
    # the template: template names contain hyphens, and `pid_vanilla-tailwind` is not a name a
    # shell will accept. Keeping the pair as data sidesteps the question.
    printf '%s %s\n' "$name" "$!" >> "$WORK/pids"
done

# Wait for everything, then report. Each template's output is replayed in the order they were
# named, so the transcript reads the same whether or not the builds overlapped.
FAILED=""
while read -r name pid; do
    if ! wait "$pid"; then
        FAILED="$FAILED $name"
    fi
    cat "$WORK/$name.log"
done < "$WORK/pids"

[ -z "$FAILED" ] || {
    echo >&2
    echo "verify-templates.sh: these templates failed:$FAILED" >&2
    exit 1
}

say "all templates verified"
