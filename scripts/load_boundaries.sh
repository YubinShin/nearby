#!/usr/bin/env bash
# 행정동 경계를 PostGIS(adm_dong)에 적재하는 재현 스크립트.
#
# 전제: 스택이 떠 있어야 함 (deploy/up.sh) + 경계 GeoJSON 이 data/raw 에 있어야 함.
#       원본: https://github.com/vuski/admdongkor  (data-model.md 참고)
# 사용:  ./scripts/load_boundaries.sh
#        DB=place_gangnam ./scripts/load_boundaries.sh   # 다른 DB 에 적재
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DB="${DB:-place}"

echo "▶ 1/3  GeoJSON → SQL (서울만)"
trap 'rm -f data/adm_dong.sql.tmp' EXIT
python3 scripts/boundaries_to_sql.py > data/adm_dong.sql.tmp
mv data/adm_dong.sql.tmp data/adm_dong.sql

echo "▶ 2/3  스키마 적용 (public.adm_dong)"
docker exec -i psp-postgis psql -U place -d "$DB" -v ON_ERROR_STOP=1 -q < deploy/postgis/boundaries.sql

echo "▶ 3/3  적재"
docker exec -i psp-postgis psql -U place -d "$DB" -v ON_ERROR_STOP=1 -q < data/adm_dong.sql

echo -n "✔ 적재 건수: "
docker exec -i psp-postgis psql -U place -d "$DB" -v ON_ERROR_STOP=1 -tAc "SELECT count(*) FROM public.adm_dong;"
echo -n "  강남구:    "
docker exec -i psp-postgis psql -U place -d "$DB" -v ON_ERROR_STOP=1 -tAc \
  "SELECT count(*) FROM public.adm_dong WHERE sigungu = '강남구';"
echo -n "  잘못된 경계: "
docker exec -i psp-postgis psql -U place -d "$DB" -v ON_ERROR_STOP=1 -tAc \
  "SELECT count(*) FROM public.adm_dong WHERE NOT ST_IsValid(geom);"
