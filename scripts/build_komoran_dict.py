#!/usr/bin/env python3
"""원천 데이터(PostGIS)에서 KOMORAN 사용자 사전을 만든다.

핵심 아이디어: 사전에 넣을 대상은 "자주 나오는 명사"가 **아니라**
"형태소 분석기가 **틀리게** 아는 문자열"이다.

  - 모르는 단어(NA)는 통짜 토큰으로 나와서 오히려 안전하다.
  - 위험한 건 오분석이다.  논현2동 -> 놓/VX + ㄴ/ETM + 현/NNP  ('논'이 증발)
    여기에 POS 필터가 얹히면 글자가 아예 사라져 **검색 누락**이 된다.

판정은 실행 중인 Elasticsearch 의 `komoran_tokenizer` 를 신탁(oracle)으로 삼는다.
토큰의 offset 을 보면 어느 구간이 잘게 부서졌는지 기계적으로 알 수 있다.

  전제:  스택 기동(deploy/up.sh) + 원천 적재(scripts/load_place.sh)
  사용:  python3 scripts/build_komoran_dict.py [--limit N] [--min-df N]
  출력:  deploy/elasticsearch/analysis/komoran/place.dict   (단어\\tPOS)
"""
import argparse
import json
import re
import subprocess
import sys
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

ES = "http://localhost:9200"
PROBE_INDEX = "komoran_probe"
OUT = "deploy/elasticsearch/analysis/komoran/place.dict"

# POS 필터 없이 **토크나이저 원본 출력**만 보는 분석기. 사전 후보를 찾을 땐 필터가 지운 뒤가 아니라
# 지우기 전을 봐야 한다 — 무엇이 왜 사라졌는지는 원본 토큰의 품사에 있기 때문.
#
# 왜 굳이 인덱스를 만드나: `_analyze` 에 tokenizer 를 즉석 지정하면 호출마다 KOMORAN 모델(수 MB)을
# 새로 로딩한다(실측 7 req/s). 인덱스 설정으로 두면 팩토리가 인덱스당 한 번만 만들어져 캐시된다
# (실측 3,133 req/s — 450배). 사전 생성은 6만 건을 분석하므로 이 차이가 곧 실행 가능/불가능이다.
PROBE_SETTINGS = {
    "settings": {
        "number_of_shards": 1,
        "number_of_replicas": 0,
        "analysis": {
            "analyzer": {
                "komoran_raw": {"type": "custom", "tokenizer": "komoran_tokenizer"},
            },
        },
    },
}

HANGUL = re.compile(r"^[가-힣]+$")
# 원천 데이터에서 법인격 표기가 잘려 상호명 끝에 '주'만 남은 경우가 있다
# (더블유에프텍주 <- 더블유에프텍 주식회사). 사전 후보를 오염시키므로 떼어낸다.
CORP_SUFFIX = re.compile(r"(주식회사|\(주\)|㈜|주)$")


# ---------- 원천 읽기 ----------

def psql(sql: str) -> list[str]:
    out = subprocess.run(
        ["docker", "exec", "psp-postgis", "psql", "-U", "place", "-d", "place", "-qtAc", sql],
        capture_output=True, text=True, check=True,
    ).stdout
    return [line.strip() for line in out.split("\n") if line.strip()]


# ---------- 형태소 분석 신탁 ----------

def ensure_probe_index() -> None:
    """사전 후보 판정용 인덱스를 준비한다. 이미 있으면 다시 만들지 않는다."""
    req = urllib.request.Request(f"{ES}/{PROBE_INDEX}", method="HEAD")
    try:
        urllib.request.urlopen(req)
        return
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
    req = urllib.request.Request(f"{ES}/{PROBE_INDEX}", method="PUT",
                                 data=json.dumps(PROBE_SETTINGS).encode(),
                                 headers={"Content-Type": "application/json"})
    urllib.request.urlopen(req)


def analyze(text: str) -> list[dict] | None:
    body = json.dumps({"analyzer": "komoran_raw", "text": text}).encode()
    req = urllib.request.Request(f"{ES}/{PROBE_INDEX}/_analyze", data=body,
                                 headers={"Content-Type": "application/json"})
    try:
        return json.load(urllib.request.urlopen(req))["tokens"]
    except Exception:
        return None


def broken_spans(text: str, tokens: list[dict]) -> list[str]:
    """토큰 offset 을 보고 '잘게 부서진 한글 구간'을 원문에서 잘라 돌려준다.

    한 글자만 덮는 토큰이 연속되면 그 구간은 어휘로 인식되지 못한 것이다.
    (투/NR + 썸/NNP -> '투썸',  놓/VX + ㄴ/ETM + 현/NNP -> '논현')
    한글이 아닌 글자를 만나면 구간을 끊는다 — 숫자·기호까지 삼키면 사전이 오염된다.
    """
    covered: dict[int, int] = {}   # 시작 offset -> 그 위치를 덮는 토큰의 길이(최소값)
    for t in tokens:
        start, end = t["start_offset"], t["end_offset"]
        width = end - start
        covered[start] = min(covered.get(start, width), width)

    spans, run = [], []
    for i, ch in enumerate(text):
        # 한 글자짜리 토큰이 덮고 있고, 그 글자가 한글이면 '부서진 조각'
        if covered.get(i) == 1 and HANGUL.match(ch):
            run.append(i)
        else:
            if len(run) >= 2:
                spans.append(text[run[0]:run[-1] + 1])
            run = []
    if len(run) >= 2:
        spans.append(text[run[0]:run[-1] + 1])
    return spans


