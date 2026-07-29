#!/usr/bin/env python3
"""검색 3채널을 같은 질의로 두들겨 **0건 수와 지연**을 잰다.

  전제:  search-api 기동 (기본 http://localhost:8080)
  실행:  python3 scripts/measure_search.py
         python3 scripts/measure_search.py --label "분리 후"

왜 스크립트로 고정하나 — 모듈을 쪼갠 뒤 "질의 동작이 그대로다"를 말하려면 **같은 방법으로
두 번 재야** 한다. 손으로 curl 을 치면 매번 조건이 조금씩 달라져서 비교가 성립하지 않는다.
(ADR 0011 의 실측 재검증)

워밍업을 먼저 돌린다. 첫 호출에는 JIT 예열과 첫 임베딩 추론이 섞여 있어서(ADR 0010 에서
851ms 를 겪었다) 그 값을 그대로 믿으면 안 된다.
"""

import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

DEFAULT_QUERIES = Path(__file__).with_name("queries_regression.txt")
CHANNELS = [("키워드", "/v1/search"), ("벡터", "/v1/vsearch"), ("하이브리드", "/v1/hsearch")]
WARMUP_ROUNDS = 2
REPEATS = 5


def load_queries(path):
    lines = path.read_text(encoding="utf-8").splitlines()
    return [s for s in (line.strip() for line in lines) if s and not s.startswith("#")]


def call(base, path, query, timeout=30):
    """(총 건수, 왕복 ms). 실패하면 (None, ms)."""
    url = f"{base}{path}?" + urllib.parse.urlencode({"q": query, "size": 10})
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            body = json.load(resp)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
        return None, (time.perf_counter() - started) * 1000, str(e)
    elapsed = (time.perf_counter() - started) * 1000
    # 하이브리드는 응답 모양이 조금 다르다(hits 만 있고 total 이 없을 수 있다).
    total = body.get("total")
    if total is None:
        total = len(body.get("hits", []))
    return total, elapsed, None


def stamps(es_url):
    """색인기가 남긴 버전 도장. 없으면 빈 dict."""
    out = {}
    for pipeline in ("search", "suggest", "vector"):
        try:
            with urllib.request.urlopen(f"{es_url}/psp_index_meta/_doc/{pipeline}", timeout=5) as r:
                out[pipeline] = json.load(r).get("_source")
        except Exception:
            out[pipeline] = None
    return out


STAGES = ["keyword", "vector", "hydrate", "fuse"]


def stage_snapshot(base):
    """`psp.query.stage.latency{channel=hybrid,stage=...}` 의 (count, 누적초).

    HybridSearchService.search()·present() 가 이미 Micrometer 로 남기는 값이다
    (QueryMetrics.stage/timer). 새 계측을 앱에 추가하지 않고, 이 실행 전후로
    같은 카운터를 두 번 읽어 **차분**하면 "이번 회귀 루프 동안"의 평균이 나온다
    (ADR 0003 이 손으로 하던 걸 스크립트 안으로 옮긴 것).
    """
    out = {}
    for stage in STAGES:
        url = (
            f"{base}/actuator/metrics/psp.query.stage.latency"
            f"?tag=channel:hybrid&tag=stage:{stage}"
        )
        try:
            with urllib.request.urlopen(url, timeout=5) as r:
                body = json.load(r)
            measurements = {m["statistic"]: m["value"] for m in body["measurements"]}
            out[stage] = (measurements.get("COUNT", 0.0), measurements.get("TOTAL_TIME", 0.0))
        except Exception:
            out[stage] = (0.0, 0.0)
    return out


def stage_breakdown(before, after):
    """스냅샷 두 장의 차분을 채널 평균 ms 로. count 가 그대로면(호출 없었으면) None."""
    out = {}
    for stage in STAGES:
        c0, t0 = before[stage]
        c1, t1 = after[stage]
        dcount = c1 - c0
        if dcount <= 0:
            out[stage] = None
            continue
        avg_ms = (t1 - t0) / dcount * 1000
        out[stage] = (dcount, avg_ms)
    return out


def percentile(values, p):
    if not values:
        return float("nan")
    ordered = sorted(values)
    idx = min(len(ordered) - 1, int(round((p / 100) * (len(ordered) - 1))))
    return ordered[idx]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--es", default="http://localhost:9200")
    ap.add_argument("--queries", type=Path, default=DEFAULT_QUERIES)
    ap.add_argument("--label", default="")
    args = ap.parse_args()

    queries = load_queries(args.queries)
    print(f"질의 {len(queries)}개 · 대상 {args.base}" + (f" · {args.label}" if args.label else ""))

    print("\n=== 버전 도장 (psp_index_meta) ===")
    for pipeline, stamp in stamps(args.es).items():
        print(f"  {pipeline:8s} {stamp if stamp else '(없음)'}")

    print(f"\n워밍업 {WARMUP_ROUNDS}회…", end="", flush=True)
    for _ in range(WARMUP_ROUNDS):
        for q in queries:
            for _, path in CHANNELS:
                call(args.base, path, q)
    print(" 완료")

    zero_hits = {name: [] for name, _ in CHANNELS}
    latencies = {name: [] for name, _ in CHANNELS}
    failures = []

    header = f"\n{'질의':<16}" + "".join(f"{name:>14}" for name, _ in CHANNELS)
    print(header)
    print("-" * (16 + 14 * len(CHANNELS)))

    stage_before = stage_snapshot(args.base)
    for q in queries:
        cells = []
        for name, path in CHANNELS:
            samples = []
            total = None
            for _ in range(REPEATS):
                total, ms, err = call(args.base, path, q)
                if err:
                    failures.append((q, name, err))
                    break
                samples.append(ms)
            if not samples:
                cells.append(f"{'실패':>14}")
                continue
            latencies[name].extend(samples)
            if total == 0:
                zero_hits[name].append(q)
            cells.append(f"{total:>7}건{statistics.median(samples):>6.1f}ms")
        print(f"{q:<16}" + "".join(cells))

    print("\n=== 0건 ===")
    for name, _ in CHANNELS:
        misses = zero_hits[name]
        print(f"  {name:<8} {len(misses):>2}개 / {len(queries)}개" + (f"  → {', '.join(misses)}" if misses else ""))

    print("\n=== 지연 (질의당 5회, 워밍업 후) ===")
    print(f"  {'채널':<10}{'중앙값':>10}{'p95':>10}{'최대':>10}")
    for name, _ in CHANNELS:
        vals = latencies[name]
        if not vals:
            continue
        print(f"  {name:<10}{statistics.median(vals):>8.1f}ms{percentile(vals, 95):>8.1f}ms{max(vals):>8.1f}ms")

    stage_after = stage_snapshot(args.base)
    breakdown = stage_breakdown(stage_before, stage_after)
    print("\n=== 하이브리드 단계 breakdown (Actuator 카운터 차분, 이 실행 동안 평균) ===")
    print(f"  {'단계':<10}{'호출':>8}{'평균':>10}")
    for stage in STAGES:
        entry = breakdown[stage]
        if entry is None:
            print(f"  {stage:<10}{'(호출 없음)':>10}")
            continue
        dcount, avg_ms = entry
        print(f"  {stage:<10}{dcount:>7.0f}회{avg_ms:>8.2f}ms")

    if failures:
        print(f"\n=== 실패 {len(failures)}건 ===")
        for q, name, err in failures[:10]:
            print(f"  {name} '{q}': {err}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
