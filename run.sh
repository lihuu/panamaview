#!/bin/sh
# Compile and run the PoC on the JVM.
set -e
cd "$(dirname "$0")"

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=java
fi

mvn -q compile

# JAVA_OPTS is deliberately unquoted: it carries several flags, e.g.
#   JAVA_OPTS=-Dpanamaview.snapshot=/tmp/shot.png ./run.sh
exec "$JAVA" \
    -XstartOnFirstThread \
    --enable-native-access=ALL-UNNAMED \
    $JAVA_OPTS \
    -cp target/classes \
    panamaview.macos.Main "$@"
