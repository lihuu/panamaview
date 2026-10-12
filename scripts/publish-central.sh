#!/bin/sh
# Publish the framework to Maven Central.
#
# Why this is a script of its own rather than `mvn -Pcentral deploy`:
#
# The central-publishing-maven-plugin (0.11.0, Sonatype's own, and the only plugin that publishes
# to Central now) treats `target/central-staging/` as a Maven *local repository*. So the install
# mechanism runs against that directory and writes its bookkeeping files there:
#
#   io/github/lihuu/panamaview/maven-metadata-local.xml        <- artifactId level, no pom in that dir
#   io/github/lihuu/panamaview/0.3.0/_remote.repositories       <- "which repository this came from"
#   ...and four checksums for each of those
#
# Central then refuses the upload, because a directory in the bundle that has no .pom is invalid:
#
#   "Bundle has content that does NOT have a .pom file: io/github/lihuu/panamaview"
#
# Measured, and not fixable from the outside: `-Dmaven.install.skip=true` does not stop it, a
# dedicated `-Dmaven.repo.local` does not stop it (the files are written into staging by the same
# build), deleting them beforehand does not survive (staging is wiped and repopulated each run), and
# the plugin exposes no filter parameter -- `excludeArtifacts` matches whole modules, not files.
#
# So this script does the one thing that is deterministic: it lets the plugin build the bundle,
# removes the twelve files that must never be published, and uploads the result through the same
# Portal API the plugin uses. The plugin's own upload step is not used.
#
# Usage:
#   ./scripts/publish-central.sh              # build the bundle and upload; stops before release
#   ./scripts/publish-central.sh --release     # ... and then publish it (irreversible)
#
# After a successful upload the deployment is VALIDATED but NOT published: this prints the id and
# asks for --release, because publishing to Central is permanent and a version cannot be withdrawn.
set -eu
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

RELEASE=0
case "${1:-}" in
    "")        ;;
    --release) RELEASE=1 ;;
    *)         echo "usage: publish-central.sh [--release]" >&2; exit 2 ;;
esac

say() { printf '\n=== %s\n' "$1"; }

# ------------------------------------------------------------------ the token
#
# Read from ~/.m2/settings.xml rather than the environment, so there is one place the credential
# lives. Server id `central`, which is what the plugin's publishingServerId names.
SETTINGS="${SETTINGS:-$HOME/.m2/settings.xml}"
[ -f "$SETTINGS" ] || { echo "publish-central.sh: no settings file at $SETTINGS" >&2; exit 2; }

CREDS=$(python3 - "$SETTINGS" <<'PY'
import base64, sys, xml.etree.ElementTree as ET

# Parsed, not pattern-matched: a settings file is XML, and a regex over it is one comment or one
# attribute away from reading the wrong value.
#
# Namespace-aware by necessity, and that is not decoration: a settings file declares the Maven
# SETTINGS namespace on <settings>, so every element below it carries that URI. Matching on a bare
# 'server' finds nothing at all, and matching the parent while reading children by unqualified name
# finds the server and then an empty id -- which reads as "no such entry" rather than as the bug it
# is. Both were written and measured before this became one function.
tree = ET.parse(sys.argv[1])
root = tree.getroot()
ns = ''
if root.tag.startswith('{'):
    ns = root.tag[:root.tag.index('}') + 1]

def text(element, name):
    found = element.find(ns + name)
    return (found.text or '').strip() if found is not None else ''

for server in root.iter(ns + 'server'):
    if text(server, 'id') != 'central':
        continue
    u, p = text(server, 'username'), text(server, 'password')
    if not u or not p:
        sys.exit('the central server entry has no username/password')
    # The base64 of "username:password", produced here rather than by the shell.
    #
    # Print the two values, let the shell join and encode them, and the separator becomes part of
    # what is encoded: it was written as a tab first, a tab is a byte like any other, and so every
    # upload answered {"error":{"message":"Invalid token"}} for a token that was correct. The Portal
    # wants a colon between the two, and encoding it away from the values is how it stops being one.
    print(base64.b64encode(f'{u}:{p}'.encode()).decode())
    sys.exit(0)
sys.exit('no <server> with id central in the settings file; see the README')
PY
) || exit 2
TOKEN="$CREDS"

# ------------------------------------------------------------------ version, from the poms
# shellcheck source=scripts/framework-coordinates.sh
. "$ROOT/scripts/framework-coordinates.sh"
echo "  framework version: $FRAMEWORK_VERSION"

[ -z "$(git status --porcelain)" ] || {
    git status --short >&2
    echo "publish-central.sh: uncommitted changes; what gets published has to be what was reviewed" >&2
    exit 1
}

