#!/usr/bin/env python3
"""ask-api 답변 픽스처를 Gemini API 로 녹화한다.

픽스처 키는 sha256(질문 + 렌더된 컨텍스트) 이므로 컨텍스트를 어디서 얻느냐가 곧 도달 범위다.

  --source trap      grounding_experiments.py 의 트랩 8개. 컨텍스트가 스크립트 상수라
                     실제 검색 결과로는 이 키에 도달하지 못한다. 단위 테스트 전용.
  --source pipeline  실제 ask-api 를 호출해 받은 검색 결과로 컨텍스트를 만든다.
                     fixture 모드가 실제 질의에 답변하려면 이쪽이 필요하다.

  전제:  export GEMINI_API_KEY=...
         --source pipeline 은 ask-api 가 psp.ask.llm=fixture 로 떠 있어야 한다.
         파싱이 결정적이어야 녹화한 키가 fixture 모드가 계산할 키와 같다.
  실행:  python3 scripts/record_answer_fixtures.py --source pipeline
         python3 scripts/record_answer_fixtures.py --source trap
         python3 scripts/record_answer_fixtures.py --source pipeline --dry-run
         python3 scripts/record_answer_fixtures.py --verify
         python3 scripts/record_answer_fixtures.py --source trap --sleep 13   # 무료 티어
"""

import argparse
import hashlib
import json
import os
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from grounding_experiments import EXPERIMENTS, render_context as render_trap_context  # noqa: E402

try:
    import yaml
except ImportError:
    sys.exit("PyYAML 이 필요합니다: pip3 install pyyaml")

ROOT = Path(__file__).resolve().parent.parent
MODULE = ROOT / "services" / "ask-api"
PROMPT_SPEC = MODULE / "src" / "main" / "resources" / "prompt" / "answer-generate.json"
APPLICATION_YML = MODULE / "src" / "main" / "resources" / "application.yml"
FIXTURES = MODULE / "src" / "test" / "resources" / "fixtures" / "answer"
PARSE_FIXTURES = MODULE / "src" / "test" / "resources" / "fixtures"
GOLDEN_SET = ROOT / "scripts" / "eval" / "golden_set.yaml"
INDEX = "index.json"
ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
ASK_URL = "http://localhost:8082"
THINKING_LEVEL = "minimal"
SEPARATOR = "\n---\n"
FINGERPRINT_LENGTH = 12
CONTEXT_HEADER = "검색결과 (거리 정보 없음):"
CONTEXT_EMPTY = "검색결과: (0건)"
FREE_TIER_RPM = 5
RETRY_MARGIN = 2.0
RETRY_FALLBACK = 15.0


def nfc(text):
    return unicodedata.normalize("NFC", text.strip())


def fingerprint(question, context):
    raw = f"{nfc(question)}{SEPARATOR}{nfc(context)}".encode("utf-8")
    return hashlib.sha256(raw).hexdigest()[:FINGERPRINT_LENGTH]


def load_prompt_spec(path):
    spec = json.loads(path.read_text(encoding="utf-8"))
    return str(spec["version"]), spec["system"], spec["responseSchema"]


def default_model(path):
    for doc in yaml.safe_load_all(path.read_text(encoding="utf-8")):
        if not doc or doc.get("spring", {}).get("config", {}).get("activate"):
            continue
        model = doc.get("psp", {}).get("ask", {}).get("gemini", {}).get("model")
        if model:
            return model
    raise KeyError(f"{path} 에 psp.ask.gemini.model 이 없습니다")


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


def ask(base_url, question, answer, timeout):
    query = urllib.parse.urlencode({"q": question, "answer": "true" if answer else "false"})
    request = urllib.request.Request(f"{base_url.rstrip('/')}/v1/ask?{query}", method="GET")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def render_pipeline_context(hits):
    if not hits:
        return CONTEXT_EMPTY
    lines = [CONTEXT_HEADER]
    for hit in hits:
        tail = "".join(f" | {value}" for value in (hit["category"], hit["dong"], hit["address"]) if value)
        lines.append(f"- [{hit['placeId']}] {hit['label']}{tail}")
    return "\n".join(lines)


