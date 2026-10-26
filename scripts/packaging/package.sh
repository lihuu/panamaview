#!/bin/sh
# Package the app as a macOS .app bundle and a .dmg.
#
# A native-image binary already runs as a bare executable, but macOS treats a process without a
# bundle as a nameless command-line tool: the application menu shows the executable's name, the
# notification centre refuses to start (it requires NSBundle.mainBundle to be a .app), and there
# is nothing to drag into /Applications. This script puts the binary where the system expects it
# and writes the Info.plist that gives the process an identity.
#
# Identity defaults come from the pom: the artifactId is the executable's name, the version is
# the bundle version, and the bundle identifier is groupId.artifactId. Override any of them when
# they do not fit. The window title is deliberately not read: it lives in Java, and packaging
# should not parse source to guess it. Pass --title when the display name differs.
#
# The signature is ad-hoc by default, which is enough to run the app on the machine that built
# it. Distributing it outside that machine needs a Developer ID signature and notarization --
# see docs/packaging.md. This script does not pretend to do that with no credentials.
#
# Usage: ./package.sh [options]
#   --name NAME        executable name inside the bundle (default: artifactId)
#   --title TITLE      display name (default: --name)
#   --bundle-id ID     reverse-DNS identifier (default: groupId.artifactId)
#   --version VERSION  dotted numeric version (default: pom version)
#   --icon FILE.icns   bundle icon
#   --sign IDENTITY    codesign identity (default: - , ad-hoc)
#   --no-build         fail instead of running build-native.sh when the binary is missing
#   --output DIR       where to write artifacts (default: target/dist)
#   -h, --help         this text
#
set -eu
# Absolute, because the next line changes the working directory and --help reads this file.
SCRIPT="$0"
case "$SCRIPT" in
    /*) ;;
    *) SCRIPT="$(pwd)/$SCRIPT" ;;
esac
cd "$(dirname "$0")"

usage() {
    sed -n 's/^# \{0,1\}//p' "$SCRIPT" | sed -n '/^Usage:/,/^$/p'
}

NAME=""
TITLE=""
BUNDLE_ID=""
VERSION=""
ICON=""
SIGN="-"
BUILD=1
OUT="target/dist"

while [ $# -gt 0 ]; do
    case "$1" in
        --name)      NAME=$2; shift 2 ;;
        --title)     TITLE=$2; shift 2 ;;
        --bundle-id) BUNDLE_ID=$2; shift 2 ;;
        --version)   VERSION=$2; shift 2 ;;
        --icon)      ICON=$2; shift 2 ;;
        --sign)      SIGN=$2; shift 2 ;;
        --no-build)  BUILD=0; shift ;;
        --output)    OUT=$2; shift 2 ;;
        -h|--help)   usage; exit 0 ;;
        *) echo "package.sh: unknown argument '$1'" >&2; usage >&2; exit 2 ;;
    esac
done

[ -f pom.xml ] || { echo "package.sh: no pom.xml beside this script" >&2; exit 1; }

pom_value() {
    sed -n "s:.*<$1>\([^<]*\)</$1>.*:\1:p" pom.xml | head -1
}

GROUP=$(pom_value groupId)
ARTIFACT=$(pom_value artifactId)
POM_VERSION=$(pom_value version)
[ -n "$ARTIFACT" ] || { echo "package.sh: pom.xml has no artifactId" >&2; exit 1; }

NAME=${NAME:-$ARTIFACT}
TITLE=${TITLE:-$NAME}
VERSION=${VERSION:-$POM_VERSION}
BUNDLE_ID=${BUNDLE_ID:-$GROUP.$ARTIFACT}

[ -n "$VERSION" ] || { echo "package.sh: no version in pom.xml; pass --version" >&2; exit 1; }
case "$VERSION" in
    *[!0-9.]*|.*|*.)
        echo "package.sh: version '$VERSION' is not a dotted number; pass --version, e.g. --version 1.2.0" >&2
        exit 1 ;;
esac
case "$BUNDLE_ID" in
    *[!A-Za-z0-9.-]*|"")
        echo "package.sh: bundle id '$BUNDLE_ID' is not reverse-DNS; pass --bundle-id, e.g. com.example.myapp" >&2
        exit 1 ;;
esac
case "$BUNDLE_ID" in
    *.*) ;;
    *) echo "package.sh: bundle id '$BUNDLE_ID' has no dot; pass --bundle-id, e.g. com.example.myapp" >&2; exit 1 ;;
esac
case "$NAME$TITLE$BUNDLE_ID" in
    *[\&\<\>]*)
        echo "package.sh: names may not contain &, < or >; they are written into Info.plist" >&2
        exit 1 ;;
esac
case "$NAME$TITLE" in
    */*)
        echo "package.sh: names may not contain '/'; they are used as path components" >&2
        exit 1 ;;
esac
case "$OUT" in
    ""|"/")
        echo "package.sh: refusing to use '$OUT' as the output directory" >&2
        exit 1 ;;
esac

BIN="target/$ARTIFACT"
if [ "$BUILD" = 1 ] && [ ! -x "$BIN" ]; then
    echo "==> building the native binary ($BIN)"
    ./build-native.sh
fi
if [ ! -x "$BIN" ]; then
    echo "package.sh: no executable at $BIN — run ./build-native.sh first" >&2
    exit 1
fi

APP="$OUT/$TITLE.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/$NAME"
chmod +x "$APP/Contents/MacOS/$NAME"

ICON_KEY=""
if [ -n "$ICON" ]; then
    case "$ICON" in
        *.icns) ;;
        *) echo "package.sh: the icon must be a .icns file; got '$ICON'" >&2; exit 1 ;;
    esac
    [ -f "$ICON" ] || { echo "package.sh: no icon at $ICON" >&2; exit 1; }
    cp "$ICON" "$APP/Contents/Resources/AppIcon.icns"
    ICON_KEY='    <key>CFBundleIconFile</key><string>AppIcon</string>'
fi

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key><string>en</string>
    <key>CFBundleExecutable</key><string>$NAME</string>
    <key>CFBundleIdentifier</key><string>$BUNDLE_ID</string>
    <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
    <key>CFBundleName</key><string>$NAME</string>
    <key>CFBundleDisplayName</key><string>$TITLE</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>$VERSION</string>
    <key>CFBundleVersion</key><string>$VERSION</string>
    <key>LSMinimumSystemVersion</key><string>14.0</string>
    <key>NSHighResolutionCapable</key><true/>
$ICON_KEY
</dict>
</plist>
PLIST

plutil -lint "$APP/Contents/Info.plist" >/dev/null

echo "==> signing with '$SIGN'"
codesign --force --sign "$SIGN" "$APP"
codesign --verify --strict "$APP"

STAGE=$(mktemp -d "${TMPDIR:-/tmp}/panamaview-dmg.XXXXXX")
trap 'rm -rf "$STAGE"' EXIT INT TERM
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"

DMG="$OUT/$ARTIFACT-$VERSION.dmg"
rm -f "$DMG"
echo "==> writing $DMG"
hdiutil create -quiet -volname "$TITLE" -srcfolder "$STAGE" -ov -format UDZO "$DMG"

echo "[package] $APP"
echo "[package] $DMG"
if [ "$SIGN" = "-" ]; then
    echo "[package] ad-hoc signed: it runs here. Distribution needs a Developer ID signature and notarization -- see docs/packaging.md."
fi
