#!/bin/sh
# Build a standalone macOS executable with GraalVM Native Image.
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
    exit 1
fi

mvn -q compile

# Resolve the dependency classpath. panamaview's own native-image metadata travels with its
# jar, so the foreign-function registrations and the client bridge are already covered —
# what this build still has to declare is *our* web assets, which are not inheritable.
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt

exec "$NATIVE_IMAGE" \
    --no-fallback \
    -H:IncludeResources='web/.*' \
    -H:+ReportExceptionStackTraces \
    -J-Xmx4g \
    --enable-native-access=ALL-UNNAMED \
    -cp "target/classes:$(cat target/classpath.txt)" \
    -o target/myapp \
    com.example.Main
