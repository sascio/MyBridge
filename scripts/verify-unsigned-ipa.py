#!/usr/bin/env python3
"""Independently inspect the downloaded unsigned StreamBridge release IPA."""
import hashlib
import plistlib
import sys
import zipfile
from pathlib import Path, PurePosixPath


def verify(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if archive.testzip() is not None:
            raise ValueError('IPA archive failed CRC validation')
        if len(names) != len(set(names)):
            raise ValueError('IPA contains duplicate entries')
        if any(PurePosixPath(n).is_absolute() or '..' in PurePosixPath(n).parts for n in names):
            raise ValueError('IPA contains unsafe paths')
        apps = [n for n in names if len(PurePosixPath(n).parts) == 3
                and PurePosixPath(n).parts[0] == 'Payload'
                and PurePosixPath(n).parts[1].endswith('.app')
                and PurePosixPath(n).parts[2] == 'Info.plist']
        if len(apps) != 1:
            raise ValueError('expected exactly one app Info.plist')
        if any('_CodeSignature/' in n or n.lower().endswith('embedded.mobileprovision') for n in names):
            raise ValueError('IPA must be unsigned')
        root = apps[0].removesuffix('Info.plist')
        widget = root + 'PlugIns/DownloadsWidgetExtension.appex/'
        for prefix, identifier in ((root, 'com.streambridge.app'),
                                   (widget, 'com.streambridge.app.DownloadsWidgetExtension')):
            info = plistlib.loads(archive.read(prefix + 'Info.plist'))
            if (info.get('CFBundleIdentifier'), info.get('CFBundleShortVersionString'),
                    str(info.get('CFBundleVersion'))) != (identifier, '0.1.19', '119'):
                raise ValueError('bundle identity or version mismatch: ' + identifier)
            executable = info.get('CFBundleExecutable')
            if not isinstance(executable, str) or '/' in executable or not executable or prefix + executable not in names:
                raise ValueError('missing bundle executable: ' + identifier)
    with path.open('rb') as file:
        digest = hashlib.file_digest(file, 'sha256').hexdigest()
    print(f'::notice title=Verified unsigned IPA::{path.name} SHA-256 {digest}; app com.streambridge.app; version 0.1.19 (119); unsigned')


if __name__ == '__main__':
    try:
        verify(Path(sys.argv[1]))
    except (IndexError, OSError, ValueError, KeyError, zipfile.BadZipFile, plistlib.InvalidFileException) as error:
        sys.exit(f'IPA verification failed: {error}')
