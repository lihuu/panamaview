#!/bin/sh
# Verify create-panamaview the way someone who runs `npx create-panamaview` receives it.
#
# The package is packed, installed into a prefix that had nothing in it, and used to generate a
# project — which is then built the way a machine that has never heard of panamaview would build
# it: an empty local Maven repository, and a settings file that declares no repository at all. The
# framework can only come from the copy the package carries, and the build is checked for saying so.
#
# That last part is the whole point of the package. A generated project depends on
# io.github.lihuu:panamaview, which is not on Maven Central; if the carried copy is not used, the
# build either fails on a machine that has nothing installed, or — much worse — succeeds on a
# machine that happens to have the framework in its local repository, and says nothing. So the
# check is not "it built" but "it built from this".
#
# Usage: verify-npm-package.sh
set -eu
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
NPM_DIR="$ROOT/npm"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT INT TERM

say() { printf '\n=== %s\n' "$1"; }
fail() { printf 'verify-npm-package.sh: %s\n' "$1" >&2; exit 1; }

# ---------------------------------------------------------------- one version, two files
say "the version the package claims, and the version it carries"
# The framework's version, from the framework's own pom — the root pom is the parent now that this
# is a reactor, and its version is a different fact that happens to agree.
. "$ROOT/scripts/framework-coordinates.sh"
VERSION="$FRAMEWORK_VERSION"
[ -n "$VERSION" ] || fail "no version in $FRAMEWORK_POM"
python3 - "$VERSION" "$NPM_DIR/package.json" <<'PY'
import json, sys

version, package = sys.argv[1], sys.argv[2]
claimed = json.load(open(package))["version"]
if version != claimed:
    sys.exit(f"\nMISMATCH: the framework is {version}, npm/package.json claims {claimed}.")
print(f"  both at {version}")
PY

# ---------------------------------------------------------------- what gets packed
#
# The pack runs package.json's prepack hook, which is what stages bin/ and lib/ — so this is also
# the test of that hook. A publish whose hook had been dropped would produce a tarball with neither,
# and the assertions below are what notice.
say "packing (prepack builds and stages bin/ and lib/)"
mkdir -p "$WORK/first"
(cd "$NPM_DIR" && npm pack --pack-destination "$WORK/first" > "$WORK/pack.out" 2>&1) \
    || { cat "$WORK/pack.out" >&2; fail "npm pack failed"; }
FIRST="$(ls "$WORK/first"/*.tgz)"
echo "  $(basename "$FIRST")  ($(wc -c < "$FIRST" | tr -d ' ') bytes)"

say "the tarball is the package and nothing else"
tar -tzf "$FIRST" | sort > "$WORK/list-first.txt"
sed 's/^/  /' "$WORK/list-first.txt"
# An exact list, not a set of inclusions: `files` in package.json is an allowlist, and a stray file
# that is not in it is a stray file that would be published. Adding one to the package means adding
# it here, which is the moment to ask whether it belongs.
cat > "$WORK/expected.txt" <<EXPECTED
package/README.md
package/bin/panamaview
package/cli.js
package/lib/io/github/lihuu/panamaview-parent/$VERSION/panamaview-parent-$VERSION.pom
package/lib/io/github/lihuu/panamaview-parent/$VERSION/panamaview-parent-$VERSION.pom.sha1
package/lib/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.jar
package/lib/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.jar.sha1
package/lib/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.pom
package/lib/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.pom.sha1
package/package.json
EXPECTED
sort -o "$WORK/expected.txt" "$WORK/expected.txt"
if ! diff -u "$WORK/expected.txt" "$WORK/list-first.txt" > "$WORK/list.diff"; then
    sed 's/^/  /' "$WORK/list.diff" >&2
    fail "the tarball is not what package.json's files list describes"
fi
echo "  exactly the ten files package.json lists"

say "a second pack is the same file list"
# --ignore-scripts because the file list cannot depend on the hook having run again, and the point
# here is the list rather than the build. The first pack is what proves the hook.
mkdir -p "$WORK/second"
(cd "$NPM_DIR" && npm pack --ignore-scripts --pack-destination "$WORK/second" > "$WORK/pack2.out" 2>&1) \
    || { cat "$WORK/pack2.out" >&2; fail "the second npm pack failed"; }
