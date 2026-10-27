import csv
import hashlib
import tempfile
import unittest
from pathlib import Path

from find_candidates import PLACE_FIELDS, building_address, find_candidates, read_places, select_change


class FindCandidatesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "places.csv"

    def places(self, rows):
        with self.path.open("w", encoding="utf-8", newline="") as target:
            writer = csv.DictWriter(target, fieldnames=PLACE_FIELDS)
            writer.writeheader()
            for row in rows:
                values = dict.fromkeys(PLACE_FIELDS, "")
                values.update(row)
                writer.writerow(values)
        return read_places(self.path)

    def change(self, before="서울특별시 강남구 도곡로 405, 1층 108호", after="서울특별시 강남구 도곡로 405"):
        return {
            "change_id": "C1", "license_id": "L1",
            "before": None if before is None else {"road_address": before, "evidence_id": "old:1"},
            "after": None if after is None else {"road_address": after, "evidence_id": "new:1"},
        }

    def test_all_businesses_at_the_building_remain_candidates(self):
        places = self.places([
            {"place_id": "B", "name": "카페", "category_small": "카페", "road_address": "서울특별시 강남구 도곡로 405"},
            {"place_id": "A", "name": "부동산", "category_small": "부동산", "road_address": "서울특별시 강남구 도곡로 405"},
        ])
        result = find_candidates(self.change(), places)
        self.assertEqual(["A", "B"], [r["place_id"] for r in result["candidates"]])
        self.assertFalse(result["identity_confirmed"])
        self.assertFalse(result["truncated"])
        self.assertEqual(["before", "after"], [m["side"] for m in result["candidates"][0]["matches"]])

    def test_building_subnumbers_and_number_prefixes_do_not_match(self):
        places = self.places([
            {"place_id": "A", "road_address": "서울특별시 강남구 도곡로 405-1"},
            {"place_id": "B", "road_address": "서울특별시 강남구 도곡로 4050"},
        ])
        self.assertEqual("no_candidates", find_candidates(self.change(), places)["status"])

    def test_different_district_does_not_match(self):
        places = self.places([{"place_id": "A", "road_address": "서울특별시 서초구 도곡로 405"}])
        self.assertEqual([], find_candidates(self.change(), places)["candidates"])

    def test_missing_permit_address_is_not_an_empty_result_claim(self):
        result = find_candidates(self.change(before="", after=""), self.places([]))
        self.assertEqual("unsearchable", result["status"])
        self.assertEqual("missing_address", result["query_targets"][0]["status"])

    def test_missing_place_address_is_visible(self):
        result = find_candidates(self.change(), self.places([{"place_id": "A"}]))
        self.assertEqual("missing_address", result["place_address_issues"][0]["kind"])
        self.assertEqual([], result["candidates"])

    def test_both_old_and_new_addresses_are_searched_without_duplicate_candidates(self):
        places = self.places([
            {"place_id": "A", "road_address": "서울특별시 강남구 도곡로 405"},
            {"place_id": "B", "road_address": "서울특별시 강남구 도곡로 407"},
        ])
        result = find_candidates(self.change(after="서울특별시 강남구 도곡로 407"), places)
        self.assertEqual(2, result["total_candidates"])
        self.assertEqual(["before"], [m["side"] for m in result["candidates"][0]["matches"]])
        self.assertEqual(["after"], [m["side"] for m in result["candidates"][1]["matches"]])

    def test_added_and_absent_changes_can_use_the_available_side(self):
        places = self.places([{"place_id": "A", "road_address": "서울특별시 강남구 도곡로 405"}])
        for change in [self.change(before=None), self.change(after=None)]:
            self.assertEqual(1, find_candidates(change, places)["total_candidates"])

    def test_details_are_preserved_even_though_the_building_key_omits_them(self):
        address = "서울특별시 강남구 도곡로 405, 2층 201호 (대치동)"
        result = find_candidates(self.change(), self.places([{"place_id": "A", "road_address": address}]))
        candidate = result["candidates"][0]
        self.assertEqual(address, candidate["road_address"])
        self.assertEqual(hashlib.sha256(self.path.read_bytes()).hexdigest() + ":1", candidate["evidence_id"])
        self.assertFalse(result["identity_confirmed"])

    def test_whitespace_and_parenthetical_suffix_are_normalized(self):
        self.assertEqual("서울특별시 강남구 도곡로 405", building_address(" 서울특별시  강남구 도곡로 405(대치동) "))

    def test_unsupported_address_is_not_guessed(self):
        for address in ["서울 강남구 도곡로 405", "서울특별시 강남구 대치동 938", "서울특별시 강남구 도곡로 405 108호"]:
            self.assertIsNone(building_address(address))

    def test_duplicate_place_identifiers_fail_instead_of_selecting_one(self):
        with self.assertRaisesRegex(ValueError, "duplicate place_id"):
            self.places([{"place_id": "A"}, {"place_id": "A"}])

    def test_unknown_or_ambiguous_change_id_is_rejected(self):
        for changes in [[], [self.change(), self.change()]]:
            with self.assertRaisesRegex(ValueError, "exactly one"):
                select_change({"schema_version": 1, "changes": changes}, "C1")


if __name__ == "__main__":
    unittest.main()
