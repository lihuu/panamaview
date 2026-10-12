#!/bin/sh
# Verify the library a packaged scaffolder carries, and that a project built from it needs nothing
# else.
#
# A generated project depends on io.github.lihuu:panamaview, which is not on Maven Central. A package
# delivered through npm therefore has to carry the framework with it, and the generated
# project has to be pointed at that copy. Anything less is a scaffolder that hands someone a project
# which cannot resolve its one dependency — the failure this script exists to catch.
#
# So none of this is checked by reading files. A packaged scaffolder is laid out the way a package
# lays one out — cli.js, src/ and lib/ at one directory, which is the whole of what npm installs —
# the project it generates is built with a settings file that declares no repositories and a local
# repository that starts empty, and the framework's recorded origin is asserted to be the bundled
# copy.
#
# The empty local repository is the part that matters. This machine's ~/.m2 already holds
# panamaview, so a build that quietly resolved from there would pass a weaker test while proving
# nothing at all.
#
# The whole way — build, run, and the origin of the resolved jar — is done for `vanilla`, on a plain
# path and on a path with a space in it. Every template the package carries gets its wiring checked
# (the copy, the repository, the ignore entry): that code is the same for all five, and the check
# fails if a template's pom is not the shape it expects. Building all five would mean four npm
# installs, which is verify-scaffold.sh's job, not this one.
#
# Usage: verify-scaffold-library.sh
set -eu
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

# Detection reads this, and a stray value in the developer's shell would silently change what is
# being tested.
unset PANAMAVIEW_LIBRARY

WORK="$(mktemp -d)"
# Resolved, because the scaffolder reports the directory it resolved to while this script compares
# that report against its own paths as text. On macOS mktemp hands out /var/folders/..., which is
# reached through a link, so one directory has two spellings and the fixture would be held to the one
# the CLI does not print. Linux has no such link, which is why this passed in CI and failed here.
WORK="$(cd "$WORK" && pwd -P)"
trap 'rm -rf "$WORK"' EXIT INT TERM

say() { printf '\n=== %s\n' "$1"; }
fail() { printf '\n%s\n' "$1" >&2; exit 1; }

# ---------------------------------------------------------------- what gets packaged
say "building the framework"
# The framework only. There is no scaffolder to build: it is JavaScript, it has no native image, and
# it is the same source at every version of this package. What is built here is what the package
# carries, and it is taken from framework/target rather than from a staged copy — so this script
# still catches it if the staging stops putting the framework where a package's lib/ has to be.
(cd "$ROOT" && mvn -q -B package -DskipTests)
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25 2>/dev/null || /usr/libexec/java_home)}"

# The coordinates come from the framework's own pom, not the root one: since the build became a
# reactor, the root pom is the parent (io.github.lihuu:panamaview-parent) and the framework is a
# module beside it. Reading the first <version> in pom.xml used to be the same thing and no longer
# is — scripts/framework-coordinates.sh is the one place that knows where to look.
. "$ROOT/scripts/framework-coordinates.sh"
VERSION="$FRAMEWORK_VERSION"
FRAMEWORK_JAR="$ROOT/framework/target/panamaview-$VERSION.jar"
PARENT_POM="$ROOT/pom.xml"
for file in "$FRAMEWORK_JAR" "$PARENT_POM"; do
    [ -f "$file" ] || fail "expected $file to exist after the build"
done
echo "  framework $VERSION"

say "laying out a packaged scaffolder: cli.js, src/ and lib/ at one directory"
PKG="$WORK/pkg"
mkdir -p "$PKG/src" "$PKG/lib/$FRAMEWORK_REPO_PATH" \
         "$PKG/lib/io/github/lihuu/panamaview-parent/$VERSION"
cp "$ROOT/npm/cli.js" "$PKG/cli.js"
cp "$ROOT/npm/src/scaffold.js" "$PKG/src/scaffold.js"
# The catalog is what says which templates exist, and the version is part of it, so the CLI reads
# package.json beside itself. A package without one does not run at all.
cp "$ROOT/npm/package.json" "$PKG/package.json"
# The templates, by git's reckoning rather than by `cp -R`: a template's frontend/node_modules is not
# part of it, and staging one makes every command fail outright — the scaffolder refuses a symlink
# in a template, and node_modules/.bin is nothing but symlinks. npm/scripts/build-package.sh stages
# the same set for the tarball, and says more about why.
for dir in "$ROOT"/templates/*/; do
    name="$(basename "$dir")"
    files="$(git -C "$ROOT" ls-files --cached --others --exclude-standard -- "templates/$name")"
    printf '%s\n' "$files" | while IFS= read -r file; do
        [ -n "$file" ] || continue
        # Paths from `git ls-files` start at the repository root, so their `templates/` is the
        # component $PKG/templates/ already is. Without stripping it the fixture holds one directory
        # called "templates" and no template at all, and every generate below fails at the catalog.
        rel="${file#templates/}"
        # Renamed here as well, because this fixture exists to be the package rather than to be a
        # checkout: npm never packs a file named .gitignore, so the package stages it under
        # .gitignore.packed and the scaffolder writes it back out as .gitignore. A fixture that
        # kept the real name would be testing a layout npm cannot produce. The `cmp` against the
        # template's own .gitignore further down is what holds the two names to the same file.
        case "$rel" in
            .gitignore|*/.gitignore) rel="${rel%.gitignore}.gitignore.packed" ;;
        esac
        mkdir -p "$PKG/templates/$(dirname "$rel")"
        cp "$ROOT/$file" "$PKG/templates/$rel"
    done
