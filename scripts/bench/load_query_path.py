#!/usr/bin/env python3
import argparse
import json
import re
import statistics
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter

CHANNELS = {
    "vector": "/v1/vsearch",
    "hybrid": "/v1/hsearch",
    "keyword": "/v1/search",
}

STEMS = [
    "카페", "편의점", "세탁소", "미용실", "약국",
    "치킨집", "고깃집", "빵집", "노래방", "주차장",
]

BUCKET = re.compile(r'^psp_query_embed_wait_seconds_bucket\{.*le="([^"]+)"')
REJECTED = re.compile(r'^psp_query_embed_rejected_total\{.*reason="([^"]+)"')


class Hammer:
    def __init__(self, base, path, unique):
        self.base = base
        self.path = path
        self.unique = unique
        self.stop = threading.Event()
        self.lock = threading.Lock()
        self.issued = 0
        self.served = []
        self.rejected = []
        self.outcomes = Counter()
        self.failures = []

    def next_query(self):
        with self.lock:
            self.issued += 1
            n = self.issued
        stem = STEMS[n % len(STEMS)]
        return f"{stem} {n}" if self.unique else stem

    def run(self):
        while not self.stop.is_set():
            q = self.next_query()
            url = f"{self.base}{self.path}?" + urllib.parse.urlencode({"q": q, "size": 10})
            started = time.perf_counter()
            try:
                with urllib.request.urlopen(url, timeout=30) as resp:
                    body = json.load(resp)
            except urllib.error.HTTPError as e:
                elapsed = (time.perf_counter() - started) * 1000
                with self.lock:
                    if e.code == 429:
                        self.rejected.append(elapsed)
                        self.outcomes["rejected"] += 1
                    else:
                        self.outcomes[f"http_{e.code}"] += 1
                        self.failures.append(f"{q}: HTTP {e.code}")
                continue
            except Exception as e:
                with self.lock:
                    self.outcomes["failed"] += 1
                    self.failures.append(f"{q}: {e}")
                continue

            elapsed = (time.perf_counter() - started) * 1000
            with self.lock:
                self.served.append(elapsed)
                self.outcomes["degraded" if body.get("degraded") else "ok"] += 1


def percentile(values, p):
    if not values:
        return float("nan")
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round((p / 100) * (len(ordered) - 1))))]


def scrape(base):
    try:
        with urllib.request.urlopen(f"{base}/actuator/prometheus", timeout=10) as resp:
            return resp.read().decode()
    except Exception:
        return ""


def samples(text):
    out = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        key, _, value = line.rpartition(" ")
        try:
            out[key.strip()] = float(value)
        except ValueError:
            continue
    return out


def wait_histogram(before, after):
    buckets = {}
    for key, value in after.items():
        m = BUCKET.match(key)
        if not m:
            continue
        le = m.group(1)
        buckets[le] = value - before.get(key, 0.0)

    ordered = sorted(buckets.items(), key=lambda kv: float(kv[0]))
    spread, previous = [], 0.0
    for le, cumulative in ordered:
        spread.append((le, cumulative - previous))
        previous = cumulative
    return [(le, int(count)) for le, count in spread if count > 0]


def rejections(before, after):
    out = Counter()
    for key, value in after.items():
        m = REJECTED.match(key)
        if not m:
            continue
        delta = value - before.get(key, 0.0)
        if delta > 0:
            out[m.group(1)] = int(delta)
    return out


def run_level(base, path, concurrency, seconds, unique):
    before = samples(scrape(base))

    hammer = Hammer(base, path, unique)
    threads = [threading.Thread(target=hammer.run, daemon=True) for _ in range(concurrency)]
    started = time.perf_counter()
    for t in threads:
        t.start()
    time.sleep(seconds)
    hammer.stop.set()
    for t in threads:
        t.join(timeout=35)
    elapsed = time.perf_counter() - started

    after = samples(scrape(base))

    total = len(hammer.served) + len(hammer.rejected) + hammer.outcomes["failed"]
    return {
        "concurrency": concurrency,
        "elapsed": elapsed,
        "requests": total,
        "rps": total / elapsed if elapsed else 0.0,
        "served": len(hammer.served),
        "rejected": len(hammer.rejected),
        "degraded": hammer.outcomes["degraded"],
        "failed": hammer.outcomes["failed"],
        "p50": percentile(hammer.served, 50),
        "p95": percentile(hammer.served, 95),
        "p99": percentile(hammer.served, 99),
        "max": max(hammer.served) if hammer.served else float("nan"),
        "rejected_p95": percentile(hammer.rejected, 95),
        "wait": wait_histogram(before, after),
        "reasons": dict(rejections(before, after)),
        "failures": hammer.failures[:5],
    }


