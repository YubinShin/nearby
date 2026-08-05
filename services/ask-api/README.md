# ask-api — 자연어 질의를 검색 요청으로 (RAG)

`ask-api` 는 `search-api` 를 HTTP 로 사용하는 별도 모듈입니다.
기본 동작은 자연어를 검색 요청으로 구조화하는 것이고, `answer=true` 일 때만 검색 결과를 근거로
답변을 생성합니다. 답변 생성이 실패해도 검색 결과는 그대로 나갑니다.

[ADR 0014](../../docs/adr/0014-ask-api-llm-query-understanding.md) ·
[ADR 0015](../../docs/adr/0015-ask-api-grounded-answer-generation.md).

```
질의 "역삼동 조용히 공부할 곳"
  → Gemini (responseSchema 로 강제된 JSON)
      { keyword: "공부할 곳", category_hint: "스터디카페", geo_anchor: "역삼동", radius_m: null, expects_empty: true }
  → GET /v1/hsearch?q=역삼동 공부할 곳 스터디카페&size=20
```

## Running

`search-api`(8080)가 실행 중이어야 합니다.

```bash
export GEMINI_API_KEY=...
cd services && ./gradlew :ask-api:bootRun          # 8082

curl -G localhost:8082/v1/ask --data-urlencode "q=회 먹을 데"
```

API 키가 없으면 애플리케이션이 시작되지 않습니다.
녹화된 응답으로 실행하려면 fixture 모드를 사용합니다.
픽스처는 테스트 리소스에 있어 실행 시에는 위치를 함께 지정합니다.

```bash
./gradlew :ask-api:bootRun \
  --args='--psp.ask.llm=fixture --psp.ask.fixtures.location=file:src/test/resources/fixtures/'
```

## `GET /v1/ask`

| 이름 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `q` | string | (필수) | 자연어 질의. 없거나 공백이면 `400` |
| `size` | int | `20` | `psp.ask.size`. 1~50 으로 잘린다 |
| `lat`, `lon` | double | – | 기준 좌표. 둘 다 있어야 `radius_m` 이 전달된다 |
| `answer` | boolean | `false` | `true` 면 검색 결과를 근거로 답변을 생성한다 (ADR 0015) |

> 반경 검색은 기준 좌표가 있을 때만 가능합니다.
> 답변 생성은 opt-in 입니다. LLM 왕복이 한 번 더 붙습니다.

```jsonc
{
  "query": "강남역 500m 안에 편의점",
  "parsed": {                       // LLM 이 만든 구조
    "keyword": "편의점", "categoryHint": null, "geoAnchor": "강남역",
    "radiusM": 500, "expectsEmpty": false
  },
  "applied": {                      // 실제로 /v1/hsearch 에 보낸 것
    "q": "강남역 편의점", "size": 20,
    "lat": null, "lon": null, "radius": null,
    "unmapped": ["geo_anchor", "radius_m"],  // 파라미터로 못 옮긴 것
    "unsupported": []                        // 코퍼스에 없어 못 거른 속성
  },
  "degraded": false,
  "degradedBy": [],                 // "llm" | "search"
  "llmVendor": "gemini",            // "gemini" | "fixture". fixture 면 llmTookMs 는 녹화 재생이다
  "llmTookMs": 0, "searchTookMs": 0, "answerTookMs": 0, "tookMs": 0,
                                    // 구간별 소요. LLM 왕복이 2~3초로 대부분 (ADR 0014)
  "search": { /* /v1/hsearch 응답 원문 */ },
  "answer": null                    // answer=true 일 때만 채워진다. 생성 실패 시에도 null
}
```

`answer=true` 로 부르면 `answer` 가 이렇게 채워집니다.

```jsonc
"answer": {
  "found": true,
  "unverifiableConditions": [],     // 코퍼스에 없어 확인할 수 없는 조건
  "sentences": [
    { "text": "강남역 근처 CU 강남역점이 있습니다.", "evidence": ["MA0101..."] }
  ],
  "droppedEvidence": [],            // 검색 결과에 없는 place_id 를 근거로 든 것 — 제거됨
  "driftingEvidence": [],           // 근거로 든 가게 이름이 문장에 없는 것
  "leakedTerms": [],                // 코퍼스에 없는 속성어(평점·영업시간 등)가 문장에 섞인 것
  "unrenderableRecords": 0
}
```

`GroundingValidator` 가 생성 결과를 검사해 위 네 목록을 채웁니다. 근거로 든 `place_id` 가 검색
결과에 없으면 그 근거를 **떼어내고** `droppedEvidence` 에 기록합니다. 답변 문장 자체는 지우지
않으므로, 이 목록들이 비어 있지 않다는 것은 **생성이 계약을 벗어났다는 신호**입니다.

