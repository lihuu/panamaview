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
# The pack runs package.json's prepack hook, which is what builds the framework and stages
# templates/ and lib/ — so this is also the test of that hook. A publish whose hook had been dropped
# would produce a tarball with neither, and the assertions below are what notice.
say "packing (prepack builds the framework and stages templates/ and lib/)"
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
#
# The package's own files are written out one by one. The templates are read back out of git
# instead, because there are 78 of them and because git is the rule build-package.sh stages by --
# so this is a check on what was staged as well as on what was packed, and a build artefact that
# found its way into npm/templates would be in the tarball and not in this list.
#
# The one rename: npm never packs a file named .gitignore, in any directory, so each template's is
# staged as .gitignore.packed and the scaffolder writes it out under the template's own name. The
# assertion after the first generate is what holds those two names to the same file.
cat > "$WORK/expected.txt" <<EXPECTED
package/README.md
package/cli.js
package/package.json
package/src/scaffold.js
package/lib/io/github/lihuu/panamaview-parent/$VERSION/panamaview-parent-$VERSION.pom
package/lib/io/github/lihuu/panamaview-parent/$VERSION/panamaview-parent-$VERSION.pom.sha1
package/lib/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.jar
package/lib/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.jar.sha1
package/lib/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.pom
package/lib/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.pom.sha1
EXPECTED
git -C "$ROOT" ls-files --cached --others --exclude-standard -- templates \
    | while IFS= read -r file; do
        case "$file" in
            *.gitignore) file="${file%.gitignore}.gitignore.packed" ;;
        esac
        printf 'package/%s\n' "$file"
      done >> "$WORK/expected.txt"
sort -o "$WORK/expected.txt" "$WORK/expected.txt"
if ! diff -u "$WORK/expected.txt" "$WORK/list-first.txt" > "$WORK/list.diff"; then
    sed 's/^/  /' "$WORK/list.diff" >&2
    fail "the tarball is not what package.json's files list describes"
fi
echo "  $(wc -l < "$WORK/expected.txt" | tr -d ' ') files, exactly the ones package.json lists"

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
# The options the help text names are the options the parser has, and the two are written in
# different places. A help text that has drifted lists an option that does not exist, which is only
# discovered by whoever tries it.
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
# 2 is the scaffolder's "you got the command line wrong", a different answer from 1. It is the CLI's
# own exit code now rather than the native executable's coming back through a launcher, which is one
# more thing the port had to keep identical.
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

# And the one file in a template that the tarball can lose without anything else noticing. npm never
# packs a file named .gitignore, so build-package.sh stages each template's as .gitignore.packed and
# the scaffolder writes it out under the real name; if either half of that stopped happening, the
# project would be generated with no template .gitignore at all. What makes it worth its own check
# is that it would still *have* a .gitignore: the scaffolder appends its own entry to one it creates,
# so the project would look right and ignore nothing but .panamaview/ -- committing target/, and
# node_modules/ where the template has a frontend.
grep -q "^target/" "$PROJECT/.gitignore" \
    || fail "the generated project does not ignore target/, so the template's .gitignore did not
  survive packing (see .gitignore.packed in npm/scripts/build-package.sh)"
grep -q "^\.panamaview/$" "$PROJECT/.gitignore" || fail "the carried library is not ignored"
echo "  .gitignore carried whole, and the carried library is ignored"

# ---------------------------------------------------------------- the cold machine
say "building it with an empty local repository and no repository configured"
# Nothing but the framework's own url: no repository, no mirror, no profile. -s and -gs both point
# at it so that neither the user's settings nor a package manager's maven can quietly add one back.
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

printf '\n=== the npm package verified\n'
