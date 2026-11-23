#!/usr/bin/env python3
"""Synthetic archive checks for unsigned release verification."""
import importlib.util
from pathlib import Path
import plistlib
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('unsigned_ipa', Path(__file__).resolve().parents[1] / 'verify-unsigned-ipa.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class UnsignedIpaTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / 'test.ipa'
    def write(self, signed=False, widget=True, version='0.1.21'):
        with zipfile.ZipFile(self.path, 'w') as z:
            for root, identifier in [('Payload/StreamBridge.app/', 'com.streambridge.app'),
                                     ('Payload/StreamBridge.app/PlugIns/DownloadsWidgetExtension.appex/', 'com.streambridge.app.DownloadsWidgetExtension')][:2 if widget else 1]:
                z.writestr(root + 'Info.plist', plistlib.dumps({'CFBundleIdentifier': identifier, 'CFBundleShortVersionString': version, 'CFBundleVersion': '121', 'CFBundleExecutable': 'bin'}))
                z.writestr(root + 'bin', b'executable')
            if signed: z.writestr('Payload/StreamBridge.app/_CodeSignature/CodeResources', b'signed')
    def test_unsigned(self):
        self.write(); module.verify(self.path)
    def test_signed_missing_widget_wrong_version(self):
        for kwargs in ({'signed': True}, {'widget': False}, {'version': '0.1.18'}):
            self.write(**kwargs)
            with self.assertRaises((ValueError, KeyError)): module.verify(self.path)

if __name__ == '__main__': unittest.main()
