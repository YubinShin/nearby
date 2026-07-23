#!/usr/bin/env python3
"""행정동 경계 GeoJSON(전국)에서 서울만 뽑아 adm_dong INSERT 문으로 바꾼다.

원본: https://github.com/vuski/admdongkor  (전국 3,558개, CRS84 = WGS84)

지오 라이브러리를 쓰지 않는다. GeoJSON 의 geometry 를 **그대로 문자열로 넘기고**
좌표 해석은 PostGIS(`ST_GeomFromGeoJSON`)에게 맡긴다 — 파이썬에서 좌표를 만지는 순간
그 변환이 맞는지 검증할 방법이 하나 더 필요해진다.

사용:  python3 scripts/boundaries_to_sql.py > data/adm_dong.sql
"""
import json
import pathlib
import sys

SRC = pathlib.Path("data/raw/HangJeongDong_ver20260701.geojson")
SIDO = "서울특별시"


def sql_str(s: str) -> str:
    return "'" + s.replace("'", "''") + "'"


def main() -> None:
    if not SRC.exists():
        sys.exit(f"원본이 없다: {SRC}")

    features = json.loads(SRC.read_text())["features"]
    rows = [f for f in features if f["properties"].get("sidonm") == SIDO]

    print("BEGIN;")
    for f in rows:
        p = f["properties"]
        dong = p["adm_nm"].split()[-1]
        geometry = json.dumps(f["geometry"], ensure_ascii=False, separators=(",", ":"))
        print(
            "INSERT INTO public.adm_dong (adm_cd, adm_nm, sido, sigungu, dong, geom) VALUES ("
            f"{sql_str(p['adm_cd2'])}, {sql_str(p['adm_nm'])}, {sql_str(p['sidonm'])}, "
            f"{sql_str(p['sggnm'])}, {sql_str(dong)}, "
            # ST_Multi: 원본에 Polygon 과 MultiPolygon 이 섞여 있어 한 타입으로 맞춘다.
            # ST_MakeValid: 자기교차가 있는 경계가 섞여 있으면 ST_Contains 가 조용히 틀린다.
            f"ST_Multi(ST_MakeValid(ST_SetSRID(ST_GeomFromGeoJSON($j${geometry}$j$), 4326)))"
            ");"
        )
    print("COMMIT;")

    print(f"서울 행정동 {len(rows)}개 (전국 {len(features)}개 중)", file=sys.stderr)


if __name__ == "__main__":
    main()
