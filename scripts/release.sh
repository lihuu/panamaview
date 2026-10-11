#!/bin/sh
# Cut a panamaview release.
#
# Usage: release.sh [major|minor|patch|x.y.z] [options]
#
#   --dry-run        do everything except commit, tag and push
#   --skip-verify    skip the end-to-end checks (fast, and only for iterating on this script)
#   --no-push        commit and tag locally, stop there
#
# The release itself is built by CI, from the tag. This script's job is to make the tag, and to
# leave the tree in a state where CI cannot disagree with it.
#
# ---------------------------------------------------------------------------------------------
# Why this is a script and not three git commands
#
# The version lives in eight places and they are not all the same kind of thing:
#
#   pom.xml                       <version>                 the framework
#   scaffold/pom.xml              <version>                 the scaffolder
#   templates/*/pom.xml           <panamaview.version>      what a generated project depends on
#                                 (x5)
#
# The first two and the five template pins are one value that must move together: the templates are
# staged from this working tree, so a template pinning a version that is not the framework's
# generates projects that cannot resolve — and scripts/verify-templates.sh fails on exactly that,
# which is how the invariant is enforced rather than remembered.
#
# What deliberately does NOT move: each template's own <version>, and the frontend package.json
# versions. Those are the version of the *application* a template describes, not of the framework,
# and tying them together would mean a generated project's version jumping whenever panamaview did.
#
# Because verification runs before the bump, a mismatch introduced here fails this script rather
# than the release. That ordering matters: a release that fails after the tag exists is one you
# cannot back out of, since a pushed tag is a public claim that the version is real.
# ---------------------------------------------------------------------------------------------
set -eu
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

BUMP=""
DRY_RUN=0
SKIP_VERIFY=0
NO_PUSH=0

say() { printf '\n=== %s\n' "$1"; }

fail() { printf 'release.sh: %s\n' "$1" >&2; exit 1; }

# ------------------------------------------------------------------ arguments
#
# Options are recognised anywhere, but the version has to be one of them and is found by
# elimination — anything that is not an option. as opposed to the other way round, so that a
# mistyped option is reported as one instead of being silently used as a version string.
for arg in "$@"; do
    case "$arg" in
        --dry-run)     DRY_RUN=1 ;;
        --skip-verify) SKIP_VERIFY=1 ;;
        --no-push)     NO_PUSH=1 ;;
        -h|--help)     sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*)            fail "unknown option: $arg" ;;
        *)
            [ -z "$BUMP" ] || fail "more than one version given: '$BUMP' and '$arg'"
            BUMP="$arg"
            ;;
    esac
done
[ -n "$BUMP" ] || fail "no version given; usage: release.sh [major|minor|patch|x.y.z] [--dry-run]"

# ------------------------------------------------------------------ a tree we can tag
say "checking the working tree"
branch="$(git rev-parse --abbrev-ref HEAD)"
[ "$branch" = "main" ] || fail "on '$branch'; releases are cut from main"
[ -z "$(git status --porcelain)" ] || {
    git status --short >&2
    fail "uncommitted changes; commit or stash them first, so the tag matches what was verified"
}
echo "  on main, clean"

CURRENT="$(python3 - "$ROOT/pom.xml" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
print(re.search(r"<version>([^<]+)</version>", text).group(1))
PY
)"

# ------------------------------------------------------------------ what the next version is
if printf '%s' "$BUMP" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    NEXT="$BUMP"
else
    NEXT="$(python3 - "$CURRENT" "$BUMP" <<'PY'
import sys

current, bump = sys.argv[1], sys.argv[2]
parts = current.split(".")
if len(parts) != 3 or not all(p.isdigit() for p in parts):
    sys.exit(f"cannot bump '{current}': not x.y.z")
major, minor, patch = (int(p) for p in parts)

if bump == "major":
    major, minor, patch = major + 1, 0, 0
elif bump == "minor":
    minor, patch = minor + 1, 0
elif bump == "patch":
    patch += 1
else:
    sys.exit(f"unknown bump '{bump}'; expected major, minor, patch or x.y.z")
print(f"{major}.{minor}.{patch}")
PY
)"
fi

say "releasing $CURRENT → $NEXT"
git rev-parse -q --verify "refs/tags/v$NEXT" >/dev/null && fail "tag v$NEXT already exists"

# ------------------------------------------------------------------ everything agrees before we move it
#
# If the eight places already disagree, bumping them would keep the disagreement and the release
# would fail after the tag exists. Cheap to check first.
say "checking every version is currently in agreement"
python3 - "$ROOT" "$CURRENT" <<'PY'
import pathlib, re, sys

root, current = pathlib.Path(sys.argv[1]), sys.argv[2]
problems = []

def first_version(path):
    match = re.search(r"<version>([^<]+)</version>", path.read_text(encoding="utf-8"))
    return match.group(1) if match else None

for rel in ("pom.xml", "scaffold/pom.xml"):
    found = first_version(root / rel)
    if found != current:
        problems.append(f"{rel} is {found}, expected {current}")

for pom in sorted(root.glob("templates/*/pom.xml")):
    text = pom.read_text(encoding="utf-8")
    match = re.search(r"<panamaview\.version>([^<]+)</panamaview\.version>", text)
    rel = pom.relative_to(root)
    if not match:
        problems.append(f"{rel} does not pin <panamaview.version>")
    elif match.group(1) != current:
        problems.append(f"{rel} pins {match.group(1)}, expected {current}")

