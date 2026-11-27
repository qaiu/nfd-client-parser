#!/usr/bin/env bash
set -euo pipefail

# Cross-link the Android/Bionic JDK modules on the Linux build machine.
if [[ $# != 2 ]]; then
  echo "usage: $0 <arm64-v8a|x86_64> <output-directory>" >&2
  exit 2
fi
abi="$1"
case "$abi" in
  arm64-v8a) triple=aarch64-linux-android ;;
  x86_64) triple=x86_64-linux-android ;;
  *) echo "unsupported embedded ABI: $abi" >&2; exit 2 ;;
esac
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$2"
output="$(cd "$2" && pwd)"
cache="${NFD_RUNTIME_CACHE:-$output/cache}"
mkdir -p "$cache" "$output/assets" "$output/jniLibs/$abi"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

while read -r package_abi checksum package; do
  [[ "$package_abi" == "$abi" ]] || continue
  deb="$cache/${package##*/}"
  if ! echo "$checksum  $deb" | sha256sum --check --status; then
    curl --fail --location --retry 3 --silent --show-error \
      "https://packages.termux.dev/apt/termux-main/$package" -o "$work/download.deb"
    echo "$checksum  $work/download.deb" | sha256sum --check
    mv "$work/download.deb" "$deb"
  fi
  dpkg-deb --extract "$deb" "$work/packages"
done < "$here/packages.lock"

prefix="$work/packages/data/data/com.termux/files/usr"
jdk="$prefix/lib/jvm/java-21-openjdk"
image="$work/image"
"${JAVA_HOME:?Set JAVA_HOME to a host JDK 21}/bin/jlink" \
  --module-path "$jdk/jmods" \
  --add-modules java.logging,java.management,java.naming,java.net.http,java.scripting,java.sql,java.xml,jdk.charsets,jdk.crypto.ec,jdk.dynalink,jdk.httpserver,jdk.management,jdk.naming.dns,jdk.net,jdk.unsupported,jdk.zipfs \
  --compress=zip-9 --strip-java-debug-attributes --strip-native-commands \
  --no-header-files --no-man-pages --output "$image"

llvm="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r27d}/toolchains/llvm/prebuilt/linux-x86_64/bin"
"$llvm/clang" --target="${triple}26" -Os -Wall -Wextra -Werror -Wl,-z,max-page-size=16384 \
  -o "$output/jniLibs/$abi/libnfd_runtime.so" "$here/launcher.c" -ldl
# Native code is loaded by the child process from the private runtime image.
# Copy names exactly as recorded in the JVM's NEEDED sections.
for library in libandroid-shmem.so libc++_shared.so libz.so.1; do
  cp -L "$prefix/lib/$library" "$image/lib/$library"
done

# The packaged Termux spawn shim is linked with 4 KiB LOAD segments. HotSpot
# needs this library as soon as it is loaded, including on 16 KiB Android devices.
spawn_revision=c7002d8f9394e60ac441cf9d8977471a2c6b85ea
mkdir -p "$work/spawn" "$image/legal/libandroid-spawn"
while read -r checksum name; do
  curl --fail --location --retry 3 --silent --show-error \
    "https://raw.githubusercontent.com/termux/termux-packages/$spawn_revision/packages/libandroid-spawn/$name" \
    -o "$work/spawn/$name"
  echo "$checksum  $work/spawn/$name" | sha256sum --check
done <<'SHA256'
5267405b1b14fcbe885bd815d9bd5dde94144dc90b319d6f4be17e47f43bcad3 posix_spawn.cpp
5cc27528237cf17727709eaab624f20878b71013b2eb265e3a6e6dae1e4294a0 posix_spawn.h
511ce23b36ca7dd3858570d8ee3054ceed18e12d284f96661d030a82590a5954 LICENSE
SHA256
"$llvm/clang" --driver-mode=g++ --target="${triple}24" -Os -fPIC -shared \
  -Wl,-soname,libandroid-spawn.so -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
  -o "$image/lib/libandroid-spawn.so" "$work/spawn/posix_spawn.cpp"
cp "$work/spawn/LICENSE" "$image/legal/libandroid-spawn/"

find "$image/lib" -type f -name '*.so*' -exec "$llvm/llvm-strip" --strip-unneeded {} +
rm -f "$image/lib/libjli.so" "$image/lib/jrt-fs.jar"
for pkg in libandroid-shmem zlib; do
  if [[ -d "$prefix/share/doc/$pkg" ]]; then
    cp -rL "$prefix/share/doc/$pkg" "$image/legal/$pkg"
  fi
done
cp "$here/NOTICE.md" "$image/legal/THIRD_PARTY.md"
cp "$here/LLVM-LICENSE.txt" "$image/legal/LLVM-LICENSE.txt"
"$here/build-aria2.sh" "$abi" "$output"
"$JAVA_HOME/bin/keytool" -list -rfc -keystore "$image/lib/security/cacerts" -storepass changeit |
  awk '/-----BEGIN CERTIFICATE-----/ { pem=1 } pem { print } /-----END CERTIFICATE-----/ { pem=0 }' \
  > "$image/lib/security/aria2-ca.pem"
grep -q 'BEGIN CERTIFICATE' "$image/lib/security/aria2-ca.pem"

# Resolve jlink's relative legal-notice symlinks before zipping. Do not ship links
# or executable files into the app's private runtime directory.
python3 - "$image" "$output/assets/runtime-$abi.zip.xz" "$work/runtime.zip" <<'PY'
import lzma
from pathlib import Path
import shutil
import sys
from zipfile import ZIP_STORED, ZipFile

image, archive, raw_zip = map(Path, sys.argv[1:])
size = 0
with ZipFile(raw_zip, 'w', ZIP_STORED) as output:
    for path in sorted(image.rglob('*')):
        if path.is_file():
            output.write(path, path.relative_to(image))
            size += path.stat().st_size
with raw_zip.open('rb') as source, lzma.open(archive, 'wb', preset=6) as compressed:
    shutil.copyfileobj(source, compressed)
print(f'{archive.stat().st_size:,} bytes compressed, {size:,} bytes extracted')
PY