done
cp "$FRAMEWORK_JAR" "$PKG/lib/$FRAMEWORK_REPO_PATH/panamaview-$VERSION.jar"
cp "$ROOT/framework/pom.xml" "$PKG/lib/$FRAMEWORK_REPO_PATH/panamaview-$VERSION.pom"
# The parent travels with the framework. It is also what npm/scripts/build-package.sh stages, and
# this is the layout that catches it if that stops happening.
cp "$PARENT_POM" "$PKG/lib/io/github/lihuu/panamaview-parent/$VERSION/panamaview-parent-$VERSION.pom"
(cd "$PKG" && find . -type f | sed 's|^|  |')

# The names come from the templates the package carries, not from a list written here, so a template
# added to the repository is covered without this script being touched.
TEMPLATES="$(cd "$PKG/templates" && for d in */; do printf '%s\n' "${d%/}"; done | sort)"
[ -n "$TEMPLATES" ] || fail "the package carries no templates"

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
    # Written as an if, not `A && B || C`: that idiom runs C when A succeeds and B fails, which
    # is not the same thing as if-then-else and is exactly the kind of near-miss a lint is for.
    if [ -z "$repositories" ] || [ -z "$dependencies" ]; then
        fail "[$label] the pom has no <repositories> or no <dependencies>"
    fi
    [ "$repositories" -lt "$dependencies" ] \
        || fail "[$label] <repositories> is not before <dependencies>"

    ignored="$(grep -c -x -F '.panamaview/' "$project/.gitignore" || true)"
    [ "$ignored" = 1 ] || fail "[$label] .gitignore lists .panamaview/ $ignored time(s), expected 1"

    echo "  [$label] copy, repository and ignore entry"
}

say "generating from every template with the library at the package root"
for name in $TEMPLATES; do
    directory="$WORK/generated/$name"
    mkdir -p "$directory"
    (cd "$directory" && node "$PKG/cli.js" create app --template "$name") > "$WORK/$name.log"
    # One line saying which library was found, so that a failure to wire is diagnosable rather than
    # silent. It is checked here for the same reason it is printed there.
    grep -q -F "library   $PKG/lib" "$WORK/$name.log" \
        || fail "[$name] the report does not say which library was found: $(cat "$WORK/$name.log")"
    grep -F '  library' "$WORK/$name.log"
    wired "$directory/app" "$name"
done

say "and through a link, which is how a package manager installs it"
#
# The one that was actually broken in the Java version, and the reason this section exists. npm
# does not copy the command into <prefix>/bin, it links it there, and a program that asks
# where it was invoked from is told the link. Both layouts above can then be present and correct in
# the keg while a user typing the command finds nothing beside the link, generates a project, and
# gets a project that resolves no framework — silently, since the scaffolder works and the project is
# written; only the dependency is unresolvable, later.
#
# The Java version needed a separate rule for this: it asked ProcessHandle, which reports the path as
# invoked, and followed the link itself. Node does it for free — a module's own filename is already
# the path it resolves to — so the third rule is the whole of it and this is the test that says so.
# It is not a test that can pass for the wrong reason: the library beside the link is asserted to be
# absent below, so a CLI that looked beside the invoked path would find nothing and fail `wired`.
LINKED="$WORK/linked"
mkdir -p "$LINKED/prefix/bin" "$LINKED/prefix/lib/node_modules"
cp -R "$PKG" "$LINKED/keg"
ln -s "$LINKED/keg" "$LINKED/prefix/lib/node_modules/create-panamaview"
# What npm writes: the bin entry is a link to the file inside the package, which is itself reached
# through a link. Invoked the way a user invokes it, shebang and all.
ln -s "../lib/node_modules/create-panamaview/cli.js" "$LINKED/prefix/bin/panamaview"

[ ! -e "$LINKED/prefix/bin/lib" ] \
    || fail "the fixture is wrong: something is sitting beside the link after all"

(cd "$WORK/generated/vanilla" && "$LINKED/prefix/bin/panamaview" create linked --template vanilla) \
    > "$WORK/linked.log"
