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
# The version lives in eight places and they are not all the same kind of thing. Since the build
# became a reactor, fewer of them are copies:
#
#   pom.xml                       <version>                 the parent, and the build's version
#   framework/pom.xml             <version>                 the framework — what a consumer resolves
#   demo-app/pom.xml              (inherited)               the demo
#   templates/*/pom.xml           <panamaview.version>      what a generated project depends on
#                                 (x5)
#   npm/package.json              version                   the package that carries the framework
#
# The two that state the version themselves, and the five template pins, are one value that must move
# together: the templates are staged from this working tree, so a template pinning a version that is
# not the framework's generates projects that cannot resolve — and scripts/verify-templates.sh fails
# on exactly that, which is how the invariant is enforced rather than remembered.
#
# The scaffolder and the demo used to state it as well, each in its own pom. The demo inherits it
# now, and what was a rewrite for it is an assertion instead: a module that grows its own copy is the
# mistake, not a value that needs bumping. The demo's copy was missed on the release that split it
# out of the framework, and the tag shipped anyway.
#
# The scaffolder has left the reactor altogether: it is npm/cli.js, a Node CLI with no pom. Its
# version is npm/package.json's, and that file is the only place it is written.
#
# npm/package.json is the same value for a different reason. That package ships the framework and
# the projects it generates pin the version it ships, so a package claiming a version other than
# the one it carries generates projects that cannot resolve — on a machine with no checkout to
# compare against. npm/scripts/build-package.sh asserts the two agree whenever it stages lib/, and
# this script is what keeps them agreeing at release time.
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

# The version being released is the framework's, read from the framework's own pom. It is no longer
# the first <version> in the root pom: the root is the reactor's parent, and a file that names the
# framework has to be the framework's pom or the release is cut from the wrong number.
. "$ROOT/scripts/framework-coordinates.sh"
CURRENT="$FRAMEWORK_VERSION"

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
import json, pathlib, re, sys

root, current = pathlib.Path(sys.argv[1]), sys.argv[2]
problems = []

def first_version(path):
    match = re.search(r"<version>([^<]+)</version>", path.read_text(encoding="utf-8"))
    return match.group(1) if match else None

def strip_comments(text):
    """A pom's own explanation is not markup. See the module check below."""
    return re.sub(r"<!--.*?-->", "", text, flags=re.S)

# Every pom that states the released version as its own. Two of them now, and the framework's is the
# one a consumer resolves — the root pom is the reactor's parent, whose version is the same number
# for a different reason. The scaffolder used to be a third; it is not a Maven project any more, and
# its version is checked below, with npm/package.json's.
for rel in ("pom.xml", "framework/pom.xml"):
    found = first_version(root / rel)
    if found != current:
        problems.append(f"{rel} is {found}, expected {current}")

# Every other module inherits the version and is expected *not* to carry a pin. If one reappears it
# is stating the framework version a second time, which is the mistake that made a release ship a
# demo depending on a version that was never published.
#
# Read from the root pom's <modules> rather than listed here. A list in this script is a list that
# goes stale the moment a module is added — which is the same failure, one level up: the release
# machinery not knowing a file exists. Adding a module is now covered without touching this script.
for module in re.findall(r"<module>([^<]+)</module>",
                         (root / "pom.xml").read_text(encoding="utf-8")):
    pom = root / module / "pom.xml"
    if not pom.exists():
        continue
    if module == "framework":
        continue                       # the framework states its own version; checked above
    # Comments stripped first, and that is not tidiness: these poms explain themselves, and
    # demo-app's comment names the very element being looked for ("it carried its own
    # <panamaview.version>") as part of saying why it no longer does. Read raw, that sentence
    # answers yes and the release refuses to run for a pin that is not there.
    if re.search(r"<panamaview\.version>", strip_comments(pom.read_text(encoding="utf-8"))):
        problems.append(f"{module}/pom.xml pins <panamaview.version>; as a module it inherits "
                        f"the version, and a second copy is one that can drift")

for pom in sorted(root.glob("templates/*/pom.xml")):
    text = pom.read_text(encoding="utf-8")
    match = re.search(r"<panamaview\.version>([^<]+)</panamaview\.version>", text)
    rel = pom.relative_to(root)
    if not match:
        problems.append(f"{rel} does not pin <panamaview.version>")
    elif match.group(1) != current:
        problems.append(f"{rel} pins {match.group(1)}, expected {current}")

# The npm package is not a pom and does not pin anything, but it is the same value: it carries the
# framework under this version and the projects it generates depend on it by that name.
#
# Parsed rather than pattern-matched, so that a package.json npm could not parse is reported as
# that, instead of as a version mismatch it is not.
try:
    package = json.loads((root / "npm/package.json").read_text(encoding="utf-8"))
except (OSError, ValueError) as exc:
    problems.append(f"npm/package.json cannot be read as JSON: {exc}")
