#!/bin/sh
# Verify the scaffolder the way someone who downloads it would use it.
#
# Builds the jar, generates each template into an unrelated directory, and takes the result all the
# way to a native executable — using the generated project's own scripts, not a hand-rolled
# command, for the same reason scripts/verify-templates.sh does: the thing that breaks is usually
# the entry point, not the code behind it.
#
# Usage: verify-scaffold.sh [template ...]      (default: everything the manifest lists)
set -eu
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

# The names to check come out of the built jar's own manifest, not a list written here. A template
# added to the repository and not checked would otherwise be a template nobody builds, which is
# exactly what this script exists to prevent.
SPECIFIC="$*"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT INT TERM

say() { printf '\n=== %s\n' "$1"; }

# ---------------------------------------------------------------- build the scaffolder
say "building the scaffolder"
(cd "$ROOT/scaffold" && mvn -q -B clean package)

set -- "$ROOT"/scaffold/target/panamaview-scaffold-*.jar
if [ "$#" -ne 1 ]; then
    echo "verify-scaffold.sh: expected one panamaview-scaffold-*.jar in scaffold/target, found $#" >&2
    echo "verify-scaffold.sh: 'mvn -q clean package' in scaffold/ clears out older ones." >&2
    exit 1
fi
JAR="$1"
echo "  $JAR"

# ---------------------------------------------------------------- versions agree
#
# The templates are staged from this working tree and pin its version, so a scaffolder built at a
# different version would generate projects depending on a release that does not exist — and the
# failure would land on whoever ran it, as an unresolved artifact.
say "checking the pinned framework version"
python3 - "$ROOT/pom.xml" "$ROOT/scaffold/pom.xml" <<'PY'
import re, sys

def first_version(path):
    text = open(path).read()
    # No <parent> in either pom, so the first <version> is the project's own.
    return re.search(r"<version>([^<]+)</version>", text).group(1)

framework, scaffold = first_version(sys.argv[1]), first_version(sys.argv[2])
if framework != scaffold:
    sys.exit(f"\nMISMATCH: the framework is {framework}, the scaffolder is {scaffold}.\n"
             f"The templates are staged from this working tree and pin {framework}, so a\n"
             f"scaffolder built at {scaffold} would generate projects that cannot resolve.\n"
             f"Fix: set <version>{framework}</version> in scaffold/pom.xml")
print(f"  both at {framework}")
PY

# ---------------------------------------------------------------- what is inside, and is it readable
#
# This is the check for the failure with no other symptom: a jar built without the resources still
# starts, still lists templates, and only fails when someone runs create. The files most likely to
# go missing are the dotfiles, which two separate Maven plugins drop by default.
say "scaffolder selftest (every resource the manifest promises is readable)"
java -jar "$JAR" --selftest

say "the manifest lists templates, and the names come from there"
TEMPLATES="$(unzip -p "$JAR" templates-manifest.txt \
    | awk '!/^#/ && NF && $1 != "version" {print $1}' | cut -d/ -f1 | sort -u)"
echo "$TEMPLATES" | sed 's/^/  /'
[ -n "$TEMPLATES" ] || { echo "no templates in the manifest" >&2; exit 1; }
[ -n "$SPECIFIC" ] && TEMPLATES="$SPECIFIC"

# ---------------------------------------------------------------- asking when there is no one to ask
say "without --template, and without a terminal, it says so instead of guessing"
set +e
OUTPUT="$(java -jar "$JAR" create "$WORK/no-template" 2>&1)"
STATUS=$?
set -e
echo "$OUTPUT" | sed 's/^/  /'
[ "$STATUS" -ne 0 ] || {
    printf '\nexpected a non-zero exit when no template can be chosen\n' >&2
    exit 1
}
case "$OUTPUT" in
    *--template*) ;;
    *)
        printf '\nthe error does not mention --template, so it is not actionable\n' >&2
        exit 1
        ;;
esac

