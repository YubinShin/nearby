#!/usr/bin/env bash
# 같은 업소가 서로 다른 `place_id` 로 두 번 들어온 것을 찾아 **색인에서 뺄 쪽**을 정한다.
#
# 전제: ./scripts/load_place.sh 와 ./scripts/recover_brands.sh 까지 끝나 있어야 함.
#       (브랜드 복원 결과를 생존자 선정에 쓴다 — 아래 우선순위 참고)
# 사용:  ./scripts/build_dedup.sh
#        DB=place_gangnam ./scripts/build_dedup.sh   # 다른 DB 에서 실행
#
# 실행 후에는 **전체 재색인이 필요하다.** 이 스크립트는 place.updated_at 을 건드리지
# 않으므로, 이미 색인된 중복 문서를 증분 색인은 집어내지 못한다.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DB="${DB:-place}"
PSQL=(docker exec -i psp-postgis psql -U place -d "$DB" -v ON_ERROR_STOP=1)

echo "▶ 1/2  스키마 적용 (public.place_duplicate)"
"${PSQL[@]}" -q < deploy/postgis/dedup.sql

echo "▶ 2/2  판정"
"${PSQL[@]}" -q <<'SQL'
WITH norm AS (
  SELECT p.place_id, p.lat, p.lon, p.category_small,
         replace(p.name || coalesce(p.branch, ''), ' ', '') AS key,
         (b.place_id IS NOT NULL)                        AS has_brand,
         (p.branch IS NOT NULL AND p.branch <> '')       AS has_branch
  FROM public.place p
  LEFT JOIN public.place_brand b USING (place_id)
  WHERE p.deleted_at IS NULL
    AND p.lat IS NOT NULL AND p.lon IS NOT NULL
),
-- 생존자 선정 우선순위:
--   ① 복원된 브랜드가 붙은 행  — 지우면 브랜드 복원 결과까지 함께 사라진다
--   ② 지점명이 분리된 행       — searchDoc 의 branch 필드가 채워진다
--   ③ place_id 사전순          — 앞의 둘이 동률일 때 **결정론**을 보장한다.
--                                재실행마다 생존자가 바뀌면 색인이 흔들린다.
grp AS (
  SELECT norm.*,
         count(*) OVER w AS n,
         first_value(place_id) OVER (
           PARTITION BY lat, lon, key, category_small
           ORDER BY has_brand DESC, has_branch DESC, place_id
         ) AS survivor
  FROM norm
  WINDOW w AS (PARTITION BY lat, lon, key, category_small)
)
INSERT INTO public.place_duplicate (place_id, survivor_id, dup_key, rule)
SELECT place_id, survivor,
       key || '|' || coalesce(category_small, ''),
       'coord+name+category'
FROM grp
WHERE n > 1 AND place_id <> survivor;
SQL

echo "── 결과 ──"
"${PSQL[@]}" -c "
  SELECT count(*) AS 제거대상행, count(DISTINCT survivor_id) AS 생존그룹 FROM public.place_duplicate;"
"${PSQL[@]}" -c "
  SELECT d.place_id AS 제거, p.name AS 이름, coalesce(p.branch,'') AS 지점,
         d.survivor_id AS 생존, s.name AS 생존이름, coalesce(s.branch,'') AS 생존지점
  FROM public.place_duplicate d
  JOIN public.place p ON p.place_id = d.place_id
  JOIN public.place s ON s.place_id = d.survivor_id
  ORDER BY d.dup_key LIMIT 10;"

echo
echo "  전체 재색인이 필요합니다 (증분으로는 기존 중복 문서가 남습니다):"
echo "    curl -XPOST localhost:8081/admin/reindex"
echo "    curl -XPOST localhost:8081/admin/vector/reindex"
