#!/usr/bin/env bash
# 인허가 데이터(휴게음식점)를 PostGIS(place_localdata)에 적재하는 재현 스크립트.
#
# 전제: 스택 기동(deploy/up.sh) + 경계 적재(scripts/load_boundaries.sh)
#       + 원본 CSV 가 data/raw 에 있어야 함 (data-model.md 의 출처).
# 사용:  ./scripts/load_localdata.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PSQL=(docker exec -i psp-postgis psql -U place -d place)

echo "▶ 1/5  정제 (cp949→utf-8, 폐업 제거, 관리번호 중복 제거)"
python3 scripts/clean_localdata.py

echo "▶ 2/5  스키마 적용 (public.place_localdata)"
"${PSQL[@]}" -q < deploy/postgis/localdata.sql

echo "▶ 3/5  적재 (COPY)"
cat data/gangnam_localdata.csv | "${PSQL[@]}" -q -c "\copy public.place_localdata(
  license_id,name,business_type,sanitary_type,jibun_address,road_address,
  jibun_postcode,road_postcode,x_tm,y_tm,phone,area_m2,facility_size,multi_use,
  licensed_at,status,status_detail,male_staff,female_staff,surroundings,grade,
  water_facility,gov_code,source_modified_at,source_updated_at)
  FROM STDIN WITH (FORMAT csv, HEADER true, NULL '')"

echo "▶ 4/5  좌표 변환 (EPSG:5174 → WGS84)"
"${PSQL[@]}" -q -c "
  UPDATE public.place_localdata
     SET geom = ST_Transform(ST_SetSRID(ST_MakePoint(x_tm, y_tm), 5174), 4326)
   WHERE x_tm IS NOT NULL AND y_tm IS NOT NULL;"

echo "▶ 5/5  행정동 부여 (공간 조인)"
"${PSQL[@]}" -q -c "
  UPDATE public.place_localdata p
     SET dong = a.dong
    FROM public.adm_dong a
   WHERE p.geom IS NOT NULL AND ST_Contains(a.geom, p.geom);"

echo "── 결과 ──"
"${PSQL[@]}" -tAc "
  SELECT '전체        ' || count(*) FROM public.place_localdata
  UNION ALL SELECT '좌표 없음   ' || count(*) FROM public.place_localdata WHERE geom IS NULL
  UNION ALL SELECT '행정동 미부여 ' || count(*) FROM public.place_localdata WHERE dong IS NULL
  UNION ALL SELECT '스타벅스    ' || count(*) FROM public.place_localdata WHERE name LIKE '%스타벅스%';"