if problems:
    print("\n".join("  " + p for p in problems), file=sys.stderr)
    sys.exit("they have to agree before a release, or the release ships a disagreement")
print(f"  all at {current}")
PY

# ------------------------------------------------------------------ prove it before claiming it
#
# The templates and the scaffolder are what a release is *for*, so both go all the way to a native
# executable here rather than in the release job. That way a broken template cannot get as far as
# having a version attached to it. Slow — about nine minutes — and the estimate is worth stating
# because --skip-verify exists mainly for iterating on this script, not for real releases.
if [ "$SKIP_VERIFY" -eq 1 ]; then
    say "SKIPPING verification (--skip-verify)"
    echo "  this release claims to work without having checked"
else
    say "verifying the templates (this takes about four minutes)"
    ./scripts/verify-templates.sh

    say "verifying the scaffolder and the projects it generates (about five minutes)"
    ./scripts/verify-scaffold.sh
fi

# ------------------------------------------------------------------ bump
say "bumping the version"
python3 - "$ROOT" "$CURRENT" "$NEXT" <<'PY'
import pathlib, re, sys

root, current, next_ = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]

def rewrite(path, pattern, replacement, label, count_wanted=1):
    text = path.read_text(encoding="utf-8")
    new, count = re.subn(pattern, replacement, text, count=count_wanted)
    if count != count_wanted:
        sys.exit(f"{path.relative_to(root)}: matched {count} × {label}, expected {count_wanted}")
    path.write_text(new, encoding="utf-8")
    print(f"  {str(path.relative_to(root)):32} {count} × {label}")

# The project's own <version> is the first one in the file, because neither pom has a <parent>.
# That assumption is asserted rather than assumed: with a <parent>, the first <version> would be
# the parent's, and a bare pattern without a count would rewrite every plugin version too — which
# it did, once, turning maven-resources-plugin 3.3.1 into 0.1.1.
for rel in ("pom.xml", "scaffold/pom.xml"):
    path = root / rel
    if "<parent>" in path.read_text(encoding="utf-8"):
        sys.exit(f"{rel} has a <parent>; the first <version> is no longer the project's own, so "
                 f"this script's rule for finding it needs revisiting")
    rewrite(path, r"<version>[^<]+</version>", f"<version>{next_}</version>", "project version")

# The demo app is a consumer like the templates and pins the framework the same way. It was
# missed when it was split out of the framework, and the miss showed up as a release that bumped
# everything except the demo, which then failed to resolve on CI three jobs downstream.
for pom in [root / "demo-app/pom.xml"] + sorted(root.glob("templates/*/pom.xml")):
    rewrite(pom, r"<panamaview\.version>[^<]+</panamaview\.version>",
            f"<panamaview.version>{next_}</panamaview.version>", "framework pin")
PY

# ------------------------------------------------------------------ what the bump did not reach
#
# Sample output in the docs quotes the version the tool printed, which is honest documentation and
# also the kind of thing that quietly becomes a lie. Listed rather than rewritten: NOTES.md records
# what a past release actually printed, and editing that would turn evidence into fiction.
say "documents that still name $CURRENT"
if found=$(git ls-files '*.md' | xargs grep -ln "panamaview[^ ]*$CURRENT\|^panamaview $CURRENT" 2>/dev/null) && [ -n "$found" ]; then
    printf '%s\n' "$found" | sed 's/^/  /'
    echo "  (sample output and past-release records; update the samples, leave the records)"
else
    echo "  none"
fi

# ------------------------------------------------------------------ what CI will be told
say "diff"
git --no-pager diff --stat

if [ "$DRY_RUN" -eq 1 ]; then
    say "dry run — stopping before the commit"
    echo "  the tree is bumped to $NEXT and nothing has been committed"
    echo "  put it back with: git checkout -- ."
    exit 0
fi

# Tagging the version that is already there leaves the tree unchanged — which is the normal way to
# make a first release, since there is nothing to bump from. An empty commit would be noise
# pretending to be a change, so in that case the tag goes straight onto the current commit.
say "committing"
if [ -z "$(git status --porcelain)" ]; then
    echo "  nothing changed — tagging $NEXT where it stands, with no release commit"
else
    git add -A
    git commit -q -m "Release v$NEXT"
    echo "  Release v$NEXT committed"
fi

# Annotated rather than lightweight: a lightweight tag is a bare pointer, and a release is a claim
# about what the code is, which deserves a message and a date of its own.
git tag -a "v$NEXT" -m "panamaview v$NEXT"
echo "  tagged v$NEXT"

if [ "$NO_PUSH" -eq 1 ]; then
    say "not pushing (--no-push)"
    echo "  when ready:  git push origin main && git push origin v$NEXT"
    exit 0
fi

say "pushing"
git push -q origin main
git push -q origin "v$NEXT"
echo "  main and v$NEXT pushed"

say "v$NEXT is tagged"
echo "The tag is the release: source only, nothing attached. The workflow on it checks that the tag"
echo "and the version fields agree:  gh run list --workflow=release.yml --limit 1"
