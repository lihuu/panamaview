#!/bin/sh
# Verify the vanilla template the way a user receives it.
#
# Builds the framework, installs it into the local repository, copies the template somewhere
# that has nothing to do with this checkout, and takes that copy all the way to a native
# executable. Runs headless, so it works in CI — which is the point: a template nobody builds
# is a template that quietly rots.
set -eu
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

TEMPLATE="$ROOT/templates/vanilla"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT INT TERM

say() { printf '\n=== %s\n' "$1"; }

# ---------------------------------------------------------------- version agreement
#
# The template pins a panamaview version. If the framework's version moves and the template's
# does not, every user of the template gets a resolution failure — so fail here instead, with
# the fix spelled out.
say "checking the template pins the current framework version"
python3 - "$ROOT/pom.xml" "$TEMPLATE/pom.xml" <<'PY'
import re, sys

def version(path, pattern):
    text = open(path, encoding="utf-8").read()
    m = re.search(pattern, text)
    if not m:
        sys.exit(f"could not find a version in {path}")
    return m.group(1)

framework = version(sys.argv[1], r"<artifactId>panamaview</artifactId>\s*<version>([^<]+)</version>")
template = version(sys.argv[2], r"<panamaview\.version>([^<]+)</panamaview\.version>")

print(f"framework {framework}  |  template pins {template}")
if framework != template:
    sys.exit(f"\nMISMATCH: update templates/vanilla/pom.xml <panamaview.version> to {framework}")
PY

# ---------------------------------------------------------------- build the framework
say "installing the framework into the local repository"
(cd "$ROOT" && mvn -q -B install -DskipTests)

VERSION="$(python3 -c '
import re, sys
print(re.search(r"<artifactId>panamaview</artifactId>\s*<version>([^<]+)</version>",
                open(sys.argv[1]).read()).group(1))' "$ROOT/pom.xml")"
jar="$HOME/.m2/repository/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.jar"

if [ ! -f "$jar" ]; then
    echo "verify-template.sh: expected artifact not installed: $jar" >&2
    exit 1
fi
echo "artifact: $jar"

# ---------------------------------------------------------------- generate a project
say "copying the template into a clean directory"
cp -R "$TEMPLATE" "$WORK/myapp"
cd "$WORK/myapp"
rm -rf target
ls -1

# ---------------------------------------------------------------- JVM
say "JVM: compile and check the staged assets"
mvn -q -B compile
mvn -q -B dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -Dpanamaview.check=true \
     -cp "target/classes:$(cat target/classpath.txt)" \
     com.example.Main

# ---------------------------------------------------------------- native
say "native: build a standalone executable"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25 2>/dev/null || /usr/libexec/java_home)}"
./build-native.sh > "$WORK/native.log" 2>&1 || {
    echo "native build failed; last 40 lines:" >&2
    tail -40 "$WORK/native.log" >&2
    exit 1
}
tail -3 "$WORK/native.log"

say "native: check the staged assets inside the executable"
# This is the check that matters. Resources are not inherited from a dependency, so an
# application that forgets -H:IncludeResources compiles, links, starts — and then dies with
# "missing resource" the moment it opens a window. Only running it catches that.
./target/myapp -Dpanamaview.check=true

say "template verified"
