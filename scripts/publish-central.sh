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
import re, sys, xml.etree.ElementTree as ET

# Parsed, not pattern-matched: a settings file is XML, and a regex over it is one comment or one
# attribute away from reading the wrong value.
ns = {'m': 'http://maven.apache.org/SETTINGS/1.0.0'}
tree = ET.parse(sys.argv[1])
for server in tree.getroot().findall('.//m:server', ns) or tree.getroot().findall('.//server'):
    if server.findtext('id', '').strip() == 'central':
        u = server.findtext('username', '').strip()
        p = server.findtext('password', '').strip()
        if not u or not p:
            sys.exit('the central server entry has no username/password')
        print(f'{u}\t{p}')
        sys.exit(0)
sys.exit('no <server> with id central in the settings file; see the README')
PY
) || exit 2
TOKEN=$(printf '%s' "$CREDS" | base64)

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
# From the plugin's staging tree, which is correct apart from the twelve files below. Read from
# there rather than from target/ so that the layout is the plugin's, not a second implementation of
# it -- if the layout ever changes, this follows it.
say "removing the files that must not be published"
STAGE="$ROOT/target/central-staging"
[ -d "$STAGE" ] || { echo "no staging tree at $STAGE; the build did not stage anything" >&2; exit 1; }

before=$(find "$STAGE" -type f | wc -l | tr -d ' ')

# maven-metadata-local.xml: the version index Maven writes at the artifactId level. It lands in a
# directory that holds no .pom, which is a hard validation failure on its own.
# _remote.repositories: Maven's note of which repository an artifact came from -- local bookkeeping,
# never part of a publication. Named as "not valid" by the validator once the other is gone.
find "$STAGE" \( -name 'maven-metadata-local.xml' -o -name '_remote.repositories' \) -print |
    sed 's|^|  |' | sort
find "$STAGE" \( -name 'maven-metadata-local.xml' -o -name '_remote.repositories' \) -delete
# Their checksums would be orphans, and a checksum for a file that is not there is itself invalid.
for f in maven-metadata-local.xml _remote.repositories; do
    find "$STAGE" \( -name "$f.md5" -o -name "$f.sha1" -o -name "$f.sha256" -o -name "$f.sha512" \) -delete
done

after=$(find "$STAGE" -type f | wc -l | tr -d ' ')
echo "  $before files -> $after"

# A directory with no pom is what the validator rejects, so assert it here instead of finding out
# from a failed upload. This is the check that would have saved the first attempt.
python3 - "$STAGE" <<'PY'
import sys, pathlib
stage = pathlib.Path(sys.argv[1])
bad = []
for d in [p for p in stage.rglob('*') if p.is_dir()]:
    files = [f for f in d.iterdir() if f.is_file()]
    if files and not any(f.name.endswith('.pom') for f in files):
        bad.append(d.relative_to(stage).as_posix() + '/  (' + ', '.join(f.name for f in files) + ')')
if bad:
    print('these directories hold files but no .pom, which Central rejects:', file=sys.stderr)
    for b in bad:
        print('  ' + b, file=sys.stderr)
    sys.exit(1)
print('  every directory with files has a .pom')
PY

say "packing the bundle"
BUNDLE="$ROOT/target/central-publish/central-bundle.zip"
rm -rf "$ROOT/target/central-publish"
mkdir -p "$ROOT/target/central-publish"
( cd "$STAGE" && zip -q -r -X "$BUNDLE" . )
echo "  $BUNDLE ($(find "$STAGE" -type f | wc -l | tr -d ' ') files, $(wc -c < "$BUNDLE" | tr -d ' ') bytes)"

# ------------------------------------------------------------------ upload
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
