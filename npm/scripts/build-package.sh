#!/bin/sh
# Populate npm/bin/ and npm/lib/ — the two things this package ships.
#
# bin/panamaview is the scaffolder as a native executable. lib/ is the framework laid out as a
# Maven repository root, which the scaffolder copies into every project it generates, so that the
# project's one dependency resolves on a machine that has no repository configured and nothing
# installed. Both are build output, neither is committed, and package.json runs this from `prepack`
# so that a publish which would have shipped an empty bin/ or lib/ fails here instead.
#
# Usage: npm/scripts/build-package.sh
set -eu
cd "$(dirname "$0")/.."
NPM_DIR="$(pwd)"
ROOT="$(cd .. && pwd)"

say() { printf '\n=== %s\n' "$1"; }
fail() { printf 'build-package.sh: %s\n' "$1" >&2; exit 1; }

# ---------------------------------------------------------------- one version, in two files
say "the version the package claims, and the version it carries"
#
# The version comes from the framework's own pom. It used to come from the root pom, which was the
# same file until the build became a reactor: now the root pom is the parent
# (io.github.lihuu:panamaview-parent) and the framework is a module beside it. Reading "the first
# <version> in pom.xml" would give the parent's version, which happens to be the same number today
# and would stop being the same number the moment anyone built the two independently.
#
# Read without a here-document inside a command substitution (see the note below), so the shared
# script is sourced and then the value is copied out of it.
. "$ROOT/scripts/framework-coordinates.sh"
VERSION="$FRAMEWORK_VERSION"
[ -n "$VERSION" ] || fail "no version in $FRAMEWORK_POM"

# The jar's name and the directory it is filed under are both the framework's version, so a
# package.json claiming a different one would ship a framework that the projects it generates do
# not depend on — and they would fail to resolve it, on the user's machine, in a file the user did
# not write. scripts/release.sh bumps the two together; this is what notices when they are not.
python3 - "$VERSION" "$NPM_DIR/package.json" "$FRAMEWORK_POM" <<'PY'
import json, re, sys

version, package, pom = sys.argv[1], sys.argv[2], sys.argv[3]
claimed = json.load(open(package))["version"]
if version != claimed:
    sys.exit(f"\nMISMATCH: the framework is {version}, npm/package.json claims {claimed}.\n"
             f"The package carries the framework under its own version, and the projects it\n"
             f"generates pin that version, so the claim has to be the version that is really in\n"
             f"there.\n"
             f"Fix: set \"version\": \"{version}\" in npm/package.json")
# Read the framework's coordinates out of its pom independently of the shared script above, so the
# two have to agree rather than one silently trusting the other. Both are looking at a pom with no
# <parent>, so the first <version> in it is the project's own.
found = re.search(r"<version>([^<]+)</version>", open(pom).read()).group(1)
if found != version:
    sys.exit(f"\n{version} came out of {pom} one way and {found} another.")
print(f"  {version}")
PY

# ---------------------------------------------------------------- what goes in
say "building the scaffolder as a native executable"
# clean, not just package: build-native.sh counts the jars in target/ and refuses to run when there
# are two, which is exactly what a leftover jar from an earlier experiment looks like.
(cd "$ROOT/scaffold" && mvn -q -B clean package -DskipTests && ./build-native.sh)
BUILT="$ROOT/scaffold/target/panamaview"
[ -f "$BUILT" ] || fail "build-native.sh wrote no $BUILT"

say "building the framework"
# The whole reactor, so the parent pom is installed as well as the framework — the framework's pom
# inherits from it, and a consumer resolving the framework reads the parent first.
(cd "$ROOT" && mvn -q -B install -DskipTests)
FRAMEWORK="$ROOT/framework/target/panamaview-$VERSION.jar"
[ -s "$FRAMEWORK" ] || fail "mvn install wrote no $FRAMEWORK"

say "staging bin/ and lib/"
# Cleared rather than written over: a version bump would otherwise leave the previous release
# sitting in lib/ for ever, inside every tarball built afterwards.
rm -rf "${NPM_DIR:?}/bin" "${NPM_DIR:?}/lib"
ARTIFACTS="$NPM_DIR/lib/$FRAMEWORK_REPO_PATH"
PARENT_ARTIFACTS="$NPM_DIR/lib/io/github/lihuu/panamaview-parent/$VERSION"
mkdir -p "$NPM_DIR/bin" "$ARTIFACTS" "$PARENT_ARTIFACTS"

