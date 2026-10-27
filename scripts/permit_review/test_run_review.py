import copy
import io
import json
import unittest
import urllib.error
from unittest.mock import patch

from gemini_client import GeminiClient, ModelError
from run_review import FixtureClient, Limits, run_review
from test_review_tools import context, submission


def response(name, args, **part_fields):
    return {
        "candidates": [{"finishReason": "STOP", "content": {"role": "model", "parts": [
            {"functionCall": {"name": name, "args": args, "id": name + "-id"}, **part_fields},
        ]}}],
        "usageMetadata": {"promptTokenCount": 8, "candidatesTokenCount": 2, "totalTokenCount": 10},
    }


def successful_responses():
    return [
        response("get_permit_change", {"change_id": "C1"}, thoughtSignature="opaque-signature"),
        response("find_place_candidates", {"change_id": "C1", "page": 0}),
        response("get_place_evidence", {"change_id": "C1", "place_id": "P00"}),
        response("submit_review", submission()),
    ]


class CapturingClient(FixtureClient):
    def __init__(self, responses):
        super().__init__(responses)
        self.bodies = []

    def generate(self, body, timeout):
        self.bodies.append(copy.deepcopy(body))
        return super().generate(body, timeout)


class RunReviewTest(unittest.TestCase):
    def test_invalid_structured_explanation_can_be_corrected_before_submission(self):
        responses = successful_responses()
        invalid = response("submit_review", submission(fact_refs=[]))
        client = CapturingClient([*responses[:3], invalid, responses[3]])
        result = run_review(client, context())
        self.assertEqual("completed", result["status"])
        self.assertEqual("permit-review-v3", result["tool_version"])
        self.assertEqual(submission()["fact_refs"], result["review"]["fact_refs"])
        self.assertEqual("새이름", result["review"]["facts"][1]["value"])
        self.assertIn("error", client.bodies[-1]["contents"][-1]["parts"][0]["functionResponse"]["response"])

    def test_full_loop_preserves_signature_call_id_and_records_usage(self):
        client = CapturingClient(successful_responses())
        result = run_review(client, context())
        self.assertEqual("completed", result["status"])
        self.assertEqual(40, result["reported_total_tokens"])
        self.assertEqual(4, result["tool_calls"])
        second = client.bodies[1]["contents"]
        self.assertEqual("opaque-signature", second[1]["parts"][0]["thoughtSignature"])
        self.assertEqual("get_permit_change-id", second[2]["parts"][0]["functionResponse"]["id"])
        self.assertEqual("C1", second[2]["parts"][0]["functionResponse"]["response"]["change_id"])

    def test_invalid_tool_arguments_return_error_and_allow_correction(self):
        client = CapturingClient([response("get_permit_change", {"change_id": "wrong"}), *successful_responses()])
        result = run_review(client, context())
        self.assertEqual("completed", result["status"])
        self.assertIn("error", client.bodies[1]["contents"][-1]["parts"][0]["functionResponse"]["response"])

    def test_identical_repeated_call_stops_without_reexecution(self):
        first = successful_responses()[0]
        result = run_review(FixtureClient([first, first]), context())
        self.assertEqual("repeated_call", result["stop_reason"])
        self.assertEqual(1, result["tool_calls"])

    def test_model_and_tool_call_limits_are_distinct(self):
        result = run_review(FixtureClient(successful_responses()), context(), Limits(model_calls=1))
        self.assertEqual("model_call_limit", result["stop_reason"])
        result = run_review(FixtureClient(successful_responses()), context(), Limits(tool_calls=1))
        self.assertEqual("tool_call_limit", result["stop_reason"])

    def test_context_and_token_limits_stop_before_tools_execute(self):
        client = CapturingClient(successful_responses())
        result = run_review(client, context(), Limits(request_bytes=1))
        self.assertEqual("context_limit", result["stop_reason"])
        self.assertEqual([], client.bodies)
        result = run_review(FixtureClient(successful_responses()), context(), Limits(total_tokens=10))
        self.assertEqual("token_limit", result["stop_reason"])
        self.assertEqual(0, result["tool_calls"])

    def test_missing_usage_does_not_allow_unmetered_followup_calls(self):
        first = successful_responses()[0]
        del first["usageMetadata"]
        result = run_review(FixtureClient([first]), context())
        self.assertEqual("usage_missing", result["stop_reason"])
        self.assertEqual(0, result["tool_calls"])

    def test_late_model_result_is_not_executed(self):
        now = [0.0]
        class SlowClient(FixtureClient):
            def generate(self, body, timeout):
                now[0] = 100
                return super().generate(body, timeout)
        result = run_review(SlowClient(successful_responses()), context(), clock=lambda: now[0])
        self.assertEqual("deadline", result["stop_reason"])
        self.assertEqual(0, result["tool_calls"])

    def test_keyboard_interrupt_preserves_partial_trace(self):
        class InterruptedClient(FixtureClient):
            def generate(self, body, timeout):
                if len(body["contents"]) > 1:
                    raise KeyboardInterrupt()
                return super().generate(body, timeout)
        result = run_review(InterruptedClient(successful_responses()), context())
        self.assertEqual("cancelled", result["stop_reason"])
        self.assertEqual(1, result["tool_calls"])
        self.assertIsNone(result["review"])

    def test_http_or_model_failure_is_not_a_successful_review(self):
        class FailingClient(FixtureClient):
            def generate(self, body, timeout):
                raise ModelError("http_429")
        result = run_review(FailingClient([]), context())
        self.assertEqual("http_429", result["stop_reason"])
        self.assertEqual("incomplete", result["status"])

    def test_plain_text_is_not_accepted_as_a_valid_review(self):
        raw = successful_responses()[0]
        raw["candidates"][0]["content"]["parts"] = [{"text": "갱신하세요"}]
        self.assertEqual("missing_function_call", run_review(FixtureClient([raw]), context())["stop_reason"])

    def test_multiple_calls_are_supported_but_submission_cannot_be_batched(self):
        responses = successful_responses()
        responses[1]["candidates"][0]["content"]["parts"].extend(responses[2]["candidates"][0]["content"]["parts"])
        result = run_review(FixtureClient([responses[0], responses[1], responses[3]]), context())
        self.assertEqual("completed", result["status"])
        responses[0]["candidates"][0]["content"]["parts"].extend(responses[3]["candidates"][0]["content"]["parts"])
        result = run_review(FixtureClient([responses[0]]), context())
        self.assertEqual("submission_must_be_single_call", result["stop_reason"])

    def test_internal_tool_failure_preserves_trace_without_exposing_exception_text(self):
        tools = context()
        tools.execute = lambda *args: (_ for _ in ()).throw(RuntimeError("private detail"))
        result = run_review(FixtureClient(successful_responses()), tools)
        self.assertEqual("tool_failure", result["stop_reason"])
        self.assertNotIn("private detail", json.dumps(result))

    def test_invalid_limits_are_rejected(self):
        for limits in [{"seconds": float("nan")}, {"model_calls": 0}, {"tool_calls": True}, {"total_tokens": 1.5}]:
            with self.assertRaises(ValueError):
                Limits(**limits)


