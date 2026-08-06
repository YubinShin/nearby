# ADR 0015 — 검색 결과를 근거로 한 답변 생성(RAG)을 `ask-api` 에 추가

- **상태:** Accepted
- **날짜:** 2026-08-04
- **관련:** ADR 0014 (같은 모듈 · 질의 이해), ADR 0003 (근거를 만드는 검색 경로), ADR 0011 (설정 실패 vs 런타임 degraded), ADR 0006 (질의 런타임)
- **범위:** ADR 0014의 질의 이해는 유지합니다. 이 결정은 그 뒤에 답변 생성 단계를 추가합니다.

## Context

ADR 0014는 답변 생성을 미뤘습니다. 근거는 "코퍼스에 답변의 근거가 될 문장이 없다"였습니다. 이 전제를 재검토합니다.

코퍼스에 문장은 없지만 구조화 레코드는 있습니다(`data-model.md`). `place`는 상호명·지점명·업종(대/중/소)·행정동·도로명주소·좌표를 가집니다. "역삼동 스터디카페 3곳"을 자연어로 답하는 근거는 이 필드에 있습니다. 없는 것은 맛·분위기·인기·평점·영업시간·가격, 즉 리뷰 축입니다.

이 프로젝트의 RAG는 열린 QA가 아니라 검색 레코드의 근거 있는 렌더링입니다. 목표는 검색 결과 밖으로 나가지 않는 답변이고, 위험은 환각이 아니라 지식 누출입니다. 모델이 학습 지식으로 맛·인기·평점을 채워 넣는 경우입니다.

바뀐 것은 "근거 문서"의 정의입니다. 자유 텍스트 문서가 아니라 검색이 반환한 구조화 레코드 집합입니다. ADR 0014 표의 답변 생성 행(LLM 출력=답변, 근거 문서 필요)으로 이동합니다.

grounding 트랩 실험으로 가능성을 확인했습니다(`scripts/grounding_experiments.py`, 2026-08-04). 이 ADR은 실험을 제품 경로로 승격합니다.

## Decision

| # | 결정 | 근거 |
|---|---|---|
| 1 | `ask-api`에 답변 생성 단계 추가. 검색 뒤 두 번째 LLM 호출로 근거 있는 답을 생성 | 근거(검색 레코드)가 있어야 생성이 성립. 파싱 호출과 목적이 다르므로 분리 |
| 2 | 근거는 검색 레코드뿐. 시스템 프롬프트로 "검색결과에 있는 정보만" 강제, 맛·분위기·인기·거리·가격 언급 금지 | 위험이 환각이 아니라 지식 누출. 금지를 명세로 고정 |
| 3 | `responseSchema`로 문장마다 `evidence: [place_id]` 강제. 근거 없는 문장은 빈 배열 | 인용을 스키마 표면에 올려 사후 검증 가능. 편차는 픽스처로 고정 (ADR 0014 결정 2와 동일) |
| 4 | 인용 검증은 코드가 결정적으로 수행. `evidence`의 place_id가 검색 레코드에 실재하는지, 이름이 문장과 일치하는지 대조 | LLM 출력을 믿지 않습니다. ADR 0014 결정 5(`unsupported` 어휘 대조)와 동일한 "LLM 제안, 코드 판정" |
| 5 | 확인 불가 조건은 답하지 않고 `unverifiable_conditions`로 반환. 결과는 좁히지 않음 | 평점·영업시간은 코퍼스 밖. 생성 대신 확인 불가를 명시 (ADR 0014 `unsupported`와 동형) |
| 6 | 생성 LLM 실패는 degraded. 답변만 빠지고 검색 결과는 반환(`degradedBy += "answer"`) | ADR 0014 실패 표의 연장. LLM이 죽어도 검색은 유지 |

### 1. Pipeline

```
질의 "역삼동 조용히 공부할 곳"
  │
  ├─(A) 질의 이해 LLM  ── ADR 0014 그대로 ─▶ { keyword, category_hint, geo_anchor, ... }
  │
  ├─    /v1/hsearch  ─────────────────────▶ 레코드 N건 (place_id·이름·업종·동·주소)
  │
  └─(B) 답변 생성 LLM  ── 레코드를 컨텍스트로 ─▶ { found, sentences[{text, evidence[]}], unverifiable_conditions }
                                                  │
                                          코드가 evidence 검증 → 응답
```

(A)와 (B)는 순차입니다. (B)가 (A)→검색의 결과를 근거로 받으므로 병렬화할 수 없습니다. 지연 비용은 "Data and cost"에서 다룹니다(결정 대기).

