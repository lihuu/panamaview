#!/bin/sh
# Verify the scaffolder the way someone who downloads it would use it.
#
# Stages the package, generates each template into an unrelated directory, and takes the result all
# the way to a native executable — using the generated project's own scripts, not a hand-rolled
# command, for the same reason scripts/verify-templates.sh does: the thing that breaks is usually
# the entry point, not the code behind it.
#
# There is no scaffolder build any more, and that is not a missing step. The scaffolder is
# `npm/cli.js`, and it reads its templates out of its own package root — `npm/templates/` — which a
# fresh checkout does not have, because npm's `files` field cannot reach outside the package
# directory and so `npm/scripts/build-package.sh` copies them in at pack time. Staging the package
# is therefore the first step, and it is the same tree `npx create-panamaview` unpacks. A
# consequence worth knowing: these projects are generated with the framework bundled beside them,
# which is the shape that ships.
#
# Usage: verify-scaffold.sh [template ...]      (default: everything under templates/)
set -eu
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

# The names to check come from the directories under templates/, not from a list written here. A
# template added to the repository and not checked would otherwise be a template nobody builds,
# which is exactly what this script exists to prevent.
#
# The staged directories in npm/templates, rather than the table `npm/cli.js templates` prints: that
# table is laid out for a person to read, and parsing it back would be a second and weaker copy of
# the same list. Nothing is lost — build-package.sh stages every directory under templates/, and the
# CLI's loadCatalog refuses to run at all when a directory has no description in its registry, so a
# template that arrives without one stops this script at the very next command rather than being
# quietly skipped.
SPECIFIC="$*"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT INT TERM

say() { printf '\n=== %s\n' "$1"; }

# ---------------------------------------------------------------- versions agree
#
# The templates are staged from this working tree and pin its version, so a scaffolder built at a
# different version would generate projects depending on a release that does not exist — and the
# failure would land on whoever ran it, as an unresolved artifact.
say "checking the pinned framework version"
# The framework's version comes from the framework's own pom; the root pom is the reactor's parent
# and is a different fact.
. "$ROOT/scripts/framework-coordinates.sh"
# The scaffolder's version has to be read from npm/package.json. It used to have to be asked of
# Maven, because the scaffolder was a module whose pom inherited the version from the parent and
# stated none of its own. It is not a Maven module any more: `npm/package.json` is the only place
# its version is written. npm/scripts/build-package.sh cross-checks the two wherever it stages
# lib/, and the check is repeated here because this script is what a person reads to find out that
# the invariant exists.
python3 - "$FRAMEWORK_VERSION" "$ROOT/npm/package.json" <<'PY'
import json, sys

framework, package = sys.argv[1], sys.argv[2]
try:
    claimed = json.load(open(package))["version"]
except (OSError, ValueError) as exc:
    sys.exit(f"\nnpm/package.json cannot be read: {exc}")
if framework != claimed:
    sys.exit(f"\nMISMATCH: the framework is {framework}, npm/package.json claims {claimed}.\n"
             f"The templates are staged from this working tree and pin {framework}, so a\n"
             f"package claiming {claimed} would generate projects that cannot resolve.")
print(f"  both at {framework}")
PY

# ---------------------------------------------------------------- what gets generated from
say "staging the package (npm/templates and npm/lib)"
# The one script that knows how the package is laid out, rather than a second copy of that layout
# here. It installs the reactor on the way, which is also what makes the framework resolvable for
# the projects below.
"$ROOT/npm/scripts/build-package.sh"

# ---------------------------------------------------------------- what is inside, and is it readable
#
# This is the check for the failure with no other symptom: a package published without some of its
# templates still starts, still lists templates, and only fails when someone runs create. The files
# most likely to go missing are the dotfiles, which packaging tools drop by default.
say "scaffolder selftest (every file the package ships is readable)"
node "$ROOT/npm/cli.js" --selftest

say "the templates, taken from the directories staged under npm/templates"
TEMPLATES="$(cd "$ROOT/npm/templates" && for d in */; do printf '%s\n' "${d%/}"; done | sort)"
echo "$TEMPLATES" | sed 's/^/  /'
[ -n "$TEMPLATES" ] || { echo "no templates were staged, so the package would ship none" >&2; exit 1; }
[ -n "$SPECIFIC" ] && TEMPLATES="$SPECIFIC"

# ---------------------------------------------------------------- asking when there is no one to ask
say "without --template, and without a terminal, it says so instead of guessing"
set +e
OUTPUT="$(node "$ROOT/npm/cli.js" create "$WORK/no-template" 2>&1)"
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

