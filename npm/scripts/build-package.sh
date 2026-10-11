#!/bin/sh
# Populate npm/lib/ and npm/templates/ — the two things this package ships besides its own source.
#
# lib/ is the framework laid out as a Maven repository root, which the scaffolder copies into every
# project it generates, so that the project's one dependency resolves on a machine that has no
# repository configured and nothing installed. templates/ is the templates themselves, copied in
# because npm's `files` field cannot reach outside the package directory (verified: a "../templates/"
# entry is silently ignored, and a silently-ignored entry means a published package with no
# templates in it).
#
# There is no longer a native executable to build. The scaffolder is JavaScript now — it was 13 MB
# of per-platform binary for a job whose whole content is "copy a directory and rename three
# strings", and Node is already present in anyone who can install this package at all. What used to
# be a build step is now simply the source.
#
# Both are build output, neither is committed, and package.json runs this from `prepack` so that a
# publish which would have shipped an empty lib/ or templates/ fails here instead.
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

# ---------------------------------------------------------------- the framework
say "building the framework"
# The whole reactor, so the parent pom is installed as well as the framework — the framework's pom
# inherits from it, and a consumer resolving the framework reads the parent first.
(cd "$ROOT" && mvn -q -B install -DskipTests)
FRAMEWORK="$ROOT/framework/target/panamaview-$VERSION.jar"
[ -s "$FRAMEWORK" ] || fail "mvn install wrote no $FRAMEWORK"

say "staging lib/ and templates/"
# Cleared rather than written over: a version bump would otherwise leave the previous release
# sitting in lib/ for ever, inside every tarball built afterwards. Same for a template that was
# renamed or removed — a stale copy in here would be published.
rm -rf "${NPM_DIR:?}/lib" "${NPM_DIR:?}/templates"
ARTIFACTS="$NPM_DIR/lib/$FRAMEWORK_REPO_PATH"
PARENT_ARTIFACTS="$NPM_DIR/lib/io/github/lihuu/panamaview-parent/$VERSION"
mkdir -p "$ARTIFACTS" "$PARENT_ARTIFACTS"

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
# A directory may hold a jar, a pom, or both — the parent has no jar — so the list is built from
# what is there rather than from a pattern that has to match. A glob that matches nothing arrives
# as the literal pattern, and `shasum` then fails on a file called "*.jar".
for staged in "$ARTIFACTS" "$PARENT_ARTIFACTS"; do
    for artifact in "$staged"/*.jar "$staged"/*.pom; do
        [ -f "$artifact" ] || continue
        (cd "$staged" && shasum -a 1 "$(basename "$artifact")" \
            | awk '{ print $1 }' > "$(basename "$artifact").sha1")
    done
done

# The templates, copied rather than referenced. `files` cannot name a path outside the package
# directory, so a package that pointed at ../templates/ would publish with none. The modes travel
# with cp, and npm preserves them from here into the tarball (verified) — which is why there is no
# manifest of executable bits any more, and no `*` marker for one to be written in.
#
# Which files are a template is a version-control question, and `cp -R` over the directory answers a
# different one. `frontend/node_modules/` and `target/` are ignored and are not part of any
# template, but a checkout that has ever built a frontend has both — CI included, since the templates
# job caches node_modules for the three templates that have one. Staging them is not merely waste:
# node_modules/.bin is a directory of symlinks, the scaffolder refuses a symlink in a template
# because it would arrive as whatever it pointed at on the packing machine, and the refusal is
# raised while the catalog is loaded — so `panamaview version` fails, and so does generating from a
# template that has nothing to do with the one at fault. Measured, not reasoned about:
#
#   panamaview: templates/react/frontend/node_modules/.bin/vite is not a regular file
#
# `git ls-files --cached --others --exclude-standard` is the set `git status` would offer to commit —
# tracked files, plus anything a template has generated into its own tree and not committed — which
# is the same rule the Java version's staging script used. It therefore requires a git checkout, as
# that one did.
mkdir -p "$NPM_DIR/templates"
for dir in "$ROOT"/templates/*/; do
    name="$(basename "$dir")"
    files="$(git -C "$ROOT" ls-files --cached --others --exclude-standard -- "templates/$name")"
    printf '%s\n' "$files" | while IFS= read -r file; do
        [ -n "$file" ] || continue
        # `git ls-files` answers with paths from the repository root, so every one of them already
        # begins with `templates/` -- the component the destination adds. Keeping it staged a second
        # `templates/` inside this one, and the package then carried one directory named "templates"
        # instead of the five, which the scaffolder refused to load at all.
        rel="${file#templates/}"
        # And a name npm will actually pack. `.gitignore` is on npm-packlist's list of files it
        # never includes, in any directory, and `files` cannot override it -- measured: a tarball
        # whose `files` names the path outright still comes back without it. The scaffolder writes
        # it out under the template's own name; see REAL_IGNORE in src/scaffold.js, and the check
        # below that refuses to stage one under the name that would be dropped.
        case "$rel" in
            .gitignore|*/.gitignore) rel="${rel%.gitignore}.gitignore.packed" ;;
        esac
        mkdir -p "$NPM_DIR/templates/$(dirname "$rel")"
        cp "$ROOT/$file" "$NPM_DIR/templates/$rel"
    done