def report(level):
    share = 100 * level["rejected"] / level["requests"] if level["requests"] else 0.0
    degraded = 100 * level["degraded"] / level["requests"] if level["requests"] else 0.0
    print(f"\n=== 동시 {level['concurrency']} · {level['elapsed']:.0f}초 ===")
    print(
        f"  요청 {level['requests']}건 · {level['rps']:.0f} req/s · "
        f"처리 {level['served']}건 · 거절 {level['rejected']}건({share:.1f}%) · "
        f"강등 {level['degraded']}건({degraded:.1f}%) · 실패 {level['failed']}건"
    )
    print(
        f"  처리 지연  p50 {level['p50']:.1f}ms · p95 {level['p95']:.1f}ms · "
        f"p99 {level['p99']:.1f}ms · 최대 {level['max']:.1f}ms"
    )
    if level["reasons"]:
        print("  거절 사유  " + " · ".join(f"{k} {v}건" for k, v in level["reasons"].items()))
    if level["wait"]:
        print("  대기 분포  " + " · ".join(f"≤{le}s {n}건" for le, n in level["wait"]))
    for f in level["failures"]:
        print(f"  실패: {f}")


def summary(levels):
    print("\n=== 요약 ===")
    print(f"  {'동시':>4}{'req/s':>10}{'p50':>10}{'p95':>10}{'p99':>10}{'거절':>9}{'강등':>9}")
    for l in levels:
        share = 100 * l["rejected"] / l["requests"] if l["requests"] else 0.0
        degraded = 100 * l["degraded"] / l["requests"] if l["requests"] else 0.0
        print(
            f"  {l['concurrency']:>4}{l['rps']:>10.0f}{l['p50']:>8.1f}ms"
            f"{l['p95']:>8.1f}ms{l['p99']:>8.1f}ms{share:>8.1f}%{degraded:>8.1f}%"
        )


def main():
    ap = argparse.ArgumentParser(
        description="질의 경로에 동시 요청을 올려가며 지연·거절·강등을 잰다.",
    )
    ap.add_argument("--base", default="http://localhost:8080", help="search-api")
    ap.add_argument("--channel", default="vector", choices=sorted(CHANNELS), help="때릴 채널")
    ap.add_argument("--levels", default="1,2,4,8,16,32,64", help="동시 수 목록")
    ap.add_argument("--seconds", type=int, default=20, help="동시 수별 측정 시간")
    ap.add_argument("--settle", type=int, default=3, help="동시 수 사이 유휴 시간")
    ap.add_argument("--warmup", type=int, default=10, help="JIT 워밍업 요청 수")
    ap.add_argument(
        "--repeat-queries",
        action="store_true",
        help="질의를 재사용한다. 질의 벡터 캐시가 히트해 게이트를 타지 않는다",
    )
    ap.add_argument("--json", help="결과를 적을 파일")
    args = ap.parse_args()

    path = CHANNELS[args.channel]
    unique = not args.repeat_queries

    try:
        urllib.request.urlopen(f"{args.base}/actuator/health", timeout=5).read()
    except Exception as e:
        sys.exit(f"search-api 에 닿지 않는다 — {args.base}: {e}")

    for i in range(args.warmup):
        try:
            url = f"{args.base}{path}?" + urllib.parse.urlencode({"q": f"워밍업 {i}", "size": 10})
            urllib.request.urlopen(url, timeout=30).read()
        except Exception:
            pass

    print(f"채널 {args.channel} ({path}) · 질의 {'매번 새로' if unique else '재사용'}")

    levels = []
    for concurrency in [int(x) for x in args.levels.split(",")]:
        level = run_level(args.base, path, concurrency, args.seconds, unique)
        report(level)
        levels.append(level)
        time.sleep(args.settle)

    summary(levels)

    if args.json:
        with open(args.json, "w") as f:
            json.dump({"channel": args.channel, "unique": unique, "levels": levels}, f, ensure_ascii=False, indent=2)
        print(f"\n기록: {args.json}")


if __name__ == "__main__":
    main()
