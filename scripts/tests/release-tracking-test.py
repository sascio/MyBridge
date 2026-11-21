#!/usr/bin/env python3
"""Exercise production tracking credential gates with synthetic values only."""
import base64
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = yaml.safe_load((ROOT / '.github/workflows/release-draft.yml').read_text())
ANDROID = WORKFLOW['jobs']['android']['steps']
CONFIGURE = next(step['run'] for step in ANDROID if step.get('name') == 'Configure release properties')
GENERATED = next(step['run'] for step in ANDROID if step.get('name') == 'Report generated tracking config presence')
IOS = next(step['run'] for step in WORKFLOW['jobs']['ios']['steps'] if step.get('name') == 'Configure runtime properties')
IOS_GENERATED = next(step['run'] for step in WORKFLOW['jobs']['ios']['steps'] if step.get('name') == 'Report generated tracking config presence')
VALUES = {
    'TRAKT_CLIENT_ID': 'test-trakt-id',
    'TRAKT_CLIENT_SECRET': 'test-trakt-secret',
    'SIMKL_CLIENT_ID': 'test-simkl-id',
    'MDBLIST_CLIENT_ID': 'test-mdblist-id',
}

class TrackingReleaseGateTest(unittest.TestCase):
    def configure(self, values, mdblist='', ios=False):
        with tempfile.TemporaryDirectory() as directory:
            # CI's iOS job runs BSD sed. Adapt only its -i form for these
            # Linux-hosted tests so the rest of the actual workflow runs intact.
            bindir = Path(directory) / 'bin'
            bindir.mkdir()
            sed = bindir / 'sed'
            sed.write_text('#!/bin/sh\nif [ "$1" = "-i" ] && [ "$2" = "" ]; then shift 2; exec /usr/bin/sed -i "$@"; fi\nexec /usr/bin/sed "$@"\n')
            sed.chmod(0o755)
            properties = ''.join(f'{key}={value}\n' for key, value in values.items())
            env = os.environ.copy()
            env.update(NUVIO_LOCAL_PROPERTIES_BASE64=base64.b64encode(properties.encode()).decode(),
                       MDBLIST_CLIENT_ID=mdblist, GITHUB_STEP_SUMMARY=f'{directory}/summary',
                       PATH=f'{bindir}:{env["PATH"]}')
            return subprocess.run(['bash', '-c', IOS if ios else CONFIGURE], cwd=directory, env=env,
                                  capture_output=True, text=True)

    def test_missing_any_required_tracking_config_fails(self):
        for ios in (False, True):
          for name in VALUES:
            with self.subTest(name=name, ios=ios):
                result = self.configure({k: v for k, v in VALUES.items() if k != name}, ios=ios)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(name, result.stdout)

    def test_configured_and_dedicated_mdblist_secret_pass_without_printing_values(self):
        for ios in (False, True):
            for values, mdblist in ((VALUES, ''), ({k: v for k, v in VALUES.items() if k != 'MDBLIST_CLIENT_ID'}, VALUES['MDBLIST_CLIENT_ID'])):
                result = self.configure(values, mdblist, ios=ios)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                for value in VALUES.values():
                    self.assertNotIn(value, result.stdout + result.stderr)

    def test_generated_empty_tracking_config_fails(self):
        self.assertEqual(GENERATED, IOS_GENERATED)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'composeApp/build/generated/runtime-config/kotlin/com/nuvio/app/features'
            for provider, constants in {
                'trakt': {'CLIENT_ID': 'id', 'CLIENT_SECRET': 'secret'},
                'simkl': {'CLIENT_ID': ''},
                'mdblist': {'CLIENT_ID': 'id'},
            }.items():
                target = root / provider / f'{provider.capitalize() if provider != "mdblist" else "MdbList"}Config.kt'
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text('\n'.join(f'const val {key} = "{value}"' for key, value in constants.items()))
            result = subprocess.run(['bash', '-c', GENERATED], cwd=directory,
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('SIMKL', result.stdout)
            # A missing declaration is not a configured credential either.
            (root / 'simkl' / 'SimklConfig.kt').write_text('// missing CLIENT_ID\n')
            result = subprocess.run(['bash', '-c', GENERATED], cwd=directory,
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('SIMKL', result.stdout)

if __name__ == '__main__':
    unittest.main(verbosity=2)