cp "$BUILT" "$NPM_DIR/bin/panamaview"
# Set rather than assumed: a copy that drops the mode turns every later `panamaview` into
# "permission denied", and the tarball is built from this file.
chmod 755 "$NPM_DIR/bin/panamaview"

cp "$FRAMEWORK" "$ARTIFACTS/panamaview-$VERSION.jar"
# The framework's own pom, unchanged. It is what Maven reads to learn the artifact's dependencies,
# and the answer for this artifact is none, which is what the file already says.
cp "$FRAMEWORK_POM" "$ARTIFACTS/panamaview-$VERSION.pom"
# And the parent it inherits from, because resolving the framework means reading this first. It is
# declared in the framework's <parent> and cannot be dropped without a consumer's build failing to
# read the pom at all — which is exactly the class of bundled-layout bug this package exists to
# avoid. scripts/verify-scaffold-library.sh builds a generated project against an empty local
# repository, so a missing parent fails there rather than on someone else's machine.
cp "$ROOT/pom.xml" "$PARENT_ARTIFACTS/panamaview-parent-$VERSION.pom"
# Checksums, because a Maven repository without them makes the first build of every generated
# project print a warning about a download it could not verify.
for staged in "$ARTIFACTS" "$PARENT_ARTIFACTS"; do
    for artifact in "$staged"/*.jar "$staged"/*.pom; do
        (cd "$staged" && shasum -a 1 "$(basename "$artifact")" \
            | awk '{ print $1 }' > "$(basename "$artifact").sha1")
    done
done

# ---------------------------------------------------------------- what is about to be packed
#
# Each check below is a way this has gone wrong or would go wrong silently: a binary built for
# another architecture than the package.json declares, a jar that is a valid zip with nothing in
# it, a file mode that was dropped, and a layout the scaffolder does not recognise — which last one
# is the only one of the four that no file list, checksum or test of this package's own contents
# could catch.
say "what was staged"
for file in "$NPM_DIR/bin/panamaview" "$ARTIFACTS/panamaview-$VERSION.jar" \
        "$ARTIFACTS/panamaview-$VERSION.pom" \
        "$PARENT_ARTIFACTS/panamaview-parent-$VERSION.pom"; do
    [ -s "$file" ] || fail "missing or empty: $file"
    printf '  %10s  %s\n' "$(wc -c < "$file" | tr -d ' ')" "${file#"$NPM_DIR/"}"
done

say "the binary is what package.json says it is"
# package.json declares darwin/arm64, so npm refuses the install anywhere else rather than letting
# a binary that cannot run that far. This is the other half of the same claim: the declaration being
# true of the file that is really in there.
DESCRIPTION="$(file -b "$NPM_DIR/bin/panamaview")"
echo "  $DESCRIPTION"
case "$DESCRIPTION" in
    *"Mach-O 64-bit executable arm64"*) ;;
    *) fail "bin/panamaview is not a Mach-O arm64 executable, so the os and cpu it declares are a lie" ;;
esac
[ -x "$NPM_DIR/bin/panamaview" ] || fail "bin/panamaview is not executable"

say "the jar has the framework in it"
unzip -l "$ARTIFACTS/panamaview-$VERSION.jar" | grep -q "panamaview/WebApp.class" \
    || fail "panamaview-$VERSION.jar does not contain the framework's own classes"

say "the binary runs"
"$NPM_DIR/bin/panamaview" --version

say "it finds the library beside it"
# The layout, not just the files. The scaffolder looks for a Maven repository root next to its own
# executable, so this is the check that lib/ is where that lookup expects it — nothing about the
# package's contents on their own would notice it being one directory out.
PROBE="$(mktemp -d)"
trap 'rm -rf "$PROBE"' EXIT INT TERM
"$NPM_DIR/bin/panamaview" create "$PROBE/probe" --template vanilla > "$PROBE/create.log"
sed 's/^/  /' "$PROBE/create.log"
CARRIED="$PROBE/probe/.panamaview/lib/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.jar"
[ -s "$CARRIED" ] || fail "the scaffolder did not carry the library into the project it generated;
  it looks next to its own executable, so check that npm/lib is that directory"

printf '\n=== staged: bin/panamaview and lib/ at %s\n' "$VERSION"
