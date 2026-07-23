-- 행정동 경계 (공간 조인용). data-model.md 참고.
-- 적용:  ./scripts/load_boundaries.sh
--
-- 왜 필요한가: 원천마다 '동'의 종류가 다르다.
--   - 상가정보  → **행정동** (역삼1동, 역삼2동)
--   - 인허가    → **법정동** (역삼동)  ※ 지번주소에 법정동만 들어 있다
-- 이름만 보고는 역삼동이 역삼1동인지 2동인지 가릴 수 없다(강남구 기준 14 → 22로 갈라진다).
-- 가릴 수 있는 정보는 **위치**뿐이라, 좌표를 경계에 떨어뜨려(ST_Contains) 판정한다.

CREATE EXTENSION IF NOT EXISTS postgis;

DROP TABLE IF EXISTS public.adm_dong;

CREATE TABLE public.adm_dong (
    adm_cd  text PRIMARY KEY,                   -- 행정동코드 (adm_cd2, 10자리)
    adm_nm  text NOT NULL,                      -- '서울특별시 강남구 역삼1동'
    sido    text NOT NULL,
    sigungu text NOT NULL,
    dong    text NOT NULL,                      -- '역삼1동' — place.dong 과 맞춰 쓰는 값
    -- 원본이 CRS84(=WGS84)라 변환 없이 4326 으로 들어온다. place.geom 과 같은 좌표계여야
    -- 공간 조인에서 ST_Transform 이 매 행마다 붙지 않는다.
    geom    geometry(MultiPolygon, 4326) NOT NULL
);

CREATE INDEX adm_dong_geom_gix ON public.adm_dong USING GIST (geom);
CREATE INDEX adm_dong_dong_idx ON public.adm_dong (sigungu, dong);
