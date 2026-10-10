#!/bin/sh
# Build a standalone macOS executable with GraalVM Native Image.
#
# Everything native-image needs to know beyond plain code lives in
# src/main/resources/META-INF/native-image/panamaview/panamaview/reachability-metadata.json:
# the web assets to embed, the reflection used to reach the upcall targets, and — the
# part that is not optional — every java.lang.foreign signature the runtime is allowed
# to call. See the comment at the top of that file.
set -e
cd "$(dirname "$0")"

JDK_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || true)}"
if [ -z "$JDK_HOME" ]; then
    echo "build-native.sh: set JAVA_HOME to a GraalVM installation" >&2
    exit 1
fi

NATIVE_IMAGE="$JDK_HOME/bin/native-image"
if [ ! -x "$NATIVE_IMAGE" ]; then
    echo "build-native.sh: no native-image in $JDK_HOME" >&2
    echo "build-native.sh: install it with: gu install native-image   (GraalVM)" >&2
    exit 1
fi

mvn -q compile

exec "$NATIVE_IMAGE" \
    --no-fallback \
    -H:IncludeResources='web/.*' \
    -H:+ReportExceptionStackTraces \
    -J-Xmx4g \
    --enable-native-access=ALL-UNNAMED \
    -cp target/classes \
    -o target/panamaview \
    panamaview.macos.Main
