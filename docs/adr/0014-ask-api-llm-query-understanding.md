# ADR 0014 — 자연어 질의 이해를 별도 모듈 `ask-api` 에, 검색은 HTTP 로 호출

- **상태:** Accepted
- **날짜:** 2026-08-04
- **관련:** ADR 0003 (이 모듈이 부르는 엔드포인트), ADR 0011 (모듈 분리 기준), ADR 0009 (키워드 완화 폴백), ADR 0006 (질의 런타임)

## Context

검색 엔진에 LLM 을 붙이는 방법은 크게 두 가지입니다.

| 레인 | 하는 일 | LLM 출력 | 환각 위험                             |
|---|---|---|---------------------------------------|
| **1. 질의 이해** | 자연어 → 구조화된 검색 요청 | **질의** | 환각 없음. 잘못된 요청 생성 시 검색 불가 |
| 2. 답변 생성 | 검색 결과 → 문장 | **답변** | 근거 문서가 필요                      |

이 프로젝트에서는 1레인을 구현합니다.
코퍼스에 답변의 근거가 될 문장이 없기 때문입니다.

## Decision

| # | 결정 | 근거 |
|---|---|---|
| 1 | 별도 모듈 `ask-api`. `/v1/hsearch` 를 HTTP 로 호출 | 내부 타입을 공유하면 플랫폼 계약을 검증할 수 없음 |
| 2 | Gemini Flash · `responseSchema` · `temperature 0` | 스키마 강제로 파서 방어 표면 축소. temperature 0 으로 편차를 줄이되, 회귀는 픽스처로 고정 |
| 3 | 설정 오류는 기동 실패, 런타임 장애는 degraded | 배포 시점 실패가 트래픽 시점 실패보다 쌈 (ADR 0011 결정 4) |
| 4 | 좌표 해석 없음. `geo_anchor` 는 원문 유지 | 지오코더 재료가 저장소에 없음. LLM 에 좌표를 물으면 1레인의 안전성이 깨짐 |
| 5 | 코퍼스에 없는 속성은 어휘 대조로 판정해 `applied.unsupported` 로 반환 | `expects_empty` 는 불리언이라 무엇이 버려졌는지 알 수 없음. 속성 집합이 스키마로 닫혀 있어 어휘로 판정 가능 |

### 1. Module boundary

```
ask-api  ──HTTP──▶  search-api  /v1/hsearch
   │                     │
   └─ Gemini             └─ ES · Qdrant
```

| | 같은 앱에 넣기 | 별도 모듈 + HTTP |
|---|---|---|
| 코드량 | 적음 | 모듈 하나 · WebClient · DTO |
| 플랫폼 계약 검증 | 안 됨 | 응답 JSON 만 보고 맞춤 |
| 장애 격리 | LLM 호출이 검색 프로세스 안 | 다른 프로세스 |

이 모듈은 내부 모듈에는 의존하지 않습니다.
실제로 이 모듈이 보는 필드는 셋뿐입니다.

| 필드 | 용도 |
|---|---|
| `degraded` | 반쪽 응답 여부를 전파 |
| `total` | 결과 유무 |
| `hits[]` | 파싱하지 않고 `JsonNode` 원문으로 통과 |

ADR 0011 기준으로는 이 필드들은 core 후보입니다.
필드명이 달라도 프로세스가 죽지 않고 결과만 달라지기 때문입니다.

그래도 `search-core`를 사용하지 않았습니다.
이 모듈의 목적이 플랫폼 계약을 실제 HTTP 응답으로 검증하는 것이기 때문입니다.

### 2. LLM

| | Gemini Flash | 프롬프트로 JSON 요청 |
|---|---|---|
| 구조화 출력 | `responseSchema` 로 스키마 강제 | 마크다운 펜스·설명이 섞임 |
| 한국어 | 실사용 수준 | 모델마다 다름 |

SDK 대신 `WebClient` 로 REST 를 직접 호출합니다. 사용하는 엔드포인트가 `generateContent` 하나이고,
SDK 의 블로킹·자체 비동기 모델을 WebFlux + 코루틴 위에 얹으면 계층이 한 겹 더해집니다.
(`QdrantSearchStore` 와 같은 판단 — ADR 0013).

`LlmClient` 구현은 둘입니다.

| 구현 | 언제                                                                                        |
|---|---------------------------------------------------------------------------------------------|
| `GeminiClient` | 실제 호출 (`psp.ask.llm=gemini`)                                                            |
| `FixtureLlmClient` | 녹화 응답 재생 (`psp.ask.llm=fixture`) — **CI 는 `FixtureLlmClient`만 사용합니다.** |

### 3. Failure handling

API 키는 `GEMINI_API_KEY` 에서 읽고 저장소에 넣지 않습니다. 부재 시 애플리케이션이 시작되지 않습니다.

```
GEMINI_API_KEY is not set. export it, or start with --psp.ask.llm=fixture to replay recorded responses.
```

