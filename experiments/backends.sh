#!/bin/sh
# Developer experiment selector. Each build produces a separate executable.
set -eu
cd "$(dirname "$0")"
action="${1:-native}"
backend="${2:-ffm}"
case "$action" in jvm|build|native) ;; *) echo 'Usage: backends.sh jvm|build|native ffm|javafx-webview|javafx-ui|all' >&2; exit 2 ;; esac
if [ "$backend" = all ]; then
  ./backends.sh "$action" ffm
  ./backends.sh "$action" javafx-webview
  ./backends.sh "$action" javafx-ui
  exit
fi
case "$backend" in
  ffm|javafx-webview)
    if [ "$backend" = ffm ]; then selected=ffm; else selected=javafx; fi
    case "$action" in
      jvm) ./backend-comparison/compile.sh "$selected"; python3 backend-comparison/verify.py "$selected" jvm ;;
      build)
        ./backend-comparison/compile.sh "$selected"
        python3 backend-comparison/verify.py "$selected" jvm
        ./backend-comparison/build-native.sh "$selected"
        ;;
      native) python3 backend-comparison/verify.py "$selected" native ;;
    esac
    ;;
  javafx-ui)
    cd javafx-native-ui
    case "$action" in
      jvm) ./compile.sh; ./run-jvm.sh ;;
      build) ./compile.sh; ./run-jvm.sh; ./build-native.sh ;;
      native) python3 verify-native.py ;;
    esac
    ;;
  *) echo 'Choose ffm, javafx-webview, javafx-ui or all' >&2; exit 2 ;;
esac
