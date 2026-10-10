#!/bin/sh
# Check the parts of the private-repository deploy that can be checked without a repository.
#
# The deploy itself cannot run in CI — it needs credentials this repository must not hold — so
# without this, the whole of scripts/deploy-private.sh would be untested there. What is testable is
# the decision it makes before it uploads anything, and that is also the part where being wrong
# means publishing an artifact to the wrong place.
#
# Everything here runs against fixture settings files in a temporary directory. No network, no
# credentials, no ~/.m2.
set -eu
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT INT TERM

CHOOSE="$ROOT/scripts/choose-repo.py"
DEPLOY="$ROOT/scripts/deploy-private.sh"

passed=0
failed=0

ok()  { printf '  PASS  %s\n' "$1"; passed=$((passed + 1)); }
bad() { printf '  FAIL  %s\n' "$1"; failed=$((failed + 1)); }
say() { printf '\n=== %s\n' "$1"; }

# ---------------------------------------------------------------- fixtures
mkdir -p "$WORK"

RELEASE_ID="team-release-AAA"
SNAPSHOT_ID="team-snapshot-BBB"
RELEASE_URL="https://repo.example.invalid/team/release"
SNAPSHOT_URL="https://repo.example.invalid/team/snapshot"

# A pair of repositories, both with credentials — what a normal settings file looks like.
cat > "$WORK/pair.xml" <<EOF
<settings>
  <servers>
    <server>
      <id>$RELEASE_ID</id>
      <username>someone</username>
      <password>secret</password>
    </server>
    <server>
      <id>$SNAPSHOT_ID</id>
      <username>someone</username>
      <password>secret</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>nexus</id>
      <repositories>
        <repository>
          <id>$RELEASE_ID</id>
          <url>$RELEASE_URL</url>
        </repository>
        <repository>
          <id>$SNAPSHOT_ID</id>
          <url>$SNAPSHOT_URL</url>
        </repository>
        <repository>
          <id>central</id>
          <url>https://repo1.maven.org/maven2</url>
        </repository>
      </repositories>
    </profile>
  </profiles>
</settings>
EOF

# Two repositories that both look like release repositories. Nothing can choose between them.
cat > "$WORK/ambiguous.xml" <<EOF
<settings>
  <servers>
    <server>
      <id>team-releases-primary</id>
      <username>u</username>
      <password>p</password>
    </server>
    <server>
      <id>team-releases-secondary</id>
      <username>u</username>
      <password>p</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>x</id>
      <repositories>
        <repository>
          <id>team-releases-primary</id>
          <url>https://a.example.invalid/maven</url>
        </repository>
        <repository>
          <id>team-releases-secondary</id>
          <url>https://b.example.invalid/maven</url>
        </repository>
      </repositories>
    </profile>
  </profiles>
</settings>
EOF

# Repositories, but no credentials for any of them: nothing can be uploaded.
cat > "$WORK/no-credentials.xml" <<EOF
<settings>
  <profiles>
    <profile>
      <id>x</id>
      <repositories>
        <repository>
          <id>some-public-thing</id>
          <url>https://a.example.invalid/maven</url>
        </repository>
      </repositories>
    </profile>
  </profiles>
</settings>
EOF

cat > "$WORK/release-pom.xml" <<'EOF'
<project>
  <groupId>io.example</groupId>
  <artifactId>thing</artifactId>
  <version>1.2.3</version>
</project>
EOF

cat > "$WORK/snapshot-pom.xml" <<'EOF'
<project>
  <groupId>io.example</groupId>
  <artifactId>thing</artifactId>
  <version>1.2.4-SNAPSHOT</version>
</project>
EOF

# Runs choose-repo.py and prints "<id>|<url>|<version>", or "!" plus the message.
run_choose() {
    python3 "$CHOOSE" "$@" 2>&1 | tr '\n' '|' || true
}

expect() {
    label="$1"; want="$2"; shift 2
    got="$(run_choose "$@")"
    case "$got" in
        "$want"*) ok "$label" ;;
        *) bad "$label"; printf '        wanted: %s\n        got:    %s\n' "$want" "$got" ;;
    esac
}

expect_fails() {
    label="$1"; shift
    if python3 "$CHOOSE" "$@" > "$WORK/out" 2>&1; then
        bad "$label (it succeeded)"
        sed 's/^/        /' "$WORK/out"
    else
        ok "$label"
    fi
    # A refusal is only useful if it says something. An empty error is a dead end.
    if [ -s "$WORK/out" ]; then
        ok "$label — says why"
    else
        bad "$label — refused without saying anything"
    fi
}

# ---------------------------------------------------------------- choosing
say "a release version goes to the release repository"
expect "1.2.3 -> ${RELEASE_ID}" "${RELEASE_ID}|${RELEASE_URL}|1.2.3" \
    "$WORK/pair.xml" "$WORK/release-pom.xml"

say "a snapshot version goes to the snapshot repository"
expect "1.2.4-SNAPSHOT -> ${SNAPSHOT_ID}" "${SNAPSHOT_ID}|${SNAPSHOT_URL}|1.2.4-SNAPSHOT" \
    "$WORK/pair.xml" "$WORK/snapshot-pom.xml"

say "the suffix is matched case-insensitively, as Maven matches it"
cat > "$WORK/lower-snapshot-pom.xml" <<'EOF'
<project><groupId>io.example</groupId><artifactId>thing</artifactId>
<version>1.2.4-snapshot</version></project>
EOF
expect "1.2.4-snapshot -> ${SNAPSHOT_ID}" "${SNAPSHOT_ID}|" \
    "$WORK/pair.xml" "$WORK/lower-snapshot-pom.xml"

say "a named repository is used even when detection would disagree"
expect "explicit release id for a snapshot version" "${RELEASE_ID}|" \
    "$WORK/pair.xml" "$WORK/snapshot-pom.xml" "$RELEASE_ID"

# ---------------------------------------------------------------- refusing
say "it refuses rather than guesses"
expect_fails "two release repositories and no way to choose" \
    "$WORK/ambiguous.xml" "$WORK/release-pom.xml"

expect_fails "repositories but no credentials anywhere" \
    "$WORK/no-credentials.xml" "$WORK/release-pom.xml"

expect_fails "a named repository that is not declared" \
    "$WORK/pair.xml" "$WORK/release-pom.xml" "not-a-repository"

# Declared, but with no <server> entry — the mistake that looks configured and is not.
expect_fails "a named repository with no credentials" \
    "$WORK/pair.xml" "$WORK/release-pom.xml" "central"

expect_fails "no settings file at all" \
    "$WORK/does-not-exist.xml" "$WORK/release-pom.xml"

# ---------------------------------------------------------------- the script around it
say "deploy-private.sh refuses cleanly when it cannot decide"
# No settings file means it must stop before uploading anything — and say which file it looked for,
# because "deployment failed" without a path sends people looking in the wrong place.
set +e
SETTINGS="$WORK/does-not-exist.xml" "$DEPLOY" --verify-only > "$WORK/deploy-out" 2>&1
status=$?
set -e
if [ "$status" -ne 0 ]; then
    ok "exits non-zero without a settings file"
else
    bad "exited 0 without a settings file"
fi
if grep -q "does-not-exist.xml" "$WORK/deploy-out"; then
    ok "names the settings file it wanted"
else
    bad "does not name the settings file it wanted"
    sed 's/^/        /' "$WORK/deploy-out"
fi

# ---------------------------------------------------------------- summary
printf '\n%s passed, %s failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ] || exit 1
