#!/usr/bin/env python3
"""증분 색인이 **바뀐 것만** 집어 인덱스에 반영하는지 검증한다.

  전제:  search-api(:8080) · indexer-batch(:8081) 기동, ES·PostGIS 가동,
         이미 전체 재색인이 한 번 돌아 alias 와 watermark 가 있어야 한다
  실행:  python3 scripts/verify_incremental.py
  소요:  합성 모드 1~2분 (변경 건수가 적어 증분 자체는 수 초)

## 왜 필요한가

`README` 와 이력서는 "watermark 기반 증분 색인을 구현했다"고 말한다. 구현은 맞다
(`PlaceSql.SELECT_SINCE`, `schema.sql` 의 `place_touch_updated_at` 트리거).
그런데 **원천이 스냅샷 한 장**이라 "실제로 바뀐 행"이 없어서, 지금까지 증분을 실제
변경분으로 돌려본 적이 없다. `verify_zero_downtime.py` 는 **무중단 재색인**을 재는
스크립트지 증분을 재지 않는다.

## 무엇을 단언하나

증분의 정의는 "바뀐 것만 읽는다"이므로, 가장 중요한 단언은 **읽은 행 수**다.

  1. 읽은 행 수 == 바꾼 행 수      ← 전체(6만+)를 읽었으면 그건 증분이 아니다
  2. 개업(INSERT)   → 인덱스에 문서가 **생겼다**
  3. 폐업(soft delete) → 인덱스에서 문서가 **사라졌다**
  4. 정보 변경(UPDATE) → 인덱스 문서의 값이 **새 값이다**
  5. watermark 가 **전진했다**

3번이 특히 중요하다. 원천은 행을 지우지 않고 `deleted_at` 만 찍는데(soft delete),
`SELECT_SINCE` 는 `deleted_at is null` 조건이 없어서 삭제된 행도 읽고,
`KeywordBulkWriter` 가 `deletedAt != null` 이면 `BulkAction.Delete` 로 바꾼다.
**"삭제도 변경이다"** 라는 설계가 실제로 도는지 보는 자리다.

## 두 가지 모드

  --synthetic (기본)  행을 직접 바꾼 뒤 증분을 돌린다. 언제든 돌릴 수 있지만
                      **진짜 변경분이 아니다.** 원천이 준 변경이 아니라 내가 만든 변경이다.
  --observe           아무것도 바꾸지 않는다. 이미 원천에 들어온 변경분
                      (예: 분기 갱신분을 upsert 한 직후)으로 증분을 돌려 검증한다.
                      이쪽이 진짜다. 이 데이터셋은 분기 갱신이므로 새 CSV 를 적재한 뒤 쓴다.

합성 모드는 기본적으로 **원상복구**한다(`--keep` 으로 끌 수 있다). 복구도 증분을 한 번 더
돌려서 되돌리므로, 스크립트가 끝나면 인덱스는 시작 시점과 같은 상태가 된다.
"""

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "http://localhost:8080"
INDEXER = "http://localhost:8081"
ES = "http://localhost:9200"

SEARCH_ALIAS = "place_search"
CHECKPOINT_INDEX = "psp_index_checkpoint"
KEYWORD_CHECKPOINT = "place"
VECTOR_CHECKPOINT = "place_vector"

PG_CONTAINER = "psp-postgis"
PG_USER = "place"
PG_DB = "place"

# 합성 행을 알아볼 표식. 원천 place_id 는 숫자 문자열이라 겹치지 않는다.
MARK = "VERIFY-INC"
RENAME_TAG = "증분검증표식"

JOB_TIMEOUT_S = 900
POLL_INTERVAL_S = 1.0


# --- 원천(PostGIS) ---------------------------------------------------------

def psql(sql):
    """한 줄 결과들을 탭 구분 문자열 리스트로 돌려준다. load_place.sh 와 같은 접속 방식."""
    proc = subprocess.run(
        ["docker", "exec", "-i", PG_CONTAINER, "psql", "-U", PG_USER, "-d", PG_DB,
         "-t", "-A", "-F", "\t", "-v", "ON_ERROR_STOP=1", "-c", sql],
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"psql 실패: {proc.stderr.strip()}")
    return [line for line in proc.stdout.strip().splitlines() if line]


