#!/bin/sh
set -eu
cd "$(dirname "$0")"
POC_JDK_HOME="${POC_JDK_HOME:-${JAVA_HOME:-$(/usr/libexec/java_home)}}"
mkdir -p target/native-config
exec "$POC_JDK_HOME/bin/java" --enable-native-access=ALL-UNNAMED \
  -Djavafx.cachedir="$PWD/target/jvm-cache" -Dpoc.snapshot="$PWD/target/jvm.ppm" \
  -agentlib:native-image-agent=config-output-dir="$PWD/target/native-config" \
  -cp "target/classes:$(cat target/classpath.txt)" poc.Main