tar -tzf "$WORK/second"/*.tgz | sort > "$WORK/list-second.txt"
diff -u "$WORK/list-first.txt" "$WORK/list-second.txt" > /dev/null \
    || fail "two packs of the same tree produced different file lists"
echo "  identical"

# ---------------------------------------------------------------- installing it
say "installing it into a prefix that had nothing in it"
PREFIX="$WORK/prefix"
mkdir -p "$PREFIX"
npm install --prefix "$PREFIX" --no-save --no-package-lock --no-fund --no-audit "$FIRST" \
    > "$WORK/install.log" 2>&1 || { cat "$WORK/install.log" >&2; fail "npm install failed"; }
BIN="$PREFIX/node_modules/.bin/panamaview"
[ -x "$BIN" ] || fail "no executable panamaview in $PREFIX/node_modules/.bin"
echo "  $BIN"

say "the installed cli answers for itself"
"$BIN" --version
"$BIN" --version | grep -qx "panamaview $VERSION" \
    || fail "--version disagrees with package.json"
"$BIN" --help > "$WORK/help.txt"
# The options in the js help are a copy of the scaffolder's, and a copy drifts. This is the cheap
# check that it still lists the options the binary really has.
for option in --template --package --title --force; do
    grep -q -- "$option" "$WORK/help.txt" || fail "--help does not mention $option"
done
echo "  --help lists the scaffolder's own options"

say "the exit code is the scaffolder's, not this wrapper's"
set +e
"$BIN" nonsense-command > "$WORK/usage.out" 2>&1
STATUS=$?
set -e
sed 's/^/  /' "$WORK/usage.out"
# 2 is the scaffolder's "you got the command line wrong", which is a different answer from 1 and has
# to survive the trip through node.
[ "$STATUS" -eq 2 ] || fail "expected exit 2 for an unknown command, got $STATUS"

# ---------------------------------------------------------------- a project, in a path with spaces
say "generating a project into a directory with spaces in it"
PROJECT="$WORK/a directory with spaces/my app"
"$BIN" create "$PROJECT" --template vanilla | sed 's/^/  /'
[ -s "$PROJECT/.panamaview/lib/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.jar" ] \
    || fail "the project did not carry the framework"
[ -f "$PROJECT/pom.xml" ] || fail "the project has no pom.xml"
grep -q "panamaview-bundled" "$PROJECT/pom.xml" \
    || fail "the generated pom does not point at the carried copy, so nothing makes it build"

# ---------------------------------------------------------------- the cold machine
say "building it with an empty local repository and no repository configured"
# Nothing but the framework's own url: no repository, no mirror, no profile. -s and -gs both point
# at it so that neither the user's settings nor Homebrew's maven can quietly add one back.
cat > "$WORK/settings.xml" <<'XML'
<settings>
  <!-- Deliberately empty. A build that needs anything configured does not belong in this test. -->
</settings>
XML
grep -Eq "<repository|<mirror" "$WORK/settings.xml" \
    && fail "the settings file this test just wrote declares a repository"
M2="$WORK/m2"
mkdir -p "$M2"
[ -z "$(find "$M2" -mindepth 1 -print -quit)" ] || fail "the local repository is not empty to start with"

(cd "$PROJECT" && mvn -B -s "$WORK/settings.xml" -gs "$WORK/settings.xml" \
    -Dmaven.repo.local="$M2" compile > "$WORK/build.log" 2>&1) || {
    echo "the build failed; last 40 lines:" >&2
    tail -40 "$WORK/build.log" >&2
    fail "a generated project does not build on a machine that has nothing installed"
}
grep -c "BUILD SUCCESS" "$WORK/build.log" > /dev/null || fail "no BUILD SUCCESS in the maven output"

say "and the framework came from the copy the package carries"
# Maven records, beside each artifact it resolves, which repository it came from. This is that
# record: the file names the repository id the scaffolder declares in the generated pom, so a build
# naming it is a build that used the carried copy rather than one from this machine.
REMOTE="$M2/io/github/lihuu/panamaview/$VERSION/_remote.repositories"
[ -s "$REMOTE" ] \
    || fail "Maven resolved the framework without recording where from: $REMOTE does not exist"
sed 's/^/  /' "$REMOTE"
grep -q "panamaview-bundled" "$REMOTE" \
    || fail "the framework was not resolved from the carried copy (see $REMOTE)"
find "$M2/io/github/lihuu/panamaview/$VERSION" -type f | sed "s|^$M2/|  |"

# ---------------------------------------------------------------- and when the install is broken
say "with the binary removed, cli.js says so instead of crashing"
BROKEN="$WORK/broken"
mkdir -p "$BROKEN"
cp -R "$PREFIX/node_modules/create-panamaview" "$BROKEN/create-panamaview"
rm -f "$BROKEN/create-panamaview/bin/panamaview"
set +e
OUTPUT="$(node "$BROKEN/create-panamaview/cli.js" templates 2>&1)"
STATUS=$?
set -e
printf '%s\n' "$OUTPUT" | sed 's/^/  /'
[ "$STATUS" -ne 0 ] || fail "expected a non-zero exit when the binary is missing"
case "$OUTPUT" in
    *bin/panamaview*) ;;
    *) fail "the error does not name the file that is missing, so it is not actionable" ;;
esac
case "$OUTPUT" in
    # A stack trace is what a crash looks like, and this is not a crash: it is an install to fix.
    *"    at "*) fail "the failure printed a stack trace" ;;
esac

printf '\n=== the npm package verified\n'
