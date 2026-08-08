# API Specification

호출 애플리케이션은 Elasticsearch·Qdrant·PostGIS를 직접 사용하지 않고, Query API와 Index API만 호출합니다.

- **Query API:** `http://localhost:8080`
- **Index API:** `http://localhost:8081`
- **Deployment:** 두 API는 서로 다른 애플리케이션으로 배포됩니다. ([Two Applications](#two-applications))
- **Response:** `application/json`
- **Recommendation / Distance Re-ranking:** 구현 예정 ([Planned](#planned))

## Endpoints

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | [`/v1/search`](#get-v1search) | 키워드 본문 검색 (BM25 + KOMORAN) |
| `GET` | [`/v1/suggest`](#get-v1suggest) | 자동완성 (edge_ngram) |
| `GET` | [`/v1/instant`](#get-v1instant) | 추천어 + 결과 미리보기 (팬아웃) |
| `GET` | [`/v1/vsearch`](#get-v1vsearch) | 벡터 검색 (Qdrant) |
| `GET` | [`/v1/hsearch`](#get-v1hsearch) | 하이브리드 결합 (키워드 + 벡터, RRF) |
| `POST` | [`/admin/reindex`](#post-adminreindex) | 무중단 전체 재색인 (키워드) |
| `POST` | [`/admin/reindex/incremental`](#post-adminreindexincremental) | 증분 색인 (키워드) |
| `POST` | [`/admin/vector/reindex`](#post-adminvectorreindex) | 무중단 전체 재색인 (벡터) |
| `POST` | `/admin/vector/reindex/incremental` | 증분 색인 (벡터) |
| `GET` | [`/admin/jobs/{jobId}`](#get-adminjobsjobid) | 색인 진행 상황·결과 조회 |
| `POST` | [`/admin/cleanup`](#post-admincleanup--post-adminvectorcleanup) | 옛 버전 정리 (동기) |
| `GET` | `/actuator/health` · `/actuator/prometheus` | 상태·지표 |
| `GET` | `/` | 세 채널 비교 페이지 (개발·시연용 정적 HTML) |

---

## `GET /v1/search`

키워드 기반 본문 검색 API입니다.
랭킹 규칙: [ADR 0009](adr/0009-keyword-ranking-and-fallback.md)

### Query Parameters

| Name | Type | Default | Description |
|---|---|---|---|
| `q` | string | **Required** | 검색어. 비어 있으면 빈 결과를 반환합니다. |
| `size` | int | `10` | 페이지 크기. `1~50` 범위로 보정됩니다. |
| `page` | int | `0` | 0부터 시작합니다. `0~99` 범위로 보정됩니다. |
| `sigungu` | string | – | 시군구 정확 일치 필터 (예: `강남구`) |
| `dong` | string | – | 행정동 정확 일치 필터 (예: `역삼1동`) |
| `category` | string | – | 분류 정확 일치 필터. 대·중·소분류 중 한 곳이라도 일치하면 통과합니다. 응답의 `category` 값을 그대로 되넣어도 매칭됩니다. |
| `lat`, `lon` | double | – | 기준 좌표. 두 값이 모두 있을 때만 위치 기반 기능이 활성화됩니다. 범위를 벗어나면 보정하지 않고 400을 반환합니다. |
| `radius` | int | `2000` | 검색 반경(m). 좌표가 지정된 경우에만 사용하며 `1~50,000` 범위로 보정됩니다. |
| `sort` | string | `relevance` | `relevance` \| `distance`. 좌표 없이 `distance`를 지정하면 `relevance`로 처리됩니다. |

범위를 벗어난 입력값은 오류를 반환하지 않고 가장 가까운 허용 범위의 값으로 보정(clamp)합니다.

좌표는 예외입니다. `lat=999`를 90으로 보정하면 북극을 기준으로 검색합니다. 그래서 보정하지 않고 400을 반환합니다. `NaN`과 무한대도 같습니다.

예를 들어 `size=100000` 요청이 그대로 실행되어 검색 클러스터에 과도한 부하를 주는 상황을 방지하기 위한 정책입니다.

### Response

```jsonc
{
  "query": "역삼 커피",
  "total": 159,        // 조건에 맞는 전체 건수
  "page": 0,
  "size": 1,
  "tookMs": 17,        // ES 가 잰 소요 시간. relaxed=true 면 완화 질의 몫만 잡히고 엄격 질의는 빠집니다
  "relaxed": false,    // true = 엄격 질의가 0건인 경우 조건을 완화해 재질의
  "hits": [
    {
      "placeId": "MA010120220803811519",
      "name": "역삼커피로스터스",
      "branch": null,
      "brand": null,     // 복원한 브랜드명. 상호명에서 브랜드가 빠진 가게에만 채워집니다
      "category": "카페",
      "address": "서울특별시 강남구 테헤란로25길 20",
      "sigungu": "강남구",
      "dong": "역삼1동",
      "lat": 37.5016243265646,
      "lon": 127.035657929451,
      "score": 53.23402,
      "distanceM": null,                        // sort=distance 일 때만 채워집니다 (미터)
      "highlight": ["<em>역삼커피</em>로스터스"],
      "label": "역삼커피로스터스"                // 표시용 문자열 (아래 Display Label)
    }
  ]
}
```

### Relaxed Fallback

기본 검색은 질의의 모든 단어가 일치하는 문서만 반환합니다(정밀도 우선).

결과가 없으면 매칭 조건을 완화하여 한 번 더 검색하며, 이 경우 응답의 `relaxed` 값이 `true`가 됩니다.

`relaxed: true` 결과는 `relaxed: false` 결과와 랭킹 기준이 다르므로 동일한 기준으로 비교할 수 없습니다.

완화 규칙과 임계값은 [ADR 0009](adr/0009-keyword-ranking-and-fallback.md)에 설명되어 있습니다.

---

### Display Label

검색 결과를 화면에 표시할 때는 `name` 대신 `label` 사용을 권장합니다.

`label`은 `brand`와 `name`으로 계산되는 표시용 문자열이며, 모든 응답에 포함됩니다.

| 조건 | `label` |
|---|---|
| `brand`가 없음 | `name` |
| `name`이 이미 브랜드명으로 시작 | `name` (브랜드명을 중복해서 붙이지 않음) |
| 그 외 | `brand` + 공백 + `name` |

예를 들어 `스타벅스`를 검색하면 실제 매칭된 문서의 `name`은 `개포동`일 수 있습니다.
`name`만 표시하면 올바른 결과가 잘못된 것처럼 보이므로 UI에서는 `label`을 사용하는 것을 권장합니다.

`highlight`는 `name`·`road_address`·`jibun_address`에서만 생성됩니다. 브랜드명만 일치한 결과는 빈 배열입니다.

`brand`는 원본 데이터의 값이 아니라 두 번째 데이터 원천(인허가 데이터)과 좌표를 이용해 복원한 값입니다.
현재 약 86개 문서에만 채워져 있습니다. 복원 절차는 [data-model.md](data-model.md#브랜드명-복원-place_brand)에서 확인할 수 있습니다.

### Examples

```bash
# 기본
curl -G localhost:8080/v1/search --data-urlencode "q=역삼 커피"

# 행정동 한정 (전체 688건 → 140건)
curl -G localhost:8080/v1/search --data-urlencode "q=커피" --data-urlencode "dong=역삼1동"

# 강남역 반경 300m, 가까운 순
curl -G localhost:8080/v1/search --data-urlencode "q=카페" \
  -d lat=37.4979 -d lon=127.0276 -d radius=300 -d sort=distance

# 브랜드 복원 확인
curl -G localhost:8080/v1/search --data-urlencode "q=스타벅스"
#  → total 84.  {"brand":"스타벅스", "name":"청담사거리", "label":"스타벅스 청담사거리",
#                "highlight":[]}
```

---

## `GET /v1/suggest`

검색창 자동완성 API입니다. 검색 API와는 별도의 인덱스를 사용합니다. ([ADR 0002](adr/0002-index-and-cluster-separation.md))

| 이름 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `q` | string | (필수) | 입력 중인 글자. 한 글자부터 |
| `size` | int | `8` | 1~20 으로 조정 |

```jsonc
{
  "query": "스타",
  "tookMs": 3,
  "items": [
    { "placeId": "MA010120220800206533", "name": "스타", "brand": null,
      "category": "컴퓨터/소프트웨어 소매업", "dong": "논현2동", "score": 5.5697155,
      "label": "스타" },
    { "placeId": "MA0106202501A0500235", "name": "포이", "brand": "스타벅스",
      "category": "카페", "dong": "개포4동", "score": 4.9,
      "label": "스타벅스 포이" }
  ]
}
```

자동완성은 `label`과 동일한 형태(`brand + name`)를 기준으로 색인합니다.
사용자가 입력한 문자열과 드롭다운에 표시되는 표시 문자열이 일치해야 하기 때문입니다.

```
'스'      → 스타 · 스팟 · 스윙 · 스텝 · 스펜
'스타'    → 스타 · 스타 · 스타벅스 포이 · 스타벅스 대치 · 스타벅스 청담
'스타벅'  → 스타벅스 포이 · 스타벅스 대치 · 스타벅스 청담 · 스타벅스 삼성 · 스타벅스 도곡역
```

동점인 경우 `placeId`를 기준으로 2차 정렬하여 결과 순서를 고정합니다.

같은 질의를 반복해도 자동완성 목록의 순서가 바뀌지 않습니다. 근거와 실측 결과는 [ADR 0009](adr/0009-keyword-ranking-and-fallback.md)를 참고하세요.

자동완성 응답에는 `total`을 포함하지 않습니다.

자동완성은 전체 결과 개수가 아니라 상위 후보만 필요하므로 전체 집계를 수행하지 않습니다.

---

## `GET /v1/instant`

검색창 입력 한 번에 필요한 결과를 한 번의 API 호출로 제공합니다.

서버는 자동완성(`GET /v1/suggest`)과 본문 검색(`GET /v1/search`)을 동시에 실행한 뒤 결과를 하나의 응답으로 합쳐 반환합니다. ([ADR 0006](adr/0006-api-runtime-reactive-vs-blocking.md))

| 이름 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `q` | string | (필수) | 입력 중인 문자열 |
| `suggestSize` | int | `8` | 자동완성 결과 개수 |
| `previewSize` | int | `5` | 검색 미리보기 결과 개수 |

```jsonc
{
  "query": "스타",
  "tookMs": 9,          // 두 API의 실행 시간을 더한 값이 아니라, 더 오래 걸린 요청의 실행 시간에 가깝습니다.
  "suggestions": [ /* /v1/suggest 의 items 와 동일 */ ],
  "preview":     [ /* /v1/search 의 hits 와 동일 */ ]
}
```

두 API를 병렬 호출하는 팬아웃(fan-out) 구조의 성능 측정 결과는 [ADR 0006](adr/0006-api-runtime-reactive-vs-blocking.md)를 참고하세요.

---

## `GET /v1/vsearch`

의미 기반 검색 API입니다. 검색어와 글자가 일치하지 않아도 의미가 유사한 문서를 반환합니다.
([ADR 0007](adr/0007-vector-engine-qdrant-vs-milvus.md), [ADR 0010](adr/0010-embedding-model-and-serving.md))

| 이름 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `q` | string | (필수) | 검색어 |
| `size` · `page` | int | `10` · `0` | `/v1/search`와 동일한 규칙을 적용합니다. |
| `sigungu` · `dong` · `category` | string | – | 정확 일치 필터. 벡터 검색 단계에서 적용됩니다. `category`는 키워드 채널과 같이 대·중·소분류를 대조합니다. |
| `lat`, `lon`, `radius` | – | `2000` | 반경 필터. 좌표가 있으면 `distanceM`도 함께 반환합니다. |

`sort`는 지원하지 않습니다. 벡터 검색 결과는 유사도 점수 순으로 반환됩니다. 자세한 이유는 [ADR 0003](adr/0003-hybrid-search-rrf-in-app-layer.md)를 참고하세요.

```bash
curl -G localhost:8080/v1/vsearch --data-urlencode "q=회 먹을 데"
```

```jsonc
{
  "query": "회 먹을 데",
  "total": 50,          // 후보 중 문턱을 넘은 수 — 키워드의 total 과 뜻이 다릅니다
                        //   후보는 최소 50건입니다. size 만큼만 남으면 total 이 size 와 같아집니다
  "page": 0, "size": 10,
  "tookMs": 5,
  "relaxed": false,     // 벡터 채널에는 완화 재질의가 없어 항상 false
  "hits": [
    { "placeId": "MA010120220810147236", "name": "먹어도", "category": "횟집",
      "address": null,          // 벡터 payload 에는 주소를 담지 않습니다
      "sigungu": "강남구", "dong": "삼성2동",
      "lat": 37.51518, "lon": 127.04282,
      "score": 0.872,           // 코사인 유사도 (0~1)
      "distanceM": null, "highlight": [],    // 걸린 글자가 없어 하이라이트도 없습니다
      "label": "먹어도" }
  ]
}
```

### Score Threshold

벡터 검색은 항상 가장 가까운 후보를 반환하므로, 최소 유사도 점수(`psp.vector.min-score`, 기본값 `0.84`)를 넘지 못하는 결과는 제외합니다. 이 문턱이 없으면 실제로는 적절한 결과가 없어도 가장 가까운 문서를 반환하게 됩니다.

단, **필터가 후보 집합(candidate set)을 실제로 줄인 경우에는 점수 문턱을 적용하지 않습니다.**

필터가 컬렉션 전체와 동일한 결과를 만드는 경우에는 문턱을 그대로 적용합니다. 반대로 필터로 후보가 실제로 줄어든 경우에는, 전체 코퍼스를 기준으로 정한 문턱을 그대로 적용하면 후보가 모두 제거될 수 있기 때문입니다.

문턱 값의 근거와 필터별 점수 분포 측정 결과는 [ADR 0010](adr/0010-embedding-model-and-serving.md)를 참고하세요.

---

## `GET /v1/hsearch`

키워드 검색과 벡터 검색 결과를 RRF(Reciprocal Rank Fusion)로 결합합니다. ([ADR 0003](adr/0003-hybrid-search-rrf-in-app-layer.md))

파라미터는 `/v1/search`와 같습니다 (`sort` 제외).

```bash
curl -G localhost:8080/v1/hsearch --data-urlencode "q=회 먹을 데"
```

```jsonc
{
  "query": "회 먹을 데",
  "total": 50,           // 결합된 후보 중 유니크 문서 수입니다. 전체 코퍼스에서의 매칭 수는 아닙니다.
  "page": 0, "size": 10,
  "tookMs": 16,
  "degraded": false,     // 채널 실패 또는 주소 채우기 실패로 검증되지 않은 결과가 섞였는지
  "channels": [          // 채널별 후보 수와 소요
    { "name": "keyword", "candidates": 0,  "tookMs": 13, "failed": false },
    { "name": "vector",  "candidates": 50, "tookMs": 5,  "failed": false }
  ],
  "hits": [
    { "placeId": "MA010120220810147236", "name": "먹어도", "category": "횟집",
      "address": "서울특별시 강남구 학동로56길 32",   // 벡터 검색에서만 찾은 문서도 Elasticsearch에서 조회해 채워 반환합니다.
      "sigungu": "강남구", "dong": "삼성2동",
      "lat": 37.51518, "lon": 127.04282,
      "score": 0.01639,                            // RRF 점수 (이 응답의 정렬 근거)
      "distanceM": null, "highlight": [], "label": "먹어도",
      "ranks":  { "vector": 1 },                   // 채널별 등수. 못 찾은 채널은 키가 없습니다
      "scores": { "vector": 0.872 }                // 채널별 원점수 (keyword=BM25, vector=코사인)
    }
  ]
}
```

각 채널에서 `psp.hybrid.candidates`(기본 `50`)개의 후보를 가져온 뒤,
`psp.hybrid.k`(기본 `60`)를 사용해 RRF로 결합합니다.

따라서 `total`은 최대 100이며, 페이지를 깊게 요청하면 더 이상 결과가 없습니다.

결합 수식과 후보 수를 선택한 근거는 [ADR 0003](adr/0003-hybrid-search-rrf-in-app-layer.md)를 참고하세요.

### Partial Response

한 채널이 실패해도 나머지로 답하고, 그 사실을 `degraded`와 `channels[].failed`로 알립니다.

| 상황 | 응답 |
|---|---|
| 정상 | `200` · `degraded:false` · total 95 |
| Qdrant 중단 | `200` · `degraded:true` · keyword `failed:false` / vector `failed:true` · total 50 |
| ES 중단 | `200` · `degraded:true` · keyword `failed:true` / vector `failed:false` · total 50 |
| 주소 채우기만 실패 | `200` · `degraded:true` · 두 채널 모두 `failed:false` |

Elasticsearch에 장애가 발생하면 주소 채우기(mget)를 호출하지 않습니다. 이 경우에는 벡터 payload 만으로 응답하므로 `address`가 `null`입니다.

### Vector Hits the Index No Longer Holds

주소 채우기가 성공했는데 그 문서가 없으면 색인에 없는 문서입니다. 벡터 채널만 아는 문서를 payload 로 되살리지 않고 응답에서 제외합니다. 원천에서 삭제되었거나 중복으로 억제된 장소의 벡터가 Qdrant 에 남아 있을 때 발생합니다.

제외한 건수는 `psp_query_stale_vectors`로 계측합니다. 이 값이 늘면 벡터 증분이 삭제를 따라가지 못하고 있다는 신호입니다.

주소 채우기 자체가 실패한 경우는 다릅니다. 색인이 답하지 않았으므로 없는 문서인지 알 수 없어 payload 를 그대로 쓰고 `degraded:true`로 표시합니다.

`degraded`는 백엔드 장애일 때만 켜집니다. 채널 코드 자체의 버그는 반쪽 응답으로 감추지 않고
`500`으로 드러냅니다. 감추면 recall 이 조용히 절반이 된 채로 지표는 정상으로 보입니다.

### Fields Used by Answer Generation

`ask-api`의 답변 생성([ADR 0015](adr/0015-ask-api-grounded-answer-generation.md))은 `hits[]`에서 아래 여섯 개 필드만 사용합니다.

`ranks`, `scores`, `lat`, `lon`, `highlight` 등 나머지 필드는 답변 생성 과정에서 사용하지 않습니다.

| 구분 | 필드 |
|---|---|
| 필수 | `placeId` · `name` · `label` |
| 선택 | `category` · `dong` · `address` |

LLM 에 넘기는 컨텍스트는 `label`로 씁니다. `name`은 원본 상호명이라 브랜드를 복원한 장소에서는 브랜드가 빠집니다 — 스타벅스 서울세관사거리점의 `name`은 `서울세관사거리`입니다. `label`로 쓰지 않으면 `스타벅스 어디 있어?` 라는 질의에 브랜드가 하나도 없는 목록이 컨텍스트로 갑니다.

`name`도 함께 넘깁니다. 근거 표류(`driftingEvidence`) 판정이 답변 문장에 장소 이름이 있는지 보는데, `label`만 쓰면 문장이 `서울세관사거리점`이나 `스타벅스`로만 적어도 표류로 잡힙니다. 둘 중 하나가 문장에 있으면 통과합니다.

필수 필드가 없는 문서는 답변에 인용할 수 없으므로 컨텍스트에서 제외됩니다. 제외된 건수는 응답의 `answer.unrenderableRecords`와 `ask-api`의 경고 로그에 기록됩니다.

선택 필드는 없으면 해당 정보만 생략됩니다. 예를 들어 Elasticsearch 장애로 `address`가 `null`인 경우에도 답변 생성은 계속 진행됩니다.

필드 이름을 변경하는 경우에는 `ask-api`의 `HsearchContract`도 함께 수정해야 합니다.

두 애플리케이션은 HTTP 계약으로만 연결되어 있으므로([ADR 0011](adr/0011-module-split-and-index-contract.md)), 컴파일 단계에서는 이러한 변경을 검출할 수 없습니다.

---

## Upstream Failures

Elasticsearch·Qdrant 장애와 애플리케이션 버그는 서로 다른 상태 코드로 구분합니다.

- **`503 Service Unavailable`**: 백엔드 장애. 기다리거나 재시도하면 복구될 수 있습니다.
- **`500 Internal Server Error`**: 애플리케이션 버그. 코드를 수정해야 해결됩니다.

| 엔드포인트 | 백엔드 장애 | 채널 코드 버그 |
|---|---|---|
| `/v1/search` · `/v1/suggest` · `/v1/instant` · `/v1/vsearch` | `503` | `500` |
| `/v1/hsearch` | `200` · `degraded: true` ([Partial Response](#partial-response)) | `500` |

```jsonc
// 503 Service Unavailable
{
  "upstream": "elasticsearch",   // 또는 "qdrant"
  "message": "cannot reach the elasticsearch upstream — not indexed yet, or temporarily down"
}
```

백엔드 장애 여부는 `UpstreamFailure`가 일관되게 판정합니다.

| 상황 | 판정 |
|---|---|
| 연결 거부 · 타임아웃 | 장애 |
| Elasticsearch·Qdrant `5xx` | 장애 |
| `429` (과부하, circuit breaker) | 장애 |
| `404` (별칭 또는 컬렉션이 아직 없음) | 장애 |
| 응답 본문 파싱 또는 디코딩 실패 | 장애 |
| Elasticsearch·Qdrant `400` (잘못된 요청 전송) | 버그 |
| 문서 스키마 불일치 (`JsonpMappingException`) | 버그 |
| 우리 응답 직렬화 실패 (`EncodingException`) | 버그 |

`404`를 장애로 분류하는 이유는 초기 배포 직후 아직 색인이 수행되지 않아 별칭이나 컬렉션이 존재하지 않을 수 있기 때문입니다. 이 경우는 코드 수정이 아니라 색인이 완료되면 해결되는 상태입니다.

반대로 스키마 불일치를 `503`으로 처리하면 영구적인 애플리케이션 버그를 일시적인 장애로 오인하게 됩니다. 그러면 호출 측은 해결되지 않을 요청을 계속 재시도하고, 실제 원인도 `500`으로 드러나지 않습니다.

예외가 다른 예외에 감싸져 있더라도 원인(cause) 체인을 따라 판정합니다. 예를 들어 임베딩 추론 실패가 `IOException`으로 감싸져 있으면 `503`으로 처리합니다.

---

## `POST /admin/reindex`

재색인 요청은 즉시 `202 Accepted`와 `jobId`를 반환하며, 실제 작업은 백그라운드에서 계속 진행됩니다.
`curl`을 끊어도 색인은 영향받지 않습니다 ([ADR 0013](adr/0013-indexer-runtime-spring-batch.md)).

무중단 전체 재색인입니다. 새 인덱스를 생성한 뒤 alias만 원자적으로 전환하여 검색 중단 없이 교체합니다.
64,239건 기준 15.6초가 소요되었으며(2026-07-25 실측), 그동안 검색 서비스는 중단되지 않습니다.

```jsonc
// 202 Accepted
{ "jobId": 12, "jobName": "keywordRebuild", "status": "STARTING", "poll": "/admin/jobs/12" }
```

## `POST /admin/reindex/incremental`

체크포인트 이후 변경된 데이터만 반영합니다. 같은 데이터를 여러 번 실행해도 결과가 동일한 멱등(idempotent) 작업입니다. 소프트 삭제된 행은 인덱스에서 지웁니다.
응답은 위와 같은 접수증 형태이고 `jobName`이 `keywordIncremental` 입니다.

## `POST /admin/vector/reindex`

벡터 컬렉션을 무중단으로 새로 만듭니다. 키워드 색인과 따로 도는 것이 핵심입니다.
임베딩 추론이 훨씬 느려서(64,239건 8분 32초 vs ES bulk 15.6초, 2026-07-25 실측) 한 파이프라인에 묶으면 느린 쪽이 주기를 결정합니다.
키워드와 벡터는 각각 독립적인 체크포인트를 사용합니다.

`POST /admin/vector/reindex/incremental`은 벡터 체크포인트 이후 바뀐 것만 다시 임베딩합니다.

## `GET /admin/jobs/{jobId}`

진행 상황과 결과입니다. 완료된 작업도 조회할 수 있습니다.
이력이 Postgres 의 `BATCH_*` 테이블에 남아 색인기를 재시작한 뒤에도 이전 실행이 몇 건이었는지 조회됩니다.

```jsonc
{
  "jobId": 12, "jobName": "keywordRebuild", "status": "COMPLETED",
  "running": false, "elapsedMs": 17204,
  // 아래 통계는 애플리케이션이 계산한 값이 아니라, Spring Batch가 각 chunk 커밋 시 DB에 기록한 실행 결과입니다.
  "steps": [
    { "name": "keywordRebuild.prepare", "status": "COMPLETED", "read": 0,     "written": 0,     "commits": 1,  "rollbacks": 0 },
    { "name": "keywordLoad",            "status": "COMPLETED", "read": 64239, "written": 64239, "commits": 33, "rollbacks": 0 },
    { "name": "keywordRebuild.promote", "status": "COMPLETED", "read": 0,     "written": 0,     "commits": 1,  "rollbacks": 0 }
  ],
  // Spring Batch 통계 외에 애플리케이션이 추가한 요약 정보입니다.
  "summary": {
    "searchIndex": "place_search_20260725163000",
    "suggestIndex": "place_suggest_20260725163000",
    "read": "64239", "upserted": "64239", "deleted": "0",
    "checkpoint": "2026-07-25T07:30:12.481Z",
    "removed": "place_search_20260724043000,place_suggest_20260724043000"
  },
  "failure": null
}
```

`GET /admin/jobs`는 최근 실행 이력을 반환합니다.

| Name | Type | Default | Description |
|---|---|---|---|
| `name` | string | `keywordRebuild` | job 이름 |
| `limit` | int | `10` | 반환할 이력 수. `1~50` 범위로 보정됩니다. |

## `POST /admin/cleanup` · `POST /admin/vector/cleanup`

이 두 엔드포인트만 동기(`200 OK`)로 동작합니다.

현재 alias를 기준으로 더 이상 사용하지 않는 이전 버전만 삭제하는 작업이라 수 밀리초 내에 완료되며, 별도의 Job 이력을 남기지 않습니다.

```jsonc
{ "kept": 2, "removed": ["place_search_20260723043000"] }
```

관리 엔드포인트에는 인증이 없습니다. 로컬 개발 환경 전용이기 때문입니다.

운영 환경이라면 관리자 인증과 레이트 리밋이 필요합니다. ([architecture-review.md #9](architecture-review.md#9-adminreindex가-무인증이다--deferred-완화만))

또한 이 엔드포인트는 `indexer-batch`(8081)에만 존재합니다. 외부 트래픽을 처리하는 `search-api` JAR에는 관련 클래스 자체가 포함되지 않습니다. ([ADR 0011](adr/0011-module-split-and-index-contract.md))

---

## Metrics

Prometheus 형식으로 `/actuator/prometheus`에서 제공합니다.

| Metric | Tags | Description |
|---|---|---|
| `psp_query_latency_seconds` | `channel=keyword\|suggest\|vector\|hybrid`, `outcome` | 채널별 질의 지연 시간과 성공/실패를 측정합니다. |
| `psp_query_stage_latency_seconds` | `channel`, `stage=embed\|ann\|narrow\|keyword\|vector\|fuse\|hydrate` | 채널 내부 단계를 분리해 측정합니다. 벡터 검색이 느릴 때는 임베딩인지 ANN 탐색인지, 하이브리드 검색이 느릴 때는 어느 채널이 병목인지 구분할 수 있습니다. |
| `psp_index_lag_seconds` | `pipeline=keyword\|vector` | 원천 데이터의 최신 변경 시각과 색인 체크포인트의 차이(초)입니다. `0`이면 최신 상태이며, `-1`이면 체크포인트가 아직 없습니다. |

질의 로그(`logs/query.log`) 형식은 [ADR 0009](adr/0009-keyword-ranking-and-fallback.md)를 참고하세요.

## `ask-api` Metrics

`ask-api`(8082)에서 노출하는 메트릭입니다. ([ADR 0014](adr/0014-ask-api-llm-query-understanding.md))

| Metric | Tags | Description |
|---|---|---|
| `psp_ask_latency_seconds` | `stage=llm\|search`, `outcome` | 단계별 지연 시간. LLM 호출과 `/v1/hsearch` 호출을 각각 측정합니다. |
| `psp_ask_degraded_total` | `stage=llm`, `reason=config\|rate_limit\|upstream\|request\|payload\|unreachable` | LLM 장애로 원문 질의를 그대로 검색한 횟수입니다. `reason=config`는 API 키나 설정 문제로, 재시도로 해결되지 않습니다. |
| `psp_ask_degraded_total` | `stage=search`, `reason=channel` | 하이브리드 검색에서 채널 하나 이상이 degraded 상태로 응답한 횟수입니다. |

## Two Applications

`/v1/*`와 `/admin/*`는 서로 다른 애플리케이션으로 빌드·배포됩니다. 하나의 애플리케이션을 설정으로 나누는 구조가 아닙니다. ([ADR 0011](adr/0011-module-split-and-index-contract.md))

| Application | Port | Includes | Excludes |
|---|---:|---|---|
| `search-api` | `8080` | `/v1/search` · `/v1/suggest` · `/v1/instant` · `/v1/vsearch` · `/v1/hsearch` | `/admin/*`, PostGIS 연결 |
| `indexer-batch` | `8081` | `/admin/*` | `/v1/*` |

`Excludes`는 비활성화(disabled)가 아니라, 해당 JAR에 관련 클래스 자체가 포함되지 않는다는 의미입니다.

```bash
cd services
./gradlew :search-api:bootRun      # Query API (8080)
./gradlew :indexer-batch:bootRun   # Indexer (8081)

# Keyword-only mode:
# disables /v1/vsearch and /v1/hsearch, and does not load the embedding model.
./gradlew :search-api:bootRun --args='--psp.vector.enabled=false'
```

남아 있는 런타임 스위치는 `psp.vector.enabled`와 `psp.hybrid.enabled` 두 개이며, 기본값은 모두 `true`입니다. `psp.hybrid.enabled=false`는 `/v1/hsearch`만 내리고 `/v1/vsearch`는 유지합니다.

`search-api`는 시작 시 색인 데이터가 현재 애플리케이션과 동일한 계약(contract)으로 생성되었는지 검증합니다. 문서 스키마 버전, 형태소 분석기 지문, 임베딩 모델, 임베딩 차원 중 하나라도 다르면 기동하지 않습니다.

형태소 분석기 지문은 본문 검색과 자동완성 인덱스 각각에 대해 대조합니다. 형태소 사전만 교체하고 재색인하지 않은 경우가 여기서 걸립니다.

```
[search] the indexed data and this process disagree on the contract.
  - document schema version: indexed=1, querying=2
  in this state nothing throws — the results just go silently wrong.
  → run a full reindex with POST /admin/reindex on the indexer (indexer-batch), then start this app again.
```

이 경우를 경고가 아니라 기동 실패로 처리하는 이유는, 계약 불일치가 즉시 드러나지 않기 때문입니다.

애플리케이션은 `200 OK`를 반환하고 로그에도 오류가 남지 않지만, 검색 결과만 조용히 잘못될 수 있습니다.

단, 계약 정보 자체가 없는 인덱스(계약 검증 도입 이전에 생성된 인덱스)는 하위 호환성을 위해 경고만 출력하고 기동합니다.

## Planned

| Phase | Planned Features |
|---|---|
| 7 | 거리 기반 재랭킹 · 추천 엔드포인트 · 쿠키리스 세션 ([ADR 0004](adr/0004-cookieless-session-model.md), [ADR 0005](adr/0005-cold-start-and-recommend-strategy.md)) |

## References

- [ADR](adr/) — 설계 결정
- [architecture.md](architecture.md) — 아키텍처
- [data-model.md](data-model.md) — 데이터 모델·원천 출처
- [search-modes-comparison.md](search-modes-comparison.md) — 세 검색 방식 비교
- [glossary.md](glossary.md) — 용어 사전
