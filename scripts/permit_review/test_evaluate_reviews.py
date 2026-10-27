import copy
import json
import unittest
from pathlib import Path

from evaluate_reviews import evaluate


class EvaluationTest(unittest.TestCase):
    def setUp(self):
        self.dataset = json.loads(Path(__file__).with_name("eval_cases.json").read_text())
        case = self.dataset["cases"][0]
        expected = case["expected"]
        self.run = {
            "change_id": case["change_id"], "status": "completed", "run_id": "test",
            "sources": {name: {"sha256": self.dataset["sources"][name + "_sha256"]}
                        for name in ("changes", "places")},
            "review": {**copy.deepcopy(expected), "change_id": case["change_id"],
                       "evidence_ids": expected["required_evidence_ids"], "reason": "설명",
                       "requires_human_review": True, "semantic_accuracy_verified": False},
        }

    def result(self):
        return evaluate(self.dataset, [self.run])["results"][0]

    def test_matching_fields_do_not_certify_reason_quality(self):
        result = self.result()
        self.assertEqual("structural_match", result["status"])
        self.assertEqual("pending", result["manual_status"])

    def test_wrong_target_fails_even_when_decision_matches(self):
        self.run["review"]["place_id"] = "wrong"
        self.assertEqual("mismatch", self.result()["status"])

    def test_deferring_a_proposal_case_is_a_mismatch(self):
        self.run["review"].update(decision="needs_review", updates=[], place_id="")
        self.assertEqual("mismatch", self.result()["status"])

    def test_wrong_update_and_missing_evidence_are_detected(self):
        self.run["review"]["updates"][0]["after"] = "wrong"
        self.run["review"]["evidence_ids"] = []
        checks = self.result()["checks"]
        self.assertFalse(checks["updates"])
        self.assertFalse(checks["evidence"])

    def test_missing_runs_remain_in_denominator(self):
        group = evaluate(self.dataset, [self.run])["groups"]["pilot"]
        self.assertEqual({"numerator": 1, "denominator": 4}, group["structural_agreement"])
        self.assertEqual(3, group["counts"]["missing"])

    def test_incomplete_is_not_a_deferral(self):
        self.run["status"] = "incomplete"
        group = evaluate(self.dataset, [self.run])["groups"]["pilot"]
        self.assertEqual(1, group["counts"]["incomplete"])
        self.assertEqual(0, group["deferred"])

    def test_mismatched_source_is_not_scored(self):
        self.run["sources"]["places"]["sha256"] = "wrong"
        self.assertEqual("source_mismatch", self.result()["status"])

    def test_pending_label_is_excluded_from_denominator(self):
        self.dataset["cases"][0]["label_status"] = "pending"
        group = evaluate(self.dataset, [self.run])["groups"]["pilot"]
        self.assertEqual(3, group["labeled_cases"])
        self.assertEqual(1, group["counts"]["unlabeled"])

    def test_duplicate_and_unknown_runs_are_rejected(self):
        with self.assertRaises(ValueError):
            evaluate(self.dataset, [self.run, self.run])
        self.run["change_id"] = "unknown"
        with self.assertRaises(ValueError):
            evaluate(self.dataset, [self.run])

    def test_malformed_evidence_fails_without_crashing(self):
        self.run["review"]["evidence_ids"] = [{}]
        self.assertEqual("mismatch", self.result()["status"])