else:
    if package.get("version") != current:
        problems.append(f"npm/package.json is {package.get('version')}, expected {current}")

if problems:
    print("\n".join("  " + p for p in problems), file=sys.stderr)
    sys.exit("they have to agree before a release, or the release ships a disagreement")
print(f"  all at {current}")
PY

# ------------------------------------------------------------------ lint
#
# Outside the --skip-verify branch on purpose, and before the slow checks rather than after.
#
# It takes a second and catches a class of failure that nothing else here sees: CI lints the shell
# scripts with a pinned shellcheck, and a release whose scripts fail that lint cannot be merged --
# six consecutive CI runs failed on one unquoted variable before this was run anywhere locally.
# A lint is cheap enough that "the caller asked to skip the nine-minute checks" is not a reason to
# skip it too; the flag exists for iterating on this script, and this runs in the time that takes.
say "linting the shell scripts"
./scripts/lint.sh

# ------------------------------------------------------------------ prove it before claiming it
#
# The templates and the scaffolder are what a release is *for*, so a project generated from every
# one of them goes all the way to a native executable here rather than in the release job. That way a
# broken template cannot get as far as having a version attached to it. Slow — about nine minutes —
# and the estimate is worth stating because --skip-verify exists mainly for iterating on this
# script, not for real releases.
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
import json, pathlib, re, sys

root, current, next_ = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]

def strip_comments(text):
    """A pom's own explanation is not markup.

    This is not tidiness. Every one of these poms documents itself, and the comments name the very
    elements being looked for -- `templates/` explains why a template must *not* have a `<parent>`,
    and `demo-app`'s comment names the `<panamaview.version>` it no longer carries. Read raw, both
    answer "yes" to a question whose answer is "no", and a release refuses to run because of a
    sentence explaining why it should not.
    """
    return re.sub(r"<!--.*?-->", "", text, flags=re.S)


def rewrite(path, pattern, replacement, label, count_wanted=1):
    text = path.read_text(encoding="utf-8")
    new, count = re.subn(pattern, replacement, text, count=count_wanted)
    if count != count_wanted:
        sys.exit(f"{path.relative_to(root)}: matched {count} × {label}, expected {count_wanted}")
    path.write_text(new, encoding="utf-8")
    print(f"  {str(path.relative_to(root)):32} {count} × {label}")

# The root pom is the reactor's parent and states the version as its own, so it is bumped here.
#
# It has no <parent>, which is what makes "the first <version> is the project's own" true of it.
# That is asserted rather than assumed: with a <parent>, the first <version> would be the parent's,
# and a bare pattern without a count would rewrite every plugin version too — which it did, once,
# turning maven-resources-plugin 3.3.1 into 0.1.1.
root_pom = root / "pom.xml"
if "<parent>" in strip_comments(root_pom.read_text(encoding="utf-8")):
    sys.exit("pom.xml has a <parent>; the first <version> is no longer the project's own, so this "
             "script's rule for finding it needs revisiting")
rewrite(root_pom, r"<version>[^<]+</version>", f"<version>{next_}</version>", "project version")

# The framework's pom says the same thing and is the file a consumer resolves. It is a module of the
# reactor, so its version is the one in its <parent>, and that is the first <version> in it — the same
# rule as the root pom's, for a different reason. Asserted rather than assumed: without a <parent> the
# first <version> would be the framework's own and this would still work, so the failure to catch is
# the one where a second, independent <version> appears beside the parent's and the two drift.
framework_pom = root / "framework/pom.xml"
if "<parent>" not in strip_comments(framework_pom.read_text(encoding="utf-8")):
    sys.exit("framework/pom.xml has no <parent>; it is a module of the reactor and inherits its "
             "version from it, so this script no longer knows where its version comes from")
rewrite(framework_pom, r"<version>[^<]+</version>", f"<version>{next_}</version>", "project version")

# The scaffolder is not a module and has no pom, so there is nothing to check here: its version is
# npm/package.json's, and that is checked and rewritten further down, where the reason is stated.

# The templates are not modules: they are copied out of this repository and built where this pom
# does not exist, so each pins the framework version by hand and a drift generates projects
# depending on a release that was never cut.
for pom in sorted(root.glob("templates/*/pom.xml")):
    rewrite(pom, r"<panamaview\.version>[^<]+</panamaview\.version>",
            f"<panamaview.version>{next_}</panamaview.version>", "framework pin")

