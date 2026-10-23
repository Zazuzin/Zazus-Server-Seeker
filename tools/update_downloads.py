#!/usr/bin/env python3
"""Keep observed GitHub release-JAR download counts after assets are removed."""

import argparse
from copy import deepcopy
import json
import os
from pathlib import Path
import re
from urllib.request import Request, urlopen


DEFAULT_REPOSITORY = "Zazuzin/Zazus-Server-Seeker"
ROOT = Path(__file__).resolve().parents[1]


def api_page(repository, path, page):
    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "server-seeker-download-counter",
    }
    token = os.environ.get("GH_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    url = f"https://api.github.com/repos/{repository}/{path}?per_page=100&page={page}"
    with urlopen(Request(url, headers=headers), timeout=30) as response:
        data = json.load(response)
    if not isinstance(data, list):
        raise ValueError("Expected a GitHub API list; saved download counts were not changed")
    return data


def api_pages(repository, path):
    page = 1
    while True:
        batch = api_page(repository, path, page)
        yield from batch
        if len(batch) < 100:
            return
        page += 1


def release_snapshot(repository):
    # Fetch every page before updating files. Network failures leave the ledger intact.
    releases = list(api_pages(repository, "releases"))
    for release in releases:
        if not release.get("draft", False):
            release["assets"] = list(api_pages(repository, f"releases/{release['id']}/assets"))
    return releases


def is_mod_jar(name):
    name = name.lower()
    return (
        name.startswith("zazus-server-seeker-")
        and name.endswith(".jar")
        and not name.endswith(("-sources.jar", "-javadoc.jar"))
    )


def nonnegative_int(value):
    if type(value) is not int or value < 0:
        raise ValueError("Invalid download count; saved counts were not changed")
    return value


def merge_counts(previous, releases, repository):
    if previous.get("schema_version") != 1 or previous.get("repository") != repository:
        raise ValueError("Unexpected download ledger schema or repository")
    if not isinstance(previous.get("assets"), dict):
        raise ValueError("Invalid asset ledger")
    state = deepcopy(previous)
    historical = nonnegative_int(state.get("historical_downloads", 0))
    for saved in state["assets"].values():
        nonnegative_int(saved["downloads"])
    old_total = historical + sum(saved["downloads"] for saved in state["assets"].values())
    if previous.get("total_downloads") != old_total:
        raise ValueError("Saved total does not match asset ledger")
    for release in releases:
        if release.get("draft", False):
            continue
        for asset in release["assets"]:
            asset_id = str(nonnegative_int(asset["id"]))
            saved = state["assets"].get(asset_id)
            # Continue tracking an existing asset even if it has since been renamed.
            if saved is None and not is_mod_jar(asset["name"]):
                continue
            count = nonnegative_int(asset["download_count"])
            state["assets"][asset_id] = {
                "name": asset["name"],
                "release_tag": release["tag_name"],
                "downloads": max(count, saved["downloads"] if saved else 0),
            }
    state["total_downloads"] = historical + sum(a["downloads"] for a in state["assets"].values())
    return state


def badge_svg(total):
    label = "Downloads"
    value = f"{nonnegative_int(total):,}"
    left, right = 82, max(34, len(value) * 8 + 16)
    width = left + right
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="22" role="img" aria-label="{label}: {value}">
  <title>{label}: {value}</title>
  <clipPath id="corners"><rect width="{width}" height="22" rx="4"/></clipPath>
  <g clip-path="url(#corners)">
    <rect width="{left}" height="22" fill="#444"/>
    <rect x="{left}" width="{right}" height="22" fill="#7c3aed"/>
  </g>
  <g fill="#fff" text-anchor="middle" font-family="Verdana,DejaVu Sans,sans-serif" font-size="11">
    <text x="{left / 2:g}" y="15">{label}</text>
    <text x="{left + right / 2:g}" y="15">{value}</text>
  </g>
</svg>
'''


def update(repository, root=ROOT):
    if re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) is None:
        raise ValueError("Invalid repository name")
    path = root / "docs/downloads.json"
    # Require an existing ledger: never silently reset it if accidentally deleted.
    previous = json.loads(path.read_text())
    state = merge_counts(previous, release_snapshot(repository), repository)
    contents = json.dumps(state, indent=2, sort_keys=True) + "\n"
    svg = badge_svg(state["total_downloads"])
    path.write_text(contents)
    (root / "docs/downloads.svg").write_text(svg)
    print(f"Recorded GitHub JAR downloads: {state['total_downloads']}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=DEFAULT_REPOSITORY)
    args = parser.parse_args()
    update(args.repository)
