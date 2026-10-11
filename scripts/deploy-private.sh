#!/bin/sh
# Publish panamaview to a private Maven repository.
#
# Not a release to Maven Central. This exists so that other machines and other projects can resolve
# `io.github.lihuu:panamaview` without cloning this repository and running `mvn install` first —
# which is what a template requires today, and is the single most awkward part of trying one.
#
# ---------------------------------------------------------------------------------------------
# Why the target is read out of settings.xml instead of being written here
#
# A private repository URL carries an account identifier, and this repository is public. Committing
# it would publish a stranger's namespace for no benefit to anyone: the URL is useless without
# credentials, and it is not information any reader of this project needs.
#
# So the deploy target comes from the same place the credentials already are — settings.xml. The
# script contains nothing specific to one account, which also means it works for any private
# repository, and this file can stay public.
#
# The poms deliberately have no <distributionManagement>. That is worth keeping: plain `mvn deploy`
# then fails with "no distributionManagement" rather than silently pushing a library into whoever's
# private repository happens to be configured on the machine.
# ---------------------------------------------------------------------------------------------
#
# Usage: deploy-private.sh [--verify-only] [project-dir ...]     (default: . framework)
#
#   --verify-only    skip the upload; just check the repository still serves these versions
#   SETTINGS=/path/to/settings.xml     use a settings file other than ~/.m2/settings.xml
#   DEPLOY_REPO=<id>                   name the repository explicitly, skipping detection
#
# What it does, and why each step is not optional:
#   1. Decides between the release and snapshot repository from the version. Maven's own rule.
#   2. Verifies a <server> entry with matching credentials exists, before uploading anything.
#   3. Deploys.
#   4. **Resolves the artifact back from a pristine local repository and compares hashes.**
#      Without this, "deploy succeeded" means the upload returned 201. With it, it means another
#      machine can actually get the file that was just built.
set -eu
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

SETTINGS="${SETTINGS:-$HOME/.m2/settings.xml}"
DEPLOY_REPO="${DEPLOY_REPO:-}"

VERIFY_ONLY=0
if [ "${1:-}" = "--verify-only" ]; then
    VERIFY_ONLY=1
    shift
fi

# The default is the two things that get published, in dependency order: the framework, and the
# root pom before it. The root pom goes first because it is the parent the framework inherits from —
# Maven resolves a parent before it will read the pom that names it, so publishing the framework
# without it publishes a pom nobody can read. demo-app is not here at all: it exists to be built, not
# depended on, and was never published.
#
# The scaffolder used to be a third, as io.github.lihuu:panamaview-scaffold. It is npm/cli.js now —
# a Node CLI with no jar and no pom — so there is no artifact of it to deploy anywhere.
if [ "$#" -gt 0 ]; then
    PROJECTS="$*"
else
    PROJECTS=". framework"
fi

say() { printf '\n=== %s\n' "$1"; }

[ -f "$SETTINGS" ] || {
    echo "deploy-private.sh: no settings file at $SETTINGS" >&2
    echo "deploy-private.sh: it needs one for both the repository URL and the credentials." >&2
    exit 1
}

# ------------------------------------------------------------------ which repository
#
# The selection logic lives in scripts/choose-repo.py rather than in a heredoc here, because it is
# the fiddliest part of this script and nothing could exercise it while it was embedded.
# scripts/verify-deploy.sh drives it against fixture settings files, with no repository, network or
# credentials — which is the only way CI can cover any of this.
say "choosing the repository for this version"
DECISION=$(python3 "$ROOT/scripts/choose-repo.py" "$SETTINGS" "$ROOT/pom.xml" "$DEPLOY_REPO")

REPO_ID=$(printf '%s\n' "$DECISION" | sed -n 1p)
REPO_URL=$(printf '%s\n' "$DECISION" | sed -n 2p)
VERSION=$(printf '%s\n' "$DECISION" | sed -n 3p)
printf '  %s\n  %s\n  version %s\n' "$REPO_ID" "$REPO_URL" "$VERSION"

if [ "$VERIFY_ONLY" -eq 0 ]; then

# ------------------------------------------------------------------ has this version already gone out
#
# Worth checking rather than letting the upload speak for itself. A release repository refuses to
# overwrite a published version, and the server says so with a bare `409 Conflict` that Maven
# reports as "Failed to deploy artifacts" — which reads like a credentials or network problem.
# It is neither. The version is simply already there, and no amount of retrying changes that.
say "checking whether $VERSION is already published"
python3 - "$SETTINGS" "$REPO_ID" "$REPO_URL" "$VERSION" "$ROOT/pom.xml" <<'CHECK_EXISTS'
import base64, pathlib, re, sys, urllib.error, urllib.request

