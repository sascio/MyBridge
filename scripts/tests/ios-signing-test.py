#!/usr/bin/env python3
"""Synthetic tests for profile selection and signed-IPA verification; no keys."""
import datetime
import importlib.util
import os
from pathlib import Path
import plistlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('ios_signing', ROOT / 'scripts/ios-signing.py')
mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)
CERT = b'synthetic-distribution-certificate'
TEAM = 'TESTTEAM01'

def make_profile(bundle, uuid='01234567-89AB-CDEF-0123-456789ABCDEF'):
    return {'UUID': uuid, 'TeamIdentifier': [TEAM], 'ApplicationIdentifierPrefix': [TEAM],
            'ExpirationDate': datetime.datetime.utcnow() + datetime.timedelta(days=30),
            'Entitlements': {'application-identifier': TEAM + '.' + bundle,
                             'com.apple.developer.team-identifier': TEAM, 'get-task-allow': False},
            'ProvisionedDevices': ['test-device'], 'DeveloperCertificates': [CERT]}

class SigningTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.profiles = {mod.APP: make_profile(mod.APP),
                         mod.WIDGET: make_profile(mod.WIDGET, '11234567-89AB-CDEF-0123-456789ABCDEF')}
        self.app_profile = self.root / 'app.mobileprovision'; self.widget_profile = self.root / 'widget.mobileprovision'
        self.app_profile.write_bytes(plistlib.dumps(self.profiles[mod.APP]))
        self.widget_profile.write_bytes(plistlib.dumps(self.profiles[mod.WIDGET]))

    def fake_run(self, cmd, **kwargs):
        if cmd[:3] == ['security', 'cms', '-D']:
            return SimpleNamespace(stdout=Path(cmd[cmd.index('-i') + 1]).read_bytes())
        if cmd[0] == 'ditto':
            with zipfile.ZipFile(cmd[3]) as z: z.extractall(cmd[4])
        elif cmd[:2] == ['codesign', '-d']:
            prefix = next(arg.split('=', 1)[1] for arg in cmd if arg.startswith('--extract-certificates='))
            Path(prefix + '0').write_bytes(CERT)
        elif cmd[:2] != ['codesign', '--verify']:
            raise AssertionError(cmd)
        return SimpleNamespace(stdout=b'')

    def args(self):
        return SimpleNamespace(app_profile=str(self.app_profile), widget_profile=str(self.widget_profile),
             source=str(ROOT / 'iosApp/iosApp.xcodeproj'), project=str(self.root / 'iosAppSigning.xcodeproj'),
             export_options=str(self.root / 'export.plist'), installed_list=str(self.root / 'installed'),
             team=TEAM, method='ad-hoc')

    def test_profiles_configure_both_targets_and_export(self):
        with patch.object(mod.subprocess, 'run', side_effect=self.fake_run), patch.dict(os.environ, HOME=str(self.root)):
            args = self.args(); mod.prepare(args)
            data = (Path(args.project) / 'project.pbxproj').read_text()
            self.assertEqual(data.count('PROVISIONING_PROFILE_SPECIFIER = "01234567-89AB-CDEF-0123-456789ABCDEF";'), 2)
            self.assertEqual(data.count('PROVISIONING_PROFILE_SPECIFIER = "11234567-89AB-CDEF-0123-456789ABCDEF";'), 2)
            options = plistlib.loads(Path(args.export_options).read_bytes())
            self.assertEqual(options['provisioningProfiles'][mod.WIDGET], self.profiles[mod.WIDGET]['UUID'])
            self.assertEqual(len(Path(args.installed_list).read_text().splitlines()), 2)

    def test_profile_identity_expiration_and_method_fail_closed(self):
        with patch.object(mod.subprocess, 'run', side_effect=self.fake_run):
            for mutation in ('bundle', 'expired', 'development', 'wrongmethod', 'team'):
                p = make_profile(mod.APP)
                if mutation == 'bundle': p['Entitlements']['application-identifier'] += '.wrong'
                if mutation == 'expired': p['ExpirationDate'] = datetime.datetime(2020, 1, 1)
                if mutation == 'development': p['Entitlements']['get-task-allow'] = True
                if mutation == 'wrongmethod': p['ProvisionedDevices'] = []
                if mutation == 'team': p['TeamIdentifier'] = ['OTHER']
                self.app_profile.write_bytes(plistlib.dumps(p))
                with self.subTest(mutation=mutation), self.assertRaises(SystemExit): mod.profile(self.app_profile, mod.APP, TEAM, 'ad-hoc')

    def make_ipa(self, include_widget_signature=True):
        path = self.root / 'app.ipa'
        app = 'Payload/StreamBridge.app/'
        widget = app + 'PlugIns/DownloadsWidgetExtension.appex/'
        with zipfile.ZipFile(path, 'w') as z:
            for prefix, bundle in ((app, mod.APP), (widget, mod.WIDGET)):
                z.writestr(prefix+'Info.plist', plistlib.dumps({'CFBundleIdentifier': bundle, 'CFBundleShortVersionString': '0.1.20', 'CFBundleVersion': '120'}))
                z.writestr(prefix+'embedded.mobileprovision', plistlib.dumps(self.profiles[bundle]))
                if bundle == mod.APP or include_widget_signature: z.writestr(prefix+'_CodeSignature/CodeResources', b'signature')
        return path

    def test_verify_accepts_matching_signed_bundles_and_rejects_missing_widget_signature(self):
        args = SimpleNamespace(ipa=str(self.make_ipa()), team=TEAM, method='ad-hoc', version='0.1.20', build='120')
        with patch.object(mod.subprocess, 'run', side_effect=self.fake_run):
            mod.verify(args)
            self.make_ipa(include_widget_signature=False)
            with self.assertRaises(SystemExit): mod.verify(args)

    def test_verify_rejects_wrong_certificate_and_failed_codesign(self):
        args = SimpleNamespace(ipa=str(self.make_ipa()), team=TEAM, method='ad-hoc', version='0.1.20', build='120')
        def mismatch(cmd, **kwargs):
            result = self.fake_run(cmd, **kwargs)
            if cmd[:2] == ['codesign', '-d']:
                prefix = next(a.split('=',1)[1] for a in cmd if a.startswith('--extract-certificates='))
                Path(prefix+'0').write_bytes(b'wrong')
            return result
        with patch.object(mod.subprocess, 'run', side_effect=mismatch), self.assertRaises(SystemExit): mod.verify(args)
        def fail(cmd, **kwargs):
            if cmd[:2] == ['codesign', '--verify']: raise subprocess.CalledProcessError(1, cmd)
            return self.fake_run(cmd, **kwargs)
        with patch.object(mod.subprocess, 'run', side_effect=fail), self.assertRaises(subprocess.CalledProcessError): mod.verify(args)