class StructuredEvaluationTest(unittest.TestCase):
    def setUp(self):
        self.dataset = json.loads(Path(__file__).with_name("eval_cases_v2.json").read_text())
        case = next(c for c in self.dataset["cases"] if c["case_id"] == "ambiguous_name")
        self.dataset["cases"] = [case]
        self.pid = next(p for p in case["expected"]["allowed_place_evidence"] if p)
        self.eid = case["expected"]["allowed_place_evidence"][self.pid]
        permit = case["expected"]["required_evidence_ids"]
        key = case["change_id"]
        self.run = {
            "change_id": key, "status": "completed", "tool_version": "permit-review-v2",
            "sources": {n: {"sha256": self.dataset["sources"][n + "_sha256"]} for n in ("changes", "places")},
            "review": {"change_id": key, "decision": "needs_review", "place_id": self.pid,
                       "updates": [], "evidence_ids": [*permit, self.eid], "reason": "동일성 미확인",
                       "facts": [{"statement": "인허가 이름 변경", "evidence_ids": permit}],
                       "uncertainties": ["층·호수 미확인"],
                       "requires_human_review": True, "semantic_accuracy_verified": False},
            "trace": [
                {"type": "tool", "call": {"name": "get_permit_change", "args": {"change_id": key}},
                 "result": {"change_id": key, "before": {"evidence_id": permit[0]}, "after": {"evidence_id": permit[1]}}},
                {"type": "tool", "call": {"name": "get_place_evidence", "args": {"change_id": key, "place_id": self.pid}},
                 "result": {"place_id": self.pid, "evidence_id": self.eid}},
            ],
        }

    def result(self):
        return evaluate(self.dataset, [self.run])["results"][0]

    def test_observed_allowed_reference_passes_but_semantics_remain_pending(self):
        self.assertEqual("structural_match", self.result()["status"])
        self.assertEqual("pending", self.result()["manual_status"])

    def test_unlisted_reference_is_not_accepted(self):
        self.run["review"]["place_id"] = "another"
        self.assertFalse(self.result()["checks"]["place_id"])

    def test_reference_without_detail_or_citation_is_rejected(self):
        self.run["trace"].pop()
        self.assertFalse(self.result()["checks"]["place_id"])
        self.setUp()
        self.run["review"]["evidence_ids"].remove(self.eid)
        self.assertFalse(self.result()["checks"]["place_id"])

    def test_old_tool_version_cannot_pass_new_contract(self):
        self.run["tool_version"] = "permit-review-v1"
        self.assertFalse(self.result()["checks"]["tool_version"])

    def test_missing_explanation_and_unobserved_fact_evidence_fail(self):
        del self.run["review"]["uncertainties"]
        self.assertFalse(self.result()["checks"]["explanation"])
        self.setUp()
        self.run["review"]["facts"][0]["evidence_ids"] = ["invented"]
        self.assertFalse(self.result()["checks"]["explanation"])

    def test_wrong_evidence_for_allowed_place_is_rejected(self):
        self.run["trace"][-1]["result"]["evidence_id"] = "other"
        self.assertFalse(self.result()["checks"]["place_id"])

    def test_empty_reference_is_allowed_for_deferral(self):
        self.run["review"]["place_id"] = ""
        self.assertEqual("structural_match", self.result()["status"])


class ReferencedEvaluationTest(unittest.TestCase):
    def setUp(self):
        from test_review_tools import context, submission
        tools = context()
        trace = []
        def execute(name, args):
            result = tools.execute(name, args)
            trace.append({"type": "tool", "call": {"name": name, "args": args}, "result": result})
            return result
        execute("get_permit_change", {"change_id": "C1"})
        lookup = execute("find_place_candidates", {"change_id": "C1", "page": 0})
        execute("get_place_evidence", {"change_id": "C1", "place_id": "P00"})
        args = submission()
        args["evidence_ids"].append(lookup["evidence_id"])
        args["fact_refs"].append({"evidence_id": lookup["evidence_id"], "field": "status"})
        execute("submit_review", args)
        self.run = {"change_id": "C1", "tool_version": "permit-review-v3", "status": "completed",
                    "review": tools.review, "trace": trace,
                    "sources": {"places": {"sha256": "placehash"}, "changes": {"sha256": "changes"}}}
        self.dataset = {"schema_version": 3, "sources": {"places_sha256": "placehash", "changes_sha256": "changes"},
                        "cases": [{"case_id": "test", "cohort": "test", "label_status": "confirmed", "change_id": "C1",
                                   "manual_criteria": [], "expected": {"decision": "propose_update", "place_id": "P00",
                                   "updates": args["updates"], "required_evidence_ids": ["old:1", "new:1", "place:0"],
                                   "allowed_place_evidence": {"P00": "place:0"}, "required_lookup_fields": ["status"]}}]}

    def result(self):
        return evaluate(self.dataset, [self.run])["results"][0]

    def test_original_values_and_query_context_pass_without_semantic_certification(self):
        self.assertEqual("structural_match", self.result()["status"])
        self.assertEqual("pending", self.result()["manual_status"])

    def test_tampered_value_is_detected(self):
        self.run["review"]["facts"][1]["value"] = "비앤스"
        self.assertFalse(self.result()["checks"]["explanation"])

    def test_removed_page_context_is_detected(self):
        del self.run["review"]["facts"][-1]["context"]
        self.assertFalse(self.result()["checks"]["explanation"])

    def test_modified_query_response_cannot_keep_its_evidence_id(self):
        self.run["trace"][1]["result"]["total_candidates"] = 99
        self.assertFalse(self.result()["checks"]["explanation"])
        self.assertFalse(self.result()["checks"]["evidence"])

    def test_wrong_query_page_or_source_is_rejected(self):
        self.run["trace"][1]["call"]["args"]["page"] = 1
        self.assertFalse(self.result()["checks"]["explanation"])
        self.setUp()
        self.run["sources"]["places"]["sha256"] = "different"
        self.assertEqual("source_mismatch", self.result()["status"])

    def test_required_query_fact_cannot_be_replaced_with_permit_fact(self):
        self.run["review"]["fact_refs"].pop()
        self.run["review"]["facts"].pop()
        self.assertTrue(self.result()["checks"]["explanation"])
        self.assertFalse(self.result()["checks"]["lookup_facts"])


if __name__ == "__main__":
    unittest.main()
