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
    updated_at     timestamptz NOT NULL DEFAULT now(),
    -- 소프트 삭제: 값이 있으면 '삭제됨'. 증분 색인이 이를 보고 ES 문서를 지운다 (ADR 0001).
    -- 실제 삭제 대신 표시만 하므로, 증분 파이프라인이 삭제 이벤트를 놓치지 않는다.
    deleted_at     timestamptz
);

CREATE INDEX place_geom_gix       ON place USING GIST (geom);
CREATE INDEX place_updated_at_idx ON place (updated_at);

-- 삭제도 '변경'이므로 updated_at 을 함께 올린다 → 증분 색인이 updated_at 워터마크로 잡아낸다.
CREATE OR REPLACE FUNCTION public.place_touch_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS place_touch_updated_at ON public.place;
CREATE TRIGGER place_touch_updated_at
    BEFORE UPDATE ON public.place
    FOR EACH ROW EXECUTE FUNCTION public.place_touch_updated_at();
