#!/usr/bin/env python3
"""판정기가 내보낸 verdicts.json 을 eval_pool.yaml 과 golden_set.yaml 에 반영한다.

  전제:  python3 scripts/build_eval_pool.py 로 풀이 만들어져 있을 것
  실행:  python3 scripts/apply_verdicts.py ~/Downloads/verdicts.json
         python3 scripts/apply_verdicts.py ~/Downloads/verdicts.json --dry-run
"""

import argparse
import json
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit("PyYAML 이 필요합니다: pip3 install pyyaml")

HERE = Path(__file__).resolve().parent
GOLDEN = HERE / "golden_set.yaml"
POOL = HERE / "eval_pool.yaml"

TODO = "TODO_HUMAN_LABEL"


def rewrite_golden(text, labelled):
    out, current = [], None
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("- query:"):
            current = stripped[len("- query:"):].strip()
        elif current in labelled:
            indent = line[: len(line) - len(line.lstrip())]
            if stripped.startswith("expected_places:"):
                ids = ", ".join(labelled[current])
                out.append(f"{indent}expected_places: [{ids}]")
                continue
            if stripped == f"expect_empty: {TODO}" and labelled[current]:
                out.append(f"{indent}expect_empty: false")
                continue
        out.append(line)
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("verdicts", type=Path)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    verdicts = json.loads(args.verdicts.read_text(encoding="utf-8"))
    pool = yaml.safe_load(POOL.read_text(encoding="utf-8"))
    golden = yaml.safe_load(GOLDEN.read_text(encoding="utf-8"))

    labelled = {}
    print(f"{'질의':<18} {'후보':>5} {'판정':>5} {'정답':>5} {'애매':>5}")
    print("-" * 44)

    for entry in pool["queries"]:
        mine = verdicts.get(entry["query"], {})
        yes, unsure, judged = [], 0, 0
        for c in entry["candidates"]:
            verdict = mine.get(c["place_id"])
            if verdict:
                c["verdict"] = verdict
                judged += 1
                if verdict == "yes":
                    yes.append(c["place_id"])
                elif verdict == "unsure":
                    unsure += 1
        if judged:
            labelled[entry["query"]] = sorted(yes)
        print(
            f"{entry['query']:<18} {len(entry['candidates']):>5} {judged:>5} "
            f"{len(yes):>5} {unsure:>5}"
        )

    conflicts = [
        (q["query"], len(labelled[q["query"]]))
        for q in golden["queries"]
        if q.get("expect_empty") is True and labelled.get(q["query"])
    ]
    updated = rewrite_golden(GOLDEN.read_text(encoding="utf-8"), labelled)
    remaining = sum(1 for q in golden["queries"] if q["query"] not in labelled)
    print("-" * 44)
    print(f"라벨 채운 질의 {len(labelled)}개 · 아직 비어 있는 질의 {remaining}개")

    for query, n in conflicts:
        print(
            f"  모순 · {query} 는 expect_empty: true 인데 정답 {n}건이 판정되었습니다",
            file=sys.stderr,
        )

    for query, ids in labelled.items():
        if not ids:
            print(
                f"  정답 0건 · {query} · expect_empty 를 채우지 않았습니다. "
                f"코퍼스에 정답이 없는지, 풀이 정답을 못 담은 것인지 확인이 필요합니다",
                file=sys.stderr,
            )

    if args.dry_run:
        print("\n--dry-run 이므로 파일을 쓰지 않았습니다")
        return

    POOL.write_text(yaml.safe_dump(pool, allow_unicode=True, sort_keys=False), encoding="utf-8")
    GOLDEN.write_text(updated, encoding="utf-8")
    print(f"\n{POOL.name} · {GOLDEN.name} 갱신 (scripts/)")


if __name__ == "__main__":
    main()
