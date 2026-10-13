#!/bin/sh
set -eu
cd "$(dirname "$0")"
POC_JDK_HOME="${POC_JDK_HOME:-${JAVA_HOME:-$(/usr/libexec/java_home)}}"
if [ ! -d target/native-config ]; then
  echo "Run ./run-jvm.sh first to trace the actual WebView and bridge." >&2
  exit 1
fi
mkdir -p target/dist
POC_CLASSPATH="$(cat target/classpath.txt)"
if [ "${POC_PATCH_WEBKIT:-0}" = 1 ]; then
  python3 patch-webkit.py
  POC_CLASSPATH="$(cat target/classpath-patched.txt)"
fi
exec "$POC_JDK_HOME/bin/native-image" \
  --no-fallback \
  --enable-native-access=ALL-UNNAMED \
  -H:ConfigurationFileDirectories="$PWD/target/native-config" \
  -H:IncludeResources='web/.*' \
  -H:+ReportExceptionStackTraces \
  --initialize-at-run-time=javafx,com.sun.javafx,com.sun.glass,com.sun.prism,com.sun.webkit,com.sun.media,poc.WebViewPoc \
  -J-Xmx4g \
  -cp "target/classes:$POC_CLASSPATH" \
  -o target/dist/javafx-webview-poc poc.Main
