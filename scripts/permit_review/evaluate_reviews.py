import argparse
import hashlib
import json
from pathlib import Path

from review_tools import ToolValidationError, lookup_evidence_id, resolve_facts, valid_explanation


def observed_records(run):
    records = {}
    trace = run.get("trace", [])
    if not isinstance(trace, list):
        return records
    for event in trace:
        if not isinstance(event, dict) or event.get("type") != "tool":
            continue
        call, result = event.get("call"), event.get("result")
        if not isinstance(call, dict) or not isinstance(result, dict) or "error" in result:
            continue
        args = call.get("args")
        if not isinstance(args, dict) or args.get("change_id") != run.get("change_id"):
            continue
        if call.get("name") == "get_permit_change" and result.get("change_id") == run.get("change_id"):
            for side in ("before", "after"):
                row = result.get(side)
                if isinstance(row, dict) and isinstance(row.get("evidence_id"), str):
                    records[row["evidence_id"]] = ("permit", row)
        elif call.get("name") == "get_place_evidence":
            if isinstance(result.get("evidence_id"), str) and result.get("place_id") == args.get("place_id"):
                records[result["evidence_id"]] = ("place", result)
        elif call.get("name") == "find_place_candidates":
            required = {"change_id", "place_source_sha256", "policy_version", "scope", "page", "page_size",
                        "total_candidates", "has_more", "next_page", "query_targets", "status", "candidates"}
            if (required.issubset(result) and result["change_id"] == run.get("change_id")
                    and type(result["page"]) is int and result["page"] == args.get("page")
                    and result["place_source_sha256"] == run.get("sources", {}).get("places", {}).get("sha256")
                    and result.get("evidence_id") == lookup_evidence_id(result)):
                records[result["evidence_id"]] = ("lookup", result)
    return records


def observed_details(run):
    evidence = set()
    places = {}
    trace = run.get("trace", [])
    if not isinstance(trace, list):
        return evidence, places
    for event in trace:
        if not isinstance(event, dict) or event.get("type") != "tool":
            continue
        call, result = event.get("call"), event.get("result")
        if not isinstance(call, dict) or not isinstance(result, dict) or "error" in result:
            continue
        args = call.get("args")
        if not isinstance(args, dict) or args.get("change_id") != run.get("change_id"):
            continue
        if call.get("name") == "get_permit_change" and result.get("change_id") == run.get("change_id"):
            for side in ("before", "after"):
                row = result.get(side)
                if isinstance(row, dict) and isinstance(row.get("evidence_id"), str):
                    evidence.add(row["evidence_id"])
        elif call.get("name") == "get_place_evidence":
            pid, eid = result.get("place_id"), result.get("evidence_id")
            if isinstance(pid, str) and pid == args.get("place_id") and isinstance(eid, str):
                places[pid] = eid
                evidence.add(eid)
    return evidence, places


