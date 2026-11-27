#!/usr/bin/env bash
set -euo pipefail

# Keep this companion archive beside the APK when distributing the embedded runtime.
if [[ $# != 1 ]]; then
  echo "usage: $0 <source-bundle.tar>" >&2
  exit 2
fi
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fetch() {
  local url="$1" destination="$2" checksum="${3:-}"
  curl --fail --location --retry 3 --silent --show-error "$url" -o "$work/$destination"
  if [[ -n "$checksum" ]]; then
    echo "$checksum  $work/$destination" | sha256sum --check
  fi
}
fetch "https://codeload.github.com/openjdk/jdk21u/tar.gz/refs/tags/jdk-21.0.13.1-ga" openjdk-21.0.13.1.tar.gz \
  222ab0f4f790dc93fc244eca0bafaa60976154141cc77ccbd83e5012c6ce8b8d
fetch "https://codeload.github.com/termux/termux-packages/tar.gz/c7002d8f9394e60ac441cf9d8977471a2c6b85ea" termux-packages.tar.gz
fetch "https://codeload.github.com/termux/libandroid-shmem/tar.gz/refs/tags/v0.7" libandroid-shmem-0.7.tar.gz \
  1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867
fetch "https://github.com/madler/zlib/releases/download/v1.4/zlib-1.4.tar.xz" zlib-1.4.tar.xz \
  4f11c512053d7bab8d9e9b89bde3575add3e4c50a383b42ef6cb837035fc5a7c
"$here/aria2-sources.sh" "$work"

cp "$here"/{README.md,NOTICE.md,LLVM-LICENSE.txt,build.sh,build-aria2.sh,aria2-sources.sh,aria2-embedded.patch,packages.lock,launcher.c} "$work"
cp "$here/../../LICENSE" "$work/NFD-LICENSE.txt"
tar -C "$work" -cf "$1" .
