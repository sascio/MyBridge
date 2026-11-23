#!/usr/bin/env python3
"""Prepare and independently validate StreamBridge's two-profile signed IPA.

Only profile metadata is printed. macOS security and codesign are required.
"""
import argparse
import datetime
import hashlib
import os
import posixpath
import stat
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import tempfile
import zipfile

APP = 'com.streambridge.app'
WIDGET = APP + '.DownloadsWidgetExtension'


def fail(message):
    raise SystemExit(message)


def profile(path, bundle, team, method):
    raw = subprocess.run(['security', 'cms', '-D', '-i', str(path)], check=True, stdout=subprocess.PIPE).stdout
    p = plistlib.loads(raw)
    ent = p.get('Entitlements', {})
    appid = ent.get('application-identifier', '')
    prefixes = p.get('ApplicationIdentifierPrefix', [])
    if team not in p.get('TeamIdentifier', []) or ent.get('com.apple.developer.team-identifier') != team:
        fail('Profile team mismatch: ' + bundle)
    if not any(appid == prefix + '.' + bundle for prefix in prefixes):
        fail('Profile must have an explicit matching application identifier: ' + bundle)
    if p.get('ExpirationDate', datetime.datetime.min) <= datetime.datetime.utcnow():
        fail('Expired provisioning profile: ' + bundle)
    if ent.get('get-task-allow') is not False:
        fail('Production profile must disable get-task-allow: ' + bundle)
    if method == 'ad-hoc' and (not p.get('ProvisionedDevices') or p.get('ProvisionsAllDevices')):
        fail('Expected an ad-hoc profile with registered devices: ' + bundle)
    if method == 'enterprise' and not p.get('ProvisionsAllDevices'):
        fail('Expected an enterprise distribution profile: ' + bundle)
    if not re.fullmatch(r'[0-9A-Fa-f-]{36}', p.get('UUID', '')) or not p.get('DeveloperCertificates'):
        fail('Invalid profile UUID or missing distribution certificates: ' + bundle)
    return p


def prepare(args):
    paths = {APP: Path(args.app_profile), WIDGET: Path(args.widget_profile)}
    profiles = {bundle: profile(path, bundle, args.team, args.method) for bundle, path in paths.items()}
    certs = [set(p['DeveloperCertificates']) for p in profiles.values()]
    if not certs[0].intersection(certs[1]):
        fail('Application and widget profiles do not share a signing certificate')
    destination = Path(args.project)
    if destination.exists(): fail('Temporary signing project already exists')
    shutil.copytree(args.source, destination)
    pbx = destination / 'project.pbxproj'
    data = pbx.read_text()
    for bundle, p in profiles.items():
        needle = 'PRODUCT_BUNDLE_IDENTIFIER = ' + bundle + ';'
        if data.count(needle) != 2: fail('Unexpected Xcode configuration for ' + bundle)
        data = data.replace(needle, 'PROVISIONING_PROFILE_SPECIFIER = "' + p['UUID'] + '";\n\t\t\t\t' + needle)
    pbx.write_text(data)
    for scheme in destination.glob('xcshareddata/xcschemes/*.xcscheme'):
        text = scheme.read_text().replace('container:iosApp.xcodeproj', 'container:' + destination.name)
        scheme.write_text(text)
    options = {'method': args.method, 'teamID': args.team, 'signingStyle': 'manual', 'destination': 'export',
               'stripSwiftSymbols': True, 'provisioningProfiles': {b: p['UUID'] for b, p in profiles.items()}}
    Path(args.export_options).write_bytes(plistlib.dumps(options))
    install = Path.home() / 'Library/MobileDevice/Provisioning Profiles'
    install.mkdir(parents=True, exist_ok=True)
    for bundle, p in profiles.items():
        dst = install / (p['UUID'] + '.mobileprovision')
        raw = paths[bundle].read_bytes()
        if dst.exists() and dst.read_bytes() != raw: fail('Refusing to overwrite a different installed profile')
        if not dst.exists():
            dst.write_bytes(raw)
            with open(args.installed_list, 'a') as f: f.write(str(dst) + '\n')
    print('Prepared separate app and widget profiles for', args.method)


