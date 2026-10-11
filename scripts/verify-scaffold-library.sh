#!/bin/sh
# Verify the library a packaged scaffolder carries, and that a project built from it needs nothing
# else.
#
# A generated project depends on io.github.lihuu:panamaview, which is not on Maven Central. A package
# delivered through npm or Homebrew therefore has to carry the framework with it, and the generated
# project has to be pointed at that copy. Anything less is a scaffolder that hands someone a project
# which cannot resolve its one dependency — the failure this script exists to catch.
#
# So none of this is checked by reading files. A packaged scaffolder is laid out the way a package
# lays one out (bin/panamaview, lib/ as a Maven repository root), the project it generates is built
# with a settings file that declares no repositories and a local repository that starts empty, and
# the framework's recorded origin is asserted to be the bundled copy.
#
# The empty local repository is the part that matters. This machine's ~/.m2 already holds
# panamaview, so a build that quietly resolved from there would pass a weaker test while proving
# nothing at all.
#
# The whole way — build, run, and the origin of the resolved jar — is done for `vanilla`, on a plain
# path and on a path with a space in it. Every template the manifest lists gets its wiring checked
# (the copy, the repository, the ignore entry): that code is the same for all five, and the check
# fails if a template's pom is not the shape it expects. Building all five would mean four npm
# installs, which is verify-scaffold.sh's job, not this one's.
#
# Usage: verify-scaffold-library.sh
set -eu
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

# Detection reads this, and a stray value in the developer's shell would silently change what is
# being tested.
unset PANAMAVIEW_LIBRARY

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT INT TERM

say() { printf '\n=== %s\n' "$1"; }
fail() { printf '\n%s\n' "$1" >&2; exit 1; }

# ---------------------------------------------------------------- what gets packaged
say "building the framework and the scaffolder"
(cd "$ROOT" && mvn -q -B package -DskipTests)
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25 2>/dev/null || /usr/libexec/java_home)}"
# clean, because build-native.sh refuses to run with two versions of the scaffolder jar in target/.
(cd "$ROOT/scaffold" && mvn -q -B clean package -DskipTests && ./build-native.sh)

VERSION="$(python3 -c '
import re, sys
print(re.search(r"<artifactId>panamaview</artifactId>\s*<version>([^<]+)</version>",
                open(sys.argv[1]).read()).group(1))' "$ROOT/pom.xml")"
FRAMEWORK_JAR="$ROOT/target/panamaview-$VERSION.jar"
BINARY="$ROOT/scaffold/target/panamaview"
SCAFFOLDER_JAR="$ROOT/scaffold/target/panamaview-scaffold-$VERSION.jar"
for file in "$FRAMEWORK_JAR" "$BINARY" "$SCAFFOLDER_JAR"; do
    [ -f "$file" ] || fail "expected $file to exist after the build"
done
echo "  framework $VERSION"

say "laying out a packaged scaffolder: bin/panamaview beside lib/ as a Maven repository"
PKG="$WORK/pkg"
mkdir -p "$PKG/bin" "$PKG/lib/io/github/lihuu/panamaview/$VERSION"
cp "$BINARY" "$PKG/bin/panamaview"
cp "$FRAMEWORK_JAR" "$PKG/lib/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.jar"
cp "$ROOT/pom.xml" "$PKG/lib/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.pom"
(cd "$PKG" && find . -type f | sed 's|^|  |')

# The names come out of the built jar's manifest, not from a list written here, so a template added
# to the repository is covered without this script being touched.
TEMPLATES="$(unzip -p "$SCAFFOLDER_JAR" templates-manifest.txt \
    | awk '!/^#/ && NF && $1 != "version" {print $1}' | cut -d/ -f1 | sort -u)"
[ -n "$TEMPLATES" ] || fail "the scaffolder's manifest lists no templates"

