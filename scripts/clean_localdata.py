#!/usr/bin/env python3
"""지방행정 인허가 데이터(휴게음식점) 원본 CSV를 쓸 수 있는 형태로 정제한다.

원본의 세 가지 특성 때문에 그대로는 못 쓴다.
  1. **CP949 인코딩** — UTF-8로 열면 전부 깨진다.
  2. **폐업이 77% 섞여 있다** — 인허가 데이터는 현재 상태가 아니라 *누적 이력*이다.
     거르지 않으면 없어진 가게를 검색 결과로 내주게 된다.
  3. **값마다 공백 패딩** — `"2026-07-13              "` 처럼 고정폭으로 채워져 온다.

이 스크립트가 하지 **않는** 것 (일부러 남겨둔다):
  - 좌표 변환. 원본은 EPSG:5174(중부원점TM, 베셀)이고 위경도를 주지 않는다.
    변환은 적재 시점에 PostGIS `ST_Transform` 으로 하는 게 정확하고 재현 가능하다.
  - 좌표 없는 행 제거. 1.3%뿐이라 여기서 지우면 "왜 없지"를 나중에 못 쫓는다.
    무엇을 버릴지는 적재 단계가 정한다.

사용:  python3 scripts/clean_localdata.py
출력:  data/gangnam_localdata.csv  (UTF-8, 헤더 포함, 영업 중만)
"""
import collections
import csv
import io
import pathlib
import sys

SRC = pathlib.Path("data/raw/서울시 강남구 휴게음식점 인허가 정보.csv")
DST = pathlib.Path("data/gangnam_localdata.csv")
SRC_ENCODING = "cp949"
LIVE = "영업/정상"

# (출력 컬럼, 원본 컬럼). 원본 컬럼명은 한글이라 그대로 두면 적재 코드가 지저분해진다.
#
# 주의: 행정동이 **없다.** 지번주소에 담긴 건 법정동(`압구정동`)이라, 상가정보의
# 행정동(`역삼1동`)과 바로 못 맞춘다. 두 원천을 합칠 때 걸릴 지점이다.
COLS = [
    ("license_id", "관리번호"),
    ("name", "사업장명"),
    ("business_type", "업태구분명"),
    ("sanitary_type", "위생업태명"),
    ("jibun_address", "지번주소"),
    ("road_address", "도로명주소"),
    ("jibun_postcode", "소재지우편번호"),
    ("road_postcode", "도로명우편번호"),
    ("x_tm", "좌표정보(X)"),        # EPSG:5174 — 위경도 아님
    ("y_tm", "좌표정보(Y)"),
    ("phone", "전화번호"),
    ("area_m2", "소재지면적"),
    ("facility_size", "시설총규모"),
    ("multi_use", "다중이용업소여부"),
    ("licensed_at", "인허가일자"),
    ("status", "영업상태명"),
    ("status_detail", "상세영업상태명"),
    ("male_staff", "남성종사자수"),
    ("female_staff", "여성종사자수"),
    ("surroundings", "영업장주변구분명"),
    ("grade", "등급구분명"),
    ("water_facility", "급수시설구분명"),
    ("gov_code", "개방자치단체코드"),
    ("source_modified_at", "최종수정일자"),
    # 증분 색인의 watermark 후보. 이 데이터는 **매일** 갱신된다.
    ("source_updated_at", "데이터갱신일자"),
]


def main() -> None:
    if not SRC.exists():
        sys.exit(f"원본이 없다: {SRC}\n  서울 열린데이터광장에서 받아 data/raw/ 에 둔다.")

    rows = list(csv.DictReader(io.StringIO(SRC.read_bytes().decode(SRC_ENCODING))))
    total = len(rows)

    status = collections.Counter(r["영업상태명"].strip() for r in rows)
    live = [r for r in rows if r["영업상태명"].strip() == LIVE]

    # 관리번호가 중복된다(실측 23건). 같은 업소의 이력이 여러 줄로 들어온 것으로 보여,
    # **가장 최근에 갱신된 줄만** 남긴다. 아무거나 남기면 실행할 때마다 결과가 달라진다.
    by_id: dict[str, dict[str, str]] = {}
    for r in live:
        key = r["관리번호"].strip()
        prev = by_id.get(key)
        if prev is None or r["데이터갱신일자"].strip() > prev["데이터갱신일자"].strip():
            by_id[key] = r
    deduped = sorted(by_id.values(), key=lambda r: r["관리번호"].strip())

    no_coord = sum(1 for r in deduped if not r["좌표정보(X)"].strip())

    DST.parent.mkdir(parents=True, exist_ok=True)
    with DST.open("w", encoding="utf-8", newline="") as fout:
        writer = csv.writer(fout)
        writer.writerow([out for out, _ in COLS])
        for r in deduped:
            writer.writerow([r[src].strip() for _, src in COLS])

    dropped = [c for c in rows[0] if c not in {src for _, src in COLS}]
    print(
        f"정제 완료: {len(deduped):,}건 → {DST}\n"
        f"  원본        {total:,}건 ({SRC_ENCODING} → utf-8)\n"
        f"  영업상태    {dict(status)}\n"
        f"  폐업 제거   {total - len(live):,}건 ({(total - len(live)) / total:.1%})\n"
        f"  중복 제거   {len(live) - len(deduped):,}건 (관리번호 기준, 최신 갱신분만)\n"
        f"  좌표 없음   {no_coord:,}건 (남겨둠 — 적재 단계에서 판단)\n"
        f"  미사용 컬럼 {len(dropped)}개: {', '.join(dropped)}",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
