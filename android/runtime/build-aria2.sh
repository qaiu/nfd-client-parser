#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 2 ]]; then
  echo "usage: $0 <arm64-v8a|x86_64> <output-directory>" >&2
  exit 2
fi
case "$1" in
  arm64-v8a) triple=aarch64-linux-android; processor=aarch64 ;;
  x86_64) triple=x86_64-linux-android; processor=x86_64 ;;
  *) echo "unsupported aria2 ABI: $1" >&2; exit 2 ;;
esac

here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$2/jniLibs/$1" "$2/assets"
out="$(cd "$2" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cache="${NFD_RUNTIME_CACHE:-$out/cache}"
mkdir -p "$cache"
"$here/aria2-sources.sh" "$cache"
tar xf "$cache/boringssl-0.20261105.0.tar.gz" -C "$work"
tar xf "$cache/aria2-1.37.0.tar.xz" -C "$work"
patch -d "$work/aria2-1.37.0" -p1 < "$here/aria2-embedded.patch"

export ANDROID_NDK_ROOT="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r27d}"
llvm="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$llvm/bin:$PATH"
export CFLAGS="-Oz -flto=thin -ffunction-sections -fdata-sections"
export CXXFLAGS="$CFLAGS"
export LDFLAGS="-Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--thinlto-jobs=2 -Wl,--pack-dyn-relocs=android"
jobs="${NFD_BUILD_JOBS:-4}"

boring="$work/boringssl-0.20261105.0"
ssl="$work/ssl"
cmake -S "$boring" -B "$ssl" -G Ninja -DBUILD_TESTING=OFF -DCMAKE_BUILD_TYPE=MinSizeRel \
  -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR="$processor" \
  -DCMAKE_C_COMPILER="$llvm/bin/clang" -DCMAKE_C_COMPILER_TARGET="${triple}26" \
  -DCMAKE_CXX_COMPILER="$llvm/bin/clang++" -DCMAKE_CXX_COMPILER_TARGET="${triple}26" \
  -DCMAKE_ASM_COMPILER="$llvm/bin/clang" -DCMAKE_ASM_COMPILER_TARGET="${triple}26" \
  -DCMAKE_C_FLAGS_MINSIZEREL="-Oz -DNDEBUG" -DCMAKE_CXX_FLAGS_MINSIZEREL="-Oz -DNDEBUG"
cmake --build "$ssl" --parallel "$jobs" --target ssl crypto
(
  cd "$work/aria2-1.37.0"
  export CC="clang --target=${triple}26" CXX="clang++ --target=${triple}26"
  export AR=llvm-ar RANLIB=llvm-ranlib NM=llvm-nm
  export OPENSSL_CFLAGS="-I$boring/include"
  export OPENSSL_LIBS="$ssl/libssl.a $ssl/libcrypto.a -lc++_shared"
  export LDFLAGS="$LDFLAGS -Wl,-s"
  ./configure --host="$triple" --disable-nls --disable-bittorrent --disable-metalink --disable-shared \
    --disable-rpath --with-openssl --without-gnutls --without-libnettle --without-libgmp \
    --without-libgcrypt --without-sqlite3 --without-libxml2 --without-libexpat \
    --without-libcares --without-libz --without-libssh2
  make -C deps/wslay/lib -j"$jobs"
  make -C src -j"$jobs" aria2c
  install -m 0755 src/aria2c "$out/jniLibs/$1/libnfd_aria2c.so"
)
{
  cat "$work/aria2-1.37.0/COPYING"
  printf '\n\nOpenSSL linking exception (aria2):\n'
  sed -n '20,28p' "$work/aria2-1.37.0/src/main.cc"
  printf '\n\nBoringSSL license:\n'
  cat "$boring/LICENSE"
} > "$out/assets/aria2-LICENSE.txt"
