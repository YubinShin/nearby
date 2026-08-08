#!/usr/bin/env bash
# 로컬에 적재된 원천 데이터를 **시드 덤프**로 뽑는다.
#
#   전제:  로컬 스택 기동 + 적재 완료 (deploy/up.sh, scripts/load_*.sh)
#   실행:  DB=place_gangnam ./deploy/postgis/make-seed.sh
#   결과:  deploy/postgis/seed.sql.gz  (강남 기준 약 3.5MB, git 에는 안 올라감)
#
# ── 어느 DB 를 떠야 하나 ────────────────────────────────────────────────────
# **k8s 시드는 강남 기준이어야 한다.** 기록된 색인·질의 실측이 전부 강남 6만 건
# 기준(ADR 0010)이라, 서울 전체(53만 건)로 뜨면 그 수치가 재현되지 않는다.
# 서울 데이터는 로컬 실험용이므로 `place` 에 두고, 시드는 `place_gangnam` 에서 뜬다.
# 기본값이 `place` 인 것은 기존 사용법을 안 깨려는 것뿐이다 — 완료 메시지에 찍히는
# DB 이름을 반드시 확인할 것.
#
# ── 왜 덤프를 git 에 안 넣나 ────────────────────────────────────────────────
# 이 저장소는 원천 데이터를 git 에 두지 않는다(1.4GB CSV). 덤프는 3.6MB 라 넣을 수도
# 있지만, 그러면 **원천이 두 곳**이 된다 — CSV 를 다시 받아 적재한 사람과 덤프로
# 복원한 사람의 데이터가 언제 갈라졌는지 아무도 모르게 된다. 덤프는 로컬에서 만드는
# **산출물**로 둔다. KOMORAN 사전을 원천에서 생성하는 것과 같은 원칙이다 (ADR 0008).
#
# ── 무엇을 빼는지, 왜 그렇게 정했는지 (두 번 틀리고 얻은 답) ──────────────────
# ① DB 전체를 덤프 → 복원이 `schema "tiger" already exists` 로 죽었다.
#    PostGIS 확장이 tiger·topology 스키마를 스스로 만드는데 덤프가 또 만들려 든다.
# ② 그래서 `--schema=public` 로 좁혔더니 이번엔 `schema "public" already exists`.
#    스키마를 지정하면 pg_dump 가 그 스키마의 CREATE 문까지 뽑기 때문이다.
# ③ 답은 **"우리 것만 고르기"가 아니라 "확장이 관리하는 것만 빼기"** 였다.
#    public 은 원래 있는 스키마라 아무도 만들려 하지 않는다.
#
# spatial_ref_sys(7MB)도 뺀다 — public 에 있지만 좌표계 정의는 확장이 채우는 것이라
# 덤프의 행과 부딪힌다.
#
# batch_* 도 뺀다 — Spring Batch 메타데이터는 원천이 아니라 **이 노트북의 실행 이력**이다.
# 구워 넣으면 새로 뜨는 배포마다 남의 잡 이력을 물려받는다. 이 테이블들은 앱이 직접
# 만든다 (ADR 0013).
#
# 확장 자체는 pg_dump 가 `CREATE EXTENSION IF NOT EXISTS` 로 뽑아주므로 충돌하지 않는다.
set -euo pipefail

cd "$(dirname "$0")/../.."
CONTAINER="${POSTGIS_CONTAINER:-psp-postgis}"
DB="${DB:-place}"
OUT="deploy/postgis/seed.sql.gz"

docker exec "$CONTAINER" pg_isready -U place -d "$DB" >/dev/null 2>&1 || {
  echo "PostGIS 컨테이너($CONTAINER)가 없거나 준비되지 않았습니다. deploy/up.sh 로 먼저 띄우세요." >&2
  exit 1
}

rows=$(docker exec "$CONTAINER" psql -U place -d "$DB" -v ON_ERROR_STOP=1 -tAc "select count(*) from public.place")
[ "$rows" -gt 0 ] || { echo "public.place 가 비어 있습니다. scripts/load_place.sh 를 먼저 실행하세요." >&2; exit 1; }

docker exec "$CONTAINER" pg_dump -U place -d "$DB" \
  --no-owner --no-privileges \
  --exclude-schema=tiger --exclude-schema=tiger_data --exclude-schema=topology \
  --exclude-table=spatial_ref_sys \
  --exclude-table='batch_*' \
  | gzip -9 > "$OUT"

echo "생성 완료: $OUT ($(du -h "$OUT" | cut -f1), DB=$DB, place $rows 행)"