# ------------------------------------------------------------------ the wiring, template by template
#
# $1 the generated project, $2 what to call it in a message.
wired() {
    project="$1"
    label="$2"
    artifacts="$project/.panamaview/lib/io/github/lihuu/panamaview/$VERSION"

    [ -f "$artifacts/panamaview-$VERSION.jar" ] || fail "[$label] no jar at $artifacts"
    [ -f "$artifacts/panamaview-$VERSION.pom" ] || fail "[$label] no pom at $artifacts"
    # The copy must not contain a copy of itself. It is a copy of a repository root into a
    # subdirectory of the project, which is exactly the shape that goes wrong on a second run.
    [ ! -e "$project/.panamaview/lib/.panamaview" ] \
        || fail "[$label] .panamaview was copied inside itself"
    [ ! -e "$project/.panamaview/lib/lib" ] || fail "[$label] lib/ was copied inside itself"

    id="$(grep -c -F '<id>panamaview-bundled</id>' "$project/pom.xml" || true)"
    [ "$id" = 1 ] || fail "[$label] the pom declares panamaview-bundled $id time(s), expected 1"
    # Double quotes with the dollar escaped: the url in the pom is the literal
    # ${project.basedir}, and grep is matching that text, not expanding it.
    url="$(grep -c -F "<url>file://\${project.basedir}/.panamaview/lib</url>" "$project/pom.xml" || true)"
    [ "$url" = 1 ] || fail "[$label] the pom's repository url is missing or repeated ($url time(s))"

    # Before the dependency it resolves, which is where a reader looks for it.
    repositories="$(grep -n -F '<repositories>' "$project/pom.xml" | head -1 | cut -d: -f1)"
    dependencies="$(grep -n -F '<dependencies>' "$project/pom.xml" | head -1 | cut -d: -f1)"
    [ -n "$repositories" ] && [ -n "$dependencies" ] \
        || fail "[$label] the pom has no <repositories> or no <dependencies>"
    [ "$repositories" -lt "$dependencies" ] \
        || fail "[$label] <repositories> is not before <dependencies>"

    ignored="$(grep -c -x -F '.panamaview/' "$project/.gitignore" || true)"
    [ "$ignored" = 1 ] || fail "[$label] .gitignore lists .panamaview/ $ignored time(s), expected 1"

    echo "  [$label] copy, repository and ignore entry"
}

say "generating from every template with the library beside the binary"
for name in $TEMPLATES; do
    directory="$WORK/generated/$name"
    mkdir -p "$directory"
    (cd "$directory" && "$PKG/bin/panamaview" create app --template "$name") > "$WORK/$name.log"
    # One line saying which library was found, so that a failure to wire is diagnosable rather than
    # silent. It is checked here for the same reason it is printed there.
    grep -q -F "library   $PKG/lib" "$WORK/$name.log" \
        || fail "[$name] the report does not say which library was found: $(cat "$WORK/$name.log")"
    grep -F '  library' "$WORK/$name.log"
    wired "$directory/app" "$name"
done

say "and with the library beside the executable itself, the other layout"
#
# The rule is <dir of the executable>/lib, which is the same predicate as the packaged layout above
# with a different directory: a launcher that sits at a package root rather than in a bin/. Both are
# exercised because both are what a package manager will produce, and neither is the other's test.
FLAT="$WORK/flat"
mkdir -p "$FLAT/lib/io/github/lihuu/panamaview/$VERSION"
cp "$BINARY" "$FLAT/panamaview"
cp "$FRAMEWORK_JAR" "$FLAT/lib/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.jar"
cp "$ROOT/pom.xml" "$FLAT/lib/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.pom"
(cd "$WORK/generated/vanilla" && "$FLAT/panamaview" create flat --template vanilla) > /dev/null
wired "$WORK/generated/vanilla/flat" "beside the executable"

say "and through a link, which is how a package manager installs it"
#
# The one that was actually broken, and the reason this section exists. Homebrew does not copy the
# binary into <prefix>/bin, it links it there, and ProcessHandle reports the path as invoked — the
# link. So both layouts above can be present and correct in the keg while a user typing "panamaview"
# finds nothing beside the link, generates a project, and gets a project that resolves no framework.
# The failure is silent: the scaffolder works, the project is written, only the dependency is
# unresolvable later. Looking beside the invoked path is therefore not enough on its own.
LINKED="$WORK/linked"
mkdir -p "$LINKED/keg/bin" "$LINKED/prefix/bin"
cp "$BINARY" "$LINKED/keg/bin/panamaview"
mkdir -p "$LINKED/keg/bin/lib/io/github/lihuu/panamaview/$VERSION"
cp "$FRAMEWORK_JAR" "$LINKED/keg/bin/lib/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.jar"
cp "$ROOT/pom.xml" "$LINKED/keg/bin/lib/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.pom"
ln -s "$LINKED/keg/bin/panamaview" "$LINKED/prefix/bin/panamaview"

