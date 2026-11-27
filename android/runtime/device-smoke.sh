#!/usr/bin/env bash
set -euo pipefail

# Use a disposable emulator with no separate Termux installation. The debug APK
# permits run-as for diagnostics and self-tests of the exact packaged JVM. When
# supplied, the release APK must itself automatically start its own engine first.
if [[ $# != 1 && $# != 2 ]]; then
  echo "usage: $0 <embedded-debug-apk> [embedded-release-apk]" >&2
  exit 2
fi
app=cn.qaiu.nfd.android
apk="$1"
timeout "${NFD_ADB_WAIT_SECONDS:-120}" adb wait-for-device
booted=false
for ((i=0; i<${NFD_BOOT_ATTEMPTS:-30}; i++)); do
  if [[ "$(timeout 5 adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; then
    booted=true
    break
  fi
  sleep 2
done
if [[ "$booted" != true ]]; then
  echo "Android did not finish booting." >&2
  exit 1
fi
if adb shell pm path com.termux | grep -q '^package:'; then
  echo "Run this test on an emulator without Termux installed." >&2
  exit 1
fi
local_port="$(adb forward tcp:0 tcp:17891)"
trap 'adb forward --remove "tcp:$local_port" >/dev/null' EXIT
health="http://127.0.0.1:$local_port/api/health"

cold_start() {
  adb shell am force-stop "$app"
  for ((i=0; i<15; i++)); do
    if ! curl -fsS --max-time 2 "$health" -o /dev/null 2>/dev/null; then
      break
    fi
    sleep 1
  done
  if curl -fsS --max-time 2 "$health" -o /dev/null 2>/dev/null; then
    echo "The previous engine did not stop." >&2
    return 1
  fi
  adb shell am start -n "$app/.MainActivity"
  for ((i=0; i<${NFD_STARTUP_ATTEMPTS:-90}; i++)); do
    if curl -fsS --max-time 2 "$health" 2>/dev/null | python3 -c '
import json, sys
try:
    response = json.load(sys.stdin)
except ValueError:
    sys.exit(1)
sys.exit(not (response.get("ok") and response.get("app") == "nfd-termux"))
'; then
      echo "Engine ready after automatic app startup"
      return 0
    fi
    sleep 2
  done
  adb shell run-as "$app" cat files/parser-runtime.log 2>/dev/null >&2 || true
  echo "The app did not automatically start the runtime." >&2
  return 1
}

if [[ $# == 2 ]]; then
  adb install -r "$2"
  cold_start
fi
adb install -r "$apk"
if [[ "${NFD_TEST_RECOVERY:-0}" == 1 ]]; then
  adb shell run-as "$app" rm -f files/embedded-runtime/.interpreter
fi
cold_start
apk_path="$(adb shell pm path "$app" | sed -n 's/^package://p' | head -1)"
# Android uses x86_64 and arm64 as the extracted library directory names.
abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$abi" != arm64-v8a ]] || abi=arm64
native_dir="${apk_path%/*}/lib/$abi"
adb shell run-as "$app" sh -s "$native_dir/libnfd_aria2c.so" <<'SH' | grep -F 'aria2 version 1.37.0'
set -eu
image="$PWD/files/embedded-runtime/jre"
export LD_LIBRARY_PATH="$image/lib:$image/lib/server"
"$1" --version
SH
# Read only the local token from the disposable test installation. Do not log it.
token="$(adb shell run-as "$app" cat shared_prefs/nfd.xml | python3 -c   'import sys, xml.etree.ElementTree as E; print(E.parse(sys.stdin).find("string[@name=\x27local_token\x27]").text)')"
test -n "$token"
curl -fsS --max-time 10 -H "X-NFD-Token: $token" "$health" | python3 -c '
import json, sys
health = json.load(sys.stdin)
assert health["authorized"] and not health["termux"], health
assert health["parserVersion"] == "10.2.6", health
print("Android app started its embedded parser", health["parserVersion"])
'
curl -fsS --max-time 15 -H "X-NFD-Token: $token" -H 'Content-Type: application/json' \
  -d '{"link":"https://pan.lanzoui.com/b66477"}' "http://127.0.0.1:$local_port/api/inspect" |
  python3 -c 'import json, sys; assert json.load(sys.stdin)["type"] == "lz"'
for ((i=0; i<10; i++)); do
  log="$(adb shell run-as "$app" cat files/parser-runtime.log |
    awk '/启动内置解析 ·/ { lines = "" } { lines = lines $0 "\n" } END { print lines }')"
  if grep -q '解析引擎已就绪' <<<"$log"; then break; fi
  sleep 1
done
grep -q '本地解析自检通过' <<<"$log"
grep -q '解析引擎已就绪' <<<"$log"

if [[ "${NFD_TEST_RECOVERY:-0}" == 1 ]]; then
  app_pid="$(adb shell pidof "$app" | tr -d '\r')"
  child="$(adb shell ps -A -o PID,PPID,NAME | awk -v app="$app_pid" '$2 == app && $3 == "libnfd_runtime.so" { print $1 }')"
  [[ "$child" =~ ^[0-9]+$ ]]
  adb shell run-as "$app" kill -6 "$child"
  for ((i=0; i<${NFD_STARTUP_ATTEMPTS:-90}; i++)); do
    if adb shell run-as "$app" test -f files/embedded-runtime/.interpreter; then
      break
    fi
    sleep 2
  done
  adb shell run-as "$app" test -f files/embedded-runtime/.interpreter
  curl -fsS --max-time 10 -H "X-NFD-Token: $token" "$health" |
    python3 -c 'import json, sys; assert json.load(sys.stdin)["authorized"]'
  echo "Engine recovered automatically from SIGABRT in interpreter mode"
fi

if [[ "${NFD_TEST_LIVE:-0}" == 1 ]]; then
  curl -fsS --max-time 60 -H "X-NFD-Token: $token" -H 'Content-Type: application/json' \
    -d '{"link":"https://pan.lanzoui.com/b66477"}' "http://127.0.0.1:$local_port/api/list" |
    python3 -c 'import json, sys; data = json.load(sys.stdin); assert data["hasMore"] and len(data["entries"]) >= 45'
  adb shell run-as "$app" sh -s "$native_dir/libnfd_runtime.so" <<'SH'
  set -eu
  image="$PWD/files/embedded-runtime/jre"
  daemon="$PWD/files/embedded-runtime/daemon"
  export TMPDIR="$PWD/cache" LD_LIBRARY_PATH="$image/lib:$image/lib/server"
  export JAVA_TOOL_OPTIONS="-Dlogback.configurationFile=$daemon/conf/logback.xml"
  cp=
  for jar in "$daemon"/lib/*.jar; do cp="${cp:+$cp:}$jar"; done
  "$1" "$image" "$cp" --self-test --online
SH
fi