### 2. Evidence contract (groundedness contract)

컨텍스트는 검색 레코드에서 코드가 렌더링합니다. 모델에 자유 형식 문서를 주지 않습니다.

```
검색결과 (거리 정보 없음):
- [MA0101...147236] 먹어도 | 횟집 | 삼성2동 | 학동로56길 32
- [MA0101...985043] 마시아 | 일식 회/초밥 | 삼성2동 | 선릉로 514
...
```

응답 스키마(실험에서 검증된 형태):

```jsonc
{
  "found": true,
  "unverifiable_conditions": ["맛있고", "가까운"],   // 코퍼스로 확인 못 하는 질의 조건
  "sentences": [
    { "text": "삼성2동에 회를 파는 곳으로 먹어도, 마시아가 있습니다.",
      "evidence": ["MA0101...147236", "MA0101...985043"] }
  ]
}
```

- 거리 정보는 컨텍스트에 포함하지 않습니다(`거리 정보 없음` 명시). `geo_anchor`/`radius_m`은 좌표 없이 풀 수 없는 축이라(ADR 0014 결정 4·gap ②) 답변이 거리를 말하면 누출입니다.
- 컨텍스트에는 검색이 반환한 레코드만 넣습니다. 원천(PostGIS) 전체는 넣지 않습니다. RAG의 검색 단계는 ES·Qdrant 하이브리드(ADR 0003)가 담당하고, 생성은 그 위에 적용됩니다.

### 3. Citation validation (deterministic, in code)

`validate` 판정(실험 `validate()`를 제품 코드로 이관):

| 검사 | 규칙 | 실패 시 |
|---|---|---|
| evidence 실재 | 인용 place_id가 검색 레코드 집합에 있는가 | 해당 문장을 근거 없음으로 강등 또는 제거 |
| 인용 표류 | place_id가 가리키는 이름이 문장에 등장하는가 | 경고(요약 문장은 오탐 가능 — 실험 주석과 동일) |
| 지식 누출 | 금지 어휘(맛·평점·브랜드 배경지식)가 답변에 있는가 | degraded 처리 후보, 회귀 실패 |
| 조건 누락 | 확인 불가 조건 수가 기대 이상인가 | `unverifiable_conditions` 누락 = silent drop |

`temperature`·thinking이 결정적이지 않으므로(ADR 0014 관측: `세탁소`→`세타포`) 판정 기준은 스키마 통과 여부가 아니라 코드 검증 통과 여부입니다.

### 4. Failure handling

ADR 0014 실패 표에 행을 추가합니다.

| 실패 지점 | 응답 |
|---|---|
| 질의 이해 LLM | `200` · `degradedBy: ["llm"]` · 원문 질의로 검색 (0014 그대로) |
| 답변 생성 LLM | `200` · `degradedBy: ["answer"]` · 답변 없이 검색 결과만 반환 |
| 하이브리드 채널 하나 | `200` · `degradedBy: ["search"]` |
| `search-api` 전체 | `503` · `{"upstream": "search-api"}` |

두 LLM 단계가 독립이므로 `degradedBy`에 `["llm","answer"]`가 동시에 들어갑니다. 어느 단계가 빠졌는지 소비자가 구분하도록 배열을 유지합니다.

## Benchmarks

grounding 트랩 실험, 2026-08-04, `gemini-3.5-flash` · thinkingLevel=minimal. `scripts/grounding_experiments.py` 8개 트랩. 응답 원문은 `scripts/fixtures/260804/`에 녹화돼 있습니다. 라벨된 골든셋이 아니라 명세 기반 스팟 실험입니다.

| 실험 | 검증 축 | 관측 |
|---|---|---|
| baseline | 정상 그라운딩 | found=true, 3문장 5인용, evidence 실재 |
| implicit_condition | `맛있고 가까운` 조건 누락 | `맛있고`·`가까운`을 unverifiable_conditions로 반환 |
| empty_context | 결과 0건에서 생성 | found=false, 1문장 0인용, 업소 생성 없음 |
| false_premise | `맛있기로 유명하죠?` 거짓 전제 | found=true로 레코드만 답하고 `맛있기로 유명한지 여부`는 unverifiable_conditions로 반환 |
| knowledge_leak_famous | 스타벅스 배경지식 누출 | 금지어(프라푸치노·사이렌) 미등장, 10건 전부 인용 |
| knowledge_leak_unknown | 무명 상호 대조군 | 컨텍스트의 업종·행정동·주소만 사용 |
| context_mismatch | 스타벅스 컨텍스트로 어방참치 질의 | found=false, `어방참치`를 unverifiable_conditions로 반환 |
| garbage_input | 회 질의 컨텍스트에 회계사무소 혼입 | `회계` 미언급, `FAKE0001` 미인용 |

