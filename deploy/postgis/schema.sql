-- 원천 창고 스키마 (PostGIS). data-model.md 참고.
-- 적용:  docker exec -i psp-postgis psql -U place -d place < deploy/postgis/schema.sql

CREATE EXTENSION IF NOT EXISTS postgis;

-- postgis/postgis 이미지엔 tiger geocoder 확장이 있고 거기에도 place 테이블이 있어서,
-- 스키마를 public 으로 명시해 이름 충돌을 피한다.
DROP TABLE IF EXISTS public.place;

CREATE TABLE public.place (
    place_id       text PRIMARY KEY,          -- 상가업소번호 (멱등 색인 기준 — ADR 0001)
    name           text NOT NULL,             -- 상호명
    branch         text,                      -- 지점명
    category_large text,                      -- 상권업종대분류명
    category_mid   text,                      -- 상권업종중분류명
    category_small text,                      -- 상권업종소분류명
    sido           text,
    sigungu        text,
    dong           text,                      -- 행정동명
    jibun_address  text,
    road_address   text,
    lon            double precision,
    lat            double precision,
    -- 경위도로부터 자동 생성되는 공간 컬럼 (거리·반경 질의용). SRID 4326 = WGS84.
    geom           geometry(Point, 4326)
                     GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(lon, lat), 4326)) STORED,
    updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX place_geom_gix       ON place USING GIST (geom);
CREATE INDEX place_updated_at_idx ON place (updated_at);
