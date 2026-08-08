# Search Quality Evaluation

검색 품질을 평가하기 위한 골든셋과 채점 도구입니다. `golden_set.yaml`에는 **25개 질의**, **1,088개 정답(place_id)** 이 있습니다. (Labeling: **2026-08-05**, 풀 재생성·재라벨: **2026-08-08**)

측정 결과는 [README](../../README.md#search-quality), 검색 모듈 개요는 [ask-api](../../services/ask-api/README.md)에 있습니다.

## Layout

| File | Description |
| --- | --- |
| `golden_set.yaml` | 질의별 정답 `place_id` 목록 |
| `eval_pool_seeds.yaml` | 모든 검색 채널이 실패하는 질의용 시드. 코퍼스에서 후보를 직접 주입 |
| `build_eval_pool.py` | 키워드·벡터·하이브리드·`ask` 네 경로의 합집합과 시드로 후보 풀 생성 |
| `judge.html` | `eval_pool.json`을 열어 후보를 판정하는 로컬 UI |
| `apply_verdicts.py` | 판정 결과를 골든셋에 반영 |
| `score_golden_set.py` | Precision, Recall, MRR, nDCG 계산 |
| `measure_search.py` / `measure_threshold.py` | 검색 지연 측정 및 벡터 임계값(threshold) 스윕 |
| `scores/` | 측정 결과 원본 (`<date>-<channel>.json`) |

## Golden Set Format

| Field | Description |
| --- | --- |
| `expected_places` | 질의의 정답 `place_id` 목록 (순서는 의미 없음) |
| `expect_empty` | 정답이 존재하지 않아야 하는 질의인지 여부 |

현재 모든 질의의 `expect_empty` 값은 `false`입니다. 평점·영업시간·배달·가격과 같은 **속성 트랩(attribute traps)** 역시 "결과가 없어야 하는 질의"가 아닙니다. 예를 들어 `평점 4.5 이상 카페`의 정답은 **평점 조건을 뺀 카페**입니다. 검색기는 장소 유형을 찾고, 평점 조건은 지원하지 않는 속성으로 처리하는 것이 계약입니다.

속성을 올바르게 인식했는지는 답변의 `unsupported` 필드로 별도 검증합니다. `AskMappingTest` 가 `평점 4.5 이상 카페` → `unsupported: ["평점"]`, `배달 되는 치킨집` → `unsupported: ["배달"]` 을 고정합니다.

## Labeling

```bash
python3 scripts/eval/build_eval_pool.py
open scripts/eval/judge.html
python3 scripts/eval/apply_verdicts.py verdicts.json
```

라벨은 하나의 검색 채널 결과만으로 만들지 않습니다. 특정 채널의 결과만 후보로 사용하면 그 채널이 찾지 못한 정답이 라벨에서 빠져 이후 개선 효과를 측정할 수 없습니다. 대신 Keyword · Vector · Hybrid · `ask` 네 경로의 **합집합(union)** 을 후보 풀로 쓰고, 판정할 때는 **채널과 순위를 모두 숨깁니다(blind labeling)**. 네 경로 모두 실패하는 질의는 `eval_pool_seeds.yaml`에 category를 지정해 코퍼스에서 후보를 직접 주입합니다.

후보 풀 밖의 정답은 여전히 라벨에 들어가지 못하므로, 이 골든셋은 **절대적인 정확도보다 설정 간 상대 비교**를 목적으로 사용합니다.

## Scoring

```bash
python3 scripts/eval/score_golden_set.py --channel hsearch

./gradlew :ask-api:bootRun \
  --args='--spring.profiles.active=fixture'

python3 scripts/eval/score_golden_set.py --ask
```

### Results (2026-08-08)

Corpus: **Gangnam 64K**, `k=10`

| Search Path | Precision@10 | MRR | nDCG@10 |
| --- | ---: | ---: | ---: |
| Keyword | 0.51 | 0.64 | 0.53 |
| Vector | 0.71 | 0.80 | 0.72 |
| Hybrid (`q`) | 0.85 | 0.88 | 0.85 |
| **Hybrid (`ask`-generated `q`)** | **0.86** | **0.98** | **0.87** |

평균은 **25개 질의 전체**를 대상으로 계산하므로, 키워드 검색이 결과를 전혀 반환하지 못한 9개 질의도 0점으로 포함됩니다. 호출이 실패한 질의는 0점이 아니라 채점에서 제외하고 개수를 따로 보고합니다. 원본 측정 결과는 `scores/2026-08-08-*.json`에 있습니다.

`Recall@10` 은 표에서 뺐습니다. 아래 Recall Ceiling 을 참고하십시오. 값은 `scores/` 의 JSON 에 남아 있습니다.

`ask` 행은 **fixture 모드**에서 측정했습니다. 실제 LLM 호출은 `temperature=0`이라도 완전히 결정적이지 않아 같은 결과를 항상 재현하지 못합니다 ([ADR 0014](../../docs/adr/0014-ask-api-llm-query-understanding.md)).

### Recall Ceiling

`recall@10`의 분모는 정답 전체입니다. 반환은 10건이므로 정답이 10건을 넘는 질의는 전부 맞혀도 1.0에 도달하지 못합니다. `치킨`은 정답이 30건이라 상한이 0.33입니다.

25개 질의 **전부** 정답이 10건을 넘습니다(평균 43.5건, 최소 25건, 최대 65건). 질의별 상한 `min(10, 정답수) / 정답수`를 평균하면 **0.248**이며, 이 값이 `k=10`에서 가능한 최대치입니다.

2026-08-08 풀을 깊이 30으로 다시 파면서 정답이 434건에서 1,088건으로 늘었고 상한도 0.629에서 0.248로 내려갔습니다. 검색이 나빠진 것이 아니라 분모가 커진 것입니다.

| Search Path | Recall@10 | Ceiling (0.248) |
| --- | ---: | ---: |
| Keyword | 0.12 | 48% |
| Vector | 0.17 | 69% |
| Hybrid (`q`) | 0.21 | 85% |
| Hybrid (`ask`-generated `q`) | 0.21 | 84% |

`nDCG@10`은 이 상한을 이미 반영합니다. `score_golden_set.py`가 ideal DCG를 `min(정답수, k)`로 계산하므로 nDCG는 `k`에서 달성 가능한 최선 대비 비율입니다. `recall`만 정규화되어 있지 않으므로 두 수치는 기준이 다릅니다.

### Depth k=30

풀을 깊이 30으로 팠으므로 `k=30`까지는 판정받지 않은 문서가 섞이지 않습니다. 하이브리드 상위 30건 675개를 대조한 결과 미판정은 **0건**입니다.

| Search Path | Precision@30 | Recall@30 | MRR | nDCG@30 |
| --- | ---: | ---: | ---: | ---: |
| Keyword | 0.49 | 0.33 | 0.64 | 0.51 |
| Vector | 0.67 | 0.46 | 0.80 | 0.69 |
| Hybrid (`q`) | 0.80 | 0.59 | 0.88 | 0.82 |
| Hybrid (`ask`-generated `q`) | 0.91 | 0.65 | 1.00 | 0.92 |

`k=30`에서는 recall 상한이 0.728로 올라가 채널 간 차이가 드러납니다. 다만 대표 수치는 `k=10`으로 둡니다. 사용자가 실제로 보는 것은 첫 페이지이고, 두 깊이를 나란히 놓으면 어느 쪽이 기준인지 흐려집니다.

### Pooling Bias

후보 풀은 네 경로의 합집합이므로 **각 채널은 자기가 올린 후보에서 유리합니다.** 정답 1,088건을 어느 채널이 올렸는지 세면 기여가 고르지 않습니다.

| 채널 | 기여 | 단독 기여 |
| --- | ---: | ---: |
| `ask` | 657 | **241** |
| hybrid | 602 | 3 |
| vector | 502 | 88 |
| keyword | 366 | 91 |
| seed | 49 | 43 |

단독 기여는 그 경로만 상위 30건에 올려서 정답이 된 문서입니다. `ask`는 25질의 전부에서 30건을 올린 반면 키워드는 뜻 질의 7개에서 0건이라, `ask` 단독 기여가 241건으로 가장 많습니다.

그래서 `ask` 행의 우위는 실제 개선과 풀 편향이 섞인 값입니다. `k=30`에서 하이브리드와의 nDCG 차이가 0.10까지 벌어지는 것도 같은 이유로 봅니다. 채널 간 절대 비교보다 **같은 채널의 설정 변경 비교**에 쓰는 것이 이 골든셋의 용도입니다.

### Sample Size

25개 질의에서는 질의 하나가 평균을 크게 움직입니다. `세탁소` 오인식 하나가 평균 nDCG를 약 0.03 낮추는 것이 그 예입니다.

따라서 Hybrid와 `ask` 사이의 nDCG 차이 **0.02**는 질의 하나의 영향보다 작아 개선으로 주장하지 않습니다. 두 경로의 `recall@10`은 0.21로 같으며, 실질적인 차이는 **MRR 0.88 → 0.98**입니다. 질의 이해는 검색 결과 집합을 넓히는 것이 아니라 상위 순위를 정확하게 만듭니다.

Keyword와 Hybrid의 nDCG 차이 **0.32**는 이 표본 크기에서도 유효합니다.

## Where Query Understanding Helped (and Hurt)

| Query | nDCG | Notes |
| --- | ---: | --- |
| 조용히 공부할 곳 | 0.00 → 1.00 | `스터디카페`를 보강하여 키워드 검색이 독서실을 찾음 |
| 차 고치는 곳 | 0.00 → 0.68 | `자동차 정비`를 보강하여 정비소 검색 성공 |
| 회 먹을 데 | 0.71 → 0.93 | 질의 이해로 순위 개선 |
| 세탁소 | 1.00 → 0.14 | LLM이 `세타포`로 오인식 |
| 배달 되는 치킨집 | 0.67 → 0.22 | `치킨집`으로 단순화되며 결과가 줄어듦 |

`세탁소` 오인식 하나가 평균 nDCG를 약 **0.03** 낮춥니다. 해당 질의를 제외하면 nDCG **0.87 → 0.90**, MRR **0.98 → 1.00** 입니다.

## Scope

이 골든셋은 **검색(Search)** 품질만 평가합니다. LLM이 검색 결과만을 근거로 답변했는지(groundedness)는 별도의 평가 대상이며, 라벨셋은 구축 예정입니다 ([ADR 0015](../../docs/adr/0015-ask-api-grounded-answer-generation.md)의 *Revisit conditions*).
