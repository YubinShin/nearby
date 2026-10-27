import argparse
import hashlib
import json
from pathlib import Path


def evaluate(dataset, runs):
    if dataset.get("schema_version") != 1:
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
                result.update({
                    "status": "structural_match" if all(checks.values()) else "mismatch",
                    "checks": checks, "decision": review.get("decision"),
                    "reason": review.get("reason"), "manual_status": "pending",
                    "manual_criteria": case["manual_criteria"],
                })
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
    return {"schema_version": 1, "groups": groups, "results": results,
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
