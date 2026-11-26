#!/bin/sh
cd "$(dirname "$0")" || exit 1
exec java \
  --module-path javafx \
  --add-modules javafx.controls \
  --enable-native-access=javafx.graphics \
  -cp "nfd-desktop.jar:lib/*" \
  cn.qaiu.nfd.desktop.DesktopApp "$@"
