#!/usr/bin/env python3
"""검색 로그에서 사전 후보를 캔다 — 사전의 **두 번째 원천** (ADR 0008).

원천 데이터 기반 사전(build_komoran_dict.py)은 *데이터에 있는 말*만 담는다.
'브런치'는 강남구 상호명에 4건뿐이라 빈도 임계를 못 넘는다. 그런데 사용자는 그 말을 친다.
그 간극을 메우는 게 이 스크립트다.

신호로 삼는 것:
  - `zero`    결과 0건 질의 — 미등록 어휘의 가장 강한 증거
  - `relaxed` 엄격 질의가 0건이라 조건을 풀어 재질의한 경우 — "글자는 아는데 못 쪼갠" 경우가 많다
  - 자동완성 0건 — 한 글자만 쳐도 걸리는 게 정상이므로, 0건이면 분석이 깨졌을 확률이 높다

**자동으로 사전에 넣지 않는다.** 질의에는 오타·장난·한 번 쓰고 마는 말이 섞인다.
사람이 검토할 수 있게 후보 목록만 내고, 채택한 것만 manual.dict 로 옮긴다.

  전제:  스택 기동 + services/search-api/logs/query.log 존재
  사용:  python3 scripts/mine_query_log.py [--log 경로] [--min-count N]
  출력:  검토용 목록 (stdout) — 그대로 manual.dict 에 붙여넣을 수 있는 형식
"""
import argparse
import json
import os
import sys
import urllib.request
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_komoran_dict import (  # noqa: E402  (경로 설정 후 임포트)
    ES,
    LIVE_PROBE_INDEX,
    MANUAL,
    analyze,
    broken_spans,
    ensure_live_probe_index,
    keep,
    load_manual,
    OUT,
)

DEFAULT_LOG = "services/search-api/logs/query.log"


def read_log(path: str) -> list[dict]:
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue          # 로그 회전 중 잘린 줄 등은 조용히 건너뛴다
    return rows


def exists_in_corpus(word: str, index: str = "place_search") -> bool:
    """그 글자가 색인된 문서 어딘가에 **원문 그대로** 존재하는가.

    0건 질의에는 두 종류가 있고, 사전은 그중 하나만 고친다.
      (a) **분석 실패로 0건** — 데이터에는 있는데 못 쪼개서 못 찾는다 → 사전으로 해결된다
      (b) **데이터에 없어서 0건** — 아무리 사전을 넣어도 결과는 그대로 0건이다
    분석과 무관한 wildcard 로 원문 존재 여부를 확인해 둘을 가른다. 이걸 구분하지 않으면
    "사전에 넣었는데 왜 그대로죠?" 를 반복하게 된다.
    """
    body = json.dumps({
        "size": 0, "terminate_after": 1,
        "query": {"wildcard": {"name.raw": {"value": f"*{word}*"}}},
    }).encode()
    req = urllib.request.Request(f"{ES}/{index}/_search", data=body,
                                 headers={"Content-Type": "application/json"})
    try:
        return json.load(urllib.request.urlopen(req))["hits"]["total"]["value"] > 0
    except Exception:
        return False


