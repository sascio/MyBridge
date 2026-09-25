#!/usr/bin/env python3
"""Surface failing unit tests from Gradle's JUnit XML results as CI annotations.

Usage: report-failing-tests.py [results-dir ...]

Reads every TEST-*.xml under the given directories (defaults to
composeApp/build/test-results) and:

- prints one GitHub Actions `::error` annotation per failed or errored test
  (the GitHub UI caps annotations at 10 per check run), and
- appends the complete failure table to $GITHUB_STEP_SUMMARY, which has no
  cap, so the full list is visible on the run page.

With no results directory the script is a no-op (reports zero failures).
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
                classname = case.get("classname") or "?"
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

    # Annotations (UI caps display at 10 per check run).
    for classname, name, message in failures:
        print(f"::error file={classname.replace('.', '/')}.kt::FAIL {classname} :: {name} :: {message}")

    # Full, uncapped list in the step summary.
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as out:
            out.write("\n## Unit test failures\n\n")
            out.write(f"{total} tests run, **{len(failures)} failing**.\n\n")
            if failures:
                out.write("| Test | Failure |\n|---|---|\n")
                for classname, name, message in failures:
                    safe = (
                        message.replace("|", "\\|").replace("\n", " ")
                        + " "
                        + name.replace("|", "\\|")
                    )
                    out.write(f"| `{classname}::{name}` | {safe[:400]} |\n")
            else:
                out.write("No failures.\n")
            out.write("\n")

    print(f"Unit tests: {total} run, {len(failures)} failing.")
    # Never fail the job: this reporter is informational; the test step
    # itself decides gating.
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
