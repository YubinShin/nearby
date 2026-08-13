#!/usr/bin/env python3
import argparse
import asyncio
import json
import re
import resource
import statistics
import sys
import urllib.parse

CHANNELS = {
    "vector": "/v1/vsearch",
    "hybrid": "/v1/hsearch",
    "keyword": "/v1/search",
}

STEMS = [
    "카페", "편의점", "세탁소", "미용실", "약국",
    "치킨집", "고깃집", "빵집", "노래방", "주차장",
]

GAUGE = re.compile(r"^psp_query_embed_queue_depth\{[^}]*\}\s+([0-9.eE+-]+)", re.M)


class Meter:
    def __init__(self):
        self.attempted = 0
        self.served = []
        self.rejected = []
        self.failed = 0
        self.errors = {}
        self.inflight = 0
        self.peakInflight = 0
        self.saturated = False

    def enter(self):
        self.attempted += 1
        self.inflight += 1
        self.peakInflight = max(self.peakInflight, self.inflight)

    def leave(self):
        self.inflight -= 1

    def note(self, name):
        self.errors[name] = self.errors.get(name, 0) + 1


async def fetch(host, port, target, timeout, keep_raw=False):
    reader, writer = await asyncio.open_connection(host, port)
    try:
        request = (
            f"GET {target} HTTP/1.0\r\n"
            f"Host: {host}:{port}\r\n"
            "Accept: */*\r\n"
            "Connection: close\r\n\r\n"
        )
        writer.write(request.encode())
        await writer.drain()
        raw = await asyncio.wait_for(reader.read(-1), timeout)
    finally:
        writer.close()
        try:
            await writer.wait_closed()
        except Exception:
            pass

    head, _, body = raw.partition(b"\r\n\r\n")
    if not head:
        raise ValueError("빈 응답")
    return int(head.split(b" ")[1]), (raw if keep_raw else body)


async def one(host, port, path, index, meter, timeout, loop):
    meter.enter()
    scheduled = loop.time()
    try:
        query = f"{STEMS[index % len(STEMS)]} {index}"
        target = f"{path}?" + urllib.parse.urlencode({"q": query, "size": 10})
        status, body = await fetch(host, port, target, timeout)
        elapsed = (loop.time() - scheduled) * 1000
        if status == 429:
            meter.rejected.append(elapsed)
        elif status == 200:
            meter.served.append(elapsed)
        else:
            meter.failed += 1
            meter.note(f"http_{status}")
    except Exception as e:
        meter.failed += 1
        meter.note(type(e).__name__)
    finally:
        meter.leave()


async def sample_depth(host, port, meter, stop, depths, misses, interval=0.25):
    while not stop.is_set():
        try:
            status, raw = await fetch(host, port, "/actuator/prometheus", 5, keep_raw=True)
            m = GAUGE.search(raw.decode("utf-8", "replace")) if status == 200 else None
            if m:
                depths.append((round(len(depths) * interval, 2), float(m.group(1)), meter.inflight))
            else:
                misses.append(f"no-match({status})")
        except Exception as e:
            misses.append(type(e).__name__)
        try:
            await asyncio.wait_for(stop.wait(), interval)
        except asyncio.TimeoutError:
            pass


async def run_rate(host, port, path, rate, seconds, timeout, cap):
    loop = asyncio.get_running_loop()
    meter = Meter()
    depths = []
    misses = []
    stop = asyncio.Event()
    sampler = asyncio.create_task(sample_depth(host, port, meter, stop, depths, misses))

    interval = 1.0 / rate
    started = loop.time()
    tasks = []
    index = 0
    while True:
        due = started + index * interval
        if due - started >= seconds:
            break
        delay = due - loop.time()
        if delay > 0:
            await asyncio.sleep(delay)
        if meter.inflight >= cap:
            meter.saturated = True
        else:
            tasks.append(asyncio.create_task(one(host, port, path, index, meter, timeout, loop)))
        index += 1

    scheduled = index
    if tasks:
        await asyncio.gather(*tasks, return_exceptions=True)
    drain = loop.time() - started - seconds

    stop.set()
    await sampler

    return {
        "rate": rate,
        "seconds": seconds,
        "scheduled": scheduled,
        "attempted": meter.attempted,
        "served": len(meter.served),
        "rejected": len(meter.rejected),
        "failed": meter.failed,
        "errors": meter.errors,
        "p50": percentile(meter.served, 50),
        "p95": percentile(meter.served, 95),
        "p99": percentile(meter.served, 99),
        "max": max(meter.served) if meter.served else float("nan"),
        "peakInflight": meter.peakInflight,
        "generatorSaturated": meter.saturated,
        "drainSeconds": round(drain, 2),
        "queueDepthMax": max((d for _, d, _ in depths), default=float("nan")),
        "queueDepthSamples": len(depths),
        "queueDepthMisses": len(misses),
        "queueDepthMissReasons": sorted(set(misses)),
        "queueDepth": depths,
    }