# ---------- 후보 추출 ----------

MIN_LEN, MAX_LEN = 2, 6


def business_name_candidates(names: list[str], workers: int) -> Counter:
    """상호명 코퍼스에서 오분석 구간을 모은다. 값은 '서로 다른 상호 몇 개에 나왔나'(문서빈도).

    부서진 구간을 통째로 쓰지 않고 **그 안의 부분문자열을 모두** 후보로 낸다.
    구간은 어휘 경계를 모르기 때문이다 — '브런치빈강남'은 '브런치빈'으로, '브런치앤모어'는
    '브런치앤'으로 잡혀서, 정작 진짜 어휘인 '브런치'는 빈도가 흩어져 사라진다.
    넓게 뽑고 빈도로 거르는 쪽이 맞다. 경계는 뒤의 [maximal] 이 잡는다.
    """
    cleaned = [CORP_SUFFIX.sub("", n).strip() for n in names]

    def one(name: str) -> set[str]:
        if not name:
            return set()
        tokens = analyze(name)
        if not tokens:
            return set()
        out: set[str] = set()
        for span in broken_spans(name, tokens):
            for size in range(MIN_LEN, MAX_LEN + 1):
                for i in range(len(span) - size + 1):
                    out.add(span[i:i + size])
        return out

    df = Counter()
    with ThreadPoolExecutor(workers) as pool:
        for spans in pool.map(one, cleaned):
            df.update(spans)             # 한 상호 안에서 중복 등장해도 1회로
    return df


def maximal(words: set[str], df: Counter) -> set[str]:
    """'항상 더 긴 것의 일부로만 나타나는' 후보를 버린다 — 어휘 경계 판정.

    '브런'은 문서빈도가 '브런치'와 똑같다. 즉 '브런'은 **한 번도 단독으로 나온 적이 없다** →
    그건 어휘가 아니라 조각이다. 반대로 '논현'은 '논현점'보다 문서빈도가 훨씬 커서
    여러 문맥에 단독으로 나온다 → 어휘다.
    (신조어 추출에서 쓰는 분기 엔트로피(branching entropy)의 단순화 버전)
    """
    # 한 글자 더 긴 후보가 어떤 조각을 좌/우로 감싸는지 미리 모아 둔다.
    wraps: dict[str, list[str]] = {}
    for longer in df:
        if len(longer) <= MIN_LEN:
            continue
        wraps.setdefault(longer[:-1], []).append(longer)   # 오른쪽 확장
        wraps.setdefault(longer[1:], []).append(longer)    # 왼쪽 확장

    kept = set()
    for w in words:
        if len(w) >= MAX_LEN:            # 더 긴 것을 관측하지 못했으니 판단 보류하고 남긴다
            kept.add(w)
            continue
        if any(df[longer] == df[w] for longer in wraps.get(w, ())):
            continue                     # 단독으로 나온 적이 한 번도 없다 → 어휘가 아니라 조각
        kept.add(w)
    return kept


def region_candidates() -> set[str]:
    """행정구역명 — 소량이지만 오분석 직격 대상이라 가장 값싸고 확실한 이득."""
    out: set[str] = set()

    # 행정동 컬럼: '논현2동' 같은 숫자 포함 형태 -> 숫자를 뺀 '논현동'과 어간 '논현'
    for dong in psql("select distinct dong from place where dong is not null"):
        base = re.sub(r"\d+", "", dong)              # 논현2동 -> 논현동
        if HANGUL.match(base) and len(base) >= 2:
            out.add(base)
            if base.endswith("동") and len(base) >= 3:
                out.add(base[:-1])                    # 논현동 -> 논현
    # 지번주소의 법정동 (행정동과 다를 수 있다)
    for addr in psql("select distinct jibun_address from place where jibun_address is not null"):
        for m in re.findall(r"([가-힣]{2,}동)(?:\s|$)", addr):
            out.add(m)
    # 도로명 — '테헤란로', '강남대로', '언주로108길'의 '언주로'
    for addr in psql("select distinct road_address from place where road_address is not null"):
        for m in re.findall(r"([가-힣]{2,}(?:대로|로|길))(?:\s|\d|$)", addr):
            out.add(m)
    return out


# 용언 활용형으로 보이는 꼬리 — '하는날', '웃는' 같은 조각이 고유명사로 박히면
# 엉뚱한 문장까지 그렇게 분석된다.
CONJUGATION_TAIL = re.compile(r"(는|은|을|던|해|하|했|됨|임|음)$")

