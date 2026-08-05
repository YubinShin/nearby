#!/usr/bin/env python3
"""골든셋 질의로 3채널을 돌려 판정 후보 풀을 만든다.

  전제:  search-api 기동 (기본 http://localhost:8080), ES (기본 http://localhost:9200)
  실행:  python3 scripts/build_eval_pool.py
         python3 scripts/build_eval_pool.py --size 20      # 채널당 건수
         python3 scripts/build_eval_pool.py --dry-run      # 파일을 쓰지 않고 규모만 출력
"""

import argparse
import hashlib
import json
import random
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
SEEDS = HERE / "eval_pool_seeds.yaml"
POOL = HERE / "eval_pool.yaml"
POOL_JSON = HERE / "eval_pool.json"
PROVENANCE = HERE / "eval_pool_provenance.json"

CHANNELS = [("keyword", "/v1/search"), ("vector", "/v1/vsearch"), ("hybrid", "/v1/hsearch")]
ES_INDEX = "place_search"


def load_queries(path):
    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    return [q["query"] for q in doc["queries"]]


def fetch_channel(base, path, query, size):
    url = f"{base}{path}?" + urllib.parse.urlencode({"q": query, "size": size})
    try:
        with urllib.request.urlopen(url, timeout=30) as resp:
            body = json.load(resp)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
        return None, str(e)
    return [h["placeId"] for h in body.get("hits", [])], None


def load_seeds(path):
    if not path.exists():
        return {}
    return (yaml.safe_load(path.read_text(encoding="utf-8")) or {}).get("seeds") or {}


def fetch_seed(es_url, spec, size):
    query = {"term": {spec["field"]: spec["value"]}}
    payload = json.dumps(
        {"size": size, "_source": False, "query": query, "sort": [{"place_id": "asc"}]}
    ).encode("utf-8")
    req = urllib.request.Request(
        f"{es_url}/{ES_INDEX}/_search",
        data=payload,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        hits = json.load(resp)["hits"]["hits"]
    return [h["_id"] for h in hits]


def fetch_documents(es_url, place_ids):
    if not place_ids:
        return {}
    payload = json.dumps({"ids": sorted(place_ids)}).encode("utf-8")
    req = urllib.request.Request(
        f"{es_url}/{ES_INDEX}/_mget",
        data=payload,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        docs = json.load(resp)["docs"]
    return {d["_id"]: d["_source"] for d in docs if d.get("found")}


def as_candidate(place_id, source):
    parts = [source.get(f) for f in ("category_large", "category_mid", "category_small")]
    return {
        "place_id": place_id,
        "brand": source.get("brand"),
        "name": source.get("name"),
        "branch": source.get("branch"),
        "category": " > ".join(p for p in parts if p),
        "address": source.get("road_address") or source.get("jibun_address"),
        "dong": source.get("dong"),
        "verdict": "TODO",
    }


def shuffled(items, query):
    seed = int(hashlib.sha256(query.encode("utf-8")).hexdigest()[:8], 16)
    out = list(items)
    random.Random(seed).shuffle(out)
    return out


def existing_verdicts(path):
    if not path.exists():
        return {}
    doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    return {
        (entry["query"], c["place_id"]): c.get("verdict", "TODO")
        for entry in doc.get("queries", [])
        for c in entry.get("candidates", [])
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--es", default="http://localhost:9200")
    ap.add_argument("--size", type=int, default=10)
    ap.add_argument("--seed-size", type=int, default=10)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    queries = load_queries(GOLDEN)
    seeds = load_seeds(SEEDS)
    kept = existing_verdicts(POOL)
    if kept:
        print(f"기존 판정 {len([v for v in kept.values() if v != 'TODO'])}건을 유지합니다\n")

    entries, provenance, failures = [], {}, []
    print(f"{'질의':<18} {'키워드':>6} {'벡터':>6} {'하이브리드':>10} {'주입':>5} {'합집합':>7} {'신규':>6}")
    print("-" * 66)

    for query in queries:
        found, per_channel = {}, {}
        for name, path in CHANNELS:
            ids, error = fetch_channel(args.base, path, query, args.size)
            if error is not None:
                failures.append((query, name, error))
                per_channel[name] = None
                continue
            per_channel[name] = len(ids)
            for pid in ids:
                found.setdefault(pid, []).append(name)

        seeded = 0
        for spec in seeds.get(query, []):
            for pid in fetch_seed(args.es, spec, args.seed_size):
                if pid not in found:
                    seeded += 1
                found.setdefault(pid, []).append("seed")

        sources = fetch_documents(args.es, set(found))
        missing = set(found) - set(sources)
        if missing:
            failures.append((query, "es", f"{len(missing)}건이 색인에 없습니다"))

        candidates = [as_candidate(pid, sources[pid]) for pid in found if pid in sources]
        new = 0
        for c in candidates:
            verdict = kept.get((query, c["place_id"]))
            if verdict is None:
                new += 1
            else:
                c["verdict"] = verdict

        entries.append({"query": query, "candidates": shuffled(candidates, query)})
        provenance[query] = {pid: found[pid] for pid in found if pid in sources}

        def cell(v):
            return "실패" if v is None else str(v)

        print(
            f"{query:<18} {cell(per_channel['keyword']):>6} {cell(per_channel['vector']):>6} "
            f"{cell(per_channel['hybrid']):>10} {seeded:>5} {len(candidates):>7} {new:>6}"
        )

    total = sum(len(e["candidates"]) for e in entries)
    todo = sum(1 for e in entries for c in e["candidates"] if c["verdict"] == "TODO")
    print("-" * 66)
    print(f"후보 {total}건 · 판정 대기 {todo}건 · 예상 소요 {todo * 10 // 60}분 (건당 10초 기준)")

    for query, where, error in failures:
        print(f"  실패 {where} · {query} · {error}", file=sys.stderr)

    if args.dry_run:
        print("\n--dry-run 이므로 파일을 쓰지 않았습니다")
        return

    document = {"version": 1, "size_per_channel": args.size, "queries": entries}
    POOL.write_text(
        yaml.safe_dump(document, allow_unicode=True, sort_keys=False),
        encoding="utf-8",
    )
    POOL_JSON.write_text(json.dumps(document, ensure_ascii=False, indent=2), encoding="utf-8")
    PROVENANCE.write_text(json.dumps(provenance, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n{POOL.name} · {POOL_JSON.name} · {PROVENANCE.name} 갱신 (scripts/)")


if __name__ == "__main__":
    main()