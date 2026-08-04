#!/usr/bin/env python3
"""골든셋 질의를 Gemini API 에 한 번씩 던져 응답 원문을 픽스처로 저장한다.

  전제:  export GEMINI_API_KEY=...
  실행:  python3 scripts/record_llm_fixtures.py
         python3 scripts/record_llm_fixtures.py --force        # 이미 녹화된 것도 다시
         python3 scripts/record_llm_fixtures.py --dry-run      # 무엇을 부를지만 출력
         python3 scripts/record_llm_fixtures.py --sleep 1      # 호출 간격(기본 13초)
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

try:
    import yaml
except ImportError:
    sys.exit("PyYAML 이 필요합니다: pip3 install pyyaml")

ROOT = Path(__file__).resolve().parent.parent
GOLDEN_SET = ROOT / "scripts" / "golden_set.yaml"
MODULE = ROOT / "services" / "ask-api"
PROMPT_SPEC = MODULE / "src" / "main" / "resources" / "prompt" / "ask-parse.json"
APPLICATION_YML = MODULE / "src" / "main" / "resources" / "application.yml"
FIXTURES = MODULE / "src" / "test" / "resources" / "fixtures"
INDEX = "index.json"
ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
FREE_TIER_RPM = 5
RETRY_MARGIN = 2.0
RETRY_FALLBACK = 15.0


def fixture_name(query):
    return hashlib.sha256(unicodedata.normalize("NFC", query).encode("utf-8")).hexdigest()[:12] + ".json"


def load_queries(path):
    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    return [entry["query"].strip() for entry in doc["queries"]]


def load_prompt_spec(path):
    spec = json.loads(path.read_text(encoding="utf-8"))
    return spec["version"], spec["system"], spec["responseSchema"]


def default_model(path):
    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    return doc["psp"]["ask"]["gemini"]["model"]


def build_body(system, schema, query):
    return {
        "systemInstruction": {"parts": [{"text": system}]},
        "contents": [{"role": "user", "parts": [{"text": query}]}],
        "generationConfig": {
            "temperature": 0,
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


def compact(detail):
    try:
        return json.loads(detail)["error"]["message"].split("\n")[0][:160]
    except (json.JSONDecodeError, KeyError):
        return detail[:160]


def parsed_text(raw):
    try:
        return raw["candidates"][0]["content"]["parts"][0]["text"]
    except (KeyError, IndexError):
        return "(본문 없음)"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--golden-set", type=Path, default=GOLDEN_SET)
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
    queries = load_queries(args.golden_set)
    args.fixtures.mkdir(parents=True, exist_ok=True)

    index_path = args.fixtures / INDEX
    index = json.loads(index_path.read_text(encoding="utf-8")) if index_path.exists() else {}
    entries = index.get("entries", {})

    outside = [query for query in entries if query not in queries]
    queries += outside

    if index.get("promptVersion") not in (None, version):
        print(f"인덱스 프롬프트 버전이 {index.get('promptVersion')} → {version} 로 바뀌었습니다.")

    todo = []
    for query in queries:
        entry = entries.get(query)
        recorded = (
            entry is not None
            and entry.get("source") == "recorded"
            and entry.get("model") == model
            and entry.get("promptVersion") == version
            and (args.fixtures / entry["file"]).exists()
        )
        if recorded and not args.force:
            continue
        todo.append(query)

    print(
        f"질의 {len(queries)}개(골든셋 {len(queries) - len(outside)} · 인덱스 전용 {len(outside)}) · "
        f"녹화 대상 {len(todo)}개 · 모델 {model} · 프롬프트 v{version}"
    )
    if not todo:
        print("전부 녹화돼 있습니다. 다시 부르려면 --force.")
        return 0

    if args.dry_run:
        for query in todo:
            print(f"  {fixture_name(query)}  {query}")
        return 0

    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not api_key:
        sys.exit("GEMINI_API_KEY 가 비어 있습니다. export GEMINI_API_KEY=... 후 다시 실행하세요.")

    print(f"호출 간격 {args.sleep:.0f}초 · 예상 소요 {len(todo) * args.sleep / 60:.1f}분")

    failures = []
    for i, query in enumerate(todo, start=1):
        name = fixture_name(query)
        raw, error = call(model, api_key, build_body(system, schema, query), args.timeout, args.retries)
        if error:
            print(f"  [{i}/{len(todo)}] 실패  {query} — {error}")
            failures.append((query, error))
            if i < len(todo):
                time.sleep(args.sleep)
            continue

        (args.fixtures / name).write_text(
            json.dumps(raw, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        entries[query] = {"file": name, "source": "recorded", "model": model, "promptVersion": version}
        print(f"  [{i}/{len(todo)}] {name}  {query}  →  {parsed_text(raw)}")

        if i < len(todo):
            time.sleep(args.sleep)

    stale = [query for query, entry in entries.items() if entry.get("promptVersion") != version]
    index_path.write_text(
        json.dumps(
            {"promptVersion": index.get("promptVersion") if stale else version, "model": model, "entries": entries},
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"\n{index_path.relative_to(ROOT)} 갱신 · 항목 {len(entries)}개")

    if stale:
        print(f"프롬프트 v{version} 로 녹화되지 않은 항목 {len(stale)}개: {', '.join(stale)}")

    if failures:
        print(f"\n실패 {len(failures)}건 — 다시 실행하면 실패분만 부릅니다.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
