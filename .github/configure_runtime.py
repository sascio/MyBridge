#!/usr/bin/env python3
"""Materialize optional runtime properties securely, without logging any values."""
from __future__ import annotations

import base64
import os
from pathlib import Path
import re

payload = os.environ.get('NUVIO_LOCAL_PROPERTIES_BASE64', '')
path = Path('local.properties')
if payload:
    try:
        data = base64.b64decode(''.join(payload.split()), validate=True).decode('utf-8')
    except (ValueError, UnicodeError):
        raise SystemExit('Runtime configuration secret must be valid base64 UTF-8; values are not logged.') from None
else:
    data = ''
# The SDK and upload-store path are runner-specific, never taken from a secret
# created on another computer. Other optional API/OAuth settings are preserved.
lines = [line for line in data.splitlines()
         if not re.match(r'^\s*(sdk\.dir|NUVIO_RELEASE_STORE_FILE)\s*[=:]', line)]
path.write_text('\n'.join(lines) + '\n')
path.chmod(0o600)
print('Optional runtime properties configured; credential values are not logged.')
