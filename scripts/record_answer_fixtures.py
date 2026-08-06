#!/usr/bin/env python3
"""그라운딩 트랩 8개를 Gemini API 에 던져 응답 원문을 ask-api 답변 픽스처로 저장한다.

  전제:  export GEMINI_API_KEY=...
  실행:  python3 scripts/record_answer_fixtures.py
         python3 scripts/record_answer_fixtures.py --force      # 이미 녹화된 것도 다시
         python3 scripts/record_answer_fixtures.py --dry-run    # 무엇을 부를지·어떤 키로 쓸지만 출력
"""

import argparse
import hashlib
import json
import os
import sys
import time
import unicodedata
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from grounding_experiments import EXPERIMENTS, render_context  # noqa: E402

try:
    import yaml
except ImportError:
    sys.exit("PyYAML 이 필요합니다: pip3 install pyyaml")

ROOT = Path(__file__).resolve().parent.parent
MODULE = ROOT / "services" / "ask-api"
PROMPT_SPEC = MODULE / "src" / "main" / "resources" / "prompt" / "answer-generate.json"
APPLICATION_YML = MODULE / "src" / "main" / "resources" / "application.yml"
FIXTURES = MODULE / "src" / "test" / "resources" / "fixtures" / "answer"
INDEX = "index.json"
ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
THINKING_LEVEL = "minimal"
SEPARATOR = "\n---\n"
FREE_TIER_RPM = 5
RETRY_MARGIN = 2.0
RETRY_FALLBACK = 15.0


def nfc(text):
    return unicodedata.normalize("NFC", text.strip())


def fingerprint(question, context):
    raw = f"{nfc(question)}{SEPARATOR}{nfc(context)}".encode("utf-8")
    return hashlib.sha256(raw).hexdigest()[:12]


def load_prompt_spec(path):
    spec = json.loads(path.read_text(encoding="utf-8"))
    return spec["version"], spec["system"], spec["responseSchema"]


def default_model(path):
    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    return doc["psp"]["ask"]["gemini"]["model"]


def build_body(system, schema, question, context):
    return {
        "contents": [{"parts": [{"text": f"질문: {question}\n\n{context}\n\n{system}"}]}],
        "generationConfig": {
            "thinkingConfig": {"thinkingLevel": THINKING_LEVEL},
            "responseMimeType": "application/json",
            "responseSchema": schema,
        },
    }


def retry_delay(detail, fallback):
    try:
        for item in json.loads(detail)["error"]["details"]:
            if item.get("@type", "").endswith("RetryInfo"):
                return float(item["retryDelay"].rstrip("s")) + RETRY_MARGIN
    except (json.JSONDecodeError, KeyError, ValueError):
        pass
    return fallback


def compact(detail):
    try:
        return json.loads(detail)["error"]["message"].split("\n")[0][:160]
    except (json.JSONDecodeError, KeyError):
        return detail[:160]


def call(model, api_key, body, timeout, retries):
    request = urllib.request.Request(
        ENDPOINT.format(model=model),
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "x-goog-api-key": api_key},
        method="POST",
    )
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.load(response), None
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")
            if e.code == 429 and attempt < retries:
                wait = retry_delay(detail, RETRY_FALLBACK)
                print(f"          429 · {wait:.0f}초 기다렸다 재시도 ({attempt + 1}/{retries})")
                time.sleep(wait)
                continue
            return None, f"HTTP {e.code} · {compact(detail)}"
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
            if attempt < retries:
                print(f"          {type(e).__name__} · {RETRY_FALLBACK:.0f}초 기다렸다 재시도 ({attempt + 1}/{retries})")
                time.sleep(RETRY_FALLBACK)
                continue
            return None, str(e)
    return None, "재시도 한도 초과"


def targets():
    for experiment in EXPERIMENTS:
        question = experiment["question"]
        context = render_context(experiment["places"])
        yield experiment["id"], question, context, fingerprint(question, context)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures", type=Path, default=FIXTURES)
    ap.add_argument("--model", default=None)
    ap.add_argument("--sleep", type=float, default=60.0 / FREE_TIER_RPM + 1, help=f"호출 간 간격(초). 무료 티어가 분당 {FREE_TIER_RPM}회")
    ap.add_argument("--retries", type=int, default=3, help="429 를 받았을 때 재시도 횟수")
    ap.add_argument("--timeout", type=float, default=120.0)
    ap.add_argument("--force", action="store_true", help="이미 녹화된 것도 다시 부른다")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    version, system, schema = load_prompt_spec(PROMPT_SPEC)
    model = args.model or default_model(APPLICATION_YML)
    args.fixtures.mkdir(parents=True, exist_ok=True)

    index_path = args.fixtures / INDEX
    index = json.loads(index_path.read_text(encoding="utf-8")) if index_path.exists() else {}
    entries = index.get("entries", {})

    if index.get("promptVersion") not in (None, version):
        print(f"인덱스 프롬프트 버전이 {index.get('promptVersion')} → {version} 로 바뀌었습니다.")

    plan = list(targets())
    todo = []
    for experiment, question, context, key in plan:
        entry = entries.get(key)
        recorded = (
            entry is not None
            and entry.get("source") == "recorded"
            and entry.get("promptVersion") == version
            and (args.fixtures / entry["file"]).exists()
        )
        if recorded and not args.force:
            continue
        todo.append((experiment, question, context, key))

    print(f"트랩 {len(plan)}개 · 녹화 대상 {len(todo)}개 · 모델 {model} · 프롬프트 v{version}")

    if args.dry_run:
        for experiment, question, context, key in plan:
            mark = "→" if any(t[3] == key for t in todo) else " "
            print(f"  {mark} {key}  {experiment:24s} {question}")
        return 0

    if not todo:
        print("전부 녹화돼 있습니다. 다시 부르려면 --force.")
        return 0

    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        sys.exit("GEMINI_API_KEY 환경변수가 없습니다.")

    failed = []
    for n, (experiment, question, context, key) in enumerate(todo):
        print(f"  [{n + 1}/{len(todo)}] {key}  {experiment}")
        raw, error = call(model, api_key, build_body(system, schema, question, context), args.timeout, args.retries)
        if error:
            print(f"          실패 · {error}")
            failed.append(experiment)
        else:
            (args.fixtures / f"{key}.json").write_text(
                json.dumps(raw, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )
            entries[key] = {
                "file": f"{key}.json",
                "experiment": experiment,
                "question": question,
                "source": "recorded",
                "promptVersion": version,
            }
        if n < len(todo) - 1:
            time.sleep(args.sleep)

    recorded_all = all(
        entries.get(key, {}).get("promptVersion") == version for _, _, _, key in plan
    )
    index_path.write_text(
        json.dumps(
            {
                "promptVersion": version if recorded_all else index.get("promptVersion"),
                "model": model,
                "entries": entries,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )

    if failed:
        print(f"\n실패 {len(failed)}건: {', '.join(failed)} — 다시 실행하면 실패분만 부릅니다.")
        return 1
    print(f"\n{len(todo)}건 녹화 · {args.fixtures}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
