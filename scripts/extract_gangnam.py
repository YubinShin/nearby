#!/usr/bin/env python3
"""서울 상가정보 CSV에서 강남구만 뽑아 place 테이블용 CSV로 정제한다.

사용:  python3 scripts/extract_gangnam.py
출력:  data/gangnam_place.csv  (place 테이블 컬럼 순서, 헤더 포함)
"""
import csv
import sys

SRC = "data/raw/소상공인시장진흥공단_상가(상권)정보_20260331/소상공인시장진흥공단_상가(상권)정보_서울_202603.csv"
DST = "data/gangnam_place.csv"
SIGUNGU = "강남구"

# (place 컬럼, CSV 원본 컬럼)
COLS = [
    ("place_id", "상가업소번호"),
    ("name", "상호명"),
    ("branch", "지점명"),
    ("category_large", "상권업종대분류명"),
    ("category_mid", "상권업종중분류명"),
    ("category_small", "상권업종소분류명"),
    ("sido", "시도명"),
    ("sigungu", "시군구명"),
    ("dong", "행정동명"),
    ("jibun_address", "지번주소"),
    ("road_address", "도로명주소"),
    ("lon", "경도"),
    ("lat", "위도"),
]

def main():
    kept = skipped = 0
    with open(SRC, encoding="utf-8") as fin, open(DST, "w", encoding="utf-8", newline="") as fout:
        reader = csv.DictReader(fin)
        writer = csv.writer(fout)
        writer.writerow([c for c, _ in COLS])
        for row in reader:
            if row["시군구명"] != SIGUNGU:
                continue
            if not row["경도"] or not row["위도"]:   # 좌표 없으면 제외 (geom 생성 불가)
                skipped += 1
                continue
            writer.writerow([row[src] for _, src in COLS])
            kept += 1
    print(f"추출 완료: {kept}건 → {DST} (좌표 없어 제외: {skipped})", file=sys.stderr)

if __name__ == "__main__":
    main()