settings_path, repo_id, repo_url, version, pom_path = sys.argv[1:6]
text = pathlib.Path(settings_path).read_text(encoding="utf-8")

def inner(block, name):
    m = re.search(rf"<{name}>(.*?)</{name}>", block, re.S)
    return m.group(1).strip() if m else None

creds = {}
for m in re.finditer(r"<server>(.*?)</server>", text, re.S):
    block = m.group(1)
    if inner(block, "id") == repo_id:
        creds = {"username": inner(block, "username"), "password": inner(block, "password")}

pom = pathlib.Path(pom_path).read_text(encoding="utf-8")
group = inner(pom, "groupId")
artifact = re.search(r"<artifactId>([^<]+)</artifactId>", pom).group(1)
path = "/".join(group.split(".")) + f"/{artifact}/{version}/{artifact}-{version}.pom"
url = f"{repo_url.rstrip('/')}/{path}"

req = urllib.request.Request(url)
if creds.get("username"):
    token = base64.b64encode(f"{creds['username']}:{creds['password']}".encode()).decode()
    req.add_header("Authorization", "Basic " + token)
try:
    with urllib.request.urlopen(req, timeout=25) as r:
        exists = r.status == 200
except urllib.error.HTTPError as e:
    exists = False            # 404 means free; anything else is checked by the deploy itself
except Exception:
    exists = False            # offline: let the deploy report the real problem

if exists:
    sys.exit(f"\n{group}:{artifact}:{version} is already in {repo_id} ({url})\n"
             f"  A release repository does not allow overwriting a published version, so the\n"
             f"  server would answer 409 Conflict. Maven reports that as a failed deploy, which\n"
             f"  reads like a credentials problem and is not one.\n"
             f"  Two ways forward:\n"
             f"    - bump the version in pom.xml and npm/package.json, then deploy that\n"
             f"    - use a -SNAPSHOT version while iterating; snapshots are made to be replaced")
print(f"  free to publish")
CHECK_EXISTS

# ------------------------------------------------------------------ versions must agree
#
# The framework and the parent it inherits from have to be at one version: the framework's pom names
# the parent, so publishing the two at different versions publishes a framework whose pom reads a
# parent that was never released. scripts/release.sh keeps them together, along with the npm
# package's copy of the same number.
say "checking every project is at the same version"
for dir in $PROJECTS; do
    pom="$ROOT/$dir/pom.xml"
    [ -f "$pom" ] || { echo "  no pom.xml in $dir" >&2; exit 1; }
    # The module's own <version>, with its <parent> block excluded. The root pom states one; the
    # modules under it do not, and reading the first <version> in those would return the parent's —
    # the same number, so the check would pass while measuring nothing. Asking Maven is what makes
    # the answer the module's own, whether it is stated or inherited.
    other="$(cd "$ROOT/$dir" && mvn -B -q help:evaluate -Dexpression=project.version \
        -DforceStdout 2>/dev/null | tail -1)"
    if [ "$other" != "$VERSION" ]; then
        echo "  $dir is at $other, but the framework is at $VERSION" >&2
        echo "  They move together: the framework's pom names the parent, and Maven reads the" >&2
        echo "  parent before it will read the pom that names it. Bump them in one commit." >&2
        exit 1
    fi
    printf '  %-10s %s\n' "$dir" "$other"
done

# ------------------------------------------------------------------ deploy
for dir in $PROJECTS; do
    say "deploying $dir"
    # -Dmaven.resolver.transport=wagon is required for this repository, not a preference.
    #
    # Maven Resolver 2.0.24's default (Apache) transport reads the Content-Type of a failed
    # response and, on seeing application/json, tries to decode the body as an RFC 9457 problem
    # document. Aliyun answers a missing maven-metadata.xml with exactly that: 404, an empty body,
    # and Content-Type: application/json. The decode fails, so instead of the "resource does not
    # exist" the deploy plugin expects, it raises a malformed-error exception and aborts — after
    # the pom and jar have already been uploaded, which is why the failure reads as a mystery.
    #
    # The legacy wagon transport does not do this, and the metadata step completes. It is a
    # workaround for an interaction between one resolver version and one server, and it is applied
    # only here: the verification below deliberately uses the default transport, because that is
    # what a consuming project uses, and proving resolution works with anything else would prove
    # the wrong thing.
    ( cd "$ROOT/$dir" && mvn -B -q deploy \
        -DskipTests \
        -Dmaven.resolver.transport=wagon \
        -DaltDeploymentRepository="$REPO_ID::default::$REPO_URL" )
    echo "  uploaded"
done

fi   # end of the deploy-only section

