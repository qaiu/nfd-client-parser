# Embedded Android runtime

The NFD launcher and client are licensed under MIT. No Termux application, terminal
emulator, shell or package manager is included.

- OpenJDK 21.0.13.1 for Android is GPLv2 with the Classpath Exception.
  [OpenJDK source](https://github.com/openjdk/jdk21u/tree/jdk-21.0.13.1-ga) and
  [Termux build scripts and patches](https://github.com/termux/termux-packages/tree/c7002d8f9394e60ac441cf9d8977471a2c6b85ea/packages/openjdk-21)
- `libandroid-shmem` is BSD-3-Clause. [Source](https://github.com/termux/libandroid-shmem/tree/v0.7)
- `libandroid-spawn` is BSD-2-Clause.
  [Source](https://github.com/termux/termux-packages/tree/c7002d8f9394e60ac441cf9d8977471a2c6b85ea/packages/libandroid-spawn)
- LLVM libc++ and zlib carried by the Termux packages retain their own licenses.
  [libc++ recipe](https://github.com/termux/termux-packages/tree/c7002d8f9394e60ac441cf9d8977471a2c6b85ea/packages/libc%2B%2B) ·
  [zlib source](https://github.com/madler/zlib/tree/v1.4)

- aria2 1.37.0 is a separate executable licensed under GPLv2 or later with an
  OpenSSL linking exception. The bundled build includes a patch disabling its RPC server.
  [aria2 source](https://github.com/aria2/aria2/tree/release-1.37.0)
- BoringSSL retains its upstream licenses, including Apache-2.0 and ISC.
  [BoringSSL source](https://github.com/google/boringssl/tree/0.20261105.0)
- XZ for Java is 0BSD. [XZ for Java source](https://tukaani.org/xz/java.html)

`packages.lock` pins the input binaries. `build.sh` is the script used to link the
runtime image and to build the executable launcher.
