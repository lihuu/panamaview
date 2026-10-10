#!/usr/bin/env python3
"""Choose which repository in a settings.xml a given version should be deployed to.

Split out of deploy-private.sh so it can be tested without a repository, a network or credentials.
It is the fiddliest part of that script — heuristic, failure-prone, and the place where guessing
wrong publishes an artifact to the wrong place — and while it lived in a heredoc nothing exercised
it except a real deploy. scripts/verify-deploy.sh drives it against fixture settings files.

Usage: choose-repo.py <settings.xml> <pom.xml> [explicit-repository-id]

On success prints three lines — the id, its url, and the project's version — and exits 0.
Otherwise prints an explanation to stderr and exits non-zero.
"""

import pathlib
import re
import sys


def inner(block: str, name: str):
    """The text of the first <name> element in block, or None."""
    match = re.search(rf"<{name}>(.*?)</{name}>", block, re.S)
    return match.group(1).strip() if match else None


def read_settings(path: pathlib.Path):
    """(ids with credentials, id -> url) out of a settings.xml."""
    text = path.read_text(encoding="utf-8")

    credentials = set()
    for match in re.finditer(r"<server>(.*?)</server>", text, re.S):
        block = match.group(1)
        if inner(block, "username") and inner(block, "password"):
            credentials.add(inner(block, "id"))

    repos = {}
    for match in re.finditer(r"<repository>(.*?)</repository>", text, re.S):
        block = match.group(1)
        repos[inner(block, "id")] = inner(block, "url")

    return credentials, repos


def read_version(pom: pathlib.Path) -> str:
    text = pom.read_text(encoding="utf-8")
    match = re.search(r"<version>([^<]+)</version>", text)
    if not match:
        sys.exit(f"{pom} has no <version>")
    return match.group(1)


def says(repo_id: str, url: str, word: str) -> bool:
    return word in (repo_id or "").lower() or word in (url or "").lower()


def choose(credentials, repos, version: str, explicit: str):
    if explicit:
        if explicit not in repos:
            sys.exit(f"'{explicit}' is not a <repository> in the settings file")
        if explicit not in credentials:
            sys.exit(f"'{explicit}' has no <server> credentials in the settings file")
        return explicit

    wants_snapshot = version.upper().endswith("-SNAPSHOT")
    word = "snapshot" if wants_snapshot else "release"

    candidates = [
        repo_id
        for repo_id, url in repos.items()
        if repo_id in credentials and says(repo_id, url, word)
    ]
    # A repository that advertises the other kind too is ambiguous — a release repository whose url
    # happens to mention snapshots cannot be told apart from a snapshot repository by name. Drop it
    # rather than pick a lane on its behalf; publishing to the wrong one is not approximately fine.
    candidates = [c for c in candidates if says(c, repos[c], "snapshot") == wants_snapshot]

    if len(candidates) != 1:
        usable = "\n".join(
            f"    {r}  {u}" for r, u in sorted(repos.items()) if r in credentials
        )
        sys.exit(
            f"cannot tell which repository to use for version {version} "
            f"({len(candidates)} match).\n"
            f"  Repositories with credentials:\n{usable}\n"
            f"  Name one explicitly."
        )
    return candidates[0]


def main(argv):
    if len(argv) < 3:
        sys.exit(__doc__.strip().splitlines()[-2])

    settings = pathlib.Path(argv[1])
    if not settings.is_file():
        sys.exit(f"no settings file at {settings}")

    credentials, repos = read_settings(settings)
    version = read_version(pathlib.Path(argv[2]))
    explicit = argv[3].strip() if len(argv) > 3 else ""

    repo_id = choose(credentials, repos, version, explicit)
    print(repo_id)
    print(repos[repo_id])
    print(version)


if __name__ == "__main__":
    main(sys.argv)
