#!/usr/bin/env python3
"""Verify the selected Enhanced 1.0.0 Media3 binary set for release builds."""
import hashlib
from pathlib import Path
import sys
import zipfile

LIBS = Path(__file__).resolve().parent.parent / 'composeApp/libs'
# SHA-256 from luqmanfadlli/NuvioMobile-Enhanced tag 1.0.0,
# commit 6c2ad7937171965608d05005080b96ca19345379.
EXPECTED = {
    'lib-exoplayer-release.aar': '5b4cf27f273c212b71e1413ec262fd0621f06ff327e6e14d32b7a09e437cf2bd',
    'lib-ui-release.aar': '3ffdb5b0d11f2a1c2d2dab8ca628cd7d3242f14dd482bd70cd2b979007c9c07f',
    'lib-decoder-av1-release.aar': 'e4f929a8132fdd2e9f8f65ad731c5e27f9251eced72aab3138e4520ab0e13e5f',
    'lib-decoder-ffmpeg-release.aar': 'f85c2fb93afb397705afc59c9f8ea2deb3378356052efd93a5885133eba1f055',
    'lib-decoder-mpegh-release.aar': 'e21884ef2f0e88b97a6baa0442d3e63696d64fd6743c30675661501d9079b114',
}
# The unconditional Gradle fileTree includes lib-*.aar. Official's custom
# Media3 modules overlap the Maven dependencies and must not be introduced.
FORBIDDEN = (
    'lib-common-release.aar', 'lib-datasource-release.aar',
    'lib-datasource-okhttp-release.aar', 'lib-extractor-release.aar',
    'lib-exoplayer-hls-release.aar',
)

def main():
    for name, expected in EXPECTED.items():
        path = LIBS / name
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != expected:
            raise ValueError(f'{name}: expected {expected}, got {actual}')
        with zipfile.ZipFile(path) as archive:
            if 'classes.jar' not in archive.namelist():
                raise ValueError(f'{name}: missing classes.jar')
        print(f'{name}: {actual}')
    for name in FORBIDDEN:
        if (LIBS / name).exists():
            raise ValueError(f'Incompatible Official Media3 artifact present: {name}')
    print('Enhanced 1.0.0 Media3 binary set verified.')

if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print(f'Enhanced Media3 verification failed: {exc}', file=sys.stderr)
        sys.exit(1)