def current_dictionary() -> set[str]:
    """이미 사전에 있는 단어 — 다시 제안하지 않는다."""
    words = set(load_manual())
    try:
        with open(OUT, encoding="utf-8") as f:
            for line in f:
                line = line.split("#", 1)[0].strip()
                if line:
                    words.add(line.split("\t")[0])
    except FileNotFoundError:
        pass
    return words


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", default=DEFAULT_LOG)
    ap.add_argument("--min-count", type=int, default=1,
                    help="후보가 등장한 서로 다른 질의 수의 하한 (운영에서는 5~10 권장)")
    args = ap.parse_args()

    if not os.path.exists(args.log):
        print(f"질의 로그가 없습니다: {args.log}\n"
              f"  앱을 띄우고 검색을 몇 번 해보세요. (logback-spring.xml 이 여기에 씁니다)",
              file=sys.stderr)
        return 1

    ensure_live_probe_index()
    rows = read_log(args.log)
    if not rows:
        print("질의 로그가 비어 있습니다.", file=sys.stderr)
        return 1

    # 질의별 집계 — 같은 질의가 여러 번 나오면 한 번으로 세되, '문제 있었나'는 OR 로 모은다
    total_by_q: Counter = Counter()
    trouble: dict[str, set[str]] = defaultdict(set)
    for r in rows:
        q = (r.get("q") or "").strip()
        if not q:
            continue
        total_by_q[q] += 1
        if r.get("zero"):
            trouble[q].add("0건")
        if r.get("relaxed"):
            trouble[q].add("완화")

    print(f"질의 로그 {len(rows):,}줄 · 서로 다른 질의 {len(total_by_q):,}개 "
          f"· 문제 있었던 질의 {len(trouble):,}개\n", file=sys.stderr)

    # 문제 질의를 형태소 분석해 '부서진 구간'을 후보로 뽑는다 (코퍼스 채굴과 같은 규칙)
    known = current_dictionary()
    seen_in: dict[str, set[str]] = defaultdict(set)   # 후보 -> 그 후보가 나온 질의들
    for q in trouble:
        tokens = analyze(q, LIVE_PROBE_INDEX)
        if not tokens:
            continue

        # 띄어쓰기 없는 한 단어인데 결과가 0건이고, 분석기가 그걸 쪼갠다면 — 그 자체가 후보다.
        # 코퍼스 채굴의 '연속 한 글자' 규칙보다 느슨하게 본다. 사용자가 실제로 친 말이고
        # 결과가 없었다는 건 그 자체로 강한 증거이기 때문. (우래옥 -> 우래·옥)
        if " " not in q and "0건" in trouble[q] and len(tokens) > 1:
            if keep(q) and q not in known and not any(q in k for k in known):
                seen_in[q].add(q)

        for span in broken_spans(q, tokens):
            for size in range(2, min(len(span), 6) + 1):
                for i in range(len(span) - size + 1):
                    w = span[i:i + size]
                    # 이미 등재된 단어의 **조각**은 제안하지 않는다. 등재어를 쪼개서 넣으면
                    # 그 단어가 다시 분해돼 애써 고친 걸 되돌린다.
                    if keep(w) and w not in known and not any(w in k for k in known):
                        seen_in[w].add(q)

    ranked = sorted(seen_in.items(), key=lambda kv: (-len(kv[1]), kv[0]))
    ranked = [(w, qs) for w, qs in ranked if len(qs) >= args.min_count]

    if not ranked:
        print("새로 제안할 후보가 없습니다 (사전이 이미 커버하고 있거나 로그가 적습니다).",
              file=sys.stderr)
        return 0

    print(f"# 검토 후보 {len(ranked)}개 — 확인 후 채택한 것만 {MANUAL} 로 옮기세요.")
    print(f"# 형식: 단어<TAB>품사   (뒤 주석은 근거: 등장 질의 / 현재 분석 결과)")
    print(f"# [분석실패] = 데이터엔 있는데 못 찾는 것 → 사전으로 해결됨 (우선 채택)")
    print(f"# [데이터없음] = 원천에 아예 없는 것 → 사전을 넣어도 결과는 0건 그대로")
    proposed = {w for w, _ in ranked}
    for word, queries in ranked:
        tokens = analyze(word, LIVE_PROBE_INDEX)
        pieces = "·".join(t["token"] for t in tokens) if tokens else "?"
        sample = ", ".join(sorted(queries)[:3])
        flags = "/".join(sorted(set().union(*(trouble[q] for q in queries))))
        # 더 짧은 후보를 품고 있으면 경고. 통짜로 등재하면 부분 검색이 죽는다
        # ('감태김밥'을 넣으면 '김밥'으로 못 찾는다). 보통은 짧은 쪽만 넣는 게 맞다.
        inner = sorted(w for w in proposed if w != word and w in word)
        warn = f"   ⚠ '{inner[0]}' 도 후보 — 보통 짧은 쪽만 넣습니다" if inner else ""
        kind = "분석실패" if exists_in_corpus(word) else "데이터없음"
        print(f"{word}\tNNP\t# [{kind}] {flags} {len(queries)}건: {sample}  |  현재: {pieces}{warn}")

    print(f"\n채택 후: python3 scripts/build_komoran_dict.py && "
          f"curl -XPOST localhost:8080/admin/reindex", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
