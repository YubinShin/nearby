#!/usr/bin/env python3
"""벡터 검색의 top-1 점수 분포를 측정하고 min-score 후보를 비교한다.

전제:
  - search-api 실행 중 (기본 http://localhost:8080)
  - psp.vector.min-score=0.0

실행:
  python3 scripts/eval/measure_threshold.py
  python3 scripts/eval/measure_threshold.py --thresholds 0.78 0.82 0.84 0.86

질의별 top-1 코사인 점수만 측정한다.
검색 결과의 정답 여부는 평가하지 않는다. (ADR 0010)
"""

import argparse
import json
import statistics
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

DEFAULT_QUERIES = Path(__file__).with_name("queries_threshold.txt")
DEFAULT_THRESHOLDS = [0.78, 0.80, 0.82, 0.84, 0.86]


def load_groups(path):
    """`# === REAL ===` / `# === JUNK ===` 구분으로 질의를 읽어온다."""
    groups, current = {"REAL": [], "JUNK": []}, None
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("# ==="):
            current = stripped.strip("# =")
        elif stripped and not stripped.startswith("#"):
            if current is None:
                sys.exit(f"{path}: 부류 표시(# === REAL ===) 앞에 질의가 있다 — {stripped}")
            groups[current].append(stripped)
    return groups


def top1(base, query, timeout=60):
    """top-1 점수와 문서 이름을 반환한다. 결과가 없으면 (None, None)."""
    url = f"{base}/v1/vsearch?" + urllib.parse.urlencode({"q": query, "size": 10})
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            body = json.load(response)
    except urllib.error.URLError as error:
        sys.exit(f"{url} 호출 실패: {error}")
    hits = body.get("hits") or []
    if not hits:
        return None, None
    head = hits[0]
    return head["score"], f'{head["name"]}[{head.get("category")}]'


def measure(base, queries, label):
    print(f"── {label} ──")
    scores = []
    for query in queries:
        score, name = top1(base, query)
        if score is None:
            print(f"  (0건)  {query}")
            continue
        scores.append(score)
        print(f"  {score:.3f}  {query:16} → {name}")
    return scores


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--queries", type=Path, default=DEFAULT_QUERIES)
    parser.add_argument("--thresholds", type=float, nargs="+", default=DEFAULT_THRESHOLDS)
    args = parser.parse_args()

    groups = load_groups(args.queries)
    real = measure(args.base, groups["REAL"], "의미 있는 질의")
    junk = measure(args.base, groups["JUNK"], "의미 없는 질의")

    missing = (len(groups["REAL"]) - len(real)) + (len(groups["JUNK"]) - len(junk))
    if missing:
        print(f"\n⚠ {missing}건은 검색 결과가 없었다.")
        print("  min-score가 적용되어 낮은 점수가 제외됐을 수 있다.")
        print("  psp.vector.min-score=0.0으로 설정한 뒤 다시 측정 권장.")

    if not real or not junk:
        sys.exit("한쪽 결과가 비었다 — 분포를 비교할 수 없다.")

    print()
    for name, scores in (("진짜", real), ("엉터리", junk)):
        print(
            f"{name} 질의 top-1: 최소 {min(scores):.3f}"
            f"  중앙값 {statistics.median(scores):.3f}  최대 {max(scores):.3f}"
        )
    overlap = max(junk) > min(real)
    print(
        f"겹침 여부: 엉터리 최대 {max(junk):.3f} vs 진짜 최소 {min(real):.3f}"
        f" → {'겹침 (절대 점수만으로는 구분 불가)' if overlap else '분리 가능'}"
    )

    print()
    real_total = len(groups["REAL"])
    junk_total = len(groups["JUNK"])
    for threshold in args.thresholds:
        alive = sum(1 for score in real if score >= threshold)
        blocked = sum(1 for score in junk if score < threshold) + (junk_total - len(junk))
        print(
            f"  문턱 {threshold:.2f}:"
            f" 진짜 통과 {alive}/{real_total},"
            f" 엉터리 차단 {blocked}/{junk_total}"
        )

if __name__ == "__main__":
    main()