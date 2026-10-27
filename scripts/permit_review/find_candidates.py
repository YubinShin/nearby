import argparse
import csv
import hashlib
import io
import json
import re
import unicodedata
from pathlib import Path


PLACE_FIELDS = (
    "place_id", "name", "branch", "category_large", "category_mid",
    "category_small", "road_address", "jibun_address",
)
BUILDING_ADDRESS = re.compile(r"서울특별시 [가-힣]+구 [^\s,()]+(?:대로|로|길) [0-9]+(?:-[0-9]+)?")


def building_address(value):
    normalized = " ".join(unicodedata.normalize("NFC", value).split())
    base = re.split(r"[,()]", normalized, maxsplit=1)[0].strip()
    return base if BUILDING_ADDRESS.fullmatch(base) else None


def read_places(path):
    path = Path(path)
    raw = path.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    reader = csv.DictReader(io.StringIO(raw.decode("utf-8-sig")), strict=True)
    headers = reader.fieldnames or []
    if len(headers) != len(set(headers)) or not set(PLACE_FIELDS).issubset(headers):
        raise ValueError("missing or duplicate place columns")
    records = []
    seen = set()
    issues = []
    for ordinal, row in enumerate(reader, 1):
        if None in row or any(value is None for value in row.values()):
            raise ValueError(f"malformed place record: {ordinal}")
        values = {key: row[key].strip() for key in PLACE_FIELDS}
        place_id = values["place_id"]
        if not place_id or place_id in seen:
            raise ValueError(f"blank or duplicate place_id at record {ordinal}")
        seen.add(place_id)
        address = building_address(values["road_address"])
        if address is None:
            issues.append({
                "record_number": ordinal, "place_id": place_id,
                "kind": "missing_address" if not values["road_address"] else "unsupported_address",
            })
        records.append({
            **values, "building_address": address, "record_number": ordinal,
            "evidence_id": f"{digest}:{ordinal}",
        })
    return {
        "source": {"file": str(path), "sha256": digest},
        "records": records, "issues": issues,
    }


def select_change(document, change_id):
    if document.get("schema_version") != 1:
        raise ValueError("unsupported change schema version")
    changes = [change for change in document["changes"] if change["change_id"] == change_id]
    if len(changes) != 1:
        raise ValueError("change_id must identify exactly one change")
    return changes[0]


def find_candidates(change, places):
    targets = []
    for side in ("before", "after"):
        record = change[side]
        if record is None:
            continue
        raw = record["road_address"]
        address = building_address(raw)
        targets.append({
            "side": side, "evidence_id": record["evidence_id"],
            "road_address": raw, "building_address": address,
            "status": "usable" if address else "missing_address" if not raw.strip() else "unsupported_address",
        })
    usable = [target for target in targets if target["building_address"] is not None]
    candidates = []
    for record in places["records"]:
        matches = [target for target in usable if target["building_address"] == record["building_address"]]
        if not matches:
            continue
        candidates.append({
            **record,
            "matches": [{
                "rule": "same_road_building_address",
                "side": target["side"], "permit_evidence_id": target["evidence_id"],
            } for target in matches],
        })
    candidates.sort(key=lambda candidate: candidate["place_id"])
    return {
        "schema_version": 1, "policy_version": "seoul-road-building-v1",
        "change_id": change["change_id"], "license_id": change["license_id"],
        "place_source": places["source"], "query_targets": targets,
        "status": "candidates_found" if candidates else "no_candidates" if usable else "unsearchable",
        "scope": "provided_place_csv", "identity_confirmed": False,
        "total_candidates": len(candidates), "truncated": False,
        "place_records_scanned": len(places["records"]),
        "place_address_issues": places["issues"], "candidates": candidates,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--changes", required=True, type=Path)
    parser.add_argument("--change-id", required=True)
    parser.add_argument("--places", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output already exists; choose a new output path")
    raw = args.changes.read_bytes()
    change = select_change(json.loads(raw), args.change_id)
    result = find_candidates(change, read_places(args.places))
    result["change_source"] = {"file": str(args.changes), "sha256": hashlib.sha256(raw).hexdigest()}
    with args.output.open("x", encoding="utf-8") as target:
        target.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("status", "total_candidates", "identity_confirmed")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
