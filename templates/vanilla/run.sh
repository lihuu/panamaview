#!/bin/sh
# Run the app on the JVM.
set -e
cd "$(dirname "$0")"

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA="${JAVA:-java}"

mvn -q compile

# The dependency classpath, not just target/classes: panamaview's own jar carries the FFM
# registrations and the client bridge, so leaving it out fails at start-up with
# NoClassDefFoundError rather than at compile time.
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt

# -XstartOnFirstThread is not optional: AppKit requires the event loop to own the process'
# first thread, and the java launcher does not give main() that thread.
# JAVA_OPTS is deliberately unquoted: it carries several flags, and splitting is the point.
#   JAVA_OPTS=-Dpanamaview.check=true ./run.sh
# shellcheck disable=SC2086
exec "$JAVA" \
    -XstartOnFirstThread \
    --enable-native-access=ALL-UNNAMED \
    ${JAVA_OPTS:-} \
    -cp "target/classes:$(cat target/classpath.txt)" \
    com.example.Main "$@"