# ---------------------------------------------------------------- the native build
#
# One template is compiled to a native executable, not five.
#
# The five templates are one Java program with five frontends. Their build-native.sh scripts are
# identical down to the md5 of the native-image command line, they all name com.example.Main, and
# what differs in their Main.java is comments and the list of assets the check step looks for. A
# native-image compile spends its ~55s analysing the Java side — the FFM calls into Cocoa, WebKit
# and WebView2 — and does not care which javascript ended up in web/. So the other four compiles
# were the same compile four more times.
#
# What is *not* the same across templates, and stays per-template below: the frontend build itself
# (vite, the angular CLI, the Tailwind CLI — seconds each, and genuinely different work), the
# asset check, and run.sh on the JVM path.
#
# vanilla is the one compiled, and that choice is deliberate rather than "the first one". It is the
# only template whose web/ contents are checked in; react, vue and angular produce theirs from a
# frontend build at run time. So it is the copy where the compile step is not entangled with npm,
# vite or an angular CLI — if native-image itself breaks, this isolates it.
#
# When only one template is being verified there is nothing to share, so that one is compiled.
# That is the CI case: its matrix hands each runner a single template, and collapsing the compile
# there would silently drop native coverage for four of the five. Locally, where no argument is
# given and all five are verified, one compile covers the set.
if [ "$(printf '%s\n' "$TEMPLATES" | wc -l | tr -d ' ')" -gt 1 ]; then
    NATIVE_TEMPLATE="vanilla"
else
    NATIVE_TEMPLATE="$TEMPLATES"
fi

: > "$WORK/pids"
for name in $TEMPLATES; do
  (
    say "[$name] generating"

    # A name and package that stress the substitution rather than matching the template's own:
    #   - a hyphen, which Maven's artifactId accepts, Angular's project name accepts, and a shell
    #     has to carry intact through four scripts
    #   - a package outside com.example, so a leftover token cannot hide inside a correct one
    #     (searching for "com.example" in a project whose package is "com.example.x" matches the
    #     right answer — which is how the first version of this check passed while being useless)
    TARGET="$WORK/$name/sample-app"
    mkdir -p "$WORK/$name"
    (cd "$WORK/$name" && node "$ROOT/npm/cli.js" create sample-app \
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
    # The mode travels: the template's own bit, preserved by `cp` into the staged package, by npm
    # into the tarball, and by the scaffolder onto the generated project. A template whose bit was
    # lost on the way generates a project that says "permission denied" and looks otherwise
    # complete, so it is checked on the far end rather than at the source.
    for script in run.sh build-native.sh package.sh; do
        [ -x "$TARGET/$script" ] || {
            echo "  $script is not executable — the mode was dropped somewhere between the" >&2
            echo "  template and the generated project; without it './$script' fails in a project" >&2
            echo "  that otherwise looks complete." >&2
            exit 1
        }
        echo "  $script"
    done

    say "[$name] ./run.sh — the JVM path, with the check that needs no window"
    (cd "$TARGET" && JAVA_OPTS=-Dpanamaview.check=true ./run.sh)

    # The native build runs for one template only; see the note above the loop for why. The other
    # four stop here, having done everything up to and including run.sh on the JVM path.
    #
    # This is not a silent skip: the log says which template was compiled, so a run that stops
    # short of a native executable does not read as one that built five of them.
    if [ "$name" = "$NATIVE_TEMPLATE" ]; then
        say "[$name] ./build-native.sh (the one template that is compiled natively)"
        export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25 2>/dev/null || /usr/libexec/java_home)}"
        (cd "$TARGET" && ./build-native.sh > "$WORK/$name-native.log" 2>&1) || {
            echo "native build failed; last 40 lines:" >&2
            tail -40 "$WORK/$name-native.log" >&2
            exit 1
        }

        say "[$name] the native executable, same check"
        (cd "$TARGET" && "./target/sample-app" -Dpanamaview.check=true)
    else
        say "[$name] native build skipped (covered by $NATIVE_TEMPLATE; this one differs in its frontend)"
    fi
  ) > "$WORK/$name.log" 2>&1 &
    printf '%s %s\n' "$name" "$!" >> "$WORK/pids"
done

FAILED=""
while read -r name pid; do
    if ! wait "$pid"; then
        FAILED="$FAILED $name"
    fi
    cat "$WORK/$name.log"
done < "$WORK/pids"

[ -z "$FAILED" ] || {
    echo >&2
    echo "verify-scaffold.sh: these templates failed:$FAILED" >&2
    exit 1
}

printf '\n=== the scaffolder verified\n'