`applied.unmapped` 는 파싱된 값 중 `/v1/hsearch` 파라미터로 옮기지 못한 항목입니다.
이유는 ADR 0014의 Platform gaps를 참고합니다.
[Platform gaps](../../docs/adr/0014-ask-api-llm-query-understanding.md#platform-gaps).

`applied.unsupported` 는 코퍼스에 데이터가 없어 거를 수 없는 속성입니다. 검색 결과는 좁히지
않고 이름만 알립니다 — `평점 4.5 이상 카페` 는 `q=카페` 로 검색하고 `unsupported: ["평점"]` 을
답합니다. LLM 장애 시(`degradedBy: ["llm"]`)에는 원문 질의를 그대로 검색하므로 이 보장이
적용되지 않습니다.
[결정 5](../../docs/adr/0014-ask-api-llm-query-understanding.md#5-unsupported-filters).

## Golden set

`scripts/eval/golden_set.yaml` 에 질의 25개와 정답 434건이 있습니다 (2026-08-05 라벨링).

| 필드 | 뜻 |
|---|---|
| `expected_places` | 이 질의에 나와야 하는 `place_id` 목록. 순서는 담지 않는다 |
| `expect_empty` | 정답이 존재하지 않아야 하는 질의인지. 관측이 아니라 명세 |

`expect_empty` 는 전부 `false` 입니다. 트랩 4개(평점·영업시간·배달·가격)도 0건이 정답이 아니라
**속성을 뺀 나머지**가 정답입니다 — `평점 4.5 이상 카페` 의 정답은 카페입니다. 축을 인지했는지는
응답의 `unsupported` 로 따로 봐야 하고, 그 계약 테스트는 아직 없습니다.

라벨을 다시 만들거나 넓힐 때는 `scripts/eval/` 의 도구를 씁니다.

```bash
python3 scripts/eval/build_eval_pool.py            # 3채널 합집합 + 씨앗 주입 → 후보 풀
open scripts/eval/judge.html                       # eval_pool.json 을 열어 판정
python3 scripts/eval/apply_verdicts.py verdicts.json
python3 scripts/eval/score_golden_set.py --channel hsearch
```

후보를 한 채널의 결과에서만 뽑으면 그 채널이 못 찾은 정답이 라벨에 못 들어가 개선을 감지할 수
없습니다. 세 채널의 합집합을 쓰고, 판정할 때는 순위와 채널을 감춥니다. 세 채널이 모두 실패하는
질의는 `eval_pool_seeds.yaml` 에 category 값을 적어 코퍼스에서 직접 후보를 넣습니다.

풀 밖의 정답은 여전히 담기지 않으므로 **점수의 절대값이 아니라 설정 간 차이만** 씁니다.

### 측정 결과 (2026-08-05, 강남 6.4만, k=10)

| 경로 | precision@10 | recall@10 | MRR | nDCG@10 |
|---|---:|---:|---:|---:|
| 키워드 | 0.60 | 0.29 | 0.64 | 0.53 |
| 벡터 | 0.76 | 0.43 | 0.79 | 0.72 |
| 하이브리드 (원문 `q`) | 0.86 | 0.54 | 0.87 | 0.86 |
| **하이브리드 (`ask` 합성 `q`)** | 0.91 | 0.54 | 0.96 | **0.89** |

25질의 전체 평균이라 키워드가 0건을 낸 9질의가 0점으로 들어갑니다. 원본은
`scripts/eval/scores/2026-08-05-*.json` 이고 위 명령으로 재현합니다. `ask` 행은 픽스처 모드로
쟀습니다 — `temperature 0` 이 결정적이지 않아 실제 호출로는 재현되지 않습니다 (ADR 0014).

```bash
./gradlew :ask-api:bootRun --args='--spring.profiles.active=fixture'
python3 scripts/eval/score_golden_set.py --ask
```

질의 이해가 이긴 곳과 진 곳이 갈립니다.

| 질의 | nDCG 변화 | 무엇이 일어났나 |
|---|---:|---|
| 조용히 공부할 곳 | 0.00 → 1.00 | `스터디카페` 를 붙여 키워드 채널이 독서실을 잡음 |
| 차 고치는 곳 | 0.00 → 0.68 | `자동차 정비` 를 붙여 정비소를 잡음. 벡터 문턱이 자르던 것 |
| 회 먹을 데 | +0.35 | |
| 세탁소 | 1.00 → 0.00 | LLM 이 `세타포` 로 잘못 읽음 |
| 배달 되는 치킨집 | 0.77 → 0.25 | `치킨집` 으로 줄었는데 결과가 1건뿐 |

`세탁소` 한 건이 평균을 0.04 깎습니다. 그 질의를 빼면 nDCG 0.85 → 0.93, MRR 0.87 → 1.00 입니다.

이 수치는 **검색 정확도**입니다. 답변이 근거를 지켰는지(groundedness)는 별도 라벨이 필요하고
아직 없습니다 (ADR 0015 Revisit conditions).

## Fixtures

`src/test/resources/fixtures/` 에 LLM 응답 원문이 있습니다. CI 는 실제 API 를 호출하지 않습니다.

```bash
python3 scripts/record_llm_fixtures.py             # 녹화되지 않은 응답만 호출
python3 scripts/record_llm_fixtures.py --force     # 전부 재녹화
python3 scripts/record_llm_fixtures.py --dry-run   # 대상만 출력
```

호출 간격은 `--sleep` 으로 조정합니다. 기본값은 요금제의 분당 호출 제한을 넘지 않도록 잡혀 있습니다.
[fixtures/README.md](src/test/resources/fixtures/README.md).

## Configuration

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `psp.ask.llm` | `gemini` | `gemini` \| `fixture` |
| `psp.ask.size` | `20` | hsearch 에 요청할 건수 |
| `psp.ask.search.base-url` | `http://localhost:8080` | `search-api` 주소 |
| `psp.ask.corpus.lexicon` | `classpath:corpus/unsupported-filters.json` | 코퍼스에 없는 속성 어휘 |
| `psp.ask.gemini.model` | `gemini-3.5-flash` | 별칭(`-latest`)은 쓰지 않는다 |
| `psp.ask.gemini.api-key` | `${GEMINI_API_KEY:}` | 비면 기동 실패 |
| `psp.ask.fixtures.location` | `classpath:fixtures/` | `classpath:` · `file:` 둘 다 |