(cd "$WORK/generated/vanilla" && "$LINKED/prefix/bin/panamaview" create linked --template vanilla) \
    > "$WORK/linked.log"
# Compared physically, not textually: the point of this rule is that the executable is followed to
# its real home, so the path it reports is the resolved one (on macOS /tmp is itself a link, so even
# an unlinked fixture reports a different string). Resolving both sides asserts the same directory
# without asserting the same spelling.
expected_library="$(cd "$LINKED/keg/bin/lib" && pwd -P)"
reported_library="$(sed -n 's/^  library   //p' "$WORK/linked.log" | head -1)"
reported_resolved="$(cd "$reported_library" 2>/dev/null && pwd -P || printf '%s' "$reported_library")"
[ "$reported_resolved" = "$expected_library" ] \
    || fail "through a link, the library found was not the keg's: $reported_resolved != $expected_library"
wired "$WORK/generated/vanilla/linked" "through a link"

say "PANAMAVIEW_LIBRARY wins over the layout beside the executable"
#
# Both are present here, so this is the first-hit-wins order and not just "a library was found".
(cd "$WORK/generated/vanilla" && PANAMAVIEW_LIBRARY="$FLAT/lib" "$PKG/bin/panamaview" create chosen \
    --template vanilla) > "$WORK/chosen.log"
grep -q -F "library   $FLAT/lib" "$WORK/chosen.log" \
    || fail "the named library was not the one used: $(grep '  library' "$WORK/chosen.log")"
if grep -q -F "$PKG/lib" "$WORK/chosen.log"; then
    fail "the layout beside the executable was used, though one was named"
fi
echo "  used $FLAT/lib, not the $PKG/lib beside the executable"

say "generating over a project that is already there, with --force"
# Something left over from an older framework is the thing to look for: the copy is emptied before
# it is written, not written over, and a stale jar in a directory that is still declared as a
# repository is invisible in every other way.
DECOY="$WORK/generated/vanilla/app/.panamaview/lib/io/github/lihuu/panamaview/$VERSION/panamaview-0.1.0.jar"
: > "$DECOY"
(cd "$WORK/generated/vanilla" && "$PKG/bin/panamaview" create app --template vanilla --force) > /dev/null
[ ! -e "$DECOY" ] || fail "a leftover file from an earlier copy is still there: $DECOY"
wired "$WORK/generated/vanilla/app" "vanilla --force"

# ------------------------------------------------------------------ with nothing to fall back on
#
# $1 the generated project, $2 a label for the settings and repository files.
isolated() {
    project="$1"
    label="$2"
    repository="$WORK/repository-$label"
    settings="$WORK/settings-$label.xml"
    mkdir -p "$repository"
    cat > "$settings" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<!-- Deliberately empty: no mirrors, no repositories, no servers. Nothing but the project's own
     pom.xml can supply an artifact, which is the whole point of building this way. -->
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
</settings>
XML

    [ -z "$(find "$repository" -mindepth 1 -print -quit)" ] \
        || fail "[$label] the isolated local repository did not start empty"

    # MAVEN_ARGS rather than a hand-rolled mvn command line, because the project's own ./run.sh is
    # what has to work and it calls plain `mvn` twice. Maven appends this to every command, so the
    # script a user runs is the script under test. If it were ignored, nothing would arrive in the
    # repository below and the origin check would fail — this cannot pass for the wrong reason.
    MAVEN_ARGS="-s $settings -Dmaven.repo.local=$repository"

    echo "  local repository: $repository (empty)"
    (cd "$project" && MAVEN_ARGS="$MAVEN_ARGS" JAVA_OPTS=-Dpanamaview.check=true ./run.sh)

    remote="$repository/io/github/lihuu/panamaview/$VERSION/_remote.repositories"
    [ -f "$remote" ] || fail "[$label] the framework never reached the isolated repository"
    # The id, with Maven's own hash of the repository url appended to it, so the prefix is what is
    # matched. This is the evidence: not that the build worked, but where the jar came from.
    grep -F "panamaview-$VERSION.jar>panamaview-bundled" "$remote" > /dev/null || {
        sed 's/^/    /' "$remote" >&2
        fail "[$label] the framework did not come from the bundled repository"
    }
    sed 's/^/  /' "$remote" | grep -v '^  #'
    # And the bytes are the ones that were packaged, not another 0.2.0 from somewhere.
    cmp "$PKG/lib/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.jar" \
        "$repository/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.jar" \
        || fail "[$label] the resolved jar is not the jar that was bundled"
}

