#!/usr/bin/env bash
set -euo pipefail
dest="${1:?Specify the source archive directory}"
fetch() {
  local name="$1" checksum="$2" url="$3"
  if ! echo "$checksum  $dest/$name" | sha256sum --check --status; then
    curl --fail --location --silent --show-error --retry 3 "$url" -o "$dest/$name"
    echo "$checksum  $dest/$name" | sha256sum --check
  fi
}
fetch aria2-1.37.0.tar.xz 60a420ad7085eb616cb6e2bdf0a7206d68ff3d37fb5a956dc44242eb2f79b66b \
  https://github.com/aria2/aria2/releases/download/release-1.37.0/aria2-1.37.0.tar.xz
fetch boringssl-0.20261105.0.tar.gz 2df2b287f3406a3d2f7f51dccb82cffbddf6cf981811e8edcfffe8fbf02c3ac4 \
  https://codeload.github.com/google/boringssl/tar.gz/refs/tags/0.20261105.0