# ---------------------------------------------------------------- one project per template
for name in $TEMPLATES; do
    say "[$name] generating"

    # A name and package that stress the substitution rather than matching the template's own:
    #   - a hyphen, which Maven's artifactId accepts, Angular's project name accepts, and a shell
    #     has to carry intact through four scripts
    #   - a package outside com.example, so a leftover token cannot hide inside a correct one
    #     (searching for "com.example" in a project whose package is "com.example.x" matches the
    #     right answer — which is how the first version of this check passed while being useless)
    TARGET="$WORK/$name/sample-app"
    mkdir -p "$WORK/$name"
    (cd "$WORK/$name" && java -jar "$JAR" create sample-app \
        --template "$name" --package dev.example.sample --title "Sample Application")

    say "[$name] no trace of the template's own names"
    python3 - "$TARGET" <<'PY'
import pathlib, sys

root = pathlib.Path(sys.argv[1])
tokens = [b"com.example", b"com/example", b"myapp", b"My App"]
hits = []
files = 0
for p in sorted(root.rglob("*")):
    if not p.is_file() or "node_modules" in p.parts or ".git" in p.parts:
        continue
    files += 1
    blob = p.read_bytes()
    for token in tokens:
        if token in blob:
            hits.append(f"{p.relative_to(root)} contains {token.decode()}")
if hits:
    print("\n".join("  " + h for h in hits))
    sys.exit(f"\n{len(hits)} file(s) still name the template; the rename missed them.\n"
             f"A generated project with the wrong name in it usually still builds, which is\n"
             f"what makes this worth checking separately from the build.")
print(f"  {files} file(s), none of them naming the template")
PY

    say "[$name] nothing links out of the project"
    # A template README is also the README of a generated project, so a relative link that
    # resolves inside this repository (../../docs/...) points outside the project once generated.
    # Nothing else notices: a README is not compiled, and a broken link is not a build failure.
    python3 - "$TARGET" <<'LINKS'
import pathlib, re, sys

root = pathlib.Path(sys.argv[1]).resolve()
bad = []
for md in root.rglob("*.md"):
    if "node_modules" in md.parts:
        continue
    for m in re.finditer(r"\[[^\]]+\]\(([^)\s]+)\)", md.read_text(encoding="utf-8")):
        target = m.group(1).split("#")[0]
        if not target or target.startswith(("http", "mailto:", "/")):
            continue
        resolved = (md.parent / target).resolve()
        if root not in resolved.parents and resolved != root:
            bad.append(f"{md.relative_to(root)} -> {m.group(1)}")
if bad:
    print("\n".join("  " + b for b in bad))
    sys.exit("\nA link in a generated project points outside it. The template README is also\n"
             "the generated project's README, so repository-relative links do not survive\n"
             "generation.")
print("  every relative link stays inside the project")
LINKS

    say "[$name] the project ignores its own build output"
    # Not cosmetic: without this, target/ — and node_modules/ where a template has one — gets
    # committed. The vanilla template was missing this file, and it went unnoticed until a generated
    # project turned out to have one file fewer than its template.
    if [ ! -s "$TARGET/.gitignore" ]; then
        echo "  $name generates a project with no .gitignore; target/ would be committed" >&2
        exit 1
    fi
    echo "  .gitignore"

    say "[$name] the scripts came out executable"
    for script in run.sh build-native.sh; do
        [ -x "$TARGET/$script" ] || {
            echo "  $script is not executable — a jar carries no file modes, so this has to be" >&2
            echo "  restored from the manifest; without it './$script' fails in a project that" >&2
            echo "  otherwise looks complete." >&2
            exit 1
        }
        echo "  $script"
    done

    say "[$name] ./run.sh — the JVM path, with the check that needs no window"
    (cd "$TARGET" && JAVA_OPTS=-Dpanamaview.check=true ./run.sh)

    say "[$name] ./build-native.sh"
    export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25 2>/dev/null || /usr/libexec/java_home)}"
    (cd "$TARGET" && ./build-native.sh > "$WORK/$name-native.log" 2>&1) || {
        echo "native build failed; last 40 lines:" >&2
        tail -40 "$WORK/$name-native.log" >&2
        exit 1
    }

    say "[$name] the native executable, same check"
    (cd "$TARGET" && "./target/sample-app" -Dpanamaview.check=true)
done

printf '\n=== the scaffolder verified\n'