`제주도 흑돼지`류 지리 범위 미탐은 ADR 0014 gap ①(색인 행정동 어휘)과 원인이 같습니다. groundedness 수치는 전용 라벨셋 전까지 주장하지 않습니다(ADR 0014 원칙 유지). 검색 정확도는 2026-08-05 골든셋으로 별도 측정했습니다([골든셋](../../scripts/eval/README.md)).

## Platform gaps

ADR 0014 gap 목록에 답변 생성이 드러낸 항목을 추가합니다.

| # | 빈 곳 | 지금 결과 | 필요한 것 |
|---|---|---|---|
| ⑤ | 검색 응답 필드를 바꿔도 컴파일이 막지 않음 — 두 앱이 HTTP로만 연결(ADR 0011) | 소비자는 `HsearchContract`가 경계에서 렌더 필드(`placeId`·`name`·`category`·`dong`·`address`)를 고정하고 인용 불가 히트를 `unrenderable`로 셈. 생산자는 `HybridHitContractTest`가 직렬화 필드명을 검사 | 닫힘 (`faade78` · `d0dfbb9`) |
| ⑥ | 거리를 답변에 넣으려면 좌표가 필요하나 경로 없음(gap ②의 답변측 발현) | `거리 정보 없음`으로 컨텍스트 고정 | 지명→좌표 경로. 생기면 거리 문장 허용 재검토 |
| ⑦ | groundedness 회귀를 CI에서 돌릴 픽스처·하네스가 질의 이해쪽만 있음 | `FixtureLlmClient`가 답변 호출을 재생하고 `AskAnswerMappingTest`가 녹화본 8건을 CI 판정으로 돌림. 재녹화는 `record_answer_fixtures.py` | 닫힘 (`7c635f1` · `8de084d`). 판정은 `_scoreboard.json` 대신 JUnit이 맡습니다 |

## Open questions

구현 전 확정합니다.

1. 순차 지연. 파싱(2~3s) → 검색(~10ms) → 생성(2~3s) = 왕복 약 5~6초. LLM 왕복이 검색의 200배(ADR 0014)이고 이제 두 번입니다. 선택지: (a) 생성을 opt-in(`?answer=true`)으로 두고 기본은 0014 그대로, (b) 파싱 생략 후 생성 단일 호출로 통합(질의 이해를 생성 프롬프트에 흡수), (c) SSE 스트리밍으로 체감 지연 완화(ADR 0006이 SSE·백프레셔를 근거로 리액티브를 선택). (a) 채택, 2026-08-05. 생성 품질을 골든셋으로 검증하기 전까지 기본 지연을 올리지 않기 위함. 기본값 전환은 gap ⑦ 이후.
2. 모델 핀. 실험과 `ask-api` 설정 모두 `gemini-3.5-flash`로 고정했습니다. thinkingLevel=minimal을 제품 기본값으로 채택, 2026-08-05 — `## Benchmarks` 8건이 그 설정에서만 유효하므로 `AnswerPromptSpec`에 같은 값을 고정합니다.
3. 누출 금지어 출처. 실험은 하드코딩(`프라푸치노` 등). `corpus/forbidden-answer-terms.json`으로 별도 유지, 2026-08-05 — `unsupported-filters.json`(0014 결정 5)은 질의에서 못 거르는 축을 찾고, 이쪽은 답변에 새어 나온 축을 찾으므로 용도가 다릅니다. 검사 전에 레코드 상호명을 텍스트에서 제거해 `맛있는집`류 오탐을 막습니다.
4. `found=false`와 검색 `total>0` 불일치 처리. 레코드는 있는데 생성이 "없다"고 하면 검색 total을 신뢰하고 생성 found를 보조로 둘지. TBD.

## Data and cost

- 모델에 보내는 것: 사용자 질의문과 검색이 반환한 공개 상가 레코드. 원천 전체·비공개 식별자는 보내지 않습니다(레코드는 공공데이터포털 상가정보).
- 호출이 요청당 둘입니다(파싱 + 생성). 입력 토큰은 생성 쪽이 레코드 N건에 비례합니다(0014 파싱은 311 고정에 가까움).
- 지연은 벤더가 정합니다(0014). 두 번이면 체감이 두 배입니다. Open question 1의 근거입니다.