# Every other module inherits the version. demo-app used to be an independent project with its own
# <panamaview.version>, and that pin was missed on the release that split it out — the failure landed
# on CI three jobs away from the cause. A module cannot get out of step with the reactor it is built
# in, so an assertion replaces the rewrite.
#
# Driven by the root pom's <modules>, so a module added tomorrow is covered here automatically.
# The alternative — one block per module — is a list someone forgets, and forgetting is the exact
# failure this replaced.
for module in re.findall(r"<module>([^<]+)</module>",
                         (root / "pom.xml").read_text(encoding="utf-8")):
    pom = root / module / "pom.xml"
    if not pom.exists() or module == "framework":
        continue                       # the framework states its own version; bumped above
    # The assertions read the comments-stripped text; the rewrite below needs the file itself.
    # Doing both through one variable is how the first version of this fix wrote every module back
    # without its comments: the checks were right and the write was wrong, and a test of the
    # assertions alone would not have noticed.
    text = pom.read_text(encoding="utf-8")
    checks = strip_comments(text)
    if "<parent>" not in checks:
        sys.exit(f"{module}/pom.xml has no <parent>; it is supposed to inherit the version")
    if "<panamaview.version>" in checks:
        sys.exit(f"{module}/pom.xml pins <panamaview.version> as well as being a module; the "
                 f"pin is the copy that gets missed, and inheriting is what replaced it")
    # The parent's <version> is a copy of the reactor's version, and it is the copy that has to be
    # rewritten here for the module to build at all. It is inside <parent>, so the "first <version>
    # in the file" rule the root and framework poms use would land on it — but only by accident of
    # the parent coming first, and the whole reason this loop exists is that "by accident of file
    # order" is how a version gets missed. Matched explicitly instead.
    #
    # Without this, a bump leaves every module but the framework pointing at the version it had
    # before, and the next build fails in a way that names neither the release script nor the
    # module: "Non-resolvable parent POM ... 'parent.relativePath' points at wrong local POM".
    # It only shows up on a machine with an empty local repository or after the old parent is gone
    # from it, which is why it survived the release that introduced the reactor.
    parent = re.search(r"<parent>.*?</parent>", text, re.S)
    if parent is None:
        sys.exit(f"{module}/pom.xml's <parent> could not be read")
    if f"<version>{current}</version>" not in parent.group(0):
        sys.exit(f"{module}/pom.xml's <parent> does not name version {current}, so this bump "
                 f"would leave it behind. Expected <version>{current}</version> inside <parent>.")
    rewritten = parent.group(0).replace(f"<version>{current}</version>",
                                        f"<version>{next_}</version>", 1)
    pom.write_text(text.replace(parent.group(0), rewritten, 1), encoding="utf-8")
    print(f"  {module + '/pom.xml':32} parent version -> {next_}")

# npm/package.json carries the framework's version too, for the reason in the header.
#
# A bare `"version"` pattern is safe here because of how it is checked, not because of rewrite()'s
# count: that passes count=1 to re.subn, and subn returns the number of substitutions it *made* --
# capped by count, so exactly one whenever there is a match at all. It cannot see a second match,
# whatever its message says. So the file is parsed before the rewrite and the value the pattern is
# about to replace is confirmed to be the top-level one, then parsed again afterwards to confirm
# the write landed. A `version` nested earlier in the file stops the release here, before anything
# has been written, rather than being quietly rewritten.
package_path = root / "npm/package.json"
try:
    package = json.loads(package_path.read_text(encoding="utf-8"))
except (OSError, ValueError) as exc:
    sys.exit(f"npm/package.json cannot be read as JSON: {exc}")
if package.get("version") != current:
    sys.exit(f"npm/package.json is {package.get('version')}, expected {current}")
first = re.search(r'"version": "([^"]+)"', package_path.read_text(encoding="utf-8"))
if first is None or first.group(1) != current:
    sys.exit("the first \"version\" in npm/package.json is not the top-level one, so rewriting it "
             "textually would change the wrong value")

rewrite(package_path, r'"version": "[^"]+"', f'"version": "{next_}"', "package version")

# Read back as the JSON it claims to be: a package.json npm cannot parse is one `npx` refuses, and
# better to find that out here than at publish.
try:
    package = json.loads(package_path.read_text(encoding="utf-8"))
except (OSError, ValueError) as exc:
    sys.exit(f"npm/package.json does not read back as JSON after the bump: {exc}")
if package.get("version") != next_:
    sys.exit(f"npm/package.json reads {package.get('version')} after the bump, expected {next_}")
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

# ------------------------------------------------------------------ the public repository
#
# After the private deploy, and after the bump, so that what would be published is the version this
# release just settled on. Not automatic: publish-central.sh uploads and validates by default and
# only publishes with --release, because a version on Maven Central cannot be withdrawn. Printing the
# command is the whole of this step -- the release script's job is the tag, and a tag that has not
# been cut yet is the wrong thing to publish.
say "the public repository"
if [ -x "$ROOT/scripts/publish-central.sh" ]; then
    echo "  this release is tagged, not published. To put v$NEXT on Maven Central:"
    echo "    ./scripts/publish-central.sh            # builds, checks and uploads; stops before publishing"
    echo "    ./scripts/publish-central.sh --release  # ... and then publishes it"
    echo "  Publishing is permanent, so it is not part of this script."
else
    echo "  no publish-central.sh"
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