# 상호명의 **구조적 접미**. '도곡점'의 '점'처럼 브랜드 본체가 아니라 위치/지점 표기다.
# 경계를 잘못 자르면 '곡점' 같은 조각이 후보로 올라오는데, 이걸 고유명사로 등록하면
# '도곡점'이 도/곡점 으로 갈려 '도곡' 검색을 오히려 막는다.
BRANCH_TAIL = re.compile(r"(점|역|동|가|층|호)$")


def keep(word: str, structural_tail_ok: bool = False) -> bool:
    """사전에 넣어도 안전한 후보인가.

    @param structural_tail_ok 행정구역명처럼 '동/로/길'로 끝나는 게 정상인 고신뢰 후보면 True.
    """
    if not HANGUL.match(word):
        return False
    if not (2 <= len(word) <= 8):        # 1글자는 무의미, 너무 길면 통짜 등록이 되어 부분검색을 죽인다
        return False
    if CONJUGATION_TAIL.search(word):
        return False
    if not structural_tail_ok and BRANCH_TAIL.search(word):
        return False
    return True


def minimal_units(words: set[str]) -> list[str]:
    """이미 등재된 단어를 **접두로 포함하는** 후보는 버린다 — 최소 단위만 남긴다.

    사전 등록의 부작용은 그 문자열이 **통짜 토큰**이 된다는 것이다.
    '논현' 과 '논현점' 을 둘 다 넣으면 '논현점' 이 하나의 고유명사가 되어 **'논현' 검색에 안 걸린다.**
    반대로 '논현' 만 넣으면 '논현점' 은 논현/점 으로 알아서 쪼개진다.
    그래서 짧은 것부터 채택하고, 그 접두를 갖는 긴 후보는 떨어뜨린다.
    """
    accepted: list[str] = []
    for word in sorted(words, key=lambda w: (len(w), w)):
        if any(word.startswith(shorter) for shorter in accepted):
            continue
        accepted.append(word)
    return sorted(accepted)


def still_broken(word: str) -> bool:
    """후보를 단독으로 분석해도 여전히 부서지는가 = 정말 미등록 어휘인가."""
    tokens = analyze(word)
    if not tokens:
        return False
    return bool(broken_spans(word, tokens))


# ---------- 실행 ----------

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0, help="상호명 표본 수 (0=전체)")
    ap.add_argument("--min-df", type=int, default=3, help="상호 몇 개 이상에 등장해야 채택할지")
    ap.add_argument("--workers", type=int, default=16)
    args = ap.parse_args()

    ensure_probe_index()
    limit = f" limit {args.limit}" if args.limit else ""
    names = psql(f"select name from place where deleted_at is null order by place_id{limit}")
    print(f"▶ 상호명 {len(names):,}건 분석 중 (ES komoran_tokenizer 기준)…", file=sys.stderr)

    df = business_name_candidates(names, args.workers)
    print(f"  오분석 구간 후보 {len(df):,}종", file=sys.stderr)

    frequent = {w for w, c in df.items() if c >= args.min_df and keep(w)}
    print(f"  문서빈도 {args.min_df}회 이상 + 형식 통과: {len(frequent):,}종", file=sys.stderr)

    biz = maximal(frequent, df)
    print(f"  어휘 경계 판정(항상 더 긴 것의 일부인 조각 제거): {len(biz):,}종", file=sys.stderr)

    # 행정구역·도로명은 '동/로/길'로 끝나는 게 정상이므로 구조적 접미 규칙에서 예외.
    region = {w for w in region_candidates() if keep(w, structural_tail_ok=True)}
    print(f"▶ 행정구역·도로명 후보 {len(region):,}종", file=sys.stderr)

    # 단독 분석에서도 깨지는 것만 최종 채택 (이미 잘 아는 단어를 사전에 넣을 이유가 없다)
    candidates = sorted(biz | region)
    with ThreadPoolExecutor(args.workers) as pool:
        verdicts = list(pool.map(still_broken, candidates))
    broken = {w for w, bad in zip(candidates, verdicts) if bad}
    print(f"▶ 단독 분석 검증: {len(candidates):,}종 중 {len(candidates) - len(broken):,}종은 이미 정상 → 제외",
          file=sys.stderr)

    final = minimal_units(broken)
    print(f"▶ 최소 단위만 남기기: {len(broken):,}종 → {len(final):,}종", file=sys.stderr)

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("# 원천 데이터에서 자동 생성된 KOMORAN 사용자 사전 (scripts/build_komoran_dict.py)\n")
        f.write("# 기준: 형태소 분석기가 '잘게 부수는' 문자열만 등록한다. 손으로 고치지 말 것 — 재생성됨.\n")
        for word in final:
            f.write(f"{word}\tNNP\n")

    print(f"✔ {OUT} — {len(final):,}개 등재", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
