#!/usr/bin/env python3
"""Check ABI, runtime payload, parser jar and APK + first-run extraction budget."""
from io import BytesIO
from pathlib import Path
import lzma
import struct
import sys
from zipfile import ZipFile


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def check_native(archive, names, abi):
    machine = {"arm64-v8a": 183, "x86_64": 62}[abi]
    for name in names:
        with archive.open(name) as binary:
            header = binary.read(64)
            if header[:4] != b"\x7fELF":
                require(".so" not in Path(name).name, f"{name}: invalid native library")
                continue
            require(header[4:6] == b"\x02\x01" and struct.unpack_from("<H", header, 18)[0] == machine,
                    f"{name}: expected a 64-bit {abi} ELF binary")
            start = struct.unpack_from("<Q", header, 32)[0]
            size, count = struct.unpack_from("<HH", header, 54)
            binary.seek(start)
            for _ in range(count):
                segment = binary.read(size)
                if struct.unpack_from("<I", segment)[0] == 1:  # PT_LOAD
                    offset, address = struct.unpack_from("<QQ", segment, 8)
                    alignment = struct.unpack_from("<Q", segment, 48)[0]
                    require(alignment >= 16384 and (offset - address) % 16384 == 0,
                            f"{name}: LOAD segment is not aligned to 16 KiB")


def main(apk, abi, parser):
    with ZipFile(apk) as archive:
        names = set(archive.namelist())
        native = [entry for entry in archive.infolist() if entry.filename.startswith("lib/") and not entry.is_dir()]
        require({entry.filename.split("/")[1] for entry in native} == {abi},
                "APK contains the wrong ABI or more than one ABI")
        require(f"lib/{abi}/libnfd_runtime.so" in names, "Native launcher is missing")
        require(f"lib/{abi}/libnfd_aria2c.so" in names and "assets/aria2-LICENSE.txt" in names,
                "aria2c executable or license is missing")
        require("assets/termux/nfd-termux-daemon.tgz" not in names, "Legacy daemon bundle would duplicate the parser")
        check_native(archive, [entry.filename for entry in native], abi)
        with ZipFile(BytesIO(lzma.decompress(archive.read(f"assets/runtime-{abi}.zip.xz")))) as image:
            require({"lib/modules", "lib/security/cacerts", "lib/security/aria2-ca.pem", "lib/server/libjvm.so",
                     "legal/THIRD_PARTY.md"}
                    <= set(image.namelist()), "OpenJDK image is incomplete")
            extracted = sum(entry.file_size for entry in image.infolist())
            check_native(image, [name for name in image.namelist() if name.startswith("lib/")], abi)
        with ZipFile(BytesIO(archive.read("assets/daemon.zip"))) as daemon:
            require({f"lib/parser-{parser}.jar", "lib/nfd-termux-daemon.jar", "conf/logback.xml"}
                    <= set(daemon.namelist()), "Daemon or pinned parser is missing")
            extracted += sum(entry.file_size for entry in daemon.infolist())
        # Legacy JNI packaging extracts the launcher and Compose libraries. Reserve the
        # uncompressed DEX size for Android's app-code artifacts as part of the budget.
        installed = apk.stat().st_size + extracted + sum(entry.file_size for entry in native)
        installed += sum(entry.file_size for entry in archive.infolist() if entry.filename.endswith(".dex"))
    print(f"{abi}: APK {apk.stat().st_size / 1e6:.2f} MB, "
          f"first-run estimate {installed / 1e6:.2f} MB including DEX reserve")
    require(installed <= 100_000_000, "APK + runtime exceeds the 100 MB installation budget")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(f"usage: {sys.argv[0]} <release-apk> <abi> <parser-version>")
    main(Path(sys.argv[1]), sys.argv[2], sys.argv[3])
