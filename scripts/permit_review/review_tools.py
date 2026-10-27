import copy
import json

from find_candidates import find_candidates


TOOL_VERSION = "permit-review-v1"
PAGE_SIZE = 10
MAX_RESULT_BYTES = 24576
STRING = {"type": "STRING"}


def declaration(name, description, properties):
    return {"name": name, "description": description, "parameters": {
        "type": "OBJECT", "properties": properties, "required": list(properties),
    }}


TOOL_DECLARATIONS = [
    declaration("get_permit_change", "현재 검토의 인허가 변경과 원본 근거 조회", {"change_id": STRING}),
    declaration("find_place_candidates", "건물 주소 후보 페이지 조회. page는 0부터 시작하며 페이지 크기는 10", {
        "change_id": STRING, "page": {"type": "INTEGER"},
    }),
    declaration("get_place_evidence", "이미 조회한 후보의 원본 필드와 근거 조회", {
        "change_id": STRING, "place_id": STRING,
    }),
    declaration("submit_review", "원본 수정 없이 검토 제안 제출. 상호 변경만 propose_update 허용. 불확실하면 needs_review", {
        "change_id": STRING,
        "decision": {"type": "STRING", "enum": ["propose_update", "no_change", "needs_review"]},
        "place_id": {"type": "STRING", "description": "대상 미확정이면 빈 문자열"},
        "updates": {"type": "ARRAY", "items": {"type": "OBJECT", "properties": {
            "field": STRING, "before": STRING, "after": STRING,
        }, "required": ["field", "before", "after"]}},
        "evidence_ids": {"type": "ARRAY", "items": STRING},
        "reason": STRING,
    }),
]


class ToolValidationError(ValueError):
    pass


class ReviewTools:
    def __init__(self, change, places):
        self.change = copy.deepcopy(change)
        self.lookup = find_candidates(change, places)
        self.candidates = {r["place_id"]: r for r in self.lookup["candidates"]}
        self.change_seen = False
        self.pages_seen = set()
        self.places_seen = set()
        self.details_seen = set()
        self.evidence_seen = set()
        self.review = None

    def execute(self, name, args):
        snapshot = copy.deepcopy((self.change_seen, self.pages_seen, self.places_seen, self.details_seen, self.evidence_seen, self.review))
        try:
            result = self.dispatch(name, args)
            if len(json.dumps(result, ensure_ascii=False).encode()) > MAX_RESULT_BYTES:
                raise ToolValidationError("tool_result_too_large")
            return result
        except Exception:
            self.change_seen, self.pages_seen, self.places_seen, self.details_seen, self.evidence_seen, self.review = snapshot
            raise

    def dispatch(self, name, args):
        schema = next((d for d in TOOL_DECLARATIONS if d["name"] == name), None)
        if schema is None:
            raise ToolValidationError("unknown_tool")
        if not isinstance(args, dict) or set(args) != set(schema["parameters"]["properties"]):
            raise ToolValidationError("invalid_argument_keys")
        if args["change_id"] != self.change["change_id"]:
            raise ToolValidationError("change_outside_current_review")
        if self.review is not None:
            raise ToolValidationError("review_already_submitted")
        if name == "get_permit_change":
            self.change_seen = True
            for side in ("before", "after"):
                if self.change[side]:
                    self.evidence_seen.add(self.change[side]["evidence_id"])
            return copy.deepcopy(self.change)
        if not self.change_seen:
            raise ToolValidationError("read_change_first")
        if name == "find_place_candidates":
            page = args["page"]
            total = len(self.candidates)
            if type(page) is not int or page < 0 or page >= max(1, (total + PAGE_SIZE - 1) // PAGE_SIZE):
                raise ToolValidationError("invalid_page")
            start = page * PAGE_SIZE
            records = self.lookup["candidates"][start:start + PAGE_SIZE]
            self.pages_seen.add(page)
            self.places_seen.update(r["place_id"] for r in records)
            return {
                "status": self.lookup["status"], "total_candidates": total,
                "page": page, "page_size": PAGE_SIZE,
                "next_page": page + 1 if start + PAGE_SIZE < total else None,
                "has_more": start + PAGE_SIZE < total,
                "scope": self.lookup["scope"], "identity_confirmed": False,
                "query_targets": self.lookup["query_targets"],
                "place_address_issue_count": len(self.lookup["place_address_issues"]),
                "candidates": [{k: r[k] for k in ("place_id", "name", "branch", "category_small", "road_address")} for r in records],
            }
        if name == "get_place_evidence":
            place_id = args["place_id"]
            if not isinstance(place_id, str) or place_id not in self.places_seen:
                raise ToolValidationError("place_not_observed")
            record = self.candidates[place_id]
            self.details_seen.add(place_id)
            self.evidence_seen.add(record["evidence_id"])
            return copy.deepcopy(record)
        return self.submit(args)

    def submit(self, args):
        decision = args["decision"]
        if decision not in ("propose_update", "no_change", "needs_review"):
            raise ToolValidationError("invalid_decision")
        if not isinstance(args["reason"], str) or not 1 <= len(args["reason"].strip()) <= 2000:
            raise ToolValidationError("invalid_reason")
        evidence = args["evidence_ids"]
        if not isinstance(evidence, list) or not evidence or len(evidence) > 20 or not all(isinstance(e, str) for e in evidence):
            raise ToolValidationError("invalid_evidence_list")
        if not set(evidence).issubset(self.evidence_seen):
            raise ToolValidationError("evidence_not_observed")
        permit_evidence = {self.change[s]["evidence_id"] for s in ("before", "after") if self.change[s]}
        if not permit_evidence.issubset(evidence):
            raise ToolValidationError("permit_evidence_required")
        place_id = args["place_id"]
        if not isinstance(place_id, str) or (place_id and place_id not in self.details_seen):
            raise ToolValidationError("place_detail_not_observed")
        if place_id and self.candidates[place_id]["evidence_id"] not in evidence:
            raise ToolValidationError("target_evidence_required")
        updates = args["updates"]
        if not isinstance(updates, list):
            raise ToolValidationError("invalid_updates")
        if decision == "needs_review":
            if updates:
                raise ToolValidationError("deferred_review_cannot_update")
        else:
            if not place_id:
                raise ToolValidationError("target_required")
            expected_pages = set(range(max(1, (len(self.candidates) + PAGE_SIZE - 1) // PAGE_SIZE)))
            if self.pages_seen != expected_pages:
                raise ToolValidationError("candidate_pages_incomplete")
            if self.change["changed_fields"] != ["name"] or not self.change["after"]:
                raise ToolValidationError("only_name_changes_supported_for_decisive_review")
            before = self.candidates[place_id]["name"]
            after = self.change["after"]["name"]
            if decision == "no_change":
                if updates or before != after:
                    raise ToolValidationError("no_change_not_supported_by_target")
            elif not after.strip() or before == after or updates != [{"field": "name", "before": before, "after": after}]:
                raise ToolValidationError("update_must_match_observed_name_values")
        self.review = {**copy.deepcopy(args), "requires_human_review": True, "semantic_accuracy_verified": False}
        return {"accepted": True, "review": self.review}
