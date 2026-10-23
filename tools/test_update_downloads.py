import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from xml.etree import ElementTree

import update_downloads as counter


REPO = counter.DEFAULT_REPOSITORY


def empty_ledger():
    return {"schema_version": 1, "repository": REPO, "assets": {}, "total_downloads": 0}


def release(*assets, tag="v1", draft=False):
    return {"tag_name": tag, "draft": draft, "assets": list(assets)}


def asset(asset_id, count, name="Zazus-Server-Seeker-1-mc26.2.jar"):
    return {"id": asset_id, "download_count": count, "name": name}


class DownloadCounterTests(unittest.TestCase):
    def test_updates_add_deltas_without_double_counting(self):
        first = counter.merge_counts(empty_ledger(), [release(asset(1, 5), asset(2, 3))], REPO)
        repeated = counter.merge_counts(first, [release(asset(1, 5), asset(2, 3))], REPO)
        self.assertEqual(first, repeated)
        grown = counter.merge_counts(repeated, [release(asset(1, 7), asset(2, 4))], REPO)
        self.assertEqual(grown["total_downloads"], 11)

    def test_deleted_releases_and_replaced_same_filename_do_not_reset(self):
        first = counter.merge_counts(empty_ledger(), [release(asset(1, 8))], REPO)
        deleted = counter.merge_counts(first, [], REPO)
        self.assertEqual(deleted["total_downloads"], 8)
        replaced = counter.merge_counts(deleted, [release(asset(2, 2), tag="v2")], REPO)
        self.assertEqual(replaced["total_downloads"], 10)

    def test_lower_reported_count_never_reduces_total(self):
        first = counter.merge_counts(empty_ledger(), [release(asset(1, 8))], REPO)
        result = counter.merge_counts(first, [release(asset(1, 0))], REPO)
        self.assertEqual(result["total_downloads"], 8)

    def test_counts_prereleases_both_versions_but_not_sources_or_drafts(self):
        result = counter.merge_counts(empty_ledger(), [
            release(asset(1, 5), asset(2, 3, "Zazus-Server-Seeker-1-mc26.3.jar"),
                    asset(3, 9, "Zazus-Server-Seeker-1-sources.jar"),
                    asset(4, 8, "Zazus-Server-Seeker-1-javadoc.jar"),
                    asset(5, 7, "source.zip"), tag="v1-rc1"),
            release(asset(6, 20), draft=True),
        ], REPO)
        self.assertEqual(result["total_downloads"], 8)

    def test_renamed_asset_keeps_tracking(self):
        first = counter.merge_counts(empty_ledger(), [release(asset(1, 5))], REPO)
        result = counter.merge_counts(first, [release(asset(1, 7, "archived.jar"))], REPO)
        self.assertEqual(result["total_downloads"], 7)

    def test_paginates_releases_and_assets(self):
        first_page = list(range(100))
        with patch.object(counter, "api_page", side_effect=[first_page, [100]]) as api:
            self.assertEqual(list(counter.api_pages(REPO, "releases")), list(range(101)))
            self.assertEqual(api.call_args_list[-1].args, (REPO, "releases", 2))
        with patch.object(counter, "api_pages", side_effect=[
            [{"id": 42}], [asset(1, 9)]
        ]) as api:
            self.assertEqual(counter.release_snapshot(REPO)[0]["assets"][0]["download_count"], 9)
            self.assertEqual(api.call_args_list[-1].args, (REPO, "releases/42/assets"))

    def test_api_failure_does_not_change_saved_files(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "docs").mkdir()
            ledger = root / "docs/downloads.json"
            ledger.write_text(json.dumps(empty_ledger()))
            badge = root / "docs/downloads.svg"
            badge.write_text("existing badge")
            before = ledger.read_bytes()
            with patch.object(counter, "release_snapshot", side_effect=RuntimeError("API unavailable")):
                with self.assertRaises(RuntimeError):
                    counter.update(REPO, root)
            self.assertEqual(ledger.read_bytes(), before)
            self.assertEqual(badge.read_text(), "existing badge")

    def test_bad_ledger_or_invalid_counts_fail_instead_of_resetting(self):
        with self.assertRaises(ValueError):
            counter.merge_counts({}, [], REPO)
        with self.assertRaises(ValueError):
            counter.merge_counts(empty_ledger(), [release(asset(1, -1))], REPO)
        corrupt = empty_ledger()
        corrupt["total_downloads"] = 99
        with self.assertRaises(ValueError):
            counter.merge_counts(corrupt, [], REPO)

    def test_badge_is_valid_svg_with_exact_total(self):
        svg = counter.badge_svg(12345)
        root = ElementTree.fromstring(svg)
        self.assertEqual(root.attrib["aria-label"], "GitHub downloads: 12,345")


if __name__ == "__main__":
    unittest.main()
