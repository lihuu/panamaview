#!/bin/sh
# Verify the templates the way a user receives them.
#
# Installs the framework, copies each template somewhere unrelated to this checkout, and takes it
# all the way to a native executable — using the scripts the README tells people to run, not a
# hand-rolled command. That detail matters: an earlier version of this script assembled its own
# java invocation, and so it never noticed that ./run.sh put only target/classes on the
# classpath and died with NoClassDefFoundError the moment it was used for real.
#
# Runs headless, so it works in CI — which is the point. A template nobody builds is a template
# that quietly rots.
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

FRAMEWORK_VERSION="$(python3 -c '
import re, sys
print(re.search(r"<artifactId>panamaview</artifactId>\s*<version>([^<]+)</version>",
                open(sys.argv[1]).read()).group(1))' "$ROOT/pom.xml")"
echo "framework version: $FRAMEWORK_VERSION"

# ---------------------------------------------------------------- each template
for name in $TEMPLATES; do
    TEMPLATE="$ROOT/templates/$name"
    [ -d "$TEMPLATE" ] || { echo "no such template: $name" >&2; exit 1; }

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

    say "[$name] ./build-native.sh"
    export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25 2>/dev/null || /usr/libexec/java_home)}"
    ./build-native.sh > "$WORK/$name-native.log" 2>&1 || {
        echo "native build failed; last 40 lines:" >&2
        tail -40 "$WORK/$name-native.log" >&2
        exit 1
    }
    tail -2 "$WORK/$name-native.log"

    say "[$name] the native executable, same check"
    #
    # This is the one that matters. Resources are not inherited from a dependency, so an app that
    # forgets -H:IncludeResources compiles, links, starts — and then dies with "missing resource"
    # the moment it opens a window. Only running it catches that.
    ./target/myapp -Dpanamaview.check=true
done

say "all templates verified"
