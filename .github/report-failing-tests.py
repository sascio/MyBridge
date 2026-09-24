#!/usr/bin/env python3
"""Surface failing unit tests from Gradle's JUnit XML results as CI annotations.

Usage: report-failing-tests.py [results-dir ...]

Reads every TEST-*.xml under the given directories (defaults to
composeApp/build/test-results) and prints one GitHub Actions `::error`
annotation per failed or errored test, plus a summary line. With no
results directory the script is a no-op (reports zero failures).
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET


def collect(results_dirs):
    failures = []
    total = 0
    for results_dir in results_dirs:
        for path in sorted(glob.glob(os.path.join(results_dir, "**", "TEST-*.xml"), recursive=True)):
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError as e:
                failures.append(("unparsable-results-file", path, str(e)))
                continue
            total += int(root.get("tests", "0") or 0)
            for case in root.iter("testcase"):
                classname = case.get("classname") or case.get("classname", "?")
                name = case.get("name", "?")
                for problem in list(case.iter("failure")) + list(case.iter("error")):
                    message = (problem.get("message") or problem.text or "").strip()
                    message = " ".join(message.split())[:300]
                    failures.append((classname, name, message))
    return total, failures


def main(argv):
    results_dirs = argv[1:] or ["composeApp/build/test-results"]
    existing = [d for d in results_dirs if os.path.isdir(d)]
    if not existing:
        print("::warning::No unit-test result directory found; nothing to report.")
        return 0
    total, failures = collect(existing)
    for classname, name, message in failures:
        print(f"::error file={classname.replace('.', '/')}.kt::FAIL {classname} :: {name} :: {message}")
    print(f"Unit tests: {total} run, {len(failures)} failing.")
    # Never fail the job: this reporter is informational; the test step
    # itself decides gating.
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
