#!/bin/sh
# Build the frontend, then produce a standalone executable.
#
# POSIX shell, so this is for macOS and Linux. On Windows the equivalent has to be a .cmd or a .ps1
# — Windows has no /bin/sh, and the platform lookup below is macOS's own. Saying so here is the
# difference between "this project does not do that yet" and a confusing failure halfway through a
# build; `run.sh` is the path that works everywhere, and it is what the README leads with.
set -e
cd "$(dirname "$0")"

JDK_HOME="${JAVA_HOME:-}"
if [ -z "$JDK_HOME" ] && [ -x /usr/libexec/java_home ]; then
    JDK_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
fi
if [ -z "$JDK_HOME" ]; then
    echo "build-native.sh: set JAVA_HOME to a GraalVM installation" >&2
    echo "build-native.sh: native-image is not part of every JDK, so it is not searched for." >&2
    exit 1
fi

NATIVE_IMAGE="$JDK_HOME/bin/native-image"
if [ ! -x "$NATIVE_IMAGE" ]; then
    echo "build-native.sh: no native-image in $JDK_HOME" >&2
    echo "build-native.sh: it ships with GraalVM, not with a plain JDK." >&2
    exit 1
fi

./scripts/build-frontend.sh
mvn -q compile

# panamaview's own native-image metadata travels with its jar, so the foreign-function
# registrations and the client bridge are already covered. What this build must declare is *our*
# web assets, which are not inheritable.
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt

# NATIVE_IMAGE_THREADS caps how many threads native-image uses to compile.
#
# Unset, native-image sizes itself for the whole machine, which is right for one build and wrong
# for several at once: measured here, five of these at once on a fourteen-core box spent their time
# waiting on each other (load average 175) and finished no faster than running them two at a time.
# Nothing in a normal build needs to set it, and nothing does now — the verification scripts
# compile one template, so there is no second build to contend with. The hook stays because the
# contention is a property of native-image, not of this repository's layout, and the next person to
# run several of these at once should not have to rediscover the number.
exec "$NATIVE_IMAGE" \
    --no-fallback \
    -H:IncludeResources='web/.*' \
    -H:+ReportExceptionStackTraces \
    -J-Xmx4g \
    ${NATIVE_IMAGE_THREADS:+-J-XX:ActiveProcessorCount=$NATIVE_IMAGE_THREADS} \
    --enable-native-access=ALL-UNNAMED \
    -cp "target/classes:$(cat target/classpath.txt)" \
    -o target/myapp \
    com.example.Main
