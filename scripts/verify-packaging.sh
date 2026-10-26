#!/bin/sh
# Verify that a scaffolded project packages into a .app and a .dmg that actually work.
#
# The packaging script is one file copied into every template, so this drives it through the
# real consumer path: stage the npm package, generate a project, and run the project's own
# ./package.sh. A test that called the script with hand-made arguments would prove the script
# can be called; this proves a generated project can be packaged.
#
# What is checked is what a user would notice: the bundle has an identity, the executable inside
# it runs from a different working directory, the signature verifies, and the dmg mounts with
# the app in it. The bundle identity matters twice — the notification centre refuses to start
# outside a .app, and the application menu shows the bundle's name.
#
# Usage: verify-packaging.sh [template]      (default: vanilla)
set -eu
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
TEMPLATE="${1:-vanilla}"

say() { printf '\n=== %s\n' "$1"; }
fail() { printf 'verify-packaging.sh: %s\n' "$1" >&2; exit 1; }

[ "$(uname -s)" = Darwin ] || fail "the .app/.dmg packager is macOS-only"
for tool in plutil codesign hdiutil; do
    command -v "$tool" >/dev/null 2>&1 || fail "no $tool on PATH; is this macOS?"
done
[ -d "templates/$TEMPLATE" ] || fail "no such template: $TEMPLATE"

WORK="$(mktemp -d)"
MOUNT=""
cleanup() {
    if [ -n "$MOUNT" ]; then
        hdiutil detach -quiet "$MOUNT" >/dev/null 2>&1 || true
    fi
    rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

say "staging the npm package"
"$ROOT/npm/scripts/build-package.sh"

say "generating a project from templates/$TEMPLATE"
(cd "$WORK" && node "$ROOT/npm/cli.js" create sample-app \
    --template "$TEMPLATE" --package dev.example.sample --title "Sample Application")

PROJECT="$WORK/sample-app"
APP="$PROJECT/target/dist/Sample Application.app"
DMG="$PROJECT/target/dist/sample-app-0.1.0.dmg"

say "packaging (./package.sh, which builds the native binary first)"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25 2>/dev/null || /usr/libexec/java_home)}"
(cd "$PROJECT" && ./package.sh)

say "the bundle has an identity"
[ -d "$APP" ] || fail "no $APP"
[ -f "$APP/Contents/Info.plist" ] || fail "the .app has no Info.plist"
[ -x "$APP/Contents/MacOS/sample-app" ] || fail "the .app has no executable at Contents/MacOS/sample-app"
plutil -lint "$APP/Contents/Info.plist" >/dev/null

BUNDLE_ID="$(plutil -extract CFBundleIdentifier raw -o - "$APP/Contents/Info.plist")"
[ "$BUNDLE_ID" = "dev.example.sample.sample-app" ] \
    || fail "CFBundleIdentifier is '$BUNDLE_ID', expected dev.example.sample.sample-app"
VERSION="$(plutil -extract CFBundleShortVersionString raw -o - "$APP/Contents/Info.plist")"
[ "$VERSION" = "0.1.0" ] || fail "CFBundleShortVersionString is '$VERSION', expected 0.1.0"
echo "  $BUNDLE_ID, version $VERSION"

say "the signature verifies"
codesign --verify --strict "$APP"
echo "  codesign --verify --strict: ok"

say "the bundled binary runs from another working directory"
# A packaged app is launched by the system, whose working directory is not the bundle's. The
# assets are embedded and staged from the classpath, so this check needs no window.
(cd / && "$APP/Contents/MacOS/sample-app" -Dpanamaview.check=true)

say "the dmg mounts and carries the app"
[ -f "$DMG" ] || fail "no $DMG"
MOUNT="$(hdiutil attach -nobrowse -readonly "$DMG" | awk -F'\t' '/\/Volumes\//{print $NF; exit}')"
[ -n "$MOUNT" ] || fail "could not mount $DMG"
[ -d "$MOUNT/Sample Application.app" ] || fail "the dmg does not contain Sample Application.app"
[ -L "$MOUNT/Applications" ] || fail "the dmg has no /Applications link"
echo "  mounted at $MOUNT with the app and the Applications link"
hdiutil detach -quiet "$MOUNT" >/dev/null
MOUNT=""

echo
echo "[packaging] $TEMPLATE: .app and .dmg verified"
