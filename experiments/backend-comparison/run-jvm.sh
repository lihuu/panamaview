#!/bin/sh
set -eu
cd "$(dirname "$0")"
backend="${1:-ffm}"
POC_JDK_HOME="${POC_JDK_HOME:-${JAVA_HOME:-$(/usr/libexec/java_home)}}"
case "$backend" in
  ffm) main=comparison.ffm.Main; first=-XstartOnFirstThread ;;
  javafx) main=comparison.fx.Main; first= ;;
  *) echo 'Choose ffm or javafx' >&2; exit 2 ;;
esac
mkdir -p "target/$backend/trace-runs" "target/$backend/snapshot-config" "target/$backend/javafx-cache"
trace="$(mktemp -d "$PWD/target/$backend/trace-runs/run-XXXXXX")"
# NSApplication terminate exits before the agent's final flush. Periodic snapshots capture
# the exercised paths; copy them only AFTER the process has stopped, without its lock file.
"$POC_JDK_HOME/bin/java" $first --enable-native-access=ALL-UNNAMED \
  -agentlib:native-image-agent="config-output-dir=$trace,config-write-period-secs=1,config-write-initial-delay-secs=0" \
  -Djavafx.cachedir="$PWD/target/$backend/javafx-cache" \
  -cp "target/$backend/classes:$(cat "target/$backend/classpath.txt")" "$main"
cp "$trace"/*.json "target/$backend/snapshot-config/"