done

# ---------------------------------------------------------------- what is about to be packed
#
# Each check below is a way this has gone wrong or would go wrong silently: a jar that is a valid
# zip with nothing in it, a template directory that arrived empty, a file mode that was dropped, and
# a layout the scaffolder does not recognise — which last one is the only one of the four that no
# file list, checksum or test of this package's own contents could catch.
say "what was staged"
for file in "$ARTIFACTS/panamaview-$VERSION.jar" \
        "$ARTIFACTS/panamaview-$VERSION.pom" \
        "$PARENT_ARTIFACTS/panamaview-parent-$VERSION.pom"; do
    [ -s "$file" ] || fail "missing or empty: $file"
    printf '  %10s  %s\n' "$(wc -c < "$file" | tr -d ' ')" "${file#"$NPM_DIR/"}"
done

say "the jar has the framework in it"
unzip -l "$ARTIFACTS/panamaview-$VERSION.jar" | grep -q "panamaview/WebApp.class" \
    || fail "panamaview-$VERSION.jar does not contain the framework's own classes"

say "every template arrived, and the scripts in them are still executable"
# The modes are the load-bearing part: `cp` preserves them, npm preserves them, and the scaffolder
# copies them onto the generated project. A template whose run.sh arrived 0644 generates a project
# that says "permission denied" and looks otherwise complete. So the count is checked, and so is the
# bit.
TEMPLATES=0
for dir in "$NPM_DIR"/templates/*/; do
    name="$(basename "$dir")"
    n=$(find "$dir" -type f | wc -l | tr -d ' ')
    [ "$n" -gt 0 ] || fail "templates/$name is empty"
    printf '  %-17s %s file(s)\n' "$name" "$n"
    TEMPLATES=$((TEMPLATES + 1))
done
[ "$TEMPLATES" -gt 0 ] || fail "no templates were staged; the published package would have none"

for script in "$NPM_DIR"/templates/*/run.sh "$NPM_DIR"/templates/*/build-native.sh; do
    [ -x "$script" ] || fail "${script#"$NPM_DIR/" } is not executable; the scaffolder copies the
  mode onto the generated project, so this one would ship a project whose ./run.sh cannot be run"
done
echo "  executable bits intact"

say "and nothing is staged under a name npm would drop"
# The failure this is for has no other symptom: the file is staged, the package is packed, and it is
# simply not in the tarball. Everything downstream still works, and the project it generates has no
# .gitignore -- so it commits target/ and its own copy of the framework. `files` cannot rescue a
# dropped name, so the only fix is not to use one.
dropped="$(find "$NPM_DIR/templates" -name .gitignore -print -o -name .npmignore -print)"
if [ -n "$dropped" ]; then
    printf 'build-package.sh: staged under a name npm never packs:\n' >&2
    printf '%s\n' "$dropped" | sed 's/^/  /' >&2
    exit 1
fi
echo "  every template file will survive the tarball"

say "the scaffolder runs, and finds the library beside it"
# The layout, not just the files. The scaffolder looks for a Maven repository root beside its own
# source, so this is the check that lib/ is where that lookup expects it — nothing about the
# package's contents on their own would notice it being one directory out.
node "$NPM_DIR/cli.js" --version

PROBE="$(mktemp -d)"
trap 'rm -rf "$PROBE"' EXIT INT TERM
node "$NPM_DIR/cli.js" create "$PROBE/probe" --template vanilla > "$PROBE/create.log"
sed 's/^/  /' "$PROBE/create.log"
CARRIED="$PROBE/probe/.panamaview/lib/io/github/lihuu/panamaview/$VERSION/panamaview-$VERSION.jar"
[ -s "$CARRIED" ] || fail "the scaffolder did not carry the library into the project it generated;
  it looks beside its own source, so check that npm/lib is that directory"

printf '\n=== staged: lib/ and templates/ at %s\n' "$VERSION"