# ------------------------------------------------------------------ build + sign
say "building and signing (the central profile)"
# A repository of its own: the framework resolves against it, and nothing here can disturb the
# developer's own ~/.m2. It is also why this can run in CI, where ~/.m2 does not exist yet.
REPO="${TMPDIR:-/tmp}/panamaview-central-repo"
rm -rf "$REPO"; mkdir -p "$REPO"
mvn -B -q -Pcentral -Dmaven.repo.local="$REPO" -DskipPublishing=true deploy

# ------------------------------------------------------------------ assemble the bundle
#
# Assembled here from the module targets, not taken from the plugin's staging tree.
#
# The plugin's tree would be the natural source, but it only exists when the plugin is allowed to
# upload -- `-DskipPublishing=true` skips the staging as well as the upload, so the tree is empty and
# there is nothing to correct. Letting it upload means a failed deployment record for every attempt.
#
# What the plugin's tree held, measured by diffing a bundle it built against one Central accepted:
# 32 files were right and 12 were not. The 12 are the local-repository bookkeeping Maven writes
# because the plugin uses its staging directory as an install target -- maven-metadata-local.xml at
# the artifactId level (a directory with no pom, a hard failure), _remote.repositories beside each
# artifact, and four checksums for each. Building the tree here means they are never created.
#
# The layout is Central's: <groupId as path>/<artifactId>/<version>/, holding the pom and, for a jar
# module, the jar with its sources and javadoc.
say "assembling the bundle"
STAGE="$ROOT/target/central-publish/staging"
rm -rf "$ROOT/target/central-publish"
mkdir -p "$STAGE"

# One file, from wherever the build left it to where Central wants it.
place() {
    src=$1; dest=$2
    [ -f "$src" ] || { echo "publish-central.sh: missing $src — was the build run?" >&2; exit 1; }
    mkdir -p "$(dirname "$dest")"
    cp "$src" "$dest"
}

V="$FRAMEWORK_VERSION"
# FRAMEWORK_REPO_PATH is "<groupId as path>/<artifactId>/<version>" -- the framework's own directory
# inside a repository. The parent's is the same path with the artifactId swapped, which is a
# directory beside it rather than under it.
FRAMEWORK_DIR="$STAGE/$FRAMEWORK_REPO_PATH"
PARENT_DIR="$STAGE/$(dirname "$FRAMEWORK_REPO_PATH")/panamaview-parent/$V"

# The parent pom. Consumers resolving the framework read it before the framework's own pom, so it
# has to be published or every dependency on the framework fails to resolve.
place "$ROOT/target/panamaview-parent-$V.pom"     "$PARENT_DIR/panamaview-parent-$V.pom"
place "$ROOT/target/panamaview-parent-$V.pom.asc" "$PARENT_DIR/panamaview-parent-$V.pom.asc"

# The framework: pom, jar, and the sources and javadoc jars Central requires beside every jar.
# The suffixes are classifiers, so they sit between the version and the extension.
for base in "panamaview-$V.pom" "panamaview-$V.jar" \
            "panamaview-$V-sources.jar" "panamaview-$V-javadoc.jar"; do
    for suffix in "" ".asc"; do
        place "$ROOT/framework/target/$base$suffix" "$FRAMEWORK_DIR/$base$suffix"
    done
done

# Checksums, written here rather than by the plugin. Central requires md5 and sha1 for every file;
# sha256 and sha512 are accepted, and are what the plugin's own bundles carried, so they are written
# too. Not for the .asc files: the plugin produced none for those, and a bundle that differs from a
# known-good one in a way nobody asked for is a risk taken for no gain.
say "checksumming"
find "$STAGE" -type f ! -name '*.asc' | while read -r f; do
    ( cd "$(dirname "$f")" && b=$(basename "$f") && {
        md5 -q "$b" | tr -d '\n' > "$b.md5"
        shasum -a 1   "$b" | cut -d' ' -f1 | tr -d '\n' > "$b.sha1"
        shasum -a 256 "$b" | cut -d' ' -f1 | tr -d '\n' > "$b.sha256"
        shasum -a 512 "$b" | cut -d' ' -f1 | tr -d '\n' > "$b.sha512"
    } )
done

# The layout is the thing Central validates, so assert it here rather than learning it from a
# rejected upload: no directory may hold files without a .pom, every artifact needs its .asc and its
# md5 and sha1. This is the check that would have caught the first attempt before it was uploaded.
python3 - "$STAGE" <<'PYCHECK'
import sys, pathlib
stage = pathlib.Path(sys.argv[1])
problems = []
for d in sorted(p for p in stage.rglob('*') if p.is_dir()):
    files = [f for f in d.iterdir() if f.is_file()]
    if files and not any(f.name.endswith('.pom') for f in files):
        problems.append(f"{d.relative_to(stage)}/ holds {len(files)} file(s) and no .pom")
