#!/usr/bin/env python3
"""Print DEX superclass chains for host Activity classes in a built APK.

This intentionally uses only the DEX tables that are needed for class and
superclass resolution. It keeps the APK/R8 audit reproducible on a GitHub
runner without requiring a decompiler or an emulator.
"""

from __future__ import annotations

import argparse
import re
import struct
import zipfile
from pathlib import Path


def uleb128(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if byte & 0x80 == 0:
            return value, offset
        shift += 7
        if shift > 35:
            raise ValueError("invalid uleb128")


def dex_strings(data: bytes) -> list[str]:
    size, offset = struct.unpack_from("<2I", data, 0x38)
    values = []
    for index in range(size):
        string_offset = struct.unpack_from("<I", data, offset + index * 4)[0]
        _, cursor = uleb128(data, string_offset)
        end = data.index(b"\0", cursor)
        values.append(data[cursor:end].decode("utf-8", errors="replace"))
    return values


def dex_classes(data: bytes) -> dict[str, str | None]:
    strings = dex_strings(data)
    type_count, type_offset = struct.unpack_from("<2I", data, 0x40)
    types = [
        strings[struct.unpack_from("<I", data, type_offset + i * 4)[0]]
        for i in range(type_count)
    ]
    class_count, class_offset = struct.unpack_from("<2I", data, 0x60)
    classes: dict[str, str | None] = {}
    for i in range(class_count):
        class_idx, _, super_idx, *_ = struct.unpack_from(
            "<8I", data, class_offset + i * 32
        )
        descriptor = types[class_idx]
        superclass = None if super_idx == 0xFFFFFFFF else types[super_idx]
        classes[descriptor] = superclass
    return classes


def class_name(value: str) -> str:
    if value.startswith("L") and value.endswith(";"):
        return value[1:-1].replace("/", ".")
    return value


def descriptor(value: str) -> str:
    value = value.strip()
    if value.startswith("L") and value.endswith(";"):
        return value
    return "L" + value.replace(".", "/") + ";"


def mapped_names(mapping_file: Path | None) -> dict[str, str]:
    if mapping_file is None:
        return {}
    names: dict[str, str] = {}
    pattern = re.compile(r"^([^ ]+) -> ([^:]+):$")
    for line in mapping_file.read_text(errors="replace").splitlines():
        match = pattern.match(line)
        if match:
            names[match.group(1)] = match.group(2)
    return names


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--mapping", type=Path)
    parser.add_argument("--class", dest="classes", action="append", required=True)
    args = parser.parse_args()

    all_classes: dict[str, str | None] = {}
    with zipfile.ZipFile(args.apk) as apk:
        dex_names = sorted(
            name
            for name in apk.namelist()
            if re.fullmatch(r"classes(?:\d+)?\.dex", Path(name).name)
        )
        if not dex_names:
            raise SystemExit(f"no DEX files found in {args.apk}")
        for name in dex_names:
            all_classes.update(dex_classes(apk.read(name)))

    mapping = mapped_names(args.mapping)
    print(f"APK: {args.apk}")
    print(f"DEX files: {len(dex_names)}; class definitions: {len(all_classes)}")
    for requested in args.classes:
        mapped = mapping.get(requested, requested)
        current = descriptor(mapped)
        chain = []
        seen = set()
        while current and current not in seen:
            seen.add(current)
            chain.append(class_name(current))
            current = all_classes.get(current)
        if not chain or chain[0] != class_name(descriptor(mapped)):
            raise SystemExit(
                f"class {requested} (mapped to {mapped}) is absent from the merged APK"
            )
        print(f"{requested} -> {' -> '.join(chain)}")
        if "androidx.appcompat.app.AppCompatActivity" not in chain:
            raise SystemExit(
                f"{requested} does not resolve to AppCompatActivity: {' -> '.join(chain)}"
            )


if __name__ == "__main__":
    main()
