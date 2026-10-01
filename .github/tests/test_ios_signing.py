from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timedelta, timezone
from pathlib import Path
import sys
import os
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_ios_signing import validate_profile, prepare, APP_ID, WIDGET_ID


class IosSigningProfileTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026,10,1,tzinfo=timezone.utc)
        self.team = 'TESTTEAM01'
        self.profile = {
            'TeamIdentifier':[self.team],
            'UUID':'01234567-89ab-cdef-0123-456789abcdef',
            'ExpirationDate':self.now + timedelta(days=30),
            'ProvisionedDevices':['test-device-identifier'],
            'DeveloperCertificates':[b'public certificate fixture, not a real certificate'],
            'Entitlements':{'application-identifier':self.team+'.'+APP_ID,
                            'com.apple.developer.team-identifier':self.team, 'get-task-allow':False},
        }

    def validate(self, profile=None, bundle=APP_ID, method='release-testing'):
        return validate_profile(profile or self.profile, self.team, bundle, method, self.now)

    def test_missing_secrets_fail_at_signing_stage_before_tools_or_files(self):
        with patch.dict(os.environ, {}, clear=True), patch('prepare_ios_signing.run') as apple_tool:
            with self.assertRaisesRegex(ValueError, 'Missing required GitHub Secrets at iOS signing stage'):
                prepare(Path('unused-signing-directory'), Path('unused-output'))
            apple_tool.assert_not_called()

    def test_ad_hoc_app_profile_is_validated(self):
        self.assertEqual(self.validate(), self.profile['UUID'])

    def test_widget_needs_its_own_explicit_profile(self):
        with self.assertRaises(ValueError):
            self.validate(bundle=WIDGET_ID)
        self.profile['Entitlements']['application-identifier'] = self.team+'.'+WIDGET_ID
        self.assertEqual(self.validate(bundle=WIDGET_ID), self.profile['UUID'])

    def test_wrong_team_upstream_bundle_and_wildcard_are_rejected(self):
        for bundle in ('com.nuvio.media','*','com.streambridge.other'):
            profile=deepcopy(self.profile)
            profile['Entitlements']['application-identifier']=self.team+'.'+bundle
            with self.assertRaises(ValueError):
                self.validate(profile)
        self.profile['TeamIdentifier']=['OTHERTEAM1']
        with self.assertRaises(ValueError):
            self.validate()

    def test_development_expired_and_malformed_profiles_are_rejected(self):
        for key,value in [('ExpirationDate',self.now),('UUID','invalid'),('DeveloperCertificates',[])]:
            profile=deepcopy(self.profile)
            profile[key]=value
            with self.assertRaises(ValueError):
                self.validate(profile)
        self.profile['Entitlements']['get-task-allow']=True
        with self.assertRaises(ValueError):
            self.validate()

    def test_ad_hoc_requires_registered_devices(self):
        self.profile['ProvisionedDevices']=[]
        with self.assertRaises(ValueError):
            self.validate()

    def test_store_and_enterprise_require_matching_profile_types(self):
        with self.assertRaises(ValueError):
            self.validate(method='app-store-connect')
        self.profile['ProvisionedDevices']=[]
        self.validate(method='app-store-connect')
        with self.assertRaises(ValueError):
            self.validate(method='enterprise')
        self.profile['ProvisionsAllDevices']=True
        self.validate(method='enterprise')
        with self.assertRaises(ValueError):
            self.validate(method='release-testing')


if __name__ == '__main__':
    unittest.main()
