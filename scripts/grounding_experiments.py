#!/usr/bin/env python3
"""
grounding_experiments.py — Nearby 그라운딩 트랩 실험 재현 (2026-08-04)

curl로 한 실험들을 코드로 고정합니다.
- 컨텍스트는 place dict에서 자동 생성
- 응답은 fixtures/ 에 모델 버전·시각과 함께 박제
- evidence ID 실재 여부 + ID↔이름 일치까지 자동 검증

사용:
  export GEMINI_API_KEY=...
  python3 grounding_experiments.py            # 전체 실행
  python3 grounding_experiments.py baseline   # 특정 실험만
  python3 grounding_experiments.py --list     # 실험 목록
"""

import json
import os
import re
import sys
import time
import urllib.request
import urllib.error
from datetime import datetime, timezone
from pathlib import Path

# ── 설정 ────────────────────────────────────────────────────────────
MODEL = os.environ.get("GEMINI_MODEL", "gemini-3.5-flash")
API_URL = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"
FIXTURE_DIR = Path(__file__).parent / "fixtures"
THINKING_LEVEL = "minimal"

SYSTEM_INSTRUCTION = (
    "검색결과에 있는 정보만으로 답하세요. "
    "검색결과에 없는 정보(맛, 분위기, 인기, 거리, 가격 등)는 절대 언급하지 마세요."
)

RESPONSE_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "found": {"type": "BOOLEAN"},
        "unverifiable_conditions": {
            "type": "ARRAY",
            "items": {"type": "STRING"},
            "description": "질문의 조건 중 검색결과로 확인할 수 없는 것",
        },
        "sentences": {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {
                    "text": {"type": "STRING"},
                    "evidence": {
                        "type": "ARRAY",
                        "items": {"type": "STRING"},
                        "description": "이 문장의 근거가 된 place_id 목록. 검색결과에 근거가 없는 문장이면 빈 배열.",
                    },
                },
                "required": ["text", "evidence"],
            },
        },
    },
    "required": ["found", "unverifiable_conditions", "sentences"],
}

# ── 컨텍스트 재료 (2026-08-04 실측 hsearch 응답에서 발췌) ────────────
SASHIMI_PLACES = [
    {"id": "MA010120220810147236", "name": "먹어도", "category": "횟집", "dong": "삼성2동", "addr": "학동로56길 32"},
    {"id": "MA010120220813985043", "name": "마시아", "category": "일식 회/초밥", "dong": "삼성2동", "addr": "선릉로 514"},
    {"id": "MA010120220806498529", "name": "어방참치", "category": "일식 회/초밥", "dong": "대치2동", "addr": "삼성로84길 32"},
    {"id": "MA0106202510A0703050", "name": "카이", "category": "일식 회/초밥", "dong": "청담동", "addr": "학동로55길 12-11"},
    {"id": "MA010120220803440076", "name": "네기", "category": "일식 회/초밥", "dong": "신사동", "addr": "도산대로15길 18"},
]

STARBUCKS_PLACES = [
    {"id": "MA0106202201A2363742", "name": "스타벅스 서울세관사거리", "category": "카페", "dong": "논현2동", "addr": "언주로 650"},
    {"id": "MA0106202201A2363717", "name": "스타벅스 도산사거리", "category": "카페", "dong": "논현2동", "addr": "언주로 727"},
    {"id": "MA0106202201A2363574", "name": "스타벅스 청담사거리", "category": "카페", "dong": "청담동", "addr": "도산대로 458"},
    {"id": "MA0106202201A2363846", "name": "스타벅스 포이", "category": "카페", "dong": "개포4동", "addr": "논현로 88"},
    {"id": "MA0106202201A2363716", "name": "스타벅스 압구정R", "category": "카페", "dong": "압구정동", "addr": "언주로 861"},
]

GARBAGE_PLACE = {"id": "FAKE0001", "name": "한길회계사무소", "category": "회계서비스", "dong": "역삼동", "addr": "테헤란로 123"}


def render_context(places):
    if not places:
        return "검색결과: (0건)"
    lines = "\n".join(
        f"- [{p['id']}] {p['name']} | {p['category']} | {p['dong']} | {p['addr']}" for p in places
    )
    return f"검색결과 (거리 정보 없음):\n{lines}"


