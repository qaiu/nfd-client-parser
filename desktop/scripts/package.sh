#!/usr/bin/env bash
# Builds the desktop client with a bundled Java runtime (jpackage) for the current OS/CPU.
#
#   desktop/scripts/package.sh                 # portable archive + native installer
#   desktop/scripts/package.sh --no-installer  # portable archive only
#
# Output: desktop/target/dist/NFDClient-<version>-<os>-<arch>.{zip,tar.gz,msi,deb,dmg}
# Needs JDK 17+ with jpackage (CI uses Temurin 21) and Maven. Windows needs Git Bash;
# the .msi additionally needs WiX 3 on PATH. Each package is self-tested before it is kept.
set -euo pipefail

cd "$(dirname "$0")/.."
INSTALLER=1
BUILD=1
for arg in "$@"; do
  case "$arg" in
    --no-installer) INSTALLER=0 ;;
    --skip-build) BUILD=0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

VERSION="${NFD_VERSION:-$(tr -d ' \r\n' < ../VERSION)}"
NAME=NFDClient
case "$(uname -s)" in
  Linux*) OS=linux; SEP=":" ;;
  Darwin*) OS=macos; SEP=":" ;;
  MINGW*|MSYS*|CYGWIN*) OS=windows; SEP=";" ;;
  *) echo "unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac
# jpackage and java on Windows need Windows paths; "pwd -W" gives C:/... which Git Bash also accepts.
if [ "$OS" = windows ]; then ROOT="$(pwd -W)"; else ROOT="$(pwd)"; fi
# The bundled runtime decides the architecture (an x64 JDK on Windows on Arm builds an x64 app).
JAVA_ARCH="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *os.arch = //p' | tr -d '\r')"
case "$JAVA_ARCH" in
  amd64|x86_64) ARCH=x64 ;;
  aarch64|arm64) ARCH=arm64 ;;
  *) ARCH="$JAVA_ARCH" ;;
esac
BASE="$NAME-$VERSION-$OS-$ARCH"
echo "==> $BASE (java $(java -version 2>&1 | head -1))"

if [ "$BUILD" = 1 ]; then
  mvn -B -ntp -Drevision="$VERSION" clean package
fi
LAYOUT="$ROOT/target/nfd-desktop"
[ -f "$LAYOUT/nfd-desktop.jar" ] || { echo "missing $LAYOUT/nfd-desktop.jar" >&2; exit 1; }

echo "==> self-test on the plain class path"
java --module-path "$LAYOUT/javafx" --add-modules javafx.controls \
  -cp "$LAYOUT/nfd-desktop.jar$SEP$LAYOUT/lib/*" cn.qaiu.nfd.desktop.Main --self-test

