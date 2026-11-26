#!/bin/sh
# Linux/macOS app-image. Windows packaging is package-windows.ps1.
set -eu
cd "$(dirname "$0")/.."
mvn -DskipTests package
layout="target/nfd-desktop"
java -cp "$layout/nfd-desktop.jar:$layout/lib/*" cn.qaiu.nfd.desktop.Check
mkdir -p target/dist
jpackage \
  --name NFDClient \
  --dest target/dist \
  --input "$layout" \
  --main-jar nfd-desktop.jar \
  --main-class cn.qaiu.nfd.desktop.DesktopApp \
  --type app-image \
  --app-version 0.1.0 \
  --vendor qaiu \
  --module-path "$layout/javafx" \
  --add-modules javafx.controls
echo "App image: target/dist/NFDClient"