class StoreSignedIpaTest(unittest.TestCase):
    def test_store_source_rejects_unverified_signed_ipa(self):
        spec = importlib.util.spec_from_file_location('store_source', ROOT / 'scripts/update-store-source.py')
        source = importlib.util.module_from_spec(spec); spec.loader.exec_module(source)
        with tempfile.TemporaryDirectory() as temp:
            ipa = Path(temp) / 'test.ipa'
            with zipfile.ZipFile(ipa, 'w') as z:
                app = 'Payload/StreamBridge.app/'
                widget = app + 'PlugIns/DownloadsWidgetExtension.appex/'
                z.writestr(app + 'Info.plist', plistlib.dumps({'CFBundleIdentifier': mod.APP}))
                for prefix in (app, widget):
                    z.writestr(prefix + '_CodeSignature/CodeResources', b'signed')
                    z.writestr(prefix + 'embedded.mobileprovision', b'profile')
            with self.assertRaises(ValueError): source.read_ipa_metadata(ipa)
    def test_store_workflow_only_accepts_unsigned_ipa(self):
        workflow = (ROOT / '.github/workflows/update-store-source.yml').read_text()
        self.assertNotIn('needs: [verify-signed-ipa]', workflow)
        self.assertNotIn('STREAMBRIDGE_IOS_TEAM_ID', workflow)
        self.assertNotIn('--verified-signed', workflow)

if __name__ == '__main__': unittest.main(verbosity=2)
