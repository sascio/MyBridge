#!/usr/bin/env python3
"""Make diagnostics visible in check annotations even if log downloads are blocked."""
from pathlib import Path
import os
import re
import xml.etree.ElementTree as ET


def annotate(message):
    escaped=message.replace('%','%25').replace('\r','%0D').replace('\n','%0A')
    print('::error::'+escaped[:3800])


for name in ('compile-debug.log','android-build.log'):
    path=Path(name)
    if not path.is_file():
        continue
    lines=path.read_text(errors='replace').splitlines()
    for index,line in enumerate(lines):
        if 'FAILURE: Build failed' in line or line.startswith('Traceback (most recent call last)'):
            context=lines[index:index+18]
            context=[line for line in context if not line.lstrip().startswith('at ') and line.strip()]
            annotate('\n'.join(context))

counts={'tests':0,'failures':0,'skipped':0}
for path in Path('.').glob('*/build/test-results/**/TEST-*.xml'):
    root=ET.parse(path).getroot()
    for test in root.iter('testcase'):
        counts['tests']+=1
        if test.find('skipped') is not None:
            counts['skipped']+=1
        failure=test.find('failure')
        if failure is None:
            failure=test.find('error')
        if failure is not None:
            counts['failures']+=1
            text=f"{test.attrib.get('classname')}: {test.attrib.get('name')}\n{failure.attrib.get('message','')}"
            annotate(text)
print('JUnit result summary: '+str(counts))
summary=os.environ.get('GITHUB_STEP_SUMMARY')
if summary:
    with open(summary,'a') as output:
        output.write('## StreamBridge unit-test results\n\n'+str(counts)+'\n')
