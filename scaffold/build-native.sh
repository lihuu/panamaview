#!/bin/sh
# Build the scaffolder as a native executable.
#
# The scaffolder needs none of what makes the framework's own native build delicate: no
# java.lang.foreign, no objective-C runtime, no reachability metadata, no required JVM flags. It
# reads its own resources and writes files, and GraalVM closes over all of that on its own. So this
# is short on purpose — if it grows, something has been pulled in that should not have been.
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

mvn -q -B package

JAR="$(ls target/panamaview-scaffold-*.jar)"
[ -f "$JAR" ] || { echo "build-native.sh: no jar in target/" >&2; exit 1; }

# The templates have to be named explicitly. Resources are not discovered by the compiler, and a
# native image built without this still starts and still lists templates — it only fails when
# someone runs create, which is a long way from the cause. The dotfiles are the easy ones to miss.
exec "$NATIVE_IMAGE" \
    --no-fallback \
    -H:IncludeResources='templates-manifest\.txt|templates/.*' \
    -o target/panamaview \
    -jar "$JAR"
