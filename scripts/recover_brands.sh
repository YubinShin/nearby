#!/usr/bin/env bash
# 상가정보 행의 **사라진 브랜드명**을 인허가 데이터에서 복원한다.
#
# 규칙:  인허가 사업장명 = [브랜드] + 상가정보 상호명 + ("점")
#        예)  스타벅스 신사역점  =  '스타벅스' + '신사역' + '점'
#
# 전제: ./scripts/load_localdata.sh 까지 끝나 있어야 함.
# 사용:  ./scripts/recover_brands.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PSQL=(docker exec -i psp-postgis psql -U place -d place)

# 같은 브랜드가 최소 몇 번 나와야 인정할지. 진짜 브랜드는 여러 매장에서 반복되고,
# 우연히 앞말이 붙은 잡음은 대체로 한 번만 나온다 — 빈도 자체가 신뢰도 신호다.
MIN_OCCURRENCE=5
# 같은 가게로 볼 최대 거리(m). 강남은 건물이 붙어 있어 넉넉히 잡으면 옆 가게를 물어온다.
MAX_DISTANCE_DEG=0.00035   # 약 30m

echo "▶ 1/2  스키마 적용 (public.place_brand)"
"${PSQL[@]}" -q < deploy/postgis/brand.sql

echo "▶ 2/2  복원"
"${PSQL[@]}" -q <<SQL
WITH l AS (
  SELECT license_id, name, replace(name, ' ', '') AS n, geom
  FROM public.place_localdata WHERE geom IS NOT NULL
),
p AS (
  SELECT place_id, replace(name || coalesce(branch, ''), ' ', '') AS n, geom
  FROM public.place WHERE deleted_at IS NULL
),
-- 인허가 이름에서 상가정보 이름을 '끝에서' 떼어내고 남는 앞말이 브랜드 후보다.
cand AS (
  SELECT p.place_id, l.license_id, l.name AS matched_name,
         ST_Distance(p.geom::geography, l.geom::geography) AS distance_m,
         left(rtrim(l.n, '점'), length(rtrim(l.n, '점')) - length(p.n)) AS brand
  FROM p JOIN l ON ST_DWithin(p.geom, l.geom, $MAX_DISTANCE_DEG)
  WHERE length(p.n) >= 2
    AND rtrim(l.n, '점') <> p.n
    AND rtrim(l.n, '점') LIKE '%' || p.n
    AND length(rtrim(l.n, '점')) > length(p.n)
),
-- 법인격 표기는 브랜드가 아니다. '(주)한식당' 의 '(주)' 를 브랜드로 삼으면 안 된다.
clean AS (
  SELECT * FROM cand
  WHERE length(brand) >= 2
    AND brand !~ '^(\\(주\\)|주식회사|㈜|유한회사|\\(유\\))\$'
),
-- 빈도가 낮은 후보는 버린다 (위 MIN_OCCURRENCE 주석 참고).
frequent AS (
  SELECT brand FROM clean GROUP BY brand HAVING count(*) >= $MIN_OCCURRENCE
),
-- '스타벅스커피' 처럼 같은 브랜드의 긴 변형은 **짧고 빈번한 쪽으로 모은다.**
-- 안 그러면 색인에 '스타벅스'와 '스타벅스커피'가 따로 들어가 한쪽만 검색된다.
canon AS (
  SELECT c.brand AS raw,
         coalesce((SELECT f.brand FROM frequent f
                    WHERE c.brand LIKE f.brand || '%'
                    ORDER BY length(f.brand) LIMIT 1), c.brand) AS brand
  FROM (SELECT DISTINCT brand FROM clean) c
),
-- 한 가게에 후보가 여럿이면 **가장 가까운** 짝을 쓴다.
picked AS (
  SELECT DISTINCT ON (place_id)
         clean.place_id, canon.brand, clean.license_id, clean.matched_name, clean.distance_m
  FROM clean JOIN canon ON canon.raw = clean.brand
  WHERE canon.brand IN (SELECT brand FROM frequent)
     OR clean.brand IN (SELECT brand FROM frequent)
  ORDER BY place_id, distance_m
)
INSERT INTO public.place_brand (place_id, brand, source, license_id, matched_name, distance_m)
SELECT place_id, brand, 'localdata', license_id, matched_name, distance_m FROM picked;
SQL

echo "── 결과 ──"
"${PSQL[@]}" -c "
  SELECT brand AS 브랜드, count(*) AS 복원건수,
         round(min(distance_m)) AS 최소거리m, round(max(distance_m)) AS 최대거리m
  FROM public.place_brand GROUP BY brand ORDER BY 2 DESC;"
