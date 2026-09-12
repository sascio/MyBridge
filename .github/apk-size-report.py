#!/usr/bin/env python3
"""Print APK on-disk size and uncompressed ZIP contents. Not a substitute for aapt."""

from __future__ import annotations

import collections
import sys
import zipfile
from pathlib import Path


def mb(n: int) -> str:
    return f"{n / (1024 * 1024):.2f} MiB"


def analyze(apk: Path) -> None:
    on_disk = apk.stat().st_size
    z = zipfile.ZipFile(apk)
    infos = z.infolist()
    buckets: collections.Counter[str] = collections.Counter()
    so_rows: list[tuple[int, str]] = []
    abis: set[str] = set()
    entries: list[tuple[int, int, str]] = []
    for info in infos:
        name = info.filename
        usize = info.file_size
        csize = info.compress_size
        entries.append((usize, csize, name))
        parts = name.split("/")
        if parts[0] == "lib" and len(parts) >= 3:
            abi = parts[1]
            abis.add(abi)
            buckets[f"lib/{abi}"] += usize
            if name.endswith(".so"):
                so_rows.append((usize, name))
        elif name.startswith("classes") and name.endswith(".dex"):
            buckets["dex"] += usize
        elif name.startswith("assets/"):
            buckets["assets"] += usize
        elif name.startswith("res/"):
            buckets["res"] += usize
        elif name == "resources.arsc":
            buckets["resources.arsc"] += usize
        elif name.startswith("META-INF/"):
            buckets["META-INF"] += usize
        else:
            buckets["other"] += usize
    uncompressed = sum(i.file_size for i in infos)
    print(f"APK {apk.name}")
    print(f"  on-disk (zip)     {on_disk:>12}  {mb(on_disk)}")
    print(f"  uncompressed sum  {uncompressed:>12}  {mb(uncompressed)}")
    print(f"  ABIs packaged     {', '.join(sorted(abis)) or '(none)'}")
    print("  buckets (uncompressed):")
    for key, total in sorted(buckets.items(), key=lambda kv: kv[1], reverse=True):
        print(f"    {key:<22} {total:>12}  {mb(total)}")
    print("  native .so:")
    for usize, name in sorted(so_rows, reverse=True):
        print(f"    {usize:>12}  {mb(usize):>10}  {name}")
    print("  top 20 entries (uncompressed):")
    for usize, csize, name in sorted(entries, reverse=True)[:20]:
        print(f"    {usize:>12}  c={csize:>10}  {name}")
    print()


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: apk-size-report.py <apk-or-dir>...", file=sys.stderr)
        return 2
    apks: list[Path] = []
    for raw in sys.argv[1:]:
        path = Path(raw)
        if path.is_dir():
            apks.extend(sorted(path.glob("*.apk")))
        else:
            apks.append(path)
    if not apks:
        print("no APKs found", file=sys.stderr)
        return 1
    for apk in apks:
        analyze(apk)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
