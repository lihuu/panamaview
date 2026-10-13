#!/bin/sh
set -eu
cd "$(dirname "$0")"
mvn -q -Dmaven.repo.local="$PWD/../javafx-webview-native-image/target/maven-repository" \
  compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
