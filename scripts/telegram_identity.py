#!/usr/bin/env python3
"""Validate exact release tags and resolve their immutable Git commit, without sending."""
from __future__ import annotations

import argparse
import json
import re
import subprocess
from typing import Any, Callable
from urllib.parse import quote


class IdentityError(RuntimeError):
    """An exact release identity could not be established safely."""


SHA = re.compile(r"^[0-9a-f]{40}$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
TAG = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._/+\-]{0,199}$")


def validate_tag(tag: object) -> str:
    """Accept only an unambiguous, exact Git tag; never interpret latest or a ref expression."""
    if (not isinstance(tag, str) or not TAG.fullmatch(tag) or tag.casefold() == "latest"
            or tag.startswith("refs/") or tag.endswith("/") or "//" in tag or ".." in tag
            or any(part.startswith(".") or part.endswith(".") or part.endswith(".lock")
                   for part in tag.split("/"))):
        raise IdentityError("An explicit, unambiguous release tag is required; latest and empty tags are forbidden.")
    return tag


def _api(endpoint: str, runner: Callable = subprocess.run) -> Any:
    try:
        result = runner(["gh", "api", endpoint], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                        timeout=60, check=False)
        if result.returncode:
            raise IdentityError("The exact Git tag or its commit could not be read from GitHub.")
        return json.loads(result.stdout.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, subprocess.TimeoutExpired) as exc:
        raise IdentityError("The exact Git tag or its commit could not be read from GitHub.") from exc


def resolve_tag_commit(repository: str, tag: str, *, runner: Callable = subprocess.run) -> str:
    """Resolve only refs/tags/<tag>, peeling annotated tags to a commit with bounded depth."""
    if not isinstance(repository, str) or not REPOSITORY.fullmatch(repository):
        raise IdentityError("The source repository identity is invalid.")
    tag = validate_tag(tag)
    ref = _api(f"repos/{repository}/git/ref/tags/{quote(tag, safe='')}", runner)
    if not isinstance(ref, dict) or ref.get("ref") != f"refs/tags/{tag}":
        raise IdentityError("GitHub did not return the exact requested tag ref.")
    obj = ref.get("object")
    visited: set[str] = set()
    for _ in range(6):
        if not isinstance(obj, dict) or not isinstance(obj.get("sha"), str) or not SHA.fullmatch(obj["sha"]):
            raise IdentityError("The tag object identity is missing or invalid.")
        sha, kind = obj["sha"], obj.get("type")
        if kind == "commit":
            return sha
        if kind != "tag" or sha in visited:
            raise IdentityError("The tag does not resolve unambiguously to a commit.")
        visited.add(sha)
        annotated = _api(f"repos/{repository}/git/tags/{sha}", runner)
        if not isinstance(annotated, dict) or annotated.get("sha") != sha:
            raise IdentityError("The annotated tag identity is inconsistent.")
        obj = annotated.get("object")
    raise IdentityError("The tag nesting limit was exceeded.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-tag", required=True)
    args = parser.parse_args()
    try:
        validate_tag(args.check_tag)
    except IdentityError as exc:
        print(f"::error::{exc}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
