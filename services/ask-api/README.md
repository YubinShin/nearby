# ask-api

자연어 질의를 검색 요청으로 구조화하는 모듈입니다. `search-api`를 HTTP 로 사용하며 `search-core`에 의존하지 않습니다. `answer=true` 일 때만 검색 결과를 근거로 답변을 생성하고, 답변 생성이 실패해도 검색 결과는 그대로 반환합니다.

[ADR 0014](../../docs/adr/0014-ask-api-llm-query-understanding.md) · [ADR 0015](../../docs/adr/0015-ask-api-grounded-answer-generation.md)

```
질의 "역삼동 조용히 공부할 곳"
  → Gemini (responseSchema 로 강제된 JSON)
      { keyword: "공부할 곳", category_hint: "스터디카페", geo_anchor: "역삼동", radius_m: null, expects_empty: true }
  → GET /v1/hsearch?q=역삼동 공부할 곳 스터디카페&size=20
```

## Running

`search-api`(8080)가 실행 중이어야 합니다. API 키가 없으면 애플리케이션이 시작되지 않습니다.

```bash
export GEMINI_API_KEY=...
cd services && ./gradlew :ask-api:bootRun          # 8082

curl -G localhost:8082/v1/ask --data-urlencode "q=회 먹을 데"
```

녹화된 응답으로 실행하려면 fixture 모드를 사용합니다. 픽스처가 테스트 리소스에 있어 위치를 함께 지정합니다.

```bash
./gradlew :ask-api:bootRun \
  --args='--psp.ask.llm=fixture --psp.ask.fixtures.location=file:src/test/resources/fixtures/'
```

## `GET /v1/ask`

| 이름 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `q` | string | (필수) | 자연어 질의. 없거나 공백이면 `400` |
| `size` | int | `20` | `psp.ask.size`. 1~50 으로 잘립니다 |
| `lat`, `lon` | double | – | 기준 좌표. 둘 다 있어야 `radius_m`이 전달됩니다  |
| `answer` | boolean | `false` | `true` 면 검색 결과를 근거로 답변을 생성합니다 |

반경 검색은 기준 좌표가 있을 때만 가능합니다. 답변 생성은 opt-in 이며 LLM 왕복이 한 번 더 붙습니다.

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
  "degradedBy": [],                 // "llm" | "search" | "answer"
  "llmVendor": "gemini",            // "gemini" | "fixture". fixture 면 llmTookMs 는 녹화 재생
  "llmTookMs": 0, "searchTookMs": 0, "answerTookMs": 0, "tookMs": 0,
                                    // 구간별 소요. LLM 왕복이 2~3초로 대부분 (ADR 0014)
  "search": { /* /v1/hsearch 응답 원문 */ },
  "answer": null                    // answer=true 일 때만. 생성 실패 시에도 null
}
```

`applied.unmapped`는 파싱된 값 중 `/v1/hsearch` 파라미터로 옮기지 못한 항목입니다 — [Platform gaps](../../docs/adr/0014-ask-api-llm-query-understanding.md#platform-gaps).

요청 하나에 전체 상한이 걸립니다(`psp.ask.budget-ms`, 기본 15초). LLM 이해와 답변 생성이 이 시간을 나눠 씁니다. 남은 시간이 없으면 그 단계를 실행하지 않고 `degradedBy`에 실어 반환합니다. 검색 단계는 자르지 않습니다 — 검색 결과 없이는 응답을 만들 수 없고, `/v1/hsearch` 호출 자체에 5초 상한이 있습니다.

`applied.unsupported`는 코퍼스에 데이터가 없어 거를 수 없는 속성입니다. 검색 결과를 좁히지 않고 이름만 알립니다 — `평점 4.5 이상 카페`는 `q=카페`로 검색하고 `unsupported: ["평점"]`을 응답합니다. LLM 장애 시(`degradedBy: ["llm"]`)에는 원문 질의를 그대로 검색하므로 이 보장이 적용되지 않습니다 — [결정 5](../../docs/adr/0014-ask-api-llm-query-understanding.md#5-unsupported-filters).

## Grounded Answer

`answer=true`로 호출하면 `answer`가 채워집니다.

```jsonc
"answer": {
  "found": true,
  "unverifiableConditions": [],     // 코퍼스에 없어 확인할 수 없는 조건
  "sentences": [
    { "text": "강남역 근처 CU 강남역점이 있습니다.", "evidence": ["MA0101..."] }
  ],
  "droppedEvidence": [],            // 검색 결과에 없는 place_id 를 근거로 든 것 — 제거됨
  "driftingEvidence": [],           // 근거로 든 가게 이름이 문장에 없는 것
  "leakedTerms": [],                // 코퍼스에 없는 속성어가 문장에 섞인 것
  "unrenderableRecords": 0
}
```

`GroundingValidator`가 생성 결과를 검사해 위 네 목록을 채웁니다. 근거로 든 `place_id`가 검색 결과에 없으면 그 근거를 떼어내고 `droppedEvidence`에 기록합니다.

## Fixtures

`src/test/resources/fixtures/`에 LLM 응답 원문이 있습니다. CI 는 실제 API 를 호출하지 않습니다.

```bash
python3 scripts/record_llm_fixtures.py             # 녹화되지 않은 응답만 호출
python3 scripts/record_llm_fixtures.py --force     # 전부 재녹화
python3 scripts/record_llm_fixtures.py --dry-run   # 대상만 출력
```

호출 간격은 `--sleep`으로 조정합니다. 기본값은 요금제의 분당 호출 제한을 넘지 않도록 잡혀 있습니다 — [fixtures/README.md](src/test/resources/fixtures/README.md).

## Configuration

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `psp.ask.llm` | `gemini` | `gemini` \| `fixture` |
| `psp.ask.size` | `20` | hsearch 에 요청할 건수 |
| `psp.ask.budget-ms` | `15000` | 요청 하나의 전체 상한. LLM 이해와 답변 생성이 나눠 씁니다 |
| `psp.ask.search.base-url` | `http://localhost:8080` | `search-api` 주소 |
| `psp.ask.corpus.lexicon` | `classpath:corpus/unsupported-filters.json` | 코퍼스에 없는 속성 어휘 |
| `psp.ask.gemini.model` | `gemini-3.5-flash` | 별칭(`-latest`)은 사용하지 않습니다 |
| `psp.ask.gemini.api-key` | `${GEMINI_API_KEY:}` | 비면 기동 실패 |
| `psp.ask.fixtures.location` | `classpath:fixtures/` | `classpath:` · `file:` 둘 다 |

## Evaluation

골든셋 25질의 기준 nDCG@10 은 하이브리드 0.85, `ask` 합성 질의를 적용하면 0.87 입니다. 라벨링 절차 · 재현 명령 · 질의별 승패는 [scripts/eval/README.md](../../scripts/eval/README.md) 에 있습니다.