def decode_hits(search):
    records = []
    for hit in search.get("hits", []):
        if not (hit.get("placeId") or "").strip() or not (hit.get("name") or "").strip():
            continue
        records.append(
            {
                "placeId": hit["placeId"],
                "label": hit.get("label") or hit["name"],
                "category": hit.get("category"),
                "dong": hit.get("dong"),
                "address": hit.get("address"),
            }
        )
    return records


def load_questions(parse_index, golden_set):
    if parse_index.exists():
        return list(json.loads(parse_index.read_text(encoding="utf-8")).get("entries", {}))
    doc = yaml.safe_load(golden_set.read_text(encoding="utf-8"))
    return [entry["query"].strip() for entry in doc["queries"]]


def trap_targets():
    for experiment in EXPERIMENTS:
        question = experiment["question"]
        context = render_trap_context(experiment["places"])
        yield {
            "key": fingerprint(question, context),
            "question": question,
            "context": context,
            "label": experiment["id"],
            "extra": {"experiment": experiment["id"], "context": "trap"},
        }


def pipeline_targets(base_url, questions, timeout):
    for question in questions:
        records = decode_hits(ask(base_url, question, False, timeout).get("search", {}))
        context = render_pipeline_context(records)
        yield {
            "key": fingerprint(question, context),
            "question": question,
            "context": context,
            "label": f"레코드 {len(records):2d}건  {question}",
            "extra": {"context": "pipeline", "records": len(records)},
        }


