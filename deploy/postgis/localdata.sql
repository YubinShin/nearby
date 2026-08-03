-- 지방행정 인허가 데이터(휴게음식점) 적재 스키마. data-model.md 참고.
-- 적용:  ./scripts/load_localdata.sh
--
-- 두 번째 원천이다. 첫 원천(상가정보, public.place)이 **대형 직영 프랜차이즈를 통째로
-- 누락**하는 걸 실측으로 확인해서 붙였다 — 강남구 스타벅스가 상가정보엔 0건, 여기엔 100건.
--
-- 아직 place 와 합치지 않고 **따로 둔다.** 통합 스키마(원천 표시·중복 제거)를 정하기 전에
-- 섞으면 어느 행이 어디서 왔는지 잃는다.
--
-- 컬럼 설명은 COMMENT ON 으로 둔다 (이유는 schema.sql 머리말).

CREATE EXTENSION IF NOT EXISTS postgis;

DROP TABLE IF EXISTS public.place_localdata;

CREATE TABLE public.place_localdata (
    license_id         text PRIMARY KEY,
    name               text NOT NULL,
    business_type      text,
    sanitary_type      text,
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
    status             text,
    status_detail      text,
    male_staff         text,
    female_staff       text,
    surroundings       text,
    grade              text,
    water_facility     text,
    gov_code           text,
    source_modified_at text,
    source_updated_at  text,

    -- 적재 후 채운다. ST_Transform 은 IMMUTABLE 이 아니라 생성 컬럼으로 못 만든다.
    geom               geometry(Point, 4326),
    -- 원본엔 **행정동이 없다**(지번주소에 법정동만 있다). 좌표를 경계에 떨어뜨려 채운다.
    -- 이름 매칭으로는 못 한다 — 강남구 기준 법정동 14개가 행정동 22개로 갈라진다.
    dong               text
);

COMMENT ON TABLE  public.place_localdata                    IS '지방행정 인허가(휴게음식점) 원천. place 가 누락한 대형 프랜차이즈를 메우려고 붙인 두 번째 원천이며, 아직 place 와 합치지 않았다.';
COMMENT ON COLUMN public.place_localdata.license_id         IS '관리번호. 이 원천의 기본키';
COMMENT ON COLUMN public.place_localdata.name               IS '사업장명. 상호와 지점이 한 덩어리로 들어 있다 (예: 스타벅스 신사역점)';
COMMENT ON COLUMN public.place_localdata.business_type      IS '업태구분명. 커피숍·편의점·다방 등';
COMMENT ON COLUMN public.place_localdata.sanitary_type      IS '위생업태명';
COMMENT ON COLUMN public.place_localdata.jibun_address      IS '지번주소. 법정동만 들어 있어 행정동은 좌표로 따로 채운다';
COMMENT ON COLUMN public.place_localdata.road_address       IS '도로명주소';
COMMENT ON COLUMN public.place_localdata.jibun_postcode     IS '지번 우편번호';
COMMENT ON COLUMN public.place_localdata.road_postcode      IS '도로명 우편번호';
COMMENT ON COLUMN public.place_localdata.x_tm               IS '원본 X 좌표. EPSG:5174 중부원점TM — 위경도가 아니라 geom 을 따로 만든다';
COMMENT ON COLUMN public.place_localdata.y_tm               IS '원본 Y 좌표. EPSG:5174 중부원점TM';
COMMENT ON COLUMN public.place_localdata.phone              IS '소재지 전화번호';
COMMENT ON COLUMN public.place_localdata.area_m2            IS '소재지 면적. 원본이 문자열이라 그대로 받는다';
COMMENT ON COLUMN public.place_localdata.facility_size      IS '시설총규모';
COMMENT ON COLUMN public.place_localdata.multi_use          IS '다중이용업소 여부';
COMMENT ON COLUMN public.place_localdata.licensed_at        IS '인허가일자';
COMMENT ON COLUMN public.place_localdata.status             IS '영업상태명. 영업/정상만 적재한다 (폐업 77% 제거)';
COMMENT ON COLUMN public.place_localdata.status_detail      IS '상세영업상태명';
COMMENT ON COLUMN public.place_localdata.male_staff         IS '남성종사자수';
COMMENT ON COLUMN public.place_localdata.female_staff       IS '여성종사자수';
COMMENT ON COLUMN public.place_localdata.surroundings       IS '영업장주변구분명';
COMMENT ON COLUMN public.place_localdata.grade              IS '등급구분명';
COMMENT ON COLUMN public.place_localdata.water_facility     IS '급수시설구분명';
COMMENT ON COLUMN public.place_localdata.gov_code           IS '개방자치단체코드';
COMMENT ON COLUMN public.place_localdata.source_modified_at IS '원천의 데이터갱신일자';
COMMENT ON COLUMN public.place_localdata.source_updated_at  IS '원천의 최종수정시점. 매일 갱신되므로 증분 색인 워터마크 후보';
COMMENT ON COLUMN public.place_localdata.geom               IS 'x_tm·y_tm 을 4326 으로 변환해 적재 후 채운 좌표. ST_Transform 이 IMMUTABLE 이 아니라 생성 컬럼으로 못 만든다';
COMMENT ON COLUMN public.place_localdata.dong               IS '행정동명. 원본에 없어서 좌표를 adm_dong 경계에 떨어뜨려 채운다';

CREATE INDEX place_localdata_geom_gix ON public.place_localdata USING GIST (geom);
CREATE INDEX place_localdata_dong_idx ON public.place_localdata (dong);