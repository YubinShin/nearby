-- 중복 판정 결과. data-model.md 참고.
-- 적용:  ./scripts/build_dedup.sh
--
-- 왜 필요한가: 같은 업소가 서로 다른 `place_id` 로 두 번 들어와 있다.
--   MA0106202506A1843320  name='커피인류 역삼점'  branch=null
--   MA0101202504A0077166  name='커피인류'         branch='역삼점'
-- 둘 다 색인되면 검색 상위 열 칸 중 두 칸을 같은 가게가 차지한다.
--
-- 왜 place 에 컬럼을 붙이지 않는가: place_brand 와 같은 이유다. 이건 원천이 준 값이
-- 아니라 우리가 추론한 값이다. 따로 두면 통째로 지우고 다시 만들 수 있고, 어떤 행이
-- 왜 검색에서 빠졌는지 나중에 되짚을 수 있다.
--
-- 왜 좌표만으로 판정하지 않는가: 상가정보 좌표는 건물 기준이라 같은 건물의 다른 업소가
-- 전부 같은 좌표를 갖는다. 좌표만 같은 그룹은 8,776개(잉여 51,687행)나 되지만 대부분
-- 중복이 아니다 — 한 건물에 든 약국·안과·치과가 그렇다.
--
-- 컬럼 설명은 COMMENT ON 으로 둔다 (이유는 schema.sql 머리말).

CREATE EXTENSION IF NOT EXISTS postgis;

DROP TABLE IF EXISTS public.place_duplicate;

CREATE TABLE public.place_duplicate (
    place_id    text PRIMARY KEY,
    survivor_id text NOT NULL,
    dup_key     text NOT NULL,
    rule        text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE  public.place_duplicate             IS '중복 판정 결과. 여기 있는 place_id 는 전체 색인에서 빠진다 (PlaceSource 의 조인 조건).';
COMMENT ON COLUMN public.place_duplicate.place_id    IS '색인에서 뺄 쪽. public.place 를 가리킨다';
COMMENT ON COLUMN public.place_duplicate.survivor_id IS '대신 남길 쪽. 같은 가게를 대표하는 place_id';
COMMENT ON COLUMN public.place_duplicate.dup_key     IS '같은 가게로 묶은 근거. 사람이 눈으로 검증할 때 쓴다';
COMMENT ON COLUMN public.place_duplicate.rule        IS '어떤 규칙이 묶었나. 규칙별로 오판을 되짚을 수 있다';
COMMENT ON COLUMN public.place_duplicate.created_at  IS '판정 실행 시각';

CREATE INDEX place_duplicate_survivor_idx ON public.place_duplicate (survivor_id);