def write_index(path, version, model, entries):
    complete = entries and all(entry.get("promptVersion") == version for entry in entries.values())
    path.write_text(
        json.dumps(
            {"promptVersion": version if complete else None, "model": model, "entries": entries},
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


def sentence_count(raw):
    try:
        return len(json.loads(raw["candidates"][0]["content"]["parts"][0]["text"])["sentences"])
    except (KeyError, IndexError, json.JSONDecodeError, TypeError):
        return 0


def verify(base_url, entries, timeout):
    targets = [(key, entry["question"]) for key, entry in entries.items() if entry.get("context") == "pipeline"]
    if not targets:
        print("pipeline 픽스처가 없습니다. --source pipeline 로 먼저 녹화하세요.")
        return 1
    print(f"pipeline 픽스처 {len(targets)}개를 fixture 모드에서 확인합니다.")
    unreachable = []
    for key, question in targets:
        body = ask(base_url, question, True, timeout)
        answer = body.get("answer")
        if answer is None:
            unreachable.append(key)
            print(f"  실패  {key}  {question}  degradedBy={body.get('degradedBy')}")
        else:
            print(f"  도달  {key}  {question}  문장 {len(answer['sentences'])}개")
    if unreachable:
        print(f"\n도달하지 못한 픽스처 {len(unreachable)}개. 코퍼스나 렌더러가 녹화 당시와 다릅니다.")
        return 1
    print("\n전부 도달했습니다.")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", choices=("trap", "pipeline"), help="컨텍스트를 어디서 얻을지")
    ap.add_argument("--fixtures", type=Path, default=FIXTURES)
    ap.add_argument("--parse-fixtures", type=Path, default=PARSE_FIXTURES)
    ap.add_argument("--golden-set", type=Path, default=GOLDEN_SET)
    ap.add_argument("--ask-url", default=ASK_URL)
    ap.add_argument("--model", default=None)
    ap.add_argument("--questions", nargs="+", default=None, help="pipeline 에서 녹화할 질문을 직접 지정")
    ap.add_argument("--sleep", type=float, default=0.0, help=f"호출 간 간격(초). 무료 티어는 분당 {FREE_TIER_RPM}회라 {60.0 / FREE_TIER_RPM + 1:.0f} 이 필요하다")
    ap.add_argument("--retries", type=int, default=3, help="429 를 받았을 때 재시도 횟수")
    ap.add_argument("--timeout", type=float, default=120.0)
    ap.add_argument("--force", action="store_true", help="이미 녹화된 것도 다시 부른다")
    ap.add_argument("--dry-run", action="store_true", help="무엇을 부를지·어떤 키로 쓸지만 출력")
    ap.add_argument("--verify", action="store_true", help="녹화 대신 pipeline 픽스처의 도달 여부만 확인")
    args = ap.parse_args()

    version, system, schema = load_prompt_spec(PROMPT_SPEC)
    model = args.model or default_model(APPLICATION_YML)
    args.fixtures.mkdir(parents=True, exist_ok=True)
    index_path = args.fixtures / INDEX
    index = json.loads(index_path.read_text(encoding="utf-8")) if index_path.exists() else {}
    entries = index.get("entries", {})

    for entry in entries.values():
        entry.setdefault("context", "trap" if entry.get("experiment") else "pipeline")

    if args.verify:
        return verify(args.ask_url, entries, args.timeout)

    if not args.source:
        ap.error("--source trap 또는 --source pipeline 을 지정하세요 (--verify 는 예외)")

    if index.get("promptVersion") not in (None, version):
        print(f"인덱스 프롬프트 버전이 {index.get('promptVersion')} → {version} 로 바뀌었습니다.")

    if args.source == "trap":
        plan = list(trap_targets())
    else:
        try:
            probe = ask(args.ask_url, "카페", False, args.timeout)
        except (urllib.error.URLError, TimeoutError) as e:
            sys.exit(f"ask-api 에 닿지 못했습니다 ({args.ask_url}): {e}")
        if probe.get("llmVendor") != "fixture":
            sys.exit(
                f"ask-api 가 llm={probe.get('llmVendor')} 로 떠 있습니다. "
                "파싱이 결정적이지 않으면 녹화한 키에 도달하지 못합니다. psp.ask.llm=fixture 로 재기동하세요."
            )
        questions = args.questions or load_questions(args.parse_fixtures / INDEX, args.golden_set)
        plan = list(pipeline_targets(args.ask_url, questions, args.timeout))

    todo = []
    for target in plan:
        entry = entries.get(target["key"])
        recorded = (
            entry is not None
            and entry.get("source") == "recorded"
            and entry.get("promptVersion") == version
            and (args.fixtures / entry["file"]).exists()
        )
        if recorded and not args.force:
            continue
        todo.append(target)

    print(f"{args.source} {len(plan)}개 · 녹화 대상 {len(todo)}개 · 모델 {model} · 프롬프트 v{version}")

    if args.dry_run:
        pending = {target["key"] for target in todo}
        for target in plan:
            mark = "→" if target["key"] in pending else " "
            print(f"  {mark} {target['key']}  {target['label']}")
        return 0

    if not todo:
        print("전부 녹화돼 있습니다. 다시 부르려면 --force.")
        return 0

    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not api_key:
        sys.exit("GEMINI_API_KEY 가 비어 있습니다. export GEMINI_API_KEY=... 후 다시 실행하세요.")

    failed = []
    for n, target in enumerate(todo):
        key = target["key"]
        raw, error = call(model, api_key, build_body(system, schema, target["question"], target["context"]), args.timeout, args.retries)
        if error:
            print(f"  [{n + 1}/{len(todo)}] 실패  {target['label']} — {error}")
            failed.append(target["label"])
        else:
            (args.fixtures / f"{key}.json").write_text(
                json.dumps(raw, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )
            entries[key] = {
                "file": f"{key}.json",
                "question": target["question"],
                "source": "recorded",
                "promptVersion": version,
                **target["extra"],
            }
            write_index(index_path, version, model, entries)
            print(f"  [{n + 1}/{len(todo)}] {key}  문장 {sentence_count(raw)}개  {target['label']}")
        if n < len(todo) - 1:
            time.sleep(args.sleep)

    write_index(index_path, version, model, entries)
    print(f"\n{index_path.relative_to(ROOT)} 갱신 · 항목 {len(entries)}개")
    if args.source == "pipeline":
        print("--verify 로 도달 여부를 확인하세요.")

    if failed:
        print(f"\n실패 {len(failed)}건: {', '.join(failed)} — 다시 실행하면 실패분만 부릅니다.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
