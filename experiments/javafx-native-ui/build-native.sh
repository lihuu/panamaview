#!/bin/sh
set -eu
cd "$(dirname "$0")"
POC_JDK_HOME="${POC_JDK_HOME:-${JAVA_HOME:-$(/usr/libexec/java_home)}}"
mkdir -p target/dist
exec "$POC_JDK_HOME/bin/native-image" --no-fallback --enable-native-access=ALL-UNNAMED \
  -H:ConfigurationFileDirectories="$PWD/target/native-config" \
  -H:+ReportExceptionStackTraces \
  --initialize-at-run-time=javafx,com.sun.javafx,com.sun.glass,com.sun.prism,poc.NativeUiPoc \
  -J-Xmx4g -cp "target/classes:$(cat target/classpath.txt)" \
  -o target/dist/javafx-native-ui poc.Main