class GeminiClientTest(unittest.TestCase):
    def test_connection_reset_is_reported_as_a_model_failure(self):
        with patch("gemini_client.urllib.request.urlopen", side_effect=ConnectionResetError()):
            with self.assertRaisesRegex(ModelError, "model_unreachable_or_timeout"):
                GeminiClient("gemini-test", "secret").generate({}, 1)

    def test_http_error_does_not_retry_or_expose_body(self):
        error = urllib.error.HTTPError("https://example", 429, "limited", {}, io.BytesIO(b"private"))
        with patch("gemini_client.urllib.request.urlopen", side_effect=error) as call:
            with self.assertRaisesRegex(ModelError, "^http_429$"):
                GeminiClient("gemini-test", "secret").generate({}, 1)
            self.assertEqual(1, call.call_count)

    def test_wire_request_uses_header_auth_and_keeps_function_payload(self):
        from run_review import request_body
        class Reply(io.BytesIO):
            pass
        body = request_body([], Limits())
        with patch("gemini_client.urllib.request.urlopen", return_value=Reply(b'{"usageMetadata":{}}')) as call:
            GeminiClient("gemini-test", "secret").generate(body, 5)
            request = call.call_args.args[0]
            self.assertNotIn("secret", request.full_url)
            self.assertEqual(body, json.loads(request.data))
            self.assertEqual("secret", request.get_header("X-goog-api-key"))


if __name__ == "__main__":
    unittest.main()