say "building and running a generated project against an empty repository"
isolated "$WORK/generated/vanilla/app" "plain"

say "the same, with a space in the project's path"
SPACED="$WORK/with a space"
mkdir -p "$SPACED"
(cd "$SPACED" && "$PKG/bin/panamaview" create "my app" --template vanilla) > /dev/null
# The url is file://${project.basedir}/..., which Maven has to accept with a space in it. Asserting
# the origin of the jar that arrives says it did more than accept it.
isolated "$SPACED/my app" "spaced"

# ------------------------------------------------------------------ with no library at all
say "with no library beside the binary: nothing wired, as before"
BARE="$WORK/bare"
mkdir -p "$BARE/bin"
cp "$BINARY" "$BARE/bin/panamaview"
(cd "$BARE" && ./bin/panamaview create app --template vanilla) > "$WORK/bare.log"
if grep -q '^  library' "$WORK/bare.log"; then
    fail "a library was reported with none beside the binary: $(grep '^  library' "$WORK/bare.log")"
fi
[ ! -e "$BARE/app/.panamaview" ] || fail "a project with no library beside it got a .panamaview"
if grep -q -F 'panamaview-bundled' "$BARE/app/pom.xml"; then
    fail "a project with no library beside it got a repository it cannot use"
fi
# Byte for byte the template's own file. The template has no token in it to substitute, so this is
# an equality and not an approximation: the scaffolder added nothing and took nothing away.
cmp "$ROOT/templates/vanilla/.gitignore" "$BARE/app/.gitignore" \
    || fail "the generated .gitignore is not the template's, though there is no library to ignore"
echo "  generated, unwired: no .panamaview, no repository, .gitignore untouched"

# ------------------------------------------------------------------ a library that is not one
say "PANAMAVIEW_LIBRARY pointing at nothing: an error, not a fall-through"
#
# Someone who names a directory meant that directory. Falling back to the layout beside the
# executable would generate a project that resolves the framework from somewhere they did not
# choose — silently, and on this machine successfully, which is the worst version of it.
set +e
OUTPUT="$(cd "$WORK" && PANAMAVIEW_LIBRARY="$WORK/does-not-exist" "$PKG/bin/panamaview" \
    create envfail/app --template vanilla 2>&1)"
STATUS=$?
set -e
printf '%s\n' "$OUTPUT" | sed 's/^/  /'
[ "$STATUS" -ne 0 ] || fail "a library directory that does not exist was accepted"
case "$OUTPUT" in
    *PANAMAVIEW_LIBRARY*) ;;
    *) fail "the error does not name the variable, so it is not actionable" ;;
esac
[ ! -e "$WORK/envfail/app" ] || fail "it generated a project anyway, with a library it could not use"

say "PANAMAVIEW_LIBRARY pointing at a repository of the wrong version: also an error"
WRONG="$WORK/wrong-version/io/github/lihuu/panamaview/0.1.0"
mkdir -p "$WRONG"
cp "$FRAMEWORK_JAR" "$WRONG/panamaview-0.1.0.jar"
cp "$ROOT/pom.xml" "$WRONG/panamaview-0.1.0.pom"
set +e
OUTPUT="$(cd "$WORK" && PANAMAVIEW_LIBRARY="$WORK/wrong-version" "$PKG/bin/panamaview" \
    create wrongfail/app --template vanilla 2>&1)"
STATUS=$?
set -e
[ "$STATUS" -ne 0 ] || fail "a library holding the wrong version was accepted: $OUTPUT"

say "PANAMAVIEW_LIBRARY set to nothing: not set, so the library beside the binary is used"
(cd "$WORK/generated/vanilla" && PANAMAVIEW_LIBRARY='' "$PKG/bin/panamaview" create empty \
    --template vanilla) > /dev/null
wired "$WORK/generated/vanilla/empty" "empty variable"

printf '\n=== a packaged scaffolder carries the framework, and a project it makes builds from it\n'
