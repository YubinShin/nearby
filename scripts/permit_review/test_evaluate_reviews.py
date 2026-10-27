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


if __name__ == "__main__":
    unittest.main()