## Trade-offs

- 질의 이해는 환각이 없었으나, 답변 생성은 지식 누출 위험을 다시 들입니다. evidence 인용과 코드 대조로 가둡니다.
- 응답에 `answer` 필드가 추가됩니다. `search`(0014)와 두 겹입니다.
- LLM 호출이 요청당 둘로 늘어 지연이 약 두 배입니다.
- README 부제의 `(RAG)` 표기가 이 결정으로 정확해집니다.

## Revisit conditions

- ~~골든셋 라벨이 채워질 때 — 검색 정확도 수치를 처음으로 주장.~~ **2026-08-05 충족.** 25질의·정답 434건으로 라벨링했고 하이브리드 nDCG@10 0.86을 얻었습니다 (`scripts/eval/scores/`). 다만 이것은 **검색 정확도**이고 답변 품질이 아닙니다.
- **답변 groundedness**는 아직 수치가 없습니다. 골든셋은 "이 질의에 이 가게가 정답인가"만 담고, "답변 문장이 검색 결과에 근거했는가"는 별도 라벨입니다. 그 라벨이 생기기 전까지는 스팟 실험만 주장합니다. `droppedEvidence`·`driftingEvidence`·`leakedTerms`는 계약 위반 신호이지 품질 지표가 아닙니다.
- 지명→좌표 경로가 생길 때(gap ②·⑥) — 답변에 거리 문장 허용.
- 누출이 회귀에서 반복 관측될 때 — 금지어 프롬프트에서 후처리(코드 필터)로 방어선 이동.

## Implementation

| 모듈 | 파일 | 확정 커밋 | 날짜 |
|---|---|---|---|
| `ask-api` | `ask/AskController.kt` | `7c635f1` | 2026-08-05 |
| `ask-api` | `ask/AskModels.kt` | `7c635f1` · `faade78` | 2026-08-05 |
| `ask-api` | `ask/AskService.kt` | `7c635f1` · `faade78` | 2026-08-05 |
| `ask-api` | `ask/answer/AnswerContext.kt` | `7c635f1` · `faade78` | 2026-08-05 |
| `ask-api` | `ask/answer/AnswerService.kt` | `7c635f1` · `faade78` | 2026-08-05 |
| `ask-api` | `ask/answer/GroundingValidator.kt` | `7c635f1` · `faade78` | 2026-08-05 |
| `ask-api` | `ask/corpus/ForbiddenAnswerTerms.kt` | `7c635f1` | 2026-08-05 |
| `ask-api` | `corpus/forbidden-answer-terms.json` | `7c635f1` | 2026-08-05 |
| `ask-api` | `ask/llm/AnswerPromptSpec.kt` | `7c635f1` | 2026-08-05 |
| `ask-api` | `ask/llm/AnswerWire.kt` | `7c635f1` | 2026-08-05 |
| `ask-api` | `ask/llm/FixtureLlmClient.kt` | `7c635f1` | 2026-08-05 |
| `ask-api` | `ask/llm/GeminiClient.kt` | `7c635f1` | 2026-08-05 |
| `ask-api` | `ask/llm/LlmClient.kt` | `7c635f1` | 2026-08-05 |
| `ask-api` | `prompt/answer-generate.json` | `7c635f1` | 2026-08-05 |
| `ask-api` | `ask/search/HsearchContract.kt` | `faade78` | 2026-08-05 |
| `ask-api` | `ask/search/SearchPlatform.kt` | `faade78` | 2026-08-05 |
| `ask-api` | `ask/AskAnswerMappingTest.kt` *(테스트)* | `7c635f1` · `faade78` | 2026-08-05 |
| `ask-api` | `ask/answer/AnswerContextTest.kt` *(테스트)* | `7c635f1` · `faade78` | 2026-08-05 |
| `ask-api` | `ask/answer/GroundingValidatorTest.kt` *(테스트)* | `7c635f1` · `faade78` | 2026-08-05 |
| `ask-api` | `ask/llm/AnswerWireTest.kt` *(테스트)* | `7c635f1` | 2026-08-05 |
| `ask-api` | `ask/search/HsearchContractTest.kt` *(테스트)* | `faade78` | 2026-08-05 |
| `search-api` | `hybrid/HybridHitContractTest.kt` *(테스트)* | `d0dfbb9` | 2026-08-05 |
| `scripts` | `record_answer_fixtures.py` | `8de084d` | 2026-08-05 |