def psql_one(sql):
    rows = psql(sql)
    return rows[0] if rows else None


def q(value):
    """SQL 문자열 리터럴. 상호명에 작은따옴표가 들어 있어도 깨지지 않게 한다."""
    return "'" + str(value).replace("'", "''") + "'"


# --- HTTP ------------------------------------------------------------------

def http(url, method="GET", body=None, quiet_404=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        if e.code == 404 and quiet_404:
            return None
        raise RuntimeError(f"{method} {url} → {e.code} {e.read().decode()[:200]}")


def es_doc(place_id):
    """서빙 alias 에서 문서 하나를 읽는다. 없으면 None."""
    doc = http(f"{ES}/{SEARCH_ALIAS}/_doc/{place_id}", quiet_404=True)
    return doc.get("_source") if doc else None


def checkpoint(doc_id):
    doc = http(f"{ES}/{CHECKPOINT_INDEX}/_doc/{doc_id}", quiet_404=True)
    return doc.get("_source", {}).get("last_updated_at") if doc else None


def run_incremental(path, label):
    """증분 job 을 접수하고 끝날 때까지 폴링한다. 컨트롤러가 없으면(벡터 비활성) None."""
    accepted = http(f"{INDEXER}{path}", method="POST", quiet_404=True)
    if accepted is None:
        print(f"  ℹ {label} 증분 엔드포인트가 없다 (비활성 프로파일) — 건너뜀")
        return None

    job_id = accepted["jobId"]
    deadline = time.time() + JOB_TIMEOUT_S
    while time.time() < deadline:
        progress = http(f"{INDEXER}/admin/jobs/{job_id}")
        if not progress["running"]:
            if progress["status"] != "COMPLETED":
                raise RuntimeError(f"{label} 증분 실패: {progress['status']} / {progress.get('failure')}")
            return progress
        time.sleep(POLL_INTERVAL_S)
    raise RuntimeError(f"{label} 증분이 {JOB_TIMEOUT_S}초 안에 끝나지 않았다")


def rows_read(progress, step_suffix):
    for step in progress["steps"]:
        if step["name"].endswith(step_suffix):
            return step["read"]
    return None


# --- 변경 시나리오 ---------------------------------------------------------

def pick_victims(count):
    """살아 있는 행 중 앞쪽 N개를 고른다. place_id 정렬이라 실행마다 같은 행이 뽑힌다."""
    rows = psql(
        f"select place_id, name from public.place "
        f"where deleted_at is null and place_id not like {q(MARK + '%')} "
        f"order by place_id limit {count}"
    )
    return [tuple(r.split("\t", 1)) for r in rows]


def apply_changes(n_new, n_closed, n_renamed):
    """개업 · 폐업 · 정보변경 세 갈래를 만든다. 되돌리기에 필요한 원본을 함께 반환."""
    victims = pick_victims(n_closed + n_renamed)
    if len(victims) < n_closed + n_renamed:
        raise RuntimeError("원천에 바꿀 행이 부족하다 — 적재가 안 된 것 같다")

    closed = victims[:n_closed]
    renamed = victims[n_closed:]

    created = []
    for i in range(n_new):
        pid = f"{MARK}-{i + 1}"
        name = f"증분검증 신규가게{i + 1}"
        psql(
            "insert into public.place "
            "(place_id, name, category_large, category_mid, category_small, "
            " sido, sigungu, dong, jibun_address, road_address, lon, lat) values "
            f"({q(pid)}, {q(name)}, '음식', '카페', '카페', "
            f" '서울특별시', '강남구', '역삼동', '검증용 주소', '검증용 도로명', 127.0276, 37.4979) "
            "on conflict (place_id) do nothing"
        )
        created.append((pid, name))

    for pid, _ in closed:
        psql(f"update public.place set deleted_at = now() where place_id = {q(pid)}")

    for pid, name in renamed:
        psql(f"update public.place set name = {q(name + ' ' + RENAME_TAG)} where place_id = {q(pid)}")

    return created, closed, renamed


def revert_changes(created, closed, renamed):
    """원상복구. 신규 행도 먼저 soft delete 해서 증분이 인덱스에서 지우게 한 뒤 물리 삭제한다."""
    for pid, _ in created:
        psql(f"update public.place set deleted_at = now() where place_id = {q(pid)}")
    for pid, _ in closed:
        psql(f"update public.place set deleted_at = null where place_id = {q(pid)}")
    for pid, name in renamed:
        psql(f"update public.place set name = {q(name)} where place_id = {q(pid)}")


def drop_synthetic_rows():
    psql(f"delete from public.place where place_id like {q(MARK + '%')}")


# --- 검증 ------------------------------------------------------------------

class Report:
    def __init__(self):
        self.checks = []

    def check(self, ok, label, detail=""):
        self.checks.append((ok, label, detail))
        print(f"  {'✔' if ok else '✘'} {label}{('  — ' + detail) if detail else ''}")

    def info(self, label):
        print(f"  ℹ {label}")

    @property
    def failed(self):
        return [c for c in self.checks if not c[0]]


def verify(report, created, closed, renamed, read_count, before_wm, after_wm):
    expected = len(created) + len(closed) + len(renamed)

    report.check(
        read_count == expected,
        f"읽은 행 수 = 바꾼 행 수 ({read_count} / {expected})",
        "" if read_count == expected else "전체를 읽었다면 증분이 아니다",
    )

    for pid, name in created:
        doc = es_doc(pid)
        report.check(doc is not None, f"개업 {pid} 문서 생성", "" if doc else "인덱스에 없다")

    for pid, name in closed:
        doc = es_doc(pid)
        report.check(doc is None, f"폐업 {pid} 문서 삭제", "" if doc is None else f"아직 남아 있다: {doc.get('name')}")

    for pid, name in renamed:
        doc = es_doc(pid)
        got = doc.get("name") if doc else None
        want = f"{name} {RENAME_TAG}"
        report.check(got == want, f"변경 {pid} 문서 갱신", "" if got == want else f"기대 {want!r} / 실제 {got!r}")

    advanced = bool(after_wm) and (not before_wm or after_wm > before_wm)
    report.check(advanced, "watermark 전진", f"{before_wm} → {after_wm}")

    # 종단 확인: 실제로 검색되는지. 형태소 분석 결과에 좌우되므로 단언이 아니라 참고로만 찍는다.
    if created:
        pid, name = created[0]
        try:
            resp = http(f"{API}/v1/search?q={urllib.parse.quote(name)}&size=10")
            hit = any(h["placeId"] == pid for h in resp.get("hits", []))
            report.info(f"search-api 로 {name!r} 조회 → {'찾음' if hit else '못 찾음(분석기 의존, 실패 아님)'}")
        except Exception as e:
            report.info(f"search-api 조회 생략: {e}")


def observe_pending():
    """--observe 모드: watermark 이후 변경분이 원천에 몇 건 있고 어떻게 구성됐는지 센다."""
    wm = checkpoint(KEYWORD_CHECKPOINT)
    if wm is None:
        raise RuntimeError("watermark 가 없다 — 먼저 전체 재색인이 필요하다")
    total = int(psql_one(f"select count(*) from public.place where updated_at > {q(wm)}::timestamptz"))
    deleted = int(
        psql_one(
            f"select count(*) from public.place "
            f"where updated_at > {q(wm)}::timestamptz and deleted_at is not null"
        )
    )
    return wm, total, total - deleted, deleted


# --- main ------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="증분 색인 검증")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--synthetic", action="store_true", help="행을 직접 바꿔서 검증 (기본)")
    mode.add_argument("--observe", action="store_true", help="바꾸지 않고 원천의 실제 변경분으로 검증")
    ap.add_argument("--new", type=int, default=3, help="합성 개업 건수 (기본 3)")
    ap.add_argument("--closed", type=int, default=3, help="합성 폐업 건수 (기본 3)")
    ap.add_argument("--renamed", type=int, default=3, help="합성 정보변경 건수 (기본 3)")
    ap.add_argument("--keep", action="store_true", help="합성 변경을 되돌리지 않는다")
    ap.add_argument("--no-vector", action="store_true", help="벡터 증분은 돌리지 않는다")
    args = ap.parse_args()

    observe = args.observe

    print("=" * 72)
    print("증분 색인 검증" + ("  [observe — 원천의 실제 변경분]" if observe else "  [synthetic — 만들어낸 변경분]"))
    print("=" * 72)

    corpus = int(psql_one("select count(*) from public.place where deleted_at is null"))
    before_wm = checkpoint(KEYWORD_CHECKPOINT)
    print(f"\n원천 살아있는 행: {corpus:,}   watermark: {before_wm or '(없음)'}")
    if before_wm is None:
        print("\n✘ watermark 가 없다. 먼저 전체 재색인(POST /admin/reindex)을 돌려야 한다.")
        return 1

    created = closed = renamed = []
    if observe:
        wm, pending, upserts, deletes = observe_pending()
        print(f"watermark 이후 변경분: {pending:,}건  (갱신·신규 {upserts:,} / 삭제 {deletes:,})")
        if pending == 0:
            print("\n✘ 반영할 변경분이 없다. 새 스냅샷을 upsert 한 뒤 다시 돌려라.")
            return 1
    else:
        print(f"\n▶ 변경 만들기 — 개업 {args.new} · 폐업 {args.closed} · 정보변경 {args.renamed}")
        created, closed, renamed = apply_changes(args.new, args.closed, args.renamed)
        print(f"  신규 {[p for p, _ in created]}")
        print(f"  폐업 {[p for p, _ in closed]}")
        print(f"  변경 {[p for p, _ in renamed]}")

    print("\n▶ 키워드 증분 실행")
    started = time.time()
    progress = run_incremental("/admin/reindex/incremental", "키워드")
    keyword_ms = progress["elapsedMs"]
    read = rows_read(progress, "keywordLoad")
    print(f"  완료 — 읽음 {read}건 / {keyword_ms}ms")

    vector_ms = None
    if not args.no_vector:
        print("\n▶ 벡터 증분 실행")
        vprogress = run_incremental("/admin/vector/reindex/incremental", "벡터")
        if vprogress:
            vector_ms = vprogress["elapsedMs"]
            print(f"  완료 — 읽음 {rows_read(vprogress, 'vectorLoad')}건 / {vector_ms}ms")

    after_wm = checkpoint(KEYWORD_CHECKPOINT)

    print("\n▶ 검증")
    report = Report()
    if observe:
        report.check(
            read == pending,
            f"읽은 행 수 = 원천 변경분 ({read} / {pending})",
            "" if read == pending else "전체를 읽었다면 증분이 아니다",
        )
        advanced = bool(after_wm) and after_wm > before_wm
        report.check(advanced, "watermark 전진", f"{before_wm} → {after_wm}")
        report.info("개별 문서 대조는 실제 변경 목록을 알아야 하므로 생략 — 스냅샷 diff 로 따로 확인한다")
    else:
        verify(report, created, closed, renamed, read, before_wm, after_wm)

    if not observe and not args.keep:
        print("\n▶ 원상복구")
        revert_changes(created, closed, renamed)
        run_incremental("/admin/reindex/incremental", "키워드")
        if not args.no_vector:
            run_incremental("/admin/vector/reindex/incremental", "벡터")
        drop_synthetic_rows()
        restored = int(psql_one("select count(*) from public.place where deleted_at is null"))
        print(f"  원천 살아있는 행: {restored:,}  ({'복구됨' if restored == corpus else '⚠ 시작값과 다르다'})")

    print("\n" + "=" * 72)
    total_s = time.time() - started
    print(f"키워드 증분 {keyword_ms}ms" + (f" · 벡터 증분 {vector_ms}ms" if vector_ms else ""))
    print(f"전체 소요 {total_s:.1f}s")
    if report.failed:
        print(f"\n✘ 실패 {len(report.failed)}건")
        for _, label, detail in report.failed:
            print(f"   - {label} {detail}")
        return 1
    print(f"\n✔ {len(report.checks)}개 단언 모두 통과")
    if not observe:
        print("  주의: 이건 만들어낸 변경분이다. 원천이 준 진짜 변경분 검증은 --observe 로 한다.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n중단됨")
        sys.exit(130)
    except Exception as e:
        print(f"\n✘ {e}")
        sys.exit(1)
