-- 브랜드명 복원 결과. data-model.md 참고.
-- 적용:  ./scripts/recover_brands.sh
--
-- 왜 필요한가: 상가정보는 스타벅스 매장을 **브랜드명 없이** 등록해 뒀다.
--   인허가 `스타벅스 신사역점`  ↔  상가정보 `신사역` [카페]  (9m)
-- 그래서 `스타벅스` 로 검색하면 0건이 나온다. 가게가 없어서가 아니라 이름이 없어서다.
--
-- 왜 place 에 컬럼을 붙이지 않는가: 이건 **원천이 준 값이 아니라 우리가 추론한 값**이다.
-- 원천 테이블에 섞으면 "어디까지가 받은 것이고 어디부터가 만든 것인지" 구분이 사라진다.
-- 따로 두면 언제든 통째로 지우고 다시 만들 수 있고, 재색인도 조인 하나만 바뀐다.
--
-- 컬럼 설명은 COMMENT ON 으로 둔다 (이유는 schema.sql 머리말).

CREATE EXTENSION IF NOT EXISTS postgis;

DROP TABLE IF EXISTS public.place_brand;

CREATE TABLE public.place_brand (
    place_id     text PRIMARY KEY,
    brand        text NOT NULL,
    source       text NOT NULL,
    license_id   text,
    matched_name text,
    distance_m   double precision,
    created_at   timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE  public.place_brand              IS '추론한 브랜드명. 원천이 준 값이 아니라 인허가 데이터와 대조해 복원한 값이라 place 와 분리해 둔다.';
COMMENT ON COLUMN public.place_brand.place_id     IS '상가업소번호. public.place 를 가리킨다';
COMMENT ON COLUMN public.place_brand.brand        IS '복원한 브랜드명. 예: 스타벅스';
COMMENT ON COLUMN public.place_brand.source       IS '어느 원천에서 가져왔나. 예: localdata';
COMMENT ON COLUMN public.place_brand.license_id   IS '근거가 된 인허가 행. public.place_localdata 를 가리킨다';
COMMENT ON COLUMN public.place_brand.matched_name IS '그 인허가 행의 사업장명. 사람이 눈으로 검증할 때 쓴다';
COMMENT ON COLUMN public.place_brand.distance_m   IS '두 좌표 사이 거리(m). 클수록 오매칭이 의심스럽다';
COMMENT ON COLUMN public.place_brand.created_at   IS '복원 실행 시각';

CREATE INDEX place_brand_brand_idx ON public.place_brand (brand);