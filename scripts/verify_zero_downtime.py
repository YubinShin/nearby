#!/usr/bin/env python3
"""색인이 도는 동안 **검색이 계속 답하는지**, 그리고 **얼마나 느려지는지** 잰다.

  전제:  search-api(:8080) · indexer-batch(:8081) 기동, ES·Qdrant 가동
  주의:  재색인은 202 + jobId 로 접수되므로 이 스크립트가 job 상태를 폴링해
         측정 창을 색인 소요 시간에 맞춘다 (ADR 0013 · reindex() 주석)
  실행:  python3 scripts/verify_zero_downtime.py
  소요:  약 10분 (벡터 전체 재색인이 8분)

## 무엇을 확인하나

모듈을 쪼갠 이유가 "색인과 질의는 자원 성격이 반대라 한 프로세스에 두면 안 된다"였다
(ADR 0011). 그 주장을 검증하려면 **색인이 CPU 를 태우는 동안 질의가 어떻게 되는지**를
재야 한다. 예전 측정은 "실패 0" 만 봤는데, 그건 절반이다 — 죽지 않아도 느려지면 문제다.

그래서 세 구간을 같은 방법으로 재고 나란히 놓는다:

  1. 유휴          — 아무것도 안 도는 상태 (기준선)
  2. 키워드 재색인 중 — ES bulk 가 도는 동안 (약 17초)
  3. 벡터 재색인 중  — 임베딩 추론이 CPU 를 태우는 동안 (약 8분)

**빈 결과(0건)를 따로 세는 이유:** alias 스왑이 원자적이지 않으면 "가리키는 곳이 없는"
순간이 생겨 200 OK 에 0건이 돌아온다. 실패 카운터만 보면 이걸 놓친다.
"""

import argparse
import json
import statistics
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

# 세 채널을 다 두들긴다. 벡터·하이브리드는 임베딩 추론을 쓰므로 색인기와 CPU 를 다툰다.
CHANNELS = [("키워드", "/v1/search"), ("벡터", "/v1/vsearch"), ("하이브리드", "/v1/hsearch")]

# 결과가 반드시 있어야 하는 질의만 쓴다 — 0건이 나오면 그건 스왑 사고지 질의 탓이 아니다.
QUERIES = ["카페", "편의점", "세탁소", "미용실", "약국"]


class Hammer:
    """멈추라고 할 때까지 세 채널을 돌아가며 호출한다."""

    def __init__(self, base):
        self.base = base
        self.stop = threading.Event()
        self.lock = threading.Lock()
        self.samples = {name: [] for name, _ in CHANNELS}
        self.failures = []
        self.empties = []

    def _record(self, name, ms):
        with self.lock:
            self.samples[name].append(ms)

    def run(self):
        i = 0
        while not self.stop.is_set():
            name, path = CHANNELS[i % len(CHANNELS)]
            query = QUERIES[(i // len(CHANNELS)) % len(QUERIES)]
            i += 1
            url = f"{self.base}{path}?" + urllib.parse.urlencode({"q": query, "size": 10})
            started = time.perf_counter()
            try:
                with urllib.request.urlopen(url, timeout=30) as resp:
                    body = json.load(resp)
            except Exception as e:                      # noqa: BLE001 - 무엇이든 실패로 센다
                with self.lock:
                    self.failures.append(f"{name} '{query}': {e}")
                continue
            self._record(name, (time.perf_counter() - started) * 1000)

            total = body.get("total")
            if total is None:
                total = len(body.get("hits", []))
            if total == 0:
                with self.lock:
                    self.empties.append(f"{name} '{query}'")

    def total_requests(self):
        return sum(len(v) for v in self.samples.values()) + len(self.failures)


def percentile(values, p):
    if not values:
        return float("nan")
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round((p / 100) * (len(ordered) - 1))))]


def measure(base, seconds=None, trigger=None, workers=4, label=""):
    """`seconds` 동안, 또는 `trigger()` 가 끝날 때까지 두들기며 잰다."""
    hammer = Hammer(base)
    threads = [threading.Thread(target=hammer.run, daemon=True) for _ in range(workers)]
    for t in threads:
        t.start()

    started = time.perf_counter()
    result = None
    if trigger is not None:
        result = trigger()
    else:
        time.sleep(seconds)
    elapsed = time.perf_counter() - started

    hammer.stop.set()
    for t in threads:
        t.join(timeout=35)

    print(f"\n=== {label} ({elapsed:.0f}초) ===")
    print(f"  요청 {hammer.total_requests()}건 · 실패 {len(hammer.failures)}건 · 빈 결과 {len(hammer.empties)}건")
    print(f"  {'채널':<12}{'중앙값':>10}{'p95':>10}{'최대':>10}")
    for name, _ in CHANNELS:
        vals = hammer.samples[name]
        if vals:
            print(f"  {name:<12}{statistics.median(vals):>8.1f}ms{percentile(vals, 95):>8.1f}ms{max(vals):>8.1f}ms")
    for f in hammer.failures[:5]:
        print(f"  실패: {f}")
    for e in hammer.empties[:5]:
        print(f"  빈 결과: {e}")

    return {
        "label": label,
        "elapsed": elapsed,
        "requests": hammer.total_requests(),
        "failures": len(hammer.failures),
        "empties": len(hammer.empties),
        "median": {n: statistics.median(v) for n, _ in CHANNELS if (v := hammer.samples[n])},
        "p95": {n: percentile(v, 95) for n, _ in CHANNELS if (v := hammer.samples[n])},
        "trigger": result,
    }