# jpackage puts every jar under --input on the class path, so JavaFX (linked into the runtime
# from --module-path) and the portable launch scripts must stay out of it.
INPUT="$ROOT/target/jpackage-input"
DIST="$ROOT/target/dist"
WORK="$ROOT/target/jpackage"
rm -rf "$INPUT" "$DIST" "$WORK"
mkdir -p "$INPUT/lib" "$DIST" "$WORK"
cp "$LAYOUT/nfd-desktop.jar" "$INPUT/"
cp "$LAYOUT"/lib/*.jar "$INPUT/lib/"

# Parser needs: java.se (scripting, sql, naming, management), jdk.unsupported (Netty), jdk.crypto.ec
# (ECDHE TLS), jdk.charsets (GBK pages), jdk.dynalink (Nashorn), jdk.localedata, jdk.zipfs,
# jdk.naming.dns/jdk.net (Netty DNS), jdk.httpserver (self-test).
MODULES="java.se,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.dynalink,jdk.localedata,jdk.zipfs,jdk.naming.dns,jdk.net,jdk.httpserver,jdk.management,javafx.controls"

# macOS bundles reject a version whose first number is 0; the file name keeps the real version.
APP_VERSION="${VERSION%%[-+]*}"
if [ "$OS" = macos ] && [ "${APP_VERSION%%.*}" = 0 ]; then
  APP_VERSION="1.${APP_VERSION#0.}"
fi

COMMON=(
  --name "$NAME"
  --app-version "$APP_VERSION"
  --vendor "qaiu"
  --description "NFD Client - netdisk share link downloader"
  --copyright "MIT License"
  --dest "$DIST"
  --temp "$WORK"
)
IMAGE_ARGS=(
  --input "$INPUT"
  --main-jar nfd-desktop.jar
  --main-class cn.qaiu.nfd.desktop.Main
  --module-path "$LAYOUT/javafx"
  --add-modules "$MODULES"
  --java-options "-Xss1m -Dfile.encoding=UTF-8"
  --jlink-options "--strip-debug --no-man-pages --no-header-files"
)
if [ "$OS" = macos ]; then
  IMAGE_ARGS+=(--mac-package-identifier cn.qaiu.nfd.client --mac-package-name "NFD Client")
fi

echo "==> jpackage app-image"
jpackage "${COMMON[@]}" "${IMAGE_ARGS[@]}" --type app-image
rm -rf "$WORK"; mkdir -p "$WORK"

case "$OS" in
  linux) IMAGE="$DIST/$NAME"; LAUNCHER="$IMAGE/bin/$NAME" ;;
  macos) IMAGE="$DIST/$NAME.app"; LAUNCHER="$IMAGE/Contents/MacOS/$NAME" ;;
  windows) IMAGE="$DIST/$NAME"; LAUNCHER="$IMAGE/$NAME.exe" ;;
esac

echo "==> self-test on the packaged runtime"
REPORT="$ROOT/target/self-test-$OS-$ARCH.txt"
rm -f "$REPORT"
set +e
"$LAUNCHER" --self-test --report "$REPORT"
STATUS=$?
set -e
[ -f "$REPORT" ] && cat "$REPORT"
if [ "$STATUS" != 0 ] || ! grep -q "SELF-TEST OK" "$REPORT" 2>/dev/null; then
  echo "packaged self-test failed (exit $STATUS)" >&2
  exit 1
fi

echo "==> portable archive"
case "$OS" in
  linux)
    tar -C "$DIST" -czf "$DIST/$BASE.tar.gz" "$NAME"
    ;;
  macos)
    ditto -c -k --keepParent "$IMAGE" "$DIST/$BASE.zip"
    ;;
  windows)
    (cd "$DIST" && powershell -NoProfile -Command "Compress-Archive -Path '$NAME' -DestinationPath '$BASE.zip' -Force")
    ;;
esac

if [ "$INSTALLER" = 1 ]; then
  echo "==> native installer"
  case "$OS" in
    linux)
      if command -v dpkg-deb >/dev/null && command -v fakeroot >/dev/null; then
        jpackage "${COMMON[@]}" --app-image "$IMAGE" --type deb \
          --linux-package-name nfd-client --linux-app-category Network --linux-shortcut \
          --linux-menu-group "Network" --license-file "$ROOT/../LICENSE"
        mv "$DIST"/nfd-client_*.deb "$DIST/$BASE.deb"
      else
        echo "dpkg-deb or fakeroot not found; skipping .deb"
      fi
      ;;
    macos)
      jpackage "${COMMON[@]}" --app-image "$IMAGE" --type dmg
      mv "$DIST/$NAME-$APP_VERSION.dmg" "$DIST/$BASE.dmg"
      ;;
    windows)
      if command -v candle.exe >/dev/null && command -v light.exe >/dev/null; then
        jpackage "${COMMON[@]}" --app-image "$IMAGE" --type msi \
          --win-menu --win-menu-group "NFD Client" --win-shortcut --win-dir-chooser \
          --win-upgrade-uuid 8f6d3c3e-5d0b-4d55-9a57-3c1f0d6f7a21 --license-file "$ROOT/../LICENSE"
        mv "$DIST/$NAME-$APP_VERSION.msi" "$DIST/$BASE.msi"
      else
        echo "WiX 3 (candle/light) not on PATH; skipping .msi"
      fi
      ;;
  esac
fi

echo "==> artifacts"
ls -la "$DIST"
