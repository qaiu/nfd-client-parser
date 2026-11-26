#!/data/data/com.termux/files/usr/bin/bash
# Runs inside termux/termux-docker: installs Termux's default OpenJDK from the Termux
# repositories, installs the daemon bundle exactly like a user would, and exercises it.
#   termux-smoke.sh /path/to/nfd-termux-daemon-X.Y.Z.tar.gz [--online]
set -euo pipefail

bundle="$1"
online="${2:-}"
token="ci-smoke-token-0123456789"

step() { printf '\n==> %s\n' "$*"; }

step "Termux packages"
for i in 1 2 3; do
  if apt-get update -y && DEBIAN_FRONTEND=noninteractive apt-get install -y -o Dpkg::Options::=--force-confnew openjdk-21 curl; then
    break
  fi
  [ "$i" = 3 ] && exit 1
  sleep 10
done
java -version

step "install.sh --bundle"
work="$(mktemp -d)"
tar -xzf "$bundle" -C "$work"
bash "$work/nfd-termux/bin/install.sh" --bundle "$bundle" --token "$token" --skip-storage --no-start
command -v nfd-termux

step "nfd-termux version / self-test"
nfd-termux version
nfd-termux self-test $online

step "daemon API"
nfd-termux start
health="$(curl -fsS -H "X-NFD-Token: $token" http://127.0.0.1:17890/api/health)"
echo "$health"
echo "$health" | grep -q '"parserVersion":"10.2.6"'
echo "$health" | grep -q '"termux":true'
inspect="$(curl -fsS -H "X-NFD-Token: $token" -X POST -d '{"link":"https://pan.lanzoui.com/b66477"}' http://127.0.0.1:17890/api/inspect)"
echo "$inspect"
echo "$inspect" | grep -q '"type":"lz"'
code="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:17890/api/tasks)"
[ "$code" = 401 ] || { echo "expected 401 without token, got $code"; exit 1; }
nfd-termux stop

echo
echo "TERMUX SMOKE OK"