TERMINAL = ("COMPLETED", "FAILED", "STOPPED", "ABANDONED")


def reindex(admin, path, poll=2.0, budget=3600):
    """색인 job 을 걸고 **끝날 때까지 기다린다.** 그 대기 시간이 곧 측정 창이다.

    ## 왜 폴링을 하게 됐나 (ADR 0013)
    전에는 이 POST 가 색인이 끝날 때까지 응답하지 않았고, 측정 창이 그 성질에 **의존**하고
    있었다 — `urlopen` 이 돌아오는 순간이 곧 "색인 끝"이었다.

    지금은 접수하면 즉시 `202 {jobId}` 가 돌아온다. 그래서 이 함수를 안 고치면 측정 창이
    50ms 로 쪼그라들고, 색인은 **측정이 끝난 뒤에** 돈다. 실패도 안 나고 숫자도 그럴듯하게
    나오는데 재는 대상만 사라지는 종류의 고장이라, 결과를 믿고 결론을 쓰게 된다.

    그래서 job 상태를 폴링해 창을 다시 색인 소요 시간에 맞춘다. 폴링은 색인기(8081)로 가고
    질의기(8080)를 건드리지 않으므로 지연 측정에 섞이지 않는다.

    바뀐 김에 얻는 것: 반환값에 프레임워크가 센 step 별 건수·커밋·롤백이 들어온다.
    전에는 "몇 초 걸렸다"뿐이었다.
    """
    def _call():
        req = urllib.request.Request(f"{admin}{path}", method="POST")
        with urllib.request.urlopen(req, timeout=30) as resp:
            if resp.status != 202:
                raise RuntimeError(f"202 를 기대했는데 {resp.status} — 엔드포인트가 동기인가?")
            accepted = json.load(resp)

        job_id = accepted["jobId"]
        deadline = time.perf_counter() + budget

        while time.perf_counter() < deadline:
            time.sleep(poll)
            with urllib.request.urlopen(f"{admin}/admin/jobs/{job_id}", timeout=15) as resp:
                progress = json.load(resp)
            if progress["status"] in TERMINAL:
                if progress["status"] != "COMPLETED":
                    raise RuntimeError(
                        f"job {job_id} 가 {progress['status']} 로 끝났다: {progress.get('failure')}"
                    )
                return {
                    "jobId": job_id,
                    "jobName": progress["jobName"],
                    "elapsedMs": progress["elapsedMs"],
                    # 프레임워크가 chunk 커밋마다 DB 에 적은 값 (BATCH_STEP_EXECUTION)
                    "steps": {
                        s["name"]: {
                            "read": s["read"],
                            "commits": s["commits"],
                            "rollbacks": s["rollbacks"],
                        }
                        for s in progress["steps"]
                    },
                    "summary": progress["summary"],
                }

        raise RuntimeError(f"job {job_id} 가 {budget}초 안에 끝나지 않았다")
    return _call


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080", help="search-api")
    ap.add_argument("--admin", default="http://localhost:8081", help="indexer-batch")
    ap.add_argument("--idle", type=int, default=30, help="기준선 측정 시간(초)")
    ap.add_argument("--skip-vector", action="store_true", help="8분짜리 벡터 재색인을 건너뛴다")
    args = ap.parse_args()

    for name, url in (("search-api", args.base), ("indexer-batch", args.admin)):
        try:
            urllib.request.urlopen(f"{url}/actuator/health", timeout=5).read()
        except Exception as e:                          # noqa: BLE001
            sys.exit(f"{name} 에 연결할 수 없습니다 ({url}): {e}")

    runs = [measure(args.base, seconds=args.idle, label="① 유휴 (기준선)")]

    runs.append(measure(
        args.base,
        trigger=reindex(args.admin, "/admin/reindex"),
        label="② 키워드 전체 재색인 중",
    ))

    if not args.skip_vector:
        runs.append(measure(
            args.base,
            trigger=reindex(args.admin, "/admin/vector/reindex"),
            label="③ 벡터 전체 재색인 중 (임베딩 추론이 CPU 를 태운다)",
        ))

    print("\n\n=== 요약 ===")
    print(f"{'구간':<34}{'요청':>7}{'실패':>6}{'빈결과':>7}{'하이브리드 중앙값':>18}{'p95':>10}")
    for r in runs:
        med = r["median"].get("하이브리드", float("nan"))
        p95 = r["p95"].get("하이브리드", float("nan"))
        print(f"{r['label']:<34}{r['requests']:>7}{r['failures']:>6}{r['empties']:>7}{med:>16.1f}ms{p95:>8.1f}ms")

    failed = sum(r["failures"] for r in runs) + sum(r["empties"] for r in runs)
    print(f"\n총 실패 {sum(r['failures'] for r in runs)}건 · 총 빈 결과 {sum(r['empties'] for r in runs)}건")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
