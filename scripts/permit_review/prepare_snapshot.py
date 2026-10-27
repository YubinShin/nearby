import argparse
import csv
import hashlib
import io
import json
from collections import Counter, defaultdict
from pathlib import Path


FIELDS = {
    "gov_code": "개방자치단체코드",
    "license_id": "관리번호",
    "name": "사업장명",
    "status_code": "영업상태코드",
    "status": "영업상태명",
    "status_detail_code": "상세영업상태코드",
    "closed_on": "폐업일자",
    "jibun_address": "지번주소",
    "road_address": "도로명주소",
    "business_type": "업태구분명",
    "x_tm": "좌표정보(X)",
    "y_tm": "좌표정보(Y)",
    "source_modified_at": "최종수정일자",
    "source_updated_at": "데이터갱신일자",
}
CHANGE_FIELDS = tuple(k for k in FIELDS if k not in {
    "gov_code", "license_id", "source_modified_at", "source_updated_at",
})


def has_invalid_bytes(value):
    return any(0xDC80 <= ord(char) <= 0xDCFF for char in value)


def read_snapshot(manifest_path, gov_code):
    manifest_path = Path(manifest_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    filename = manifest["file"]
    if not isinstance(filename, str) or Path(filename).name != filename:
        raise ValueError("manifest file must be a filename")
    source = manifest_path.parent / filename
    raw = source.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != manifest["sha256"] or len(raw) != manifest["size_bytes"]:
        raise ValueError(f"snapshot integrity mismatch: {manifest_path}")
    reader = csv.DictReader(io.StringIO(raw.decode("cp949", errors="surrogateescape")), strict=True)
    headers = reader.fieldnames or []
    if len(headers) != len(set(headers)) or not set(FIELDS.values()).issubset(headers):
        raise ValueError(f"missing or duplicate columns: {manifest_path}")
    groups = defaultdict(list)
    issues = []
    total = 0
    selected = 0
    for ordinal, row in enumerate(reader, 1):
        total += 1
        if None in row or any(value is None for value in row.values()):
            raise ValueError(f"malformed CSV record {ordinal}: {manifest_path}")
        values = {key: value.strip() for key, value in row.items()}
        invalid = [key for key, value in values.items() if has_invalid_bytes(value)]
        if invalid:
            issues.append({"kind": "decode_error", "record_number": ordinal, "fields": invalid})
        if values[FIELDS["gov_code"]] != gov_code:
            continue
        selected += 1
        license_id = values[FIELDS["license_id"]]
        if not license_id or has_invalid_bytes(license_id):
            issues.append({"kind": "invalid_identifier", "record_number": ordinal})
            continue
        record = {
            "record_number": ordinal,
            "values": {key: values[column] for key, column in FIELDS.items()},
            "invalid": bool(invalid),
        }
        groups[license_id].append(record)
    eligible = {}
    blocked = set()
    for license_id, records in sorted(groups.items()):
        if len(records) > 1:
            issues.append({
                "kind": "duplicate_identifier", "license_id": license_id,
                "record_numbers": [r["record_number"] for r in records],
            })
        if len(records) > 1 or any(r["invalid"] for r in records):
            blocked.add(license_id)
        else:
            record = records[0]
            eligible[license_id] = {
                "evidence_id": f"{digest}:{record['record_number']}",
                "record_number": record["record_number"],
                **record["values"],
            }
    return {
        "source": {
            "manifest": str(manifest_path), "sha256": digest,
            "collected_on": manifest.get("collected_on"),
            "url": manifest.get("source", {}).get("url"),
        },
        "summary": {
            "total_records": total, "selected_records": selected,
            "eligible_identifiers": len(eligible), "blocked_identifiers": len(blocked),
            "issue_counts": dict(sorted(Counter(i["kind"] for i in issues).items())),
        },
        "records": eligible, "blocked": blocked, "issues": issues,
    }


def compare_snapshots(previous, current, gov_code):
    blocked = previous["blocked"] | current["blocked"]
    old = previous["records"]
    new = current["records"]
    changes = []
    unchanged = 0
    for license_id in sorted((old.keys() | new.keys()) - blocked):
        before = old.get(license_id)
        after = new.get(license_id)
        if before is None:
            kind = "added"
            fields = []
        elif after is None:
            kind = "absent_from_current"
            fields = []
        else:
            fields = [key for key in CHANGE_FIELDS if before[key] != after[key]]
            if not fields:
                unchanged += 1
                continue
            kind = "modified"
        identity = [previous["source"]["sha256"], current["source"]["sha256"], gov_code, license_id]
        change_id = hashlib.sha256(json.dumps(identity, ensure_ascii=False).encode()).hexdigest()
        changes.append({
            "change_id": change_id, "kind": kind, "license_id": license_id,
            "changed_fields": fields, "before": before, "after": after,
        })
    return {
        "schema_version": 1, "policy_version": "quarantine-duplicates-v1",
        "gov_code": gov_code,
        "previous": {key: previous[key] for key in ("source", "summary", "issues")},
        "current": {key: current[key] for key in ("source", "summary", "issues")},
        "summary": {
            "unchanged": unchanged, "excluded_identifiers": len(blocked),
            "changes_by_kind": dict(sorted(Counter(c["kind"] for c in changes).items())),
            "changes_by_field": dict(sorted(Counter(f for c in changes for f in c["changed_fields"]).items())),
        },
        "excluded_identifiers": sorted(blocked), "changes": changes,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--previous", required=True, type=Path)
    parser.add_argument("--current", required=True, type=Path)
    parser.add_argument("--gov-code", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output already exists; choose a new output path")
    previous = read_snapshot(args.previous, args.gov_code)
    current = read_snapshot(args.current, args.gov_code)
    result = compare_snapshots(previous, current, args.gov_code)
    serialized = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    with args.output.open("x", encoding="utf-8") as target:
        target.write(serialized)
    print(json.dumps(result["summary"], ensure_ascii=False))


if __name__ == "__main__":
    main()