# Compared physically, not textually: the point of this rule is that the package is followed to its
# real home, so what it reports is a resolved path rather than the one it was invoked by. Resolving
# both sides asserts the same directory without asserting the same spelling.
expected_library="$(cd "$LINKED/keg/lib" && pwd -P)"
reported_library="$(sed -n 's/^  library   //p' "$WORK/linked.log" | head -1)"
reported_resolved="$(cd "$reported_library" 2>/dev/null && pwd -P || printf '%s' "$reported_library")"
[ "$reported_resolved" = "$expected_library" ] \
    || fail "through a link, the library found was not the keg's: $reported_resolved != $expected_library"
wired "$WORK/generated/vanilla/linked" "through a link"

say "PANAMAVIEW_LIBRARY wins over the layout beside the package"
#
# Both are present here, so this is the first-hit-wins order and not just "a library was found".
(cd "$WORK/generated/vanilla" && PANAMAVIEW_LIBRARY="$LINKED/keg/lib" node "$PKG/cli.js" create \
    chosen --template vanilla) > "$WORK/chosen.log"
grep -q -F "library   $LINKED/keg/lib" "$WORK/chosen.log" \
    || fail "the named library was not the one used: $(grep '  library' "$WORK/chosen.log")"
if grep -q -F "$PKG/lib" "$WORK/chosen.log"; then
    fail "the layout beside the package was used, though one was named"
fi
echo "  used $LINKED/keg/lib, not the $PKG/lib beside the package"

say "generating over a project that is already there, with --force"
# Something left over from an older framework is the thing to look for: the copy is emptied before
# it is written, not written over, and a stale jar in a directory that is still declared as a
# repository is invisible in every other way.
DECOY="$WORK/generated/vanilla/app/.panamaview/lib/io/github/lihuu/panamaview/$VERSION/panamaview-0.1.0.jar"
: > "$DECOY"
(cd "$WORK/generated/vanilla" && node "$PKG/cli.js" create app --template vanilla --force) > /dev/null
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
(cd "$SPACED" && node "$PKG/cli.js" create "my app" --template vanilla) > /dev/null
# The url is file://${project.basedir}/..., which Maven has to accept with a space in it. Asserting
# the origin of the jar that arrives says it did more than accept it.
isolated "$SPACED/my app" "spaced"

# ------------------------------------------------------------------ with no library at all
say "with no library in the package: nothing wired, as before"
# A checkout is this: the sources, the templates, and no lib/ beside them. Generating from one has to
# keep working, and has to leave the project alone.
BARE="$WORK/bare"
mkdir -p "$BARE"
cp "$PKG/cli.js" "$BARE/cli.js"
cp "$ROOT/npm/package.json" "$BARE/package.json"
cp -R "$PKG/src" "$BARE/src"
cp -R "$PKG/templates" "$BARE/templates"
(cd "$BARE" && node "$BARE/cli.js" create app --template vanilla) > "$WORK/bare.log"
if grep -q '^  library' "$WORK/bare.log"; then
    fail "a library was reported with none in the package: $(grep '^  library' "$WORK/bare.log")"
fi
[ ! -e "$BARE/app/.panamaview" ] || fail "a project with no library beside the package got a .panamaview"
if grep -q -F 'panamaview-bundled' "$BARE/app/pom.xml"; then
    fail "a project with no library beside the package got a repository it cannot use"
fi
# Byte for byte the template's own file. The template has no token in it to substitute, so this is
# an equality and not an approximation: the scaffolder added nothing and took nothing away.
cmp "$ROOT/templates/vanilla/.gitignore" "$BARE/app/.gitignore" \
    || fail "the generated .gitignore is not the template's, though there is no library to ignore"
echo "  generated, unwired: no .panamaview, no repository, .gitignore untouched"

# ------------------------------------------------------------------ a library that is not one
say "PANAMAVIEW_LIBRARY pointing at nothing: an error, not a fall-through"
#
# Someone who names a directory meant that directory. Falling back to the layout beside the package
# would generate a project that resolves the framework from somewhere they did not choose —
# silently, and on this machine successfully, which is the worst version of it.
set +e
OUTPUT="$(cd "$WORK" && PANAMAVIEW_LIBRARY="$WORK/does-not-exist" node "$PKG/cli.js" \
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
cp "$ROOT/framework/pom.xml" "$WRONG/panamaview-0.1.0.pom"
set +e
OUTPUT="$(cd "$WORK" && PANAMAVIEW_LIBRARY="$WORK/wrong-version" node "$PKG/cli.js" \
    create wrongfail/app --template vanilla 2>&1)"
STATUS=$?
set -e
[ "$STATUS" -ne 0 ] || fail "a library holding the wrong version was accepted: $OUTPUT"

say "PANAMAVIEW_LIBRARY set to nothing: not set, so the library in the package is used"
(cd "$WORK/generated/vanilla" && PANAMAVIEW_LIBRARY='' node "$PKG/cli.js" create empty \
    --template vanilla) > /dev/null
wired "$WORK/generated/vanilla/empty" "empty variable"

printf '\n=== a packaged scaffolder carries the framework, and a project it makes builds from it\n'