| 무엇이 죽었나 | 응답 |
|---|---|
| LLM | `200` · `degradedBy: ["llm"]` · 원문 질의로 검색 |
| 하이브리드 채널 하나 | `200` · `degradedBy: ["search"]` (하류 `degraded` 를 전파) |
| `search-api` 전체 | `503` · `{"upstream": "search-api", ...}` |

LLM 이 죽었을 때의 결과는 `/v1/hsearch` 를 직접 부른 것과 같습니다. 
실패 시 LLM 만 빠지고 기존 검색만 수행합니다.
LLM 장애와 검색 채널 장애를 구분할 수 있도록 `degradedBy`를 배열로 뒀습니다.

### 4. `geo_anchor` and `category_hint`

현재 단계에선 필터로 옮기지 않고 질의 문자열에 포함합니다.

```
{ keyword: "공부할 곳", category_hint: "스터디카페", geo_anchor: "역삼동" }
  → q = "역삼동 공부할 곳 스터디카페"
```

`dong.txt`·`category_small.txt` 는 키워드 검색 대상 필드이기에 문자열 검색에는 걸립니다. (`PlaceQueries.SEARCH_FIELDS`) 
무엇이 파라미터로 못 옮겨졌는지는 응답의 `applied.unmapped` 로 반환하게 합니다.

### 5. Unsupported filters

코퍼스에 데이터가 없어 거를 수 없는 속성은 이름을 `applied.unsupported` 로 반환합니다.
검색 결과는 좁히지 않습니다.

```
"평점 4.5 이상 카페"  →  q = "카페" · unsupported = ["평점"]   (하이브리드 89건)
```

판정은 원문 질의와 `corpus/unsupported-filters.json` 의 어휘를 대조합니다.

| | `expects_empty` (LLM) | 어휘 대조 (코드) |
|---|---|---|
| 출력 | 불리언 | 속성 이름 |
| 결정성 | `temperature 0` 이 결정적이지 않음 | 결정적 |
| 프롬프트 변경 시 | 픽스처 27건 재녹화 | 무관 |

**실측** (2026-08-04, 강남 6.4만)

| 항목 | 값 |
|---|---|
| 회귀 20개 오탐 | 0 |
| 상호명 116개 오탐 | 1 (`씨유강남거평점`) |
| 골든셋 트랩 탐지 | 4/5 |

미탐 1건 `제주도 흑돼지 맛집` 은 지리 범위라 색인의 행정동 어휘가 필요합니다(Platform gap ①).

## Platform gaps

ask-api 를 붙여 보니 플랫폼에서 비어 있는 부분이 보였습니다.
이번 구현에서는 메우지 않고 기록만 남깁니다.

| # | 빈 곳 | 지금 결과 | 필요한 것 |
|---|---|---|---|
| ① | 정확 일치 필터(`dong`·`category`)의 어휘를 소비자가 알 경로가 없음 | 필터를 아예 쓰지 않음 | 어휘 목록 경로(예: `GET /v1/facets`). 목록이 있으면 소비자가 스키마 `enum` 으로 강제 가능 |
| ② | 좌표 없이 반경만 주는 방법이 없음 (`SearchRequest.of`) | `radius_m` 을 버림 | 지명 → 좌표 경로. ①이 풀리면 함께 풀림 |
| ③ | recall 상한이 플랫폼 상수에 걸림 — `size` 50, `total` 최대 100 (ADR 0003) | 기본 20 으로 사용 | 강남구 6만 건에서는 문제 없음. 서울 53.7만이면 재검토 |
| ④ | `/v1/hsearch` 응답에 `relaxed` 가 없음 (ADR 0009) | 완화 결과인지 구분 불가 | 결합 응답에 채널별 `relaxed` 노출 |

## Open questions

아직은 1레인이 실제로 검색 품질을 높였는지 판단할 데이터가 없습니다. ADR 0003 이 "합쳐서 더 좋아졌나"를 증명하지 못한 것과 같습니다. `scripts/golden_set.yaml` 에 회귀 질의 20개와 0건이 정답인 트랩
질의 5개(평점·영업시간·배달·가격·지역 범위)를 라벨 없이 만들어 두었습니다.
라벨을 만들기 전까지는 정확도 수치를 주장하지 않습니다.

`category_hint` 가 키워드 채널의 `AND` 조건을 좁혀 0건을 늘릴 가능성은 **미실측**입니다.

**트랩 5건은 실제로 0건을 반환하지 않습니다.** 합성 질의를 `/v1/hsearch` 에 넣어 잰 값입니다
(2026-08-04, 강남 6.4만).

| 트랩 | 합성 `q` | 하이브리드 |
|---|---|---|
| 평점 4.5 이상 카페 | `카페` | 89 |
| 지금 문 연 약국 | `약국` | 91 |
| 배달 되는 치킨집 | `치킨집` | 1 |
| 1만원 이하 파스타 | `파스타 이탈리아음식` | 33 |
| 제주도 흑돼지 맛집 | `제주도 흑돼지 맛집 돼지고기구이` | 50 |

`expects_empty` 와 `unsupported` 는 결과를 좁히지 않고 알리기만 합니다. 0건이 옳은 응답인지는
골든셋 라벨을 채운 뒤 판단합니다.

