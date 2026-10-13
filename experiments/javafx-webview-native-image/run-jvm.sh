#!/bin/sh
set -eu
cd "$(dirname "$0")"
POC_JDK_HOME="${POC_JDK_HOME:-${JAVA_HOME:-$(/usr/libexec/java_home)}}"
if [ ! -f target/classpath.txt ]; then ./compile.sh; fi
mkdir -p target/native-config target/javafx-cache
exec "$POC_JDK_HOME/bin/java" \
  --enable-native-access=ALL-UNNAMED \
  -Djavafx.cachedir="$PWD/target/javafx-cache" \
  -Dpoc.snapshot="$PWD/target/jvm.ppm" \
  -agentlib:native-image-agent=config-output-dir="$PWD/target/native-config" \
  -cp "target/classes:$(cat target/classpath.txt)" poc.Main "$@"