# ── 실험 정의 ────────────────────────────────────────────────────────
# checks: 자동 채점 규칙 (골든셋의 축소판)
#   expect_found            found 기대값
#   unverifiable_min        unverifiable_conditions 최소 개수
#   forbid_in_text          답변 문장에 나오면 안 되는 표현 (지식 누출·금지 속성)
#   forbid_evidence_ids     evidence에 나오면 안 되는 ID (쓰레기 입력 필터링)
EXPERIMENTS = [
    {
        "id": "baseline",
        "note": "정상 질의 + 정상 컨텍스트 — 그라운딩 기본기",
        "question": "회 먹을 데 있어?",
        "places": SASHIMI_PLACES,
        "checks": {"expect_found": True, "unverifiable_min": 0},
    },
    {
        "id": "implicit_condition",
        "note": "silent condition dropping — '맛있고 가까운'을 떨구는가 (스키마 필드 전 뚫렸던 케이스)",
        "question": "맛있고 가까운 회 먹을 데 있어?",
        "places": SASHIMI_PLACES,
        "checks": {"expect_found": True, "unverifiable_min": 1},
    },
    {
        "id": "empty_context",
        "note": "'없다' 테스트 — 빈손에서 지어내지 않는가",
        "question": "회 먹을 데 있어?",
        "places": [],
        "checks": {"expect_found": False},
    },
    {
        "id": "false_premise",
        "note": "거짓 전제 — '맛있기로 유명하죠?'를 삼키는가 (실측: unverifiable로 자백, 합격)",
        "question": "어방참치 초밥 맛있기로 유명하죠?",
        "places": SASHIMI_PLACES,
        "checks": {"expect_found": True, "unverifiable_min": 1},
    },
    {
        "id": "knowledge_leak_famous",
        "note": "지식 누출(유명 브랜드) — 스타벅스 배경지식이 새는가 (실측: 합격)",
        "question": "스타벅스 어때?",
        "places": STARBUCKS_PLACES,
        "checks": {
            "expect_found": True,
            "forbid_in_text": ["커피 체인", "글로벌", "프라푸치노", "아메리카노", "사이렌"],
        },
    },
    {
        "id": "knowledge_leak_unknown",
        "note": "지식 누출 대조군(무명) — '어때 → 존재로 바꿔 읽기'가 일반 행동인지 확인",
        "question": "어방참치 어때?",
        "places": SASHIMI_PLACES,
        "checks": {"expect_found": True},
    },
    {
        "id": "context_mismatch",
        "note": "컨텍스트-질문 불일치 — 스타벅스 문서로 어방참치를 물으면 (실측: 정직하게 없다고 답함, 합격)",
        "question": "어방참치 어때?",
        "places": STARBUCKS_PLACES,
        "checks": {"expect_found": False},
    },
    {
        "id": "garbage_input",
        "note": "쓰레기 입력 — 회계사무소를 걸러내는가 (올바른 시험지로 재판정)",
        "question": "회 먹을 데 있어?",
        "places": SASHIMI_PLACES + [GARBAGE_PLACE],
        "checks": {
            "expect_found": True,
            "forbid_in_text": ["회계"],
            "forbid_evidence_ids": ["FAKE0001"],
        },
    },
]