# ------------------------------------------------------------------ prove they can be fetched
#
# The step that makes the previous one mean something. A pristine local repository is the only way
# to be sure the artifact came from the private repository: any other check can be satisfied by the
# copy that `mvn install` left on this machine.
say "resolving them back from an empty local repository"
echo "  (a pristine local repository is the point: any other check can be satisfied by the copy"
echo "   that 'mvn install' left on this machine)"
FRESH="$(mktemp -d)"
trap 'rm -rf "$FRESH"' EXIT INT TERM

status=0
for dir in $PROJECTS; do
    # Coordinates and the path they land at, computed together. Splitting these was a real bug:
    # translating ":" and "." to "/" across the whole coordinate string turned the version into a
    # directory chain, so a -SNAPSHOT artifact was looked for under 0/1/0-SNAPSHOT/. Only the group
    # id is a path; the artifact and version are plain names.
    INFO=$(python3 - "$ROOT/$dir/pom.xml" "$ROOT/pom.xml" "$FRESH" <<'COORDS'
import re, sys

# Reads the module's own coordinates, falling back to the parent for anything it inherits. The
# <parent> block comes first in a module's pom and names the same three elements, so reading "the
# first groupId, artifactId, version" would return the parent's — which for this repository is
# io.github.lihuu:panamaview-parent:<same version>. That is a real artifact, so the wrong answer
# would resolve, which is the worst kind: the check would confirm the parent had been published and
# say nothing about the module it was actually asked about.
module, parent = sys.argv[1], sys.argv[2]

def strip_comments(text):
    return re.sub(r"<!--.*?-->", "", text, flags=re.S)

def header_without_parent(path):
    text = strip_comments(open(path).read())
    text = re.sub(r"<parent>.*?</parent>", "", text, flags=re.S)
    return re.split(r"<properties>|<dependencies>|<build>|<modules>|<profiles>", text)[0]

def one(text, name):
    m = re.search(rf"<{name}>([^<]+)</{name}>", text)
    return m.group(1) if m else None

own, inherited = header_without_parent(module), header_without_parent(parent)
# groupId and version are the two that a module may inherit; artifactId never is, or the module
# would not be a module. Each falls back independently, so a module that inherits one and states the
# other is read correctly rather than taking whichever arrangement the other one happens to use.
group = one(own, "groupId") or one(inherited, "groupId")
artifact = one(own, "artifactId")
version = one(own, "version") or one(inherited, "version")

missing = [n for n, v in (("groupId", group), ("artifactId", artifact), ("version", version))
           if v is None]
if missing:
    sys.exit(f"{module}: no " + ", ".join(missing))

print(f"{group}:{artifact}:{version}")
print(f"{sys.argv[3]}/{'/'.join(group.split('.'))}/{artifact}/{version}/{artifact}-{version}.jar")
COORDS
)
    coordinates=$(printf '%s\n' "$INFO" | sed -n 1p)
    remote_jar=$(printf '%s\n' "$INFO" | sed -n 2p)
    local_jar="$ROOT/$dir/target/$(basename "$remote_jar")"

    # No padding: the coordinate is longer than any width worth guessing, and a %-46s against a
    # 49-character string makes the two lines collide.
    printf '  %s  ' "$coordinates"
    if mvn -B -q dependency:get \
        -Dartifact="$coordinates" \
        -DremoteRepositories="$REPO_ID::default::$REPO_URL" \
        -Dmaven.repo.local="$FRESH" > "$FRESH/get.log" 2>&1; then
        # Same file, or the deploy did something other than upload what was built.
        if [ ! -f "$remote_jar" ]; then
            echo "FAILED: Maven reported success but nothing is at $remote_jar" >&2
            status=1
        elif [ ! -f "$local_jar" ]; then
            echo "ok (no local build to compare against)"
        elif [ "$(shasum -a 256 "$local_jar" | cut -d' ' -f1)" = "$(shasum -a 256 "$remote_jar" | cut -d' ' -f1)" ]; then
            echo "ok, and identical to the local build"
        else
            echo "MISMATCH: the repository returned a different file" >&2
            status=1
        fi
    else
        echo "FAILED" >&2
        tail -20 "$FRESH/get.log" >&2
        status=1
    fi
done

[ "$status" -eq 0 ] || exit "$status"

if [ "$VERIFY_ONLY" -eq 1 ]; then
    printf '\n=== %s is present and fetchable in %s\n' "$VERSION" "$REPO_ID"
else
    printf '\n=== deployed %s to %s\n' "$VERSION" "$REPO_ID"
fi
echo "Any machine whose settings.xml declares this repository can resolve the framework, so a"
echo "generated project builds with ./run.sh without a framework checkout present."
