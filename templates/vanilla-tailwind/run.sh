#!/bin/sh
# Run the app on the JVM.
set -e
cd "$(dirname "$0")"

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA="${JAVA:-java}"

# Empty on every platform but macOS, where it is the flag AppKit insists on.
case "$(uname -s)" in
    Darwin) START_ON_FIRST_THREAD=1 ;;
    *)      START_ON_FIRST_THREAD= ;;
esac

./scripts/build-css.sh >/dev/null
mvn -q compile

# The dependency classpath, not just target/classes: panamaview's own jar carries the FFM
# registrations and the client bridge, so leaving it out fails at start-up with
# NoClassDefFoundError rather than at compile time.
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt

# A flag per platform, built as an argument list rather than spelled out on the command line.
#
# -XstartOnFirstThread is macOS's alone and is not optional there: AppKit requires the event loop
# to own the process' first thread, and the java launcher does not give main() that thread.
# Everywhere else it is worse than useless, because the JVM treats an unknown -X option as a fatal
# start-up error. A list is the one shape that expresses "sometimes one flag, sometimes none"
# without either platform seeing the other's.
#
# shellcheck disable=SC2086
set -- \
    ${START_ON_FIRST_THREAD:+-XstartOnFirstThread} \
    --enable-native-access=ALL-UNNAMED \
    ${JAVA_OPTS:-} \
    -cp "target/classes:$(cat target/classpath.txt)" \
    com.example.Main "$@"
exec "$JAVA" "$@"