def evaluate(dataset, runs):
    version = dataset.get("schema_version")
    if version not in (1, 2, 3):
        raise ValueError("unsupported evaluation schema")
    cases = dataset["cases"]
    ids = [case["change_id"] for case in cases]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate evaluation change")
    indexed = {}
    for run in runs:
        key = run.get("change_id")
        if key not in ids or key in indexed:
            raise ValueError("unknown or duplicate run change_id")
        indexed[key] = run
    results = []
    for case in cases:
        result = {"case_id": case["case_id"], "cohort": case["cohort"]}
        run = indexed.get(case["change_id"])
        if case["label_status"] != "confirmed":
            result["status"] = "unlabeled"
        elif run is None:
            result["status"] = "missing"
        else:
            sources = run.get("sources", {})
            valid_source = all(
                sources.get(name, {}).get("sha256") == dataset["sources"][name + "_sha256"]
                for name in ("changes", "places")
            )
            result.update({"run_id": run.get("run_id"), "stop_reason": run.get("stop_reason")})
            if not valid_source:
                result["status"] = "source_mismatch"
            elif run.get("status") != "completed" or not isinstance(run.get("review"), dict):
                result["status"] = "incomplete"
            else:
                review = run["review"]
                expected = case["expected"]
                evidence = review.get("evidence_ids")
                checks = {
                    "decision": review.get("decision") == expected["decision"],
                    "place_id": review.get("place_id") == expected["place_id"],
                    "updates": review.get("updates") == expected["updates"],
                    "change_id": review.get("change_id") == case["change_id"],
                    "evidence": isinstance(evidence, list) and all(isinstance(e, str) for e in evidence)
                    and set(expected["required_evidence_ids"]).issubset(evidence),
                    "human_gate": review.get("requires_human_review") is True
                    and review.get("semantic_accuracy_verified") is False,
                    "reason_present": isinstance(review.get("reason"), str) and bool(review["reason"].strip()),
                }
                if version in (2, 3):
                    observed, details = observed_details(run)
                    records = observed_records(run) if version == 3 else {}
                    if version == 3:
                        observed = set(records)
                    evidence_valid = isinstance(evidence, list) and all(isinstance(e, str) for e in evidence)
                    cited = set(evidence) if evidence_valid else set()
                    pid = review.get("place_id")
                    allowed = expected["allowed_place_evidence"]
                    target_valid = isinstance(pid, str) and pid in allowed
                    if target_valid and pid:
                        target_valid = details.get(pid) == allowed[pid] and allowed[pid] in cited
                    checks["place_id"] = target_valid
                    checks["evidence"] = checks["evidence"] and cited.issubset(observed)
                    checks["explanation"] = valid_explanation(review, observed)
                    checks["tool_version"] = run.get("tool_version") == "permit-review-v2"
                    if version == 3:
                        checks["tool_version"] = run.get("tool_version") == "permit-review-v3"
                        try:
                            facts = resolve_facts(review, records)
                            checks["explanation"] = json.dumps(facts, sort_keys=True) == json.dumps(review.get("facts"), sort_keys=True)
                            lookup_fields = {f["field"] for f in facts if f["kind"] == "lookup"}
                            checks["lookup_facts"] = set(expected.get("required_lookup_fields", [])).issubset(lookup_fields)
                        except ToolValidationError:
                            checks["explanation"] = False
                            checks["lookup_facts"] = False
                result.update({
                    "status": "structural_match" if all(checks.values()) else "mismatch",
                    "checks": checks, "decision": review.get("decision"),
                    "reason": review.get("reason"), "manual_status": "pending",
                    "manual_criteria": case["manual_criteria"],
                })
                if version in (2, 3):
                    result.update({"facts": review.get("facts"), "uncertainties": review.get("uncertainties")})
        results.append(result)
    groups = {}
    for cohort in sorted({r["cohort"] for r in results}):
        rows = [r for r in results if r["cohort"] == cohort]
        labeled = [r for r in rows if r["status"] != "unlabeled"]
        counts = {s: sum(r["status"] == s for r in rows) for s in (
            "unlabeled", "missing", "source_mismatch", "incomplete", "structural_match", "mismatch"
        )}
        groups[cohort] = {
            "counts": counts, "labeled_cases": len(labeled),
            "structural_agreement": {"numerator": counts["structural_match"], "denominator": len(labeled)},
            "completed": counts["structural_match"] + counts["mismatch"],
            "deferred": sum(r.get("decision") == "needs_review" for r in rows),
            "proposed": sum(r.get("decision") == "propose_update" for r in rows),
            "incorrect_proposals": sum(r.get("decision") == "propose_update" and r["status"] == "mismatch" for r in rows),
            "manual_pending": sum(r.get("manual_status") == "pending" for r in rows),
        }
    return {"schema_version": version, "groups": groups, "results": results,
            "semantic_accuracy_verified": False}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--runs", type=Path, nargs="*", default=[])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    cases_raw = args.cases.read_bytes()
    runs_raw = [path.read_bytes() for path in args.runs]
    report = evaluate(json.loads(cases_raw), [json.loads(raw) for raw in runs_raw])
    report["inputs"] = {
        "cases_sha256": hashlib.sha256(cases_raw).hexdigest(),
        "runs": [{"file": str(path), "sha256": hashlib.sha256(raw).hexdigest()}
                 for path, raw in zip(args.runs, runs_raw)],
    }
    with args.output.open("x", encoding="utf-8") as target:
        target.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report["groups"], ensure_ascii=False))


if __name__ == "__main__":
    main()
