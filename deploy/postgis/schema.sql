-- 원천 창고 스키마 (PostGIS). data-model.md 참고.
-- 적용:  docker exec -i psp-postgis psql -U place -d place < deploy/postgis/schema.sql
--
-- 컬럼 설명은 `--` 가 아니라 COMMENT ON 으로 둔다. `--` 는 이 파일 안에서만 살지만
-- COMMENT ON 은 카탈로그에 들어가 pg_dump 를 타고 seed.sql.gz → k8s 이미지까지 따라간다.
-- k8s 의 DB 는 이 파일이 아니라 그 덤프가 만들기 때문에, `--` 로 적으면 배포된 DB 에는
-- 설명이 하나도 남지 않는다.

CREATE EXTENSION IF NOT EXISTS postgis;

-- postgis/postgis 이미지엔 tiger geocoder 확장이 있고 거기에도 place 테이블이 있어서,
-- 스키마를 public 으로 명시해 이름 충돌을 피한다.
DROP TABLE IF EXISTS public.place;

CREATE TABLE public.place (
    place_id       text PRIMARY KEY,
    name           text NOT NULL,
    branch         text,
    category_large text,
    category_mid   text,
    category_small text,
    sido           text,
    sigungu        text,
    dong           text,
    jibun_address  text,
    road_address   text,
    lon            double precision,
    lat            double precision,
    geom           geometry(Point, 4326)
                     GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(lon, lat), 4326)) STORED,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    -- 실제 삭제 대신 표시만 하므로, 증분 파이프라인이 삭제 이벤트를 놓치지 않는다.
    deleted_at     timestamptz
);

COMMENT ON TABLE  public.place                IS '원천 창고 — 공개 상가정보 스냅샷. 앱은 읽기만 하고 적재는 scripts/load_*.sh 가 한다.';
COMMENT ON COLUMN public.place.place_id       IS '상가업소번호. 멱등 색인의 기준 키 (ADR 0001)';
COMMENT ON COLUMN public.place.name           IS '상호명';
COMMENT ON COLUMN public.place.branch         IS '지점명';
COMMENT ON COLUMN public.place.category_large IS '상권업종대분류명';
COMMENT ON COLUMN public.place.category_mid   IS '상권업종중분류명';
COMMENT ON COLUMN public.place.category_small IS '상권업종소분류명';
COMMENT ON COLUMN public.place.sido           IS '시도명';
COMMENT ON COLUMN public.place.sigungu        IS '시군구명';
COMMENT ON COLUMN public.place.dong           IS '행정동명';
COMMENT ON COLUMN public.place.jibun_address  IS '지번주소';
COMMENT ON COLUMN public.place.road_address   IS '도로명주소';
COMMENT ON COLUMN public.place.lon            IS '경도 (WGS84)';
COMMENT ON COLUMN public.place.lat            IS '위도 (WGS84)';
COMMENT ON COLUMN public.place.geom           IS '경위도에서 자동 생성되는 공간 컬럼. 거리·반경 질의용, SRID 4326 = WGS84';
COMMENT ON COLUMN public.place.updated_at     IS '마지막 변경 시각. 증분 색인의 워터마크 기준 (ADR 0001)';
COMMENT ON COLUMN public.place.deleted_at     IS '소프트 삭제 표시. 값이 있으면 삭제된 행이고, 증분 색인이 이를 보고 ES 문서를 지운다 (ADR 0001)';

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