**`temperature 0` 은 결정적이지 않습니다.** 바이트가 같은 입력을 8회 보내 `세탁소` → `세타포` 7회,
`세탁소` 1회를 관측했습니다(2026-08-04, `gemini-3.5-flash`). 흔한 단어가 다수 회차에서 다른 글자로
바뀝니다. 파싱 결과를 그대로 믿을 수 없다는 뜻이고, 회귀 측정이 픽스처 위에서만 성립하는 이유입니다.
이런 오파싱을 잡는 것이 골든셋의 일입니다.

## Data and cost

- 모델에 보내는 것은 **사용자 질의문뿐**입니다. 검색 결과와 코퍼스 문서는 보내지 않습니다.
  코퍼스도 공개된 상업시설 정보입니다(공공데이터포털 상가정보 · 행안부 인허가).
- 질의 로그는 `search-api` 쪽에 남고 질의문 외 식별자를 남기지 않습니다(`api-spec.md`).
- **호출당 실측** (2026-08-04, `gemini-3.5-flash`, 골든셋 25건)

  | 항목 | 평균 | 최소~최대 | 표본 |
  |---|---|---|---|
  | 입력 토큰 | 311 | 308~316 | 25 |
  | 출력 + thinking 토큰 | 319 | 167~1,114 | 25 |
  | 왕복 지연 | 2.0~3.0초 | — | 7 |

  입력이 311로 고정에 가까운 것은 시스템 프롬프트가 대부분이기 때문입니다. 지연은 별도로 잰
  7회 표본이고, 같은 질의가 30초·60초 타임아웃을 낸 경우도 2회 있었습니다.
- **LLM 왕복이 검색의 200배가 넘습니다.** 하이브리드 중앙값 9.7ms 대비 2~3초입니다.
  사용자 체감의 거의 전부가 LLM 이고, `/v1/ask` 의 지연은 이 모듈이 아니라 벤더가 정합니다.

## Trade-offs

- 데모에 띄울 앱이 하나 늘었습니다 — `search-api`(8080) · `ask-api`(8082).
- HTTP 호출이 하나 더 생깁니다. 대신 실제 플랫폼 계약을 그대로 검증할 수 있습니다.
- 응답이 두 겹입니다. `search` 필드 안에 `/v1/hsearch` 응답이 통째로 들어갑니다.

## Revisit conditions

- **코퍼스에 문장이 생길 때** — 2레인이 성립하고 groundedness 평가가 중심이 됩니다.
- **LLM 지연이 검색 지연을 압도할 때** — 하이브리드 중앙값 9.7ms 대비 LLM 왕복이 수백 ms 라면 그때는 파싱 결과 캐시를 먼저 검토합니다.
- **필터 어휘 경로(①)가 생길 때** — 질의문 합성이 정확 일치 필터로 바뀝니다.

## Implementation

| 모듈 | 파일 | 확정 커밋 | 날짜 |
|---|---|---|---|
| `ask-api` | `ask/AskApiApplication.kt` | `5d37107` | 2026-08-04 |
| `ask-api` | `ask/AskController.kt` | `5d37107` | 2026-08-04 |
| `ask-api` | `ask/AskModels.kt` | `5d37107` | 2026-08-04 |
| `ask-api` | `ask/AskQueryPlanner.kt` | `5d37107` | 2026-08-04 |
| `ask-api` | `ask/AskService.kt` | `5d37107` · `b57d6bd` | 2026-08-04 |
| `ask-api` | `ask/corpus/UnsupportedFilters.kt` | `b57d6bd` | 2026-08-04 |
| `ask-api` | `corpus/unsupported-filters.json` | `b57d6bd` | 2026-08-04 |
| `ask-api` | `ask/llm/AskPromptSpec.kt` | `5d37107` | 2026-08-04 |
| `ask-api` | `ask/llm/FixtureLlmClient.kt` | `5d37107` | 2026-08-04 |
| `ask-api` | `ask/llm/GeminiClient.kt` | `5d37107` | 2026-08-04 |
| `ask-api` | `ask/llm/GeminiWire.kt` | `5d37107` | 2026-08-04 |
| `ask-api` | `ask/llm/LlmClient.kt` | `5d37107` | 2026-08-04 |
| `ask-api` | `ask/search/SearchPlatform.kt` | `5d37107` | 2026-08-04 |
| `ask-api` | `ask/web/SearchPlatformErrorHandler.kt` | `5d37107` | 2026-08-04 |
| `ask-api` | `ask/AskMappingTest.kt` *(테스트)* | `5d37107` | 2026-08-04 |
| `ask-api` | `ask/AskQueryPlannerTest.kt` *(테스트)* | `5d37107` | 2026-08-04 |
| `ask-api` | `ask/corpus/UnsupportedFiltersTest.kt` *(테스트)* | `b57d6bd` | 2026-08-04 |
| `ask-api` | `ask/llm/GeminiWireTest.kt` *(테스트)* | `5d37107` | 2026-08-04 |