#!/usr/bin/env python3
import argparse
import json
import os
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

TERMINAL = ("COMPLETED", "FAILED", "STOPPED", "ABANDONED")

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JAR = os.path.join(ROOT, "services", "indexer-batch", "build", "libs", "indexer-batch-0.0.1-SNAPSHOT.jar")


class Rss:
    def __init__(self, pid):
        self.pid = pid
        self.peak = 0
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)

    def run(self):
        while not self.stop.is_set():
            try:
                out = subprocess.run(
                    ["ps", "-o", "rss=", "-p", str(self.pid)],
                    capture_output=True, text=True, timeout=5,
                )
                value = int(out.stdout.strip() or 0)
                self.peak = max(self.peak, value)
            except Exception:
                pass
            self.stop.wait(2)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.stop.set()
        self.thread.join(timeout=5)


def wait_healthy(admin, deadline):
    while time.perf_counter() < deadline:
        try:
            with urllib.request.urlopen(f"{admin}/actuator/health", timeout=5) as resp:
                if json.load(resp).get("status") == "UP":
                    return True
        except Exception:
            pass
        time.sleep(2)
    return False


def reindex(admin, poll, budget):
    req = urllib.request.Request(f"{admin}/admin/vector/reindex", method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        if resp.status != 202:
            raise RuntimeError(f"202 를 기대했는데 {resp.status}")
        job_id = json.load(resp)["jobId"]

    deadline = time.perf_counter() + budget
    while time.perf_counter() < deadline:
        time.sleep(poll)
        with urllib.request.urlopen(f"{admin}/admin/jobs/{job_id}", timeout=15) as resp:
            progress = json.load(resp)
        if progress["status"] in TERMINAL:
            if progress["status"] != "COMPLETED":
                raise RuntimeError(f"job {job_id} 가 {progress['status']} 로 끝났다: {progress.get('failure')}")
            return progress
    raise RuntimeError(f"job {job_id} 가 {budget}초 안에 끝나지 않았다")


def run_one(size, args, log_dir):
    env = dict(os.environ, PSP_EMBEDDING_BATCHSIZE=str(size))
    log_path = os.path.join(log_dir, f"indexer-batch-{size}.log")

    with open(log_path, "w") as log:
        proc = subprocess.Popen(
            ["java", "-jar", JAR], env=env, stdout=log, stderr=subprocess.STDOUT,
            start_new_session=True,
        )

    try:
        if not wait_healthy(args.admin, time.perf_counter() + args.startup):
            raise RuntimeError(f"색인기가 {args.startup}초 안에 기동하지 않았다 — {log_path}")

        with Rss(proc.pid) as rss:
            started = time.perf_counter()
            progress = reindex(args.admin, args.poll, args.budget)
            wall = time.perf_counter() - started

        summary = progress.get("summary", {})
        embed_ms = int(summary.get("embedMs", 0))
        return {
            "batchSize": size,
            "wallSeconds": round(wall, 1),
            "jobElapsedMs": progress["elapsedMs"],
            "embedMs": embed_ms,
            "embedShare": embed_ms / progress["elapsedMs"] if progress["elapsedMs"] else 0.0,
            "read": int(summary.get("read", 0)),
            "upserted": int(summary.get("upserted", 0)),
            "collection": summary.get("collection"),
            "peakRssMb": round(rss.peak / 1024, 1),
            "log": log_path,
        }
    finally:
        os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
        try:
            proc.wait(timeout=60)
        except subprocess.TimeoutExpired:
            os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
        time.sleep(args.settle)


def report(rows):
    print("\n=== 임베딩 배치 크기 스윕 ===")
    print(f"  {'배치':>5}{'전체':>10}{'임베딩':>11}{'비율':>8}{'문서':>9}{'상주 최대':>11}")
    for r in rows:
        print(
            f"  {r['batchSize']:>5}{r['jobElapsedMs'] / 1000:>8.1f}초"
            f"{r['embedMs'] / 1000:>9.1f}초{r['embedShare'] * 100:>7.1f}%"
            f"{r['read']:>9,}{r['peakRssMb']:>9.0f}MB"
        )


def main():
    ap = argparse.ArgumentParser(
        description="psp.embedding.batch-size 를 바꿔가며 벡터 전체 재색인의 소요와 상주 메모리를 잰다.",
    )
    ap.add_argument("--admin", default="http://localhost:8081", help="indexer-batch")
    ap.add_argument("--sizes", default="16,32,64,128,256", help="임베딩 배치 크기 목록")
    ap.add_argument("--startup", type=int, default=180, help="기동 대기 상한(초)")
    ap.add_argument("--budget", type=int, default=3600, help="재색인 대기 상한(초)")
    ap.add_argument("--poll", type=float, default=5.0, help="job 폴링 간격(초)")
    ap.add_argument("--settle", type=int, default=10, help="실행 사이 유휴 시간(초)")
    ap.add_argument("--json", help="결과를 적을 파일")
    args = ap.parse_args()

    if not os.path.exists(JAR):
        sys.exit(f"jar 이 없다 — {JAR}\n  services 에서 ./gradlew :indexer-batch:bootJar 를 먼저 실행하십시오.")

    try:
        urllib.request.urlopen(f"{args.admin}/actuator/health", timeout=3).read()
        sys.exit(f"색인기가 이미 떠 있다 — {args.admin}. 이 스크립트가 직접 기동하므로 먼저 내리십시오.")
    except urllib.error.URLError:
        pass

    log_dir = os.path.join(ROOT, "logs", "embed-batch-sweep")
    os.makedirs(log_dir, exist_ok=True)

    rows = []
    for size in [int(x) for x in args.sizes.split(",")]:
        print(f"\n▶ 배치 {size} — 기동 후 전체 재색인")
        row = run_one(size, args, log_dir)
        print(
            f"  전체 {row['jobElapsedMs'] / 1000:.1f}초 · "
            f"임베딩 {row['embedMs'] / 1000:.1f}초({row['embedShare'] * 100:.1f}%) · "
            f"문서 {row['read']:,} · 상주 최대 {row['peakRssMb']:.0f}MB"
        )
        rows.append(row)

    report(rows)

    if args.json:
        with open(args.json, "w") as f:
            json.dump(rows, f, ensure_ascii=False, indent=2)
        print(f"\n기록: {args.json}")


if __name__ == "__main__":
    main()
