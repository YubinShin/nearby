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

CREATE EXTENSION IF NOT EXISTS postgis;

DROP TABLE IF EXISTS public.place_brand;

CREATE TABLE public.place_brand (
    place_id     text PRIMARY KEY,
    brand        text NOT NULL,          -- 복원한 브랜드명 ('스타벅스')
    source       text NOT NULL,          -- 어디서 가져왔나 ('localdata')
    license_id   text,                   -- 근거가 된 인허가 행
    matched_name text,                   -- 그 행의 사업장명 (사람이 눈으로 검증할 때 쓴다)
    distance_m   double precision,       -- 두 좌표 사이 거리. 클수록 의심스럽다
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX place_brand_brand_idx ON public.place_brand (brand);
