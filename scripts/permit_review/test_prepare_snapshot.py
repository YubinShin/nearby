import csv
import hashlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from prepare_snapshot import FIELDS, compare_snapshots, read_snapshot


class PrepareSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def row(self, license_id="A", **values):
        result = {key: "" for key in FIELDS}
        result.update(gov_code="3220000", license_id=license_id, name="가게", status_code="01", status="영업/정상")
        result.update(values)
        return {column: result[key] for key, column in FIELDS.items()}

    def snapshot(self, label, rows, corrupt=False):
        directory = self.root / label
        directory.mkdir()
        text = io.StringIO(newline="")
        writer = csv.DictWriter(text, fieldnames=list(FIELDS.values()))
        writer.writeheader()
        writer.writerows(rows)
        raw = text.getvalue().encode("cp949")
        if corrupt:
            raw = raw.replace("가게".encode("cp949"), b"\xff")
        (directory / "source.csv").write_bytes(raw)
        manifest = directory / "manifest.json"
        manifest.write_text(json.dumps({"file": "source.csv", "sha256": hashlib.sha256(raw).hexdigest(), "size_bytes": len(raw)}))
        return manifest

    def compare(self, old, new):
        return compare_snapshots(read_snapshot(old, "3220000"), read_snapshot(new, "3220000"), "3220000")

    def test_closed_records_remain_available_for_status_change_review(self):
        old = self.snapshot("old", [self.row()])
        new = self.snapshot("new", [self.row(status_code="03", status="폐업")])
        change = self.compare(old, new)["changes"][0]
        self.assertEqual("modified", change["kind"])
        self.assertEqual("03", change["after"]["status_code"])

    def test_any_duplicate_blocks_comparison_even_when_the_other_snapshot_is_unique(self):
        old = self.snapshot("old", [self.row(), self.row(status_code="03", source_updated_at="2026-10-27")])
        new = self.snapshot("new", [self.row(name="새가게")])
        result = self.compare(old, new)
        self.assertEqual([], result["changes"])
        self.assertEqual(["A"], result["excluded_identifiers"])
        self.assertEqual([1, 2], result["previous"]["issues"][0]["record_numbers"])

    def test_name_change_preserves_source_values_and_record_evidence(self):
        old = self.snapshot("old", [self.row(name=" 빽다방 ")])
        new = self.snapshot("new", [self.row(name="우지커피")])
        result = self.compare(old, new)
        change = result["changes"][0]
        self.assertEqual(["name"], change["changed_fields"])
        self.assertEqual("빽다방", change["before"]["name"])
        self.assertEqual("우지커피", change["after"]["name"])
        self.assertEqual(result["previous"]["source"]["sha256"] + ":1", change["before"]["evidence_id"])
        self.assertEqual(result, self.compare(old, new))

    def test_absence_is_not_reported_as_closure(self):
        old = self.snapshot("old", [self.row()])
        new = self.snapshot("new", [])
        change = self.compare(old, new)["changes"][0]
        self.assertEqual("absent_from_current", change["kind"])
        self.assertIsNone(change["after"])
        self.assertEqual([], change["changed_fields"])

    def test_invalid_encoding_is_reported_and_never_replaced_in_a_proposal(self):
        old = self.snapshot("old", [self.row()], corrupt=True)
        new = self.snapshot("new", [self.row()])
        result = self.compare(old, new)
        self.assertEqual([], result["changes"])
        self.assertEqual("decode_error", result["previous"]["issues"][0]["kind"])
        self.assertEqual(["사업장명"], result["previous"]["issues"][0]["fields"])
        json.dumps(result, ensure_ascii=False).encode("utf-8")

    def test_modified_source_fails_integrity_validation(self):
        manifest = self.snapshot("old", [self.row()])
        (manifest.parent / "source.csv").write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "integrity mismatch"):
            read_snapshot(manifest, "3220000")

    def test_updated_timestamp_alone_is_not_a_business_change(self):
        old = self.snapshot("old", [self.row(source_updated_at="2026-07-31")])
        new = self.snapshot("new", [self.row(source_updated_at="2026-10-27")])
        self.assertEqual([], self.compare(old, new)["changes"])

    def test_multiline_fields_use_logical_record_numbers(self):
        old = self.snapshot("old", [self.row(name="긴\n이름"), self.row("B")])
        snapshot = read_snapshot(old, "3220000")
        self.assertEqual(2, snapshot["records"]["B"]["record_number"])

    def test_new_records_are_scoped_to_the_requested_district(self):
        old = self.snapshot("old", [])
        new = self.snapshot("new", [self.row(), self.row("B", gov_code="OTHER")])
        result = self.compare(old, new)
        self.assertEqual(["A"], [c["license_id"] for c in result["changes"]])
        self.assertEqual("added", result["changes"][0]["kind"])
        self.assertEqual(1, result["current"]["summary"]["selected_records"])

    def test_blank_identifiers_are_quarantined(self):
        manifest = self.snapshot("old", [self.row(license_id=" ")])
        result = read_snapshot(manifest, "3220000")
        self.assertEqual({}, result["records"])
        self.assertEqual("invalid_identifier", result["issues"][0]["kind"])

    def test_missing_columns_fail_before_comparison(self):
        manifest = self.snapshot("old", [self.row()])
        source = manifest.parent / "source.csv"
        raw = source.read_bytes().replace("사업장명".encode("cp949"), b"unknown")
        source.write_bytes(raw)
        metadata = json.loads(manifest.read_text())
        metadata.update(sha256=hashlib.sha256(raw).hexdigest(), size_bytes=len(raw))
        manifest.write_text(json.dumps(metadata))
        with self.assertRaisesRegex(ValueError, "missing or duplicate columns"):
            read_snapshot(manifest, "3220000")

    def test_cli_refuses_to_overwrite_an_existing_output(self):
        old = self.snapshot("old", [self.row()])
        new = self.snapshot("new", [self.row(name="새가게")])
        output = self.root / "result.json"
        output.write_text("keep")
        result = subprocess.run([
            sys.executable, str(Path(__file__).with_name("prepare_snapshot.py")),
            "--previous", str(old), "--current", str(new),
            "--gov-code", "3220000", "--output", str(output),
        ], capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertEqual("keep", output.read_text())


if __name__ == "__main__":
    unittest.main()
