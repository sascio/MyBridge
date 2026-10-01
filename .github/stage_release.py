#!/usr/bin/env python3
"""Stage coordinated, checksum-verified signed artifacts; never publish CI packages."""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import re
import shutil

from streambridge_release import ROOT, check_release


def stage(source: Path, destination: Path, version: str, target: str) -> list[Path]:
    if target not in {'both','android','ios'}:
        raise ValueError('Unknown release platform selection')
    platforms = {'Android','iOS'} if target == 'both' else {'Android' if target == 'android' else 'iOS'}
    expected = {
        'Android': {f'StreamBridge-{version}-{abi}.apk' for abi in ('arm64-v8a','armeabi-v7a','x86','x86_64')} |
                   {f'StreamBridge-{version}-full.aab'},
        'iOS': {f'StreamBridge-{version}.ipa',f'StreamBridge-{version}.xcarchive.zip'},
    }
    validated = []
    for platform in sorted(platforms):
        directory = source / f'StreamBridge-production-{platform}'
        checksums = {}
        for line in (directory/'checksums.sha256').read_text().splitlines():
            if not line.strip():
                continue
            digest, filename = line.split('  ',1)
            if not re.fullmatch('[0-9a-f]{64}',digest) or Path(filename).name != filename:
                raise ValueError('Malformed artifact checksum manifest')
            if filename in checksums:
                raise ValueError('Duplicate checksum filename')
            checksums[filename] = digest
        if set(checksums) != expected[platform]:
            raise ValueError(f'{platform} production artifact set is incomplete or contains unexpected packages')
        for filename in sorted(expected[platform]):
            path=directory/filename
            if hashlib.sha256(path.read_bytes()).hexdigest() != checksums[filename]:
                raise ValueError(f'Artifact checksum mismatch: {filename}')
            validated.append(path)
    destination.mkdir(parents=True,exist_ok=True)
    assets=[]
    for path in validated:
        result=destination/path.name
        shutil.copyfile(path,result)
        assets.append(result)
    manifest=destination/'checksums.sha256'
    manifest.write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in sorted(assets)))
    return [*assets,manifest]


if __name__ == '__main__':
    assets=stage(ROOT/'build/release-inputs',ROOT/'build/release-assets',check_release().version,os.environ['RELEASE_TARGET'])
    print('\n'.join(path.name for path in assets))
