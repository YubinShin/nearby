#!/usr/bin/env python3
"""골든셋 라벨로 검색 결과를 채점한다. precision@k · recall@k · MRR · nDCG@k.

  전제:  ES 기동. --channel 모드는 search-api 도 기동
  실행:  python3 scripts/eval/score_golden_set.py                       # hsearch 채점
         python3 scripts/eval/score_golden_set.py --channel search
         python3 scripts/eval/score_golden_set.py --fields default --save base.json
         python3 scripts/eval/score_golden_set.py --fields "name^10,brand_text^5" --baseline base.json
         python3 scripts/eval/score_golden_set.py --verify               # ES 재현이 /v1/search 와 같은지
"""

import argparse
import json
import math
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit("PyYAML 이 필요합니다: pip3 install pyyaml")

HERE = Path(__file__).resolve().parent
GOLDEN = HERE / "golden_set.yaml"
ES_INDEX = "place_search"

DEFAULT_FIELDS = [
    "name^5",
    "brand_text^5",
    "branch^3",
    "category_small.txt^2",
    "category_mid.txt",
    "dong.txt^1.5",
    "sigungu.txt",
    "road_address",
    "jibun_address",
]
PHRASE_BOOST = 3.0


def load_golden(path):
    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    return [
        (q["query"], set(q["expected_places"]))
        for q in doc["queries"]
        if isinstance(q["expected_places"], list)
    ]


def post(url, body, timeout=30):
    req = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"), headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.load(resp)


def es_query(query, fields, relaxed):
    match = {
        "query": query,
        "fields": fields,
        "type": "cross_fields",
    }
    if relaxed:
        match["operator"] = "or"
        match["minimum_should_match"] = "70%"
    else:
        match["operator"] = "and"
    return {
        "bool": {
            "must": [{"multi_match": match}],
            "should": [{"match_phrase": {"name": {"query": query, "boost": PHRASE_BOOST}}}],
        }
    }


def rank_es(es_url, query, fields, k):
    for relaxed in (False, True):
        body = {"size": k, "_source": False, "query": es_query(query, fields, relaxed)}
        hits = post(f"{es_url}/{ES_INDEX}/_search", body)["hits"]["hits"]
        if hits:
            return [h["_id"] for h in hits]
    return []


def rank_ask(base, query, k, notes):
    url = f"{base}/v1/ask?" + urllib.parse.urlencode({"q": query, "size": k})
    try:
        with urllib.request.urlopen(url, timeout=60) as resp:
            body = json.load(resp)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
        print(f"  실패 · {query} · {e}", file=sys.stderr)
        return []
    notes[query] = {
        "vendor": body.get("llmVendor"),
        "applied_q": body.get("applied", {}).get("q"),
        "degraded_by": body.get("degradedBy", []),
        "unsupported": body.get("applied", {}).get("unsupported", []),
    }
    return [h["placeId"] for h in body.get("search", {}).get("hits", [])]


def rank_api(base, channel, query, k):
    url = f"{base}/v1/{channel}?" + urllib.parse.urlencode({"q": query, "size": k})
    try:
        with urllib.request.urlopen(url, timeout=30) as resp:
            body = json.load(resp)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
        print(f"  실패 · {query} · {e}", file=sys.stderr)
        return []
    return [h["placeId"] for h in body.get("hits", [])]


def score(ranked, expected, k):
    ranked = ranked[:k]
    if not expected:
        return None
    hits = [i for i, pid in enumerate(ranked) if pid in expected]
    gain = sum(1 / math.log2(i + 2) for i in hits)
    ideal = sum(1 / math.log2(i + 2) for i in range(min(len(expected), k)))
    return {
        "returned": len(ranked),
        "found": len(hits),
        "precision": len(hits) / len(ranked) if ranked else 0.0,
        "recall": len(hits) / len(expected),
        "mrr": 1 / (hits[0] + 1) if hits else 0.0,
        "ndcg": gain / ideal if ideal else 0.0,
    }


