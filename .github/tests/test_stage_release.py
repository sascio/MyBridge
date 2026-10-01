from __future__ import annotations

import hashlib
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from stage_release import stage


class ReleaseAssetTests(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root=Path(temporary.name)
        self.source=self.root/'source'
        self.output=self.root/'output'
        self.version='0.5.5-beta'
        self.expected={
            'Android':[f'StreamBridge-{self.version}-{abi}.apk' for abi in ('arm64-v8a','armeabi-v7a','x86','x86_64')]+[f'StreamBridge-{self.version}-full.aab'],
            'iOS':[f'StreamBridge-{self.version}.ipa',f'StreamBridge-{self.version}.xcarchive.zip'],
        }
        for platform,names in self.expected.items():
            directory=self.source/f'StreamBridge-production-{platform}'
            directory.mkdir(parents=True)
            for name in names:
                (directory/name).write_bytes(b'unit-test placeholder, not a real app package')
            self.manifest(platform)

    def manifest(self,platform):
        directory=self.source/f'StreamBridge-production-{platform}'
        (directory/'checksums.sha256').write_text(''.join(f'{hashlib.sha256((directory/name).read_bytes()).hexdigest()}  {name}\n' for name in self.expected[platform]))

    def test_both_platforms_stage_exact_streambridge_names_and_one_manifest(self):
        assets=stage(self.source,self.output,self.version,'both')
        self.assertEqual({p.name for p in assets},set(self.expected['Android']+self.expected['iOS']+['checksums.sha256']))
        self.assertEqual(len((self.output/'checksums.sha256').read_text().splitlines()),7)

    def test_android_only_does_not_need_apple_artifacts(self):
        assets=stage(self.source,self.output,self.version,'android')
        self.assertFalse(any(p.suffix=='.ipa' for p in assets))

    def test_tampering_missing_platform_and_wrong_version_fail_before_staging(self):
        directory=self.source/'StreamBridge-production-iOS'
        (directory/self.expected['iOS'][0]).write_bytes(b'tampered')
        with self.assertRaises(ValueError):
            stage(self.source,self.output,self.version,'both')
        self.assertFalse(self.output.exists())
        with self.assertRaises(ValueError):
            stage(self.source,self.output,'0.5.6-beta','both')

    def test_debug_unsigned_upstream_or_unexpected_assets_are_rejected(self):
        for invalid in ('StreamBridge-0.5.5-beta-unsigned.ipa','Nuvio-0.5.5-Enhanced.ipa','StreamBridge-0.5.5-beta-debug-not-production.apk'):
            directory=self.source/'StreamBridge-production-iOS'
            (directory/'checksums.sha256').write_text('0'*64+'  '+invalid+'\n')
            with self.assertRaises(ValueError):
                stage(self.source,self.output,self.version,'ios')

    def test_path_traversal_and_duplicate_manifest_lines_are_rejected(self):
        directory=self.source/'StreamBridge-production-iOS'
        (directory/'checksums.sha256').write_text('0'*64+'  ../outside.ipa\n')
        with self.assertRaises(ValueError):
            stage(self.source,self.output,self.version,'ios')
        self.manifest('iOS')
        with (directory/'checksums.sha256').open('a') as output:
            output.write((directory/'checksums.sha256').read_text().splitlines()[0]+'\n')
        with self.assertRaises(ValueError):
            stage(self.source,self.output,self.version,'ios')


if __name__ == '__main__':
    unittest.main()