for artifact in sorted(stage.rglob('*.pom')) + sorted(stage.rglob('*.jar')):
    for suffix in ('.asc', '.md5', '.sha1'):
        if not artifact.with_name(artifact.name + suffix).is_file():
            problems.append(f"{artifact.relative_to(stage)} has no {suffix}")
if problems:
    print('the bundle would be rejected:', file=sys.stderr)
    for p in problems:
        print('  ' + p, file=sys.stderr)
    sys.exit(1)
total = len([f for f in stage.rglob('*') if f.is_file()])
print(f'  {total} files, layout checked')
PYCHECK

# ------------------------------------------------------------------ upload
# zipped from inside the staging directory, so the archive's entries are the repository paths and
# not a wrapping directory: Central reads the bundle as a repository tree.
say "packing the bundle"
BUNDLE="$ROOT/target/central-publish/central-bundle.zip"
( cd "$STAGE" && zip -q -r -X "$BUNDLE" . )
echo "  central-bundle.zip ($(wc -c < "$BUNDLE" | tr -d ' ') bytes)"

say "uploading to the Central Portal"
ID=$(curl -sS -X POST -H "Authorization: Bearer $TOKEN" \
        -F "bundle=@$BUNDLE" \
        "https://central.sonatype.com/api/v1/publisher/upload?name=panamaview-$FRAMEWORK_VERSION")
echo "  deployment id: $ID"

# The Portal validates asynchronously. Note POST, not GET: the GET form of this endpoint answers 500
# for every deployment, which is how the first attempt spent its time looking at nothing.
say "waiting for validation"
state=""
i=0
while [ "$i" -lt 40 ]; do
    i=$((i + 1))
    sleep 5
    RESP=$(curl -sS -X POST -H "Authorization: Bearer $TOKEN" \
             "https://central.sonatype.com/api/v1/publisher/status?id=$ID")
    state=$(printf '%s' "$RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["deploymentState"])' 2>/dev/null || echo "?")
    [ "$state" = "VALIDATED" ] && break
    case "$state" in FAILED) break ;; esac
done

if [ "$state" != "VALIDATED" ]; then
    echo >&2
    echo "deployment $ID is $state, not VALIDATED. The Portal's own reasons:" >&2
    printf '%s' "$RESP" | python3 -c '
import json, sys
d = json.load(sys.stdin)
for group, messages in (d.get("errors") or {}).items():
    for m in messages:
        print(f"  [{group}] {m}")
' >&2
    echo >&2
    echo "Nothing was published. The bundle is at $BUNDLE if it is worth inspecting." >&2
    exit 1
fi
echo "  VALIDATED"

# ------------------------------------------------------------------ publish
if [ "$RELEASE" -eq 0 ]; then
    say "validated, not published"
    echo "  Publishing is permanent: a version cannot be withdrawn from Central once it is out."
    echo "  To publish this deployment:"
    echo "    ./scripts/publish-central.sh --release   (rebuilds and uploads a fresh one)"
    echo "  or release the one that is already validated:"
    echo "    curl -X POST -H \"Authorization: Bearer ...\" \\"
    echo "      https://central.sonatype.com/api/v1/publisher/deployment/$ID"
    exit 0
fi

say "publishing"
curl -sS -X POST -H "Authorization: Bearer $TOKEN" \
     "https://central.sonatype.com/api/v1/publisher/deployment/$ID" -o /dev/null -w '  HTTP %{http_code}\n'

say "waiting for it to reach Maven Central"
i=0
while [ "$i" -lt 60 ]; do
    i=$((i + 1))
    sleep 20
    code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 20 \
             "https://repo1.maven.org/maven2/io/github/lihuu/panamaview/$FRAMEWORK_VERSION/panamaview-$FRAMEWORK_VERSION.pom" || true)
    if [ "$code" = "200" ]; then
        echo "  live: https://repo1.maven.org/maven2/io/github/lihuu/panamaview/$FRAMEWORK_VERSION/"
        exit 0
    fi
    printf '  [%s/60] repo1 answers %s\n' "$i" "$code"
done

echo "  still not on repo1.maven.org after 20 minutes. That is not necessarily a failure —"
echo "  Central's own sync can take up to a couple of hours — check the deployment:"
echo "    https://central.sonatype.com/publishing/deployments"