def verify(args):
    with tempfile.TemporaryDirectory(prefix='streambridge-ipa-verify-') as temp:
        root = Path(temp)
        with zipfile.ZipFile(args.ipa) as z:
            names = z.namelist()
            if len(names) != len(set(names)) or z.testzip(): fail('Invalid IPA archive')
            links = set()
            for entry in z.infolist():
                n = entry.filename
                path = Path(n)
                if path.is_absolute() or '..' in path.parts or '\\' in n: fail('Unsafe IPA path')
                if stat.S_ISLNK(entry.external_attr >> 16):
                    target = z.read(entry).decode('utf-8')
                    resolved = posixpath.normpath(posixpath.join(posixpath.dirname(n), target))
                    if target.startswith('/') or resolved == '..' or resolved.startswith('../'):
                        fail('Unsafe IPA symlink')
                    links.add(n.rstrip('/'))
            for n in names:
                if any(n.startswith(link + '/') for link in links): fail('IPA writes through a symlink')
            apps = [n for n in names if re.fullmatch(r'Payload/[^/]+\.app/Info\.plist', n)]
            if len(apps) != 1: fail('Expected exactly one application')
        # ditto preserves Mach-O executable permissions and framework symlinks.
        subprocess.run(['ditto', '-x', '-k', str(args.ipa), str(root)], check=True)
        app = root / Path(apps[0]).parent
        widget = app / 'PlugIns/DownloadsWidgetExtension.appex'
        app_build = None
        for bundle, path in ((APP, app), (WIDGET, widget)):
            info = plistlib.loads((path / 'Info.plist').read_bytes())
            build = str(info.get('CFBundleVersion', ''))
            if info.get('CFBundleIdentifier') != bundle or info.get('CFBundleShortVersionString') != args.version or not build.isdigit() or (args.build and build != args.build):
                fail('Bundle identity or version mismatch: ' + bundle)
            if app_build is None: app_build = build
            if build != app_build: fail('App and widget build numbers differ')
            embedded = path / 'embedded.mobileprovision'
            if not embedded.is_file() or not (path / '_CodeSignature/CodeResources').is_file():
                fail('Missing embedded provisioning profile or signature: ' + bundle)
            p = profile(embedded, bundle, args.team, args.method)
            subprocess.run(['codesign', '--verify', '--strict', '--deep', '--verbose=2', str(path)], check=True)
            prefix = str(root / ('cert-' + ('app-' if bundle == APP else 'widget-')))
            subprocess.run(['codesign', '-d', '--extract-certificates=' + prefix, str(path)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            cert = Path(prefix + '0').read_bytes()
            if cert not in p['DeveloperCertificates']: fail('Signing certificate not authorized by profile: ' + bundle)
            print(bundle, 'certificate SHA-256', hashlib.sha256(cert).hexdigest())
        digest = hashlib.sha256()
        with open(args.ipa, 'rb') as f:
            for chunk in iter(lambda: f.read(1024 * 1024), b''): digest.update(chunk)
        print('IPA SHA-256', digest.hexdigest())


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='action', required=True)
    prep = sub.add_parser('prepare')
    for opt in ('app-profile', 'widget-profile', 'source', 'project', 'export-options', 'installed-list'):
        prep.add_argument('--' + opt, required=True)
    check = sub.add_parser('verify')
    check.add_argument('--ipa', required=True)
    check.add_argument('--version', default='0.1.21')
    check.add_argument('--build')
    for p in (prep, check):
        p.add_argument('--team', required=True)
        p.add_argument('--method', required=True, choices=['ad-hoc', 'enterprise'])
    args = parser.parse_args()
    (prepare if args.action == 'prepare' else verify)(args)

if __name__ == '__main__': main()
