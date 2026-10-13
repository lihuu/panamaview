#!/bin/sh
set -eu
cd "$(dirname "$0")"
backend="${1:-ffm}"
POC_JDK_HOME="${POC_JDK_HOME:-${JAVA_HOME:-$(/usr/libexec/java_home)}}"
case "$backend" in
  ffm) main=comparison.ffm.Main; initialization=panamaview.macos,panamaview.windows; protocols= ;;
  javafx)
    main=comparison.fx.Main
    protocols=--enable-url-protocols=http,https
    initialization=javafx,com.sun.javafx,com.sun.glass,com.sun.prism,com.sun.webkit,com.sun.media,comparison.fx,panamaview.macos,panamaview.windows
    if [ "${POC_PATCH_WEBKIT:-0}" != 1 ]; then
      echo 'JavaFX WebView needs the tested experimental WebKit copy patch; opt in with POC_PATCH_WEBKIT=1.' >&2
      exit 1
    fi
    # Reuse the documented patch algorithm, operate only on this backend's target directory.
    python3 ../javafx-webview-native-image/patch-webkit.py --target target/javafx
    ;;
  *) echo 'Choose ffm or javafx' >&2; exit 2 ;;
esac
if [ ! -f "target/$backend/snapshot-config/reachability-metadata.json" ]; then
  echo 'Trace run-jvm.sh first.' >&2; exit 1
fi
mkdir -p "target/$backend/dist"
classpath="$(cat "target/$backend/classpath.txt")"
if [ "$backend" = javafx ]; then classpath="$(cat target/javafx/classpath-patched.txt)"; fi
exec "$POC_JDK_HOME/bin/native-image" --no-fallback --enable-native-access=ALL-UNNAMED \
  $protocols \
  -H:ConfigurationFileDirectories="$PWD/target/$backend/snapshot-config" \
  -H:IncludeResources='web/.*' -H:+ReportExceptionStackTraces \
  --initialize-at-run-time="$initialization" -J-Xmx4g \
  -cp "target/$backend/classes:$classpath" \
  -o "target/$backend/dist/$backend-comparison" "$main"