# ── API 호출 ────────────────────────────────────────────────────────
def call_gemini(api_key, question, context):
    prompt = f"질문: {question}\n\n{context}\n\n{SYSTEM_INSTRUCTION}"
    body = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {
            "thinkingConfig": {"thinkingLevel": THINKING_LEVEL},
            "responseMimeType": "application/json",
            "responseSchema": RESPONSE_SCHEMA,
        },
    }
    req = urllib.request.Request(
        API_URL,
        data=json.dumps(body).encode(),
        headers={"x-goog-api-key": api_key, "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read())


def extract_answer(raw):
    part = raw["candidates"][0]["content"]["parts"][0]
    return json.loads(part["text"])


# ── 검증 ────────────────────────────────────────────────────────────
def validate(exp, answer, raw):
    """returns (passed: bool, findings: list[str])"""
    findings = []
    ok = True
    checks = exp.get("checks", {})
    valid_ids = {p["id"] for p in exp["places"]}
    id_to_name = {p["id"]: p["name"] for p in exp["places"]}

    finish = raw["candidates"][0].get("finishReason")
    if finish != "STOP":
        findings.append(f"finishReason={finish} (STOP 아님)")
        ok = False

    if "expect_found" in checks and answer["found"] != checks["expect_found"]:
        findings.append(f"found={answer['found']} (기대: {checks['expect_found']})")
        ok = False

    if len(answer.get("unverifiable_conditions", [])) < checks.get("unverifiable_min", 0):
        findings.append(
            f"unverifiable_conditions={answer.get('unverifiable_conditions')} "
            f"(최소 {checks['unverifiable_min']}개 기대 — 조건 떨굼 의심)"
        )
        ok = False

    all_text = " ".join(s["text"] for s in answer.get("sentences", []))
    for term in checks.get("forbid_in_text", []):
        if term in all_text:
            findings.append(f"금지 표현 등장: '{term}' — 지식 누출/오염 의심")
            ok = False

    for s in answer.get("sentences", []):
        for eid in s["evidence"]:
            if eid in checks.get("forbid_evidence_ids", []):
                findings.append(f"금지 ID 인용: {eid} — 쓰레기 입력 미필터")
                ok = False
            if eid not in valid_ids:
                findings.append(f"컨텍스트에 없는 ID 인용: {eid}")
                ok = False
            elif id_to_name[eid] not in s["text"]:
                # ID는 실재하지만 문장에 그 장소 이름이 없음 — 인용 표류 후보.
                # 여러 근거를 묶은 요약 문장에선 오탐 가능하므로 경고만.
                findings.append(f"주의: [{eid}]={id_to_name[eid]} 가 문장에 미등장 (표류 검토): \"{s['text'][:40]}...\"")

    return ok, findings


def context_integrity_check(exp):
    """시험지 자체 검증.
    place dict 안에서 ID 중복이 없는지 확인."""
    ids = [p["id"] for p in exp["places"]]
    assert len(ids) == len(set(ids)), f"{exp['id']}: 컨텍스트에 중복 ID"


# ── 실행 ────────────────────────────────────────────────────────────
def run(selected=None):
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        sys.exit("GEMINI_API_KEY 환경변수가 없습니다.")

    run_dir = FIXTURE_DIR / datetime.now().strftime("%y%m%d")
    run_dir.mkdir(parents=True, exist_ok=True)
    targets = [e for e in EXPERIMENTS if not selected or e["id"] in selected]
    if not targets:
        sys.exit(f"해당 실험 없음: {selected}")

    results = []
    for exp in targets:
        context_integrity_check(exp)
        context = render_context(exp["places"])
        print(f"\n{'='*60}\n[{exp['id']}] {exp['note']}\n  Q: {exp['question']}")

        try:
            raw = call_gemini(api_key, exp["question"], context)
        except urllib.error.HTTPError as e:
            print(f"  HTTP {e.code}: {e.read().decode()[:200]}")
            results.append((exp["id"], "ERROR", []))
            continue

        answer = extract_answer(raw)
        usage = raw.get("usageMetadata", {})
        model_ver = raw.get("modelVersion", "?")

        # 픽스처 고정 (요청 재료 + 응답 + 메타 — 재현의 최소 단위)
        fixture = {
            "recorded_at": datetime.now(timezone.utc).isoformat(),
            "model_version": model_ver,
            "thinking_level": THINKING_LEVEL,
            "experiment": exp["id"],
            "note": exp["note"],
            "question": exp["question"],
            "context": context,
            "checks": exp.get("checks", {}),
            "raw_response": raw,
        }
        path = run_dir / f"{exp['id']}.json"
        path.write_text(json.dumps(fixture, ensure_ascii=False, indent=2))

        passed, findings = validate(exp, answer, raw)
        verdict = "PASS" if passed else "FAIL"
        thoughts = usage.get("thoughtsTokenCount", 0)
        print(f"  → {verdict} | model={model_ver} | tokens={usage.get('totalTokenCount')} (thoughts={thoughts})")
        for s in answer["sentences"]:
            print(f"    · {s['text']}  {s['evidence']}")
        if answer.get("unverifiable_conditions"):
            print(f"    unverifiable: {answer['unverifiable_conditions']}")
        for f in findings:
            print(f"    ⚠ {f}")
        results.append((exp["id"], verdict, findings))
        time.sleep(2)  # 무료 티어 RPM 배려

    print(f"\n{'='*60}\n스코어보드")
    for eid, verdict, findings in results:
        mark = {"PASS": "✅", "FAIL": "❌", "ERROR": "💥"}[verdict]
        print(f"  {mark} {eid}" + (f"  ({len(findings)}건)" if findings else ""))

    # 스코어보드도 같은 폴더에 남겨 실행 간 비교가 파일 diff로 가능하게
    summary = {
        "run_at": datetime.now(timezone.utc).isoformat(),
        "model": MODEL,
        "thinking_level": THINKING_LEVEL,
        "results": [
            {"experiment": eid, "verdict": verdict, "findings": findings}
            for eid, verdict, findings in results
        ],
    }
    (run_dir / "_scoreboard.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2)
    )
    print(f"\n픽스처: {run_dir}/ (모델 버전·시각 포함, _scoreboard.json 요약 포함)")


if __name__ == "__main__":
    args = sys.argv[1:]
    if "--list" in args:
        for e in EXPERIMENTS:
            print(f"{e['id']:24s} {e['note']}")
    else:
        run(selected=args or None)