def percentile(values, p):
    if not values:
        return float("nan")
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round((p / 100) * (len(ordered) - 1))))]


def report(r):
    share = 100 * r["rejected"] / r["attempted"] if r["attempted"] else 0.0
    print(f"\n=== 도착률 {r['rate']}/s · {r['seconds']}초 ===")
    print(
        f"  예정 {r['scheduled']}건 · 처리 {r['served']}건 · 거절 {r['rejected']}건({share:.1f}%) · "
        f"실패 {r['failed']}건 · 배수 소요 {r['drainSeconds']}초"
    )
    print(
        f"  처리 지연  p50 {r['p50']:.1f}ms · p95 {r['p95']:.1f}ms · "
        f"p99 {r['p99']:.1f}ms · 최대 {r['max']:.1f}ms"
    )
    print(
        f"  서버 대기열  최대 {r['queueDepthMax']:.0f} "
        f"(표본 {r['queueDepthSamples']}건 · 실패 {r['queueDepthMisses']}건"
        + (f" — {', '.join(r['queueDepthMissReasons'])}" if r.get("queueDepthMissReasons") else "")
        + ")"
    )
    print(f"  클라이언트 동시 진행  최대 {r['peakInflight']}")
    if r["generatorSaturated"]:
        print(f"  ** 생성기 포화 — 동시 진행 상한에 닿아 도착률을 못 지켰다 **")
    if r["errors"]:
        print("  오류  " + " · ".join(f"{k} {v}건" for k, v in r["errors"].items()))


def summary(results, label):
    print(f"\n=== 요약 · {label} ===")
    print(f"  {'도착률':>7}{'처리':>8}{'거절':>9}{'p50':>10}{'p95':>10}{'p99':>10}{'대기열 최대':>13}")
    for r in results:
        share = 100 * r["rejected"] / r["attempted"] if r["attempted"] else 0.0
        print(
            f"  {r['rate']:>7}{r['served']:>8}{share:>8.1f}%{r['p50']:>8.1f}ms"
            f"{r['p95']:>8.1f}ms{r['p99']:>8.1f}ms{r['queueDepthMax']:>13.0f}"
        )


async def main():
    ap = argparse.ArgumentParser(
        description="응답과 무관하게 고정 도착률로 질의를 밀어 넣고, 서버 대기열이 발산하는지 본다.",
    )
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--channel", default="vector", choices=sorted(CHANNELS))
    ap.add_argument("--rates", default="150,250,400", help="초당 도착 건수 목록")
    ap.add_argument("--seconds", type=int, default=15, help="도착률별 주입 시간")
    ap.add_argument("--settle", type=int, default=20, help="도착률 사이 유휴 시간")
    ap.add_argument("--timeout", type=float, default=30.0, help="요청 타임아웃")
    ap.add_argument("--cap", type=int, default=6000, help="클라이언트 동시 진행 상한")
    ap.add_argument("--warmup", type=int, default=200, help="워밍업 요청 수")
    ap.add_argument("--label", default="")
    ap.add_argument("--json")
    args = ap.parse_args()

    resource.setrlimit(resource.RLIMIT_NOFILE, (8192, 8192))
    path = CHANNELS[args.channel]

    try:
        status, _ = await fetch(args.host, args.port, "/actuator/health", 5)
        if status != 200:
            sys.exit(f"질의기 health 가 {status}")
    except Exception as e:
        sys.exit(f"질의기에 닿지 않는다 — {args.host}:{args.port}: {e}")

    loop = asyncio.get_running_loop()
    warm = Meter()
    for i in range(args.warmup):
        await one(args.host, args.port, path, 10_000_000 + i, warm, args.timeout, loop)

    print(f"{args.label + ' · ' if args.label else ''}채널 {args.channel} ({path}) · 워밍업 {args.warmup}건")

    results = []
    for rate in [int(x) for x in args.rates.split(",")]:
        r = await run_rate(args.host, args.port, path, rate, args.seconds, args.timeout, args.cap)
        report(r)
        results.append(r)
        await asyncio.sleep(args.settle)

    summary(results, args.label or args.channel)

    if args.json:
        with open(args.json, "w") as f:
            json.dump({"label": args.label, "channel": args.channel, "rates": results}, f, ensure_ascii=False, indent=2)
        print(f"\n기록: {args.json}")


if __name__ == "__main__":
    asyncio.run(main())
