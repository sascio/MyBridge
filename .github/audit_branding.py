#!/usr/bin/env python3
"""Inventory Nuvio-like references without deleting technical or legal identities.

The TSV identifies every tracked matching path/line and count, not duplicated
source/credential excerpts. XML visible strings are additionally guarded by the
reviewed exception list. Generated inventory files are excluded from self-scan.
"""
from __future__ import annotations

from collections import Counter
import csv
import hashlib
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

from streambridge_release import ROOT
from verify_update_track import EXTERNAL_STRINGS

BRAND = re.compile('nuvio',re.I)
EXCLUDED = {'docs/branding-occurrences-0.5.5.tsv','docs/BRANDING-AUDIT-0.5.5.md', 'docs/UPDATE-REPORT-0.5.5.md'}


def category(path: str, text: str, resource_key: str | None = None) -> str:
    if path.startswith('MPVKit/'):
        return 'pinned-third-party-source'
    if path.startswith('legacy/'):
        return 'unbuilt-historical-compatibility'
    if resource_key is not None:
        if resource_key not in EXTERNAL_STRINGS:
            raise ValueError(f'Unreviewed visible branding: {path}: {resource_key}')
        return 'legal-credit' if re.search('credits|licenses|based_on',resource_key) else 'external-service-or-operator'
    if path.startswith('docs/') or Path(path).suffix == '.md':
        return 'source-audit-documentation'
    if Path(path).name in {'LICENSE','NOTICE','COPYING'} or re.search('copyright|license|GPL',text,re.I):
        return 'legal-credit'
    if path.startswith(('.github/','scripts/','gradle/')) or Path(path).suffix in {'.kts','.properties','.toml','.xcconfig','.pbxproj'}:
        return 'build-dependency-source-pin-or-secret-name'
    if 'Test/' in path or 'Test/kotlin' in path or '/androidTest/' in path or '/debug/' in path:
        return 'test-fixture-or-compatibility-contract'
    if re.search('nuvio[-_. ]?engine|NuvioEngine',text,re.I):
        return 'dependency-component-or-its-data'
    if re.search('https?://|well-known/nuvio|Nuvio Sync|Nuvio watch progress|NuvioMembership|storageId',text):
        return 'external-service-or-protocol'
    if re.search('nuvio://|"nuvio"|nuvio-mobile-|nuvio-download',text):
        return 'callback-protocol-or-existing-data'
    return 'technical-namespace-symbol-notification-or-diagnostic'


def inventory() -> tuple[list[list], Counter]:
    names=subprocess.check_output(['git','ls-files','--recurse-submodules','-z'],cwd=ROOT).decode().split('\0')
    rows=[]
    for name in sorted(filter(None,names)):
        if name in EXCLUDED:
            continue
        path=ROOT/name
        if not path.is_file():
            continue
        path_matches=len(BRAND.findall(name))
        if path_matches:
            rows.append([name,0,path_matches,category(name,name),'retain reviewed technical/source path'])
        raw=path.read_bytes()
        if b'\0' in raw[:4096]:
            continue
        try:
            text=raw.decode('utf-8')
        except UnicodeDecodeError:
            continue
        resource_categories={}
        if path.suffix=='.xml':
            root=ET.fromstring(text)
            if root.tag=='resources':
                for item in root:
                    value=''.join(item.itertext())
                    if item.tag=='string' and BRAND.search(value):
                        key=item.attrib.get('name','')
                        resource_categories[key]=category(name,value,key)
        current_resource_key=None
        for number,line in enumerate(text.splitlines(),1):
            opening=re.search(r'<string\s+name="([^"]+)"',line)
            if opening:
                current_resource_key=opening.group(1)
            matches=len(BRAND.findall(line))
            if not matches:
                if '</string>' in line:
                    current_resource_key=None
                continue
            kind=resource_categories.get(current_resource_key) or category(name,line)
            rows.append([name,number,matches,kind,'retain for reviewed role; not own-product identity'])
            if '</string>' in line:
                current_resource_key=None
    counts=Counter()
    for _,_,count,kind,_ in rows:
        counts[kind]+=count
    return rows,counts


def assets():
    result=[]
    candidates=list((ROOT/'composeApp/src/commonMain/composeResources/drawable').glob('app_icon_*.png'))
    candidates+=list((ROOT/'composeApp/src/commonMain/composeResources/drawable').glob('app_logo_wordmark*.png'))
    candidates+=list((ROOT/'iosApp/iosApp/Assets.xcassets').glob('AppIcon*.appiconset/app-icon-1024.png'))
    for path in sorted(candidates):
        name=path.relative_to(ROOT).as_posix()
        try:
            baseline=subprocess.check_output(['git','show','ee6f99b20ef06236029979d1636ebde5b151d677:'+name],cwd=ROOT,stderr=subprocess.DEVNULL)
        except subprocess.CalledProcessError:
            baseline=b''
        digest=hashlib.sha256(path.read_bytes()).hexdigest()
        role='own primary icon replaced with canonical StreamBridge launcher' if '/AppIcon.appiconset/' in name else 'unchanged StreamBridge baseline artwork'
        if '/AppIcon.appiconset/' not in name and baseline!=path.read_bytes():
            raise ValueError(f'Artwork needs separate review: {name}')
        result.append((name,digest,role))
    return result


if __name__=='__main__':
    rows,counts=inventory()
    with (ROOT/'docs/branding-occurrences-0.5.5.tsv').open('w',newline='') as file:
        writer=csv.writer(file,delimiter='\t',lineterminator='\n')
        writer.writerow(['path','line (0=path)','occurrences','classification','decision'])
        writer.writerows(rows)
    print('Classified references (including paths):')
    for kind,count in sorted(counts.items()):
        print(f'{kind}: {count}')
    print(f'Total: {sum(counts.values())}; indexed rows: {len(rows)}')
    print('\nVerified own branding assets:')
    for name,digest,role in assets():
        print(f'{name}\t{digest}\t{role}')