def verify(es_url, base, golden, k):
    same, diff = 0, []
    for query, _ in golden:
        a = rank_es(es_url, query, DEFAULT_FIELDS, k)
        b = rank_api(base, "search", query, k)
        if a == b:
            same += 1
        else:
            diff.append((query, len(a), len(b)))
    print(f"재현 일치 {same}/{len(golden)} 질의")
    for query, na, nb in diff:
        print(f"  불일치 · {query} · ES {na}건 / API {nb}건")
    return not diff


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--es", default="http://localhost:9200")
    ap.add_argument("--channel", choices=["search", "vsearch", "hsearch"])
    ap.add_argument("--ask", action="store_true", help="ask-api 가 만든 합성 질의로 채점")
    ap.add_argument("--ask-base", default="http://localhost:8082")
    ap.add_argument("--fields", help='"default" 또는 "name^5,brand_text^5,..."')
    ap.add_argument("-k", type=int, default=10)
    ap.add_argument("--label")
    ap.add_argument("--save", type=Path)
    ap.add_argument("--baseline", type=Path)
    ap.add_argument("--verify", action="store_true")
    args = ap.parse_args()

    golden = load_golden(GOLDEN)

    if args.verify:
        sys.exit(0 if verify(args.es, args.base, golden, args.k) else 1)

    notes = {}
    if args.ask:
        runner = lambda q: rank_ask(args.ask_base, q, args.k, notes)
        label = args.label or "ask"
    elif args.fields:
        fields = DEFAULT_FIELDS if args.fields == "default" else args.fields.split(",")
        runner = lambda q: rank_es(args.es, q, fields, args.k)
        label = args.label or ("es:" + ("default" if args.fields == "default" else args.fields))
    else:
        channel = args.channel or "hsearch"
        runner = lambda q: rank_api(args.base, channel, q, args.k)
        label = args.label or f"api:{channel}"

    rows = {}
    print(f"[{label}]  k={args.k}\n")
    print(f"{'질의':<18} {'반환':>4} {'정답':>4} {'prec':>6} {'recall':>7} {'MRR':>6} {'nDCG':>6}")
    print("-" * 58)
    for query, expected in golden:
        s = score(runner(query), expected, args.k)
        if s is None:
            print(f"{query:<18} {'라벨 없음':>30}")
            continue
        rows[query] = s
        print(
            f"{query:<18} {s['returned']:>4} {s['found']:>4} {s['precision']:>6.2f} "
            f"{s['recall']:>7.2f} {s['mrr']:>6.2f} {s['ndcg']:>6.2f}"
        )

    means = {
        m: sum(r[m] for r in rows.values()) / len(rows)
        for m in ("precision", "recall", "mrr", "ndcg")
    }
    print("-" * 58)
    print(
        f"{'평균 (질의 ' + str(len(rows)) + '개)':<18} {'':>4} {'':>4} "
        f"{means['precision']:>6.2f} {means['recall']:>7.2f} {means['mrr']:>6.2f} {means['ndcg']:>6.2f}"
    )

    if args.baseline:
        prev = json.loads(args.baseline.read_text(encoding="utf-8"))
        print(f"\n[{prev['label']}] 대비")
        for m in ("precision", "recall", "mrr", "ndcg"):
            delta = means[m] - prev["means"][m]
            print(f"  {m:<10} {prev['means'][m]:>6.3f} → {means[m]:>6.3f}   {delta:+.3f}")
        moved = sorted(
            (
                (rows[q]["ndcg"] - prev["rows"][q]["ndcg"], q)
                for q in rows
                if q in prev["rows"] and abs(rows[q]["ndcg"] - prev["rows"][q]["ndcg"]) > 1e-9
            ),
            key=lambda x: x[0],
        )
        if moved:
            print("\n  nDCG 가 바뀐 질의")
            for delta, q in moved:
                print(f"    {delta:+.3f}  {q}")

    if notes:
        vendors = sorted({n["vendor"] for n in notes.values() if n["vendor"]})
        degraded = [q for q, n in notes.items() if n["degraded_by"]]
        print(f"\n  llmVendor {', '.join(vendors)}"
              + (f" · degraded {len(degraded)}질의" if degraded else ""))

    if args.save:
        record = {"label": label, "k": args.k, "means": means, "rows": rows}
        if notes:
            record["ask"] = notes
        args.save.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n{args.save} 저장")


if __name__ == "__main__":
    main()
