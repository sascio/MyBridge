#!/usr/bin/env python3
"""Test non-publishing production artifact inspection with synthetic artifacts."""
import hashlib
import os
from pathlib import Path
import plistlib
import subprocess
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / 'audit-preflight-assets.sh'
CERT = '5da621d8e6f5c4ffb7396de3fbf686f3bf61bcec71cccffba9bbbbdef56285fc'

class PreflightAssetsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)
        self.android = self.root / 'android'
        self.apks = self.android / 'androidApp/build/outputs/apk/full/release'
        self.apks.mkdir(parents=True)
        lines = []
        for abi in ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'):
            path = self.apks / f'androidApp-full-{abi}-release.apk'
            data = abi.encode(); path.write_bytes(data)
            lines.append(f'{hashlib.sha256(data).hexdigest()}  {path.relative_to(self.android)}')
        (self.android / 'checksums.sha256').write_text('\n'.join(lines) + '\n')
        self.ios = self.root / 'ios'; self.ios.mkdir()
        self.ipa = self.ios / 'StreamBridge-0.1.19-iOS-unsigned.ipa'
        self.write_ipa()
        tools = self.root / 'sdk/build-tools/35.0.0'; tools.mkdir(parents=True)
        (tools / 'aapt').write_text("#!/bin/sh\necho \"package: name='com.streambridge.app' versionCode='119' versionName='0.1.19'\"\n")
        (tools / 'apksigner').write_text(f'#!/bin/sh\necho "Signer #1 certificate SHA-256 digest: {CERT}"\n')
        for tool in tools.iterdir(): tool.chmod(0o755)

    def write_ipa(self, version='0.1.19', signed=False):
        with zipfile.ZipFile(self.ipa, 'w') as z:
            z.writestr('Payload/StreamBridge.app/Info.plist', plistlib.dumps({
                'CFBundleIdentifier': 'com.streambridge.app', 'CFBundleShortVersionString': version, 'CFBundleVersion': '119'}))
            if signed: z.writestr('Payload/StreamBridge.app/_CodeSignature/CodeResources', b'signed')

    def audit(self):
        env = {**os.environ, 'ANDROID_HOME': str(self.root / 'sdk')}
        return subprocess.run([str(SCRIPT), str(self.android), str(self.ios)], env=env, capture_output=True, text=True)

    def test_valid_artifacts_pass(self):
        result = self.audit(); self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_tampered_apk_and_bad_signer_fail(self):
        apk = next(self.apks.glob('*.apk')); data = apk.read_bytes(); apk.write_bytes(b'tampered')
        self.assertNotEqual(self.audit().returncode, 0)
        apk.write_bytes(data)
        signer = self.root / 'sdk/build-tools/35.0.0/apksigner'
        signer.write_text('#!/bin/sh\necho "Signer #1 certificate SHA-256 digest: deadbeef"\n'); signer.chmod(0o755)
        self.assertNotEqual(self.audit().returncode, 0)

    def test_signed_or_wrong_version_ipa_fails(self):
        self.write_ipa(signed=True); self.assertNotEqual(self.audit().returncode, 0)
        self.write_ipa(version='0.1.18'); self.assertNotEqual(self.audit().returncode, 0)

if __name__ == '__main__': unittest.main(verbosity=2)
