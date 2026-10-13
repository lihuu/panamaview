#!/bin/sh
set -eu
cd "$(dirname "$0")"
backend="${1:-ffm}"
case "$backend" in ffm) profile= ;; javafx) profile=-Pjavafx ;; *) echo 'Choose ffm or javafx' >&2; exit 2 ;; esac
repo="$PWD/../javafx-webview-native-image/target/maven-repository"
# Install unchanged core into the experiment's local cache, never publish it.
mvn -q -f ../../pom.xml -Dmaven.repo.local="$repo" -pl framework -am install -DskipTests
mvn -q -Dmaven.repo.local="$repo" $profile compile dependency:build-classpath \
  -Dmdep.outputFile="target/$backend/classpath.txt"
