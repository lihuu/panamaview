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

# A glob that matched nothing passes through unchanged, and one that matched two things makes a
# single string containing both — neither of which `[ -f ]` reports accurately. `set --` counts the
# matches instead, so the message can say which of the two happened. (Found by doing it: a leftover
# -SNAPSHOT.jar from trying the snapshot repository made this fail with "no jar in target/" while a
# jar was sitting right there.)
set -- target/panamaview-scaffold-*.jar
if [ "$#" -ne 1 ]; then
    echo "build-native.sh: expected one panamaview-scaffold-*.jar in target/, found $#" >&2
    echo "build-native.sh: run 'mvn -q clean package' to clear out any older ones." >&2
    exit 1
fi
JAR="$1"

# The templates have to be named explicitly. Resources are not discovered by the compiler, and a
# native image built without this still starts and still lists templates — it only fails when
# someone runs create, which is a long way from the cause. The dotfiles are the easy ones to miss.
exec "$NATIVE_IMAGE" \
    --no-fallback \
    -H:IncludeResources='templates-manifest\.txt|templates/.*' \
    -o target/panamaview \
    -jar "$JAR"
