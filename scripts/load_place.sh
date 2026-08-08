#!/usr/bin/env bash
# 강남구 상가정보를 PostGIS 원천 테이블(place)에 적재하는 재현 스크립트.
#
# 전제: 스택이 떠 있어야 함 (deploy/up.sh) + 원본 CSV가 data/raw 에 있어야 함
#       (data-model.md 의 출처에서 서울 CSV 다운로드).
# 사용:  ./scripts/load_place.sh
#        DB=place_gangnam ./scripts/load_place.sh   # 다른 DB 에 적재
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DB="${DB:-place}"

echo "▶ 1/4  강남구 추출 (서울 CSV → data/gangnam_place.csv)"
python3 scripts/extract_gangnam.py

echo "▶ 2/4  스키마 적용 (public.place)"
docker exec -i psp-postgis psql -U place -d "$DB" -v ON_ERROR_STOP=1 < deploy/postgis/schema.sql

echo "▶ 3/4  파생 테이블 초기화 (place_brand · place_duplicate)"
docker exec -i psp-postgis psql -U place -d "$DB" -v ON_ERROR_STOP=1 -q < deploy/postgis/brand.sql
docker exec -i psp-postgis psql -U place -d "$DB" -v ON_ERROR_STOP=1 -q < deploy/postgis/dedup.sql

echo "▶ 4/4  적재 (COPY)"
cat data/gangnam_place.csv | docker exec -i psp-postgis psql -U place -d "$DB" -v ON_ERROR_STOP=1 \
  -c "\copy public.place(place_id,name,branch,category_large,category_mid,category_small,sido,sigungu,dong,jibun_address,road_address,lon,lat) FROM STDIN WITH (FORMAT csv, HEADER true)"

echo -n "✔ 적재 건수: "
docker exec -i psp-postgis psql -U place -d "$DB" -v ON_ERROR_STOP=1 -tAc "SELECT count(*) FROM public.place;"
echo "  브랜드·중복 판정은 비어 있습니다. 필요하면 이어서 실행하세요:"
echo "    ./scripts/recover_brands.sh   # 브랜드 복원"
echo "    ./scripts/build_dedup.sh      # 중복 판정"
