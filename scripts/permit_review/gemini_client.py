import json
import re
import time
import urllib.error
import urllib.request


class ModelError(Exception):
    pass


class GeminiClient:
    def __init__(self, model, api_key):
        if not re.fullmatch(r"gemini-[a-zA-Z0-9.-]+", model) or not api_key:
            raise ValueError("model and GEMINI_API_KEY are required")
        self.model = model
        self.api_key = api_key

    def generate(self, body, timeout):
        request = urllib.request.Request(
            f"https://generativelanguage.googleapis.com/v1beta/models/{self.model}:generateContent",
            data=json.dumps(body, ensure_ascii=False).encode(),
            headers={"Content-Type": "application/json", "x-goog-api-key": self.api_key},
            method="POST",
        )
        deadline = time.monotonic() + timeout
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                payload = bytearray()
                while True:
                    if time.monotonic() >= deadline:
                        raise ModelError("model_timeout")
                    chunk = response.read1(16384)
                    if not chunk:
                        break
                    payload.extend(chunk)
                    if len(payload) > 1048576:
                        raise ModelError("model_response_too_large")
                return json.loads(payload)
        except urllib.error.HTTPError as error:
            error.close()
            raise ModelError(f"http_{error.code}") from None
        except OSError:
            raise ModelError("model_unreachable_or_timeout") from None
        except (ValueError, UnicodeError):
            raise ModelError("invalid_model_json") from None
