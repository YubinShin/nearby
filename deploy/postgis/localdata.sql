-- 지방행정 인허가 데이터(휴게음식점) 적재 스키마. data-model.md 참고.
-- 적용:  ./scripts/load_localdata.sh
--
-- 두 번째 원천이다. 첫 원천(상가정보, public.place)이 **대형 직영 프랜차이즈를 통째로
-- 누락**하는 걸 실측으로 확인해서 붙였다 — 강남구 스타벅스가 상가정보엔 0건, 여기엔 100건.
--
-- 아직 place 와 합치지 않고 **따로 둔다.** 통합 스키마(원천 표시·중복 제거)를 정하기 전에
-- 섞으면 어느 행이 어디서 왔는지 잃는다.

CREATE EXTENSION IF NOT EXISTS postgis;

DROP TABLE IF EXISTS public.place_localdata;

CREATE TABLE public.place_localdata (
    license_id         text PRIMARY KEY,    -- 관리번호
    name               text NOT NULL,       -- 사업장명 (상호+지점이 한 덩어리다)
    business_type      text,                -- 업태구분명 (커피숍·편의점·다방…)
    sanitary_type      text,                -- 위생업태명
    jibun_address      text,
    road_address       text,
    jibun_postcode     text,
    road_postcode      text,
    -- 원본 좌표. **EPSG:5174**(중부원점TM) 이고 위경도를 주지 않는다.
    -- 후보 좌표계(5174/5181)를 전수 대조해 확정했다 — 경계 밖 0건·법정동 일치율 90.9%
    -- vs 5181 은 강남구 밖 347건·62.8%. 근거는 data-model.md.
    x_tm               double precision,
    y_tm               double precision,
    phone              text,
    area_m2            text,
    facility_size      text,
    multi_use          text,
    licensed_at        text,
    status             text,                -- '영업/정상'만 적재된다 (폐업 77% 제거)
    status_detail      text,
    male_staff         text,
    female_staff       text,
    surroundings       text,
    grade              text,
    water_facility     text,
    gov_code           text,
    source_modified_at text,
    -- 이 원천은 **매일** 갱신된다. 증분 색인 워터마크 후보.
    source_updated_at  text,

    -- 적재 후 채운다. ST_Transform 은 IMMUTABLE 이 아니라 생성 컬럼으로 못 만든다.
    geom               geometry(Point, 4326),
    -- 원본엔 **행정동이 없다**(지번주소에 법정동만 있다). 좌표를 경계에 떨어뜨려 채운다.
    -- 이름 매칭으로는 못 한다 — 강남구 기준 법정동 14개가 행정동 22개로 갈라진다.
    dong               text
);

CREATE INDEX place_localdata_geom_gix ON public.place_localdata USING GIST (geom);
CREATE INDEX place_localdata_dong_idx ON public.place_localdata (dong);
