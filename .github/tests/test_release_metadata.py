from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from streambridge_release import Release, check_release, read_release, properties


class ReleaseMetadataTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'iosApp/Configuration').mkdir(parents=True)
        self.data = {
            'STREAMBRIDGE_VERSION_NAME': '0.5.5-beta',
            'STREAMBRIDGE_VERSION_CODE': '108',
            'STREAMBRIDGE_IOS_MARKETING_VERSION': '0.5.5',
            'NUVIO_UPSTREAM_COMMIT': '5c6028f24d3017f27fc4aeb0b6234b18fcfd2ee8',
            'NUVIO_ENHANCED_COMMIT': '8b3bd867e172757c9fd7373da301e6489914f3fc',
        }
        self.write()

    def write(self):
        (self.root / 'streambridge.version.properties').write_text(''.join(f'{k}={v}\n' for k,v in self.data.items()))
        (self.root / 'iosApp/Configuration/Version.xcconfig').write_text(Release('0.5.5-beta', 108, '0.5.5').xcconfig())

    def test_beta_uses_numeric_apple_version_and_shared_build(self):
        release = check_release(self.root)
        self.assertEqual(release.outputs(), {'version':'0.5.5-beta','build':'108','ios_version':'0.5.5','tag':'v0.5.5-beta','prerelease':'true'})

    def test_stable_is_not_a_prerelease(self):
        self.assertFalse(Release('0.5.5',109,'0.5.5').prerelease)

    def test_missing_streambridge_version_never_falls_back_to_upstream(self):
        del self.data['STREAMBRIDGE_VERSION_NAME']
        self.write()
        with self.assertRaises(KeyError):
            read_release(self.root)

    def test_rejects_non_numeric_or_mismatched_apple_version(self):
        for value in ('0.5.5-beta','0.5.4'):
            self.data['STREAMBRIDGE_IOS_MARKETING_VERSION'] = value
            self.write()
            with self.assertRaises(ValueError):
                read_release(self.root)

    def test_rejects_invalid_version_build_and_unpinned_source(self):
        for key,value in [('STREAMBRIDGE_VERSION_NAME','../0.5.5'),('STREAMBRIDGE_VERSION_CODE','0'),('STREAMBRIDGE_VERSION_CODE','2147483647'),('NUVIO_ENHANCED_COMMIT','enhanced')]:
            before = self.data[key]
            self.data[key] = value
            self.write()
            with self.assertRaises(ValueError):
                read_release(self.root)
            self.data[key] = before

    def test_duplicate_property_is_rejected(self):
        path=self.root/'streambridge.version.properties'
        with path.open('a') as output:
            output.write('STREAMBRIDGE_VERSION_CODE=109\n')
        with self.assertRaises(ValueError):
            properties(path)

    def test_stale_xcode_metadata_is_rejected(self):
        (self.root/'iosApp/Configuration/Version.xcconfig').write_text('MARKETING_VERSION=0.5.4\n')
        with self.assertRaises(ValueError):
            check_release(self.root)


if __name__ == '__main__':
    unittest.main()
