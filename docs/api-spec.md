# API 명세 — 검색 플랫폼 공통 입구

호출 측은 ES·Qdrant·PostGIS 를 직접 다루지 않고 이 API 만 사용합니다.

- 기준 URL(로컬): `http://localhost:8080` — 질의(`/v1/*`).
  색인(`/admin/*`)은 **다른 앱**이라 `http://localhost:8081` 입니다 ([앱은 둘이다](#앱은-둘이다-adr-0011))
- 응답: `application/json` (UTF-8)
- 구현 상태: **6단계까지 실제 동작.** 추천·거리 재랭킹은 7단계 예정 (아래 [예정](#예정) 참고)

## 목차

| 메서드 | 경로 | 설명 | 상태 |
|---|---|---|---|
| `GET` | [`/v1/search`](#get-v1search) | 키워드 본문 검색 (BM25 + KOMORAN) | ✅ |
| `GET` | [`/v1/suggest`](#get-v1suggest) | 자동완성 (edge_ngram) | ✅ |
| `GET` | [`/v1/instant`](#get-v1instant) | 추천어 + 결과 미리보기 (팬아웃) | ✅ |
| `GET` | [`/v1/vsearch`](#get-v1vsearch) | 벡터(의미) 검색 (Qdrant) | ✅ |
| `GET` | [`/v1/hsearch`](#get-v1hsearch) | **하이브리드 결합** (키워드 + 벡터, RRF) | ✅ |
| `POST` | [`/admin/reindex`](#post-adminreindex) | 무중단 전체 재색인 (키워드) — **202 접수증** | ✅ |
| `POST` | [`/admin/reindex/incremental`](#post-adminreindexincremental) | 증분 색인 (키워드) — **202** | ✅ |
| `POST` | [`/admin/vector/reindex`](#post-adminvectorreindex) | 무중단 전체 재색인 (벡터) — **202** | ✅ |
| `POST` | `/admin/vector/reindex/incremental` | 증분 색인 (벡터) — **202** | ✅ |
| `GET` | [`/admin/jobs/{jobId}`](#get-adminjobsjobid) | 색인 진행 상황·결과 조회 | ✅ |
| `POST` | [`/admin/cleanup`](#post-admincleanup--post-adminvectorcleanup) | 옛 버전 정리 (동기) | ✅ |
| `GET` | `/actuator/health` · `/actuator/prometheus` | 상태·지표 | ✅ |
| `GET` | `/` | 세 채널 비교 페이지 (개발·시연용 정적 HTML) | ✅ |

---

## `GET /v1/search`

키워드 본문 검색. 랭킹 규칙은 [ADR 0009](adr/0009-keyword-ranking-and-fallback.md)에 있습니다.

### 요청 파라미터

| 이름 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `q` | string | (필수) | 검색어. 비면 빈 결과를 돌려준다 |
| `size` | int | `10` | 페이지 크기. **1~50으로 잘린다** |
| `page` | int | `0` | 0부터. **0~99로 잘린다** |
| `sigungu` | string | – | 시군구 정확 일치 필터 (예: `강남구`) |
| `dong` | string | – | 행정동 정확 일치 필터 (예: `역삼1동`) |
| `category` | string | – | 대분류 정확 일치 필터 |
| `lat`, `lon` | double | – | 기준 좌표. **둘 다 있어야** 위치 기능이 켜진다 |
| `radius` | int | `2000` | 반경(m). 좌표가 있을 때만. **1~50,000으로 잘린다** |
| `sort` | string | `relevance` | `relevance` \| `distance` (좌표 없이 `distance`면 `relevance`로 되돌린다) |

범위를 벗어난 값은 **에러 대신 가장 가까운 합법값으로 접습니다.** `size=100000` 한 번이 그대로
클러스터 부하가 되는 것을 막기 위한 첫 방어선입니다.

### 응답

```jsonc
{
  "query": "역삼 커피",
  "total": 159,        // 조건에 맞는 전체 건수
  "page": 0,
  "size": 1,
  "tookMs": 17,        // ES가 잡은 소요 시간
  "relaxed": false,    // true = 엄격 질의가 0건이라 조건을 풀어 재질의함
  "hits": [
    {
      "placeId": "MA010120220803811519",
      "name": "역삼커피로스터스",
      "branch": null,
      "brand": null,     // 복원한 브랜드명. 상호명에서 브랜드가 빠져 있던 가게에만 채워진다 (아래 참고)
      "category": "카페",
      "address": "서울특별시 강남구 테헤란로25길 20",
      "sigungu": "강남구",
      "dong": "역삼1동",
      "lat": 37.5016243265646,
      "lon": 127.035657929451,
      "score": 53.23402,
      "distanceM": null,                        // sort=distance 일 때만 채워진다 (미터)
      "highlight": ["<em>역삼커피</em>로스터스"]  // 어느 글자가 걸렸는지
    }
  ]
}
```

### `relaxed` 를 반드시 확인한다

기본은 **질의의 모든 단어**를 요구합니다(정밀도 우선). 그래서 0건이 나오면 조건을 풀어 한 번 더
질의하는데, 그때 `relaxed: true` 가 됩니다. **조건이 다른 결과**이므로 UI에서 "정확히 일치하는
결과가 없어 유사 결과를 보여줍니다" 같은 안내를 붙이는 것을 권합니다.

### 예시

```bash
# 기본
curl -G localhost:8080/v1/search --data-urlencode "q=역삼 커피"

# 행정동 한정 (전체 688건 → 140건)
curl -G localhost:8080/v1/search --data-urlencode "q=커피" --data-urlencode "dong=역삼1동"

# 강남역 반경 300m, 가까운 순
curl -G localhost:8080/v1/search --data-urlencode "q=카페" \
  -d lat=37.4979 -d lon=127.0276 -d radius=300 -d sort=distance
```

---

### 브랜드가 없는 가게가 있다

원천(상가정보)에는 스타벅스 매장이 브랜드명 없이(`신사역`) 등록돼 있어, 두 번째 원천(인허가)과
좌표로 맞춰 브랜드를 복원했습니다 (상세: [data-model.md](data-model.md)).
검색은 `brand` 필드로도 걸립니다.

```bash
curl -G localhost:8080/v1/search --data-urlencode "q=스타벅스"
#  → total 86.  {"brand":"스타벅스", "name":"개포동", "highlight":["<em>스타벅스</em>"]}
```

> ⚠️ **`brand` 를 화면에 반드시 함께 표시해야 합니다.** 그렇지 않으면 `스타벅스` 를 입력한 사용자에게
> `개포동` 이라는 가게가 나와서 **맞는 결과인데 틀린 것처럼** 보입니다.
> `highlight` 에 브랜드가 실리는 것도 같은 이유입니다 — 왜 걸렸는지가 응답에 보여야 합니다.

`brand` 는 **원천이 준 값이 아니라 추론한 값**입니다. 없는 것이 기본이고, 지금은 86건에만
채워져 있습니다.

---

## `GET /v1/suggest`

검색창 자동완성. **본문 검색과 다른 인덱스**를 봅니다 ([ADR 0002](adr/0002-index-and-cluster-separation.md)).
한 글자마다 불려 호출량이 많은 대신 문서가 가볍기 때문입니다.

| 이름 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `q` | string | (필수) | 입력 중인 글자. 한 글자부터 걸린다 |
| `size` | int | `8` | **1~20으로 잘린다** |

```jsonc
{
  "query": "스타",
  "tookMs": 3,
  "items": [
    { "placeId": "MA010120220800206533", "name": "스타", "brand": null,
      "category": "컴퓨터/소프트웨어 소매업", "dong": "논현2동", "score": 5.5697155 },
    { "placeId": "MA0106202501A0500235", "name": "포이", "brand": "스타벅스",
      "category": "카페", "dong": "개포4동", "score": 4.9 }   // 화면에는 "스타벅스 포이"로 붙여 표시한다
  ]
}
```

자동완성은 **`브랜드 + 상호명`을 한 덩어리로** 색인해 매칭합니다 — 걸리는 글자와 드롭다운에
보이는 글자가 같아야 하기 때문입니다.

```
'스'      → 스타 · 스팟 · 스윙 · 스텝 · 스펜
'스타'    → 스타 · 스타 · 스타벅스 포이 · 스타벅스 대치 · 스타벅스 청담
'스타벅'  → 스타벅스 포이 · 스타벅스 대치 · 스타벅스 청담 · 스타벅스 삼성 · 스타벅스 도곡역
```

**순서는 결정적입니다.** 점수 동점이 대량으로 생기는데(실측: `스타` 상위 20건의 점수가 3종),
동점을 `place_id` 로 못 박아 두었습니다. 같은 글자를 다시 입력해도 목록이 튀지 않습니다.

`total` 이 없는 것은 의도한 것입니다 — 자동완성은 "몇 건인지"가 필요 없어서 전체 집계를 끕니다.

---

## `GET /v1/instant`

검색창 한 번의 입력에 필요한 것을 **한 번에** 줍니다. 서버가 자동완성과 본문 검색을 **동시에**
호출합니다 ([ADR 0006](adr/0006-api-runtime-reactive-vs-blocking.md)).

| 이름 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `q` | string | (필수) | 입력 중인 글자 |
| `suggestSize` | int | `8` | 추천어 개수 |
| `previewSize` | int | `5` | 미리보기 결과 개수 |

```jsonc
{
  "query": "스타",
  "tookMs": 9,          // 두 호출의 '합'이 아니라 '느린 쪽'에 가깝다
  "suggestions": [ /* /v1/suggest 의 items 와 같은 모양 */ ],
  "preview":     [ /* /v1/search 의 hits 와 같은 모양 */ ]
}
```

**실측(100회):** 두 채널 평균이 각각 4.5ms · 3.1ms(합 7.6ms)인데, 팬아웃 응답 중앙값은 **3ms**였습니다.
클라이언트에서 따로 두 번 부르면 중앙값 7.9ms, `instant` 한 번이면 4.6ms.

---

## `GET /v1/vsearch`

**의미로** 찾습니다. 글자가 하나도 겹치지 않아도 의미가 비슷하면 나옵니다
([ADR 0007](adr/0007-vector-engine-qdrant-vs-milvus.md), [ADR 0010](adr/0010-embedding-model-and-serving.md)).

파라미터는 `/v1/search` 와 **동일합니다** (`sort` 제외).

| 이름 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `q` | string | (필수) | 검색어 |
| `size` · `page` | int | `10` · `0` | `/v1/search` 와 같은 규칙 |
| `sigungu` · `dong` · `category` | string | – | 정확 일치 필터 (**벡터 엔진 안에서** 걸린다) |
| `lat`, `lon`, `radius` | – | `2000` | 반경 필터. 좌표가 있으면 `distanceM` 도 채워진다 |

`sort` 는 지원하지 않습니다 — 의미로 뽑은 순서를 거리로 다시 세우면 벡터 점수가 통째로 버려집니다.

```bash
curl -G localhost:8080/v1/vsearch --data-urlencode "q=회 먹을 데"
```

```jsonc
{
  "query": "회 먹을 데",
  "total": 50,          // '몇 건 있나'가 아니라 '건져 올린 후보 중 문턱을 넘은 수' — 키워드의 total 과 뜻이 다르다
                        //   후보는 최소 50건 뜬다. size 만큼만 뜨면 total 이 size 를 그대로 되읊는다.
  "page": 0, "size": 10,
  "tookMs": 5,
  "relaxed": false,     // 벡터 채널에는 완화 재질의가 없다 (항상 false)
  "hits": [
    { "placeId": "MA010120220810147236", "name": "먹어도", "category": "횟집",
      "address": null,          // 벡터 payload 에는 주소를 담지 않는다 (토큰만 차지하고 의미가 없어서)
      "sigungu": "강남구", "dong": "삼성2동",
      "lat": 37.51518, "lon": 127.04282,
      "score": 0.872,           // 코사인 유사도 (0~1)
      "distanceM": null, "highlight": [] }   // 하이라이트는 없다 — 걸린 '글자'가 없기 때문
  ]
}
```

### 키워드 검색과의 차이

| | 키워드 (`/v1/search`) | 벡터 (`/v1/vsearch`) |
|---|---|---|
| 판단 기준 | 글자가 겹치나 | 뜻이 가까운가 |
| `회 먹을 데` | **0건** | 횟집·일식 회/초밥 |
| `스타벅스`([원천에 없음](data-model.md#이-데이터가-담지-못하는-것)) | 0건 (**정확**) | 0건 (**정확** — 최고점 0.834 < 문턱 0.84) |
| `차 고치는 곳`(정비소 없음) | 0건 (**정확**) | 차병원사거리포차 (**틀림** — 0.842) |
| 점수 | BM25 (수십 점대) | 코사인 (0~1) |
| 지연(중앙값) | 8.0ms | 4.8ms (질의 캐시 히트) / 9.3ms (미스) |

**벡터는 "없다"고 말할 줄 모릅니다.** 항상 가장 가까운 것들을 주기 때문에, 코사인 `0.84`
미만은 잘라냅니다. 그래도 완벽하지는 않습니다 (`차 고치는 곳` 0.842가 통과) — 진짜 해결책은
"맞는 것이 있느냐"를 글자로 판단하는 [`/v1/hsearch`](#get-v1hsearch) 입니다.

> ⚠️ **필터(`sigungu`·`dong`·`category`·`radius`)가 붙으면 이 문턱을 적용하지 않습니다.**
> 문턱 `0.84`는 *필터 없는 전체 코퍼스*의 점수 분포에서 정한 값입니다. 필터가 후보를 좁히면
> "그 안에서 제일 가까운 것"의 절대 점수도 같이 내려가는데, 거기에 옛 기준선을 그대로 대면
> **결과를 통째로 지웁니다** — 반경 300m 안에 2,015건이 있는데 0건이 나왔습니다(실측).
> 범위를 좁힌 판단은 이미 사용자가 내린 것이고, 남는 질문은 "그 안에서 무엇이 제일 가깝나"뿐입니다.

---

## `GET /v1/hsearch`

키워드와 벡터를 **합쳐서** 찾습니다 ([ADR 0003](adr/0003-hybrid-search-rrf-in-app-layer.md)).

파라미터는 `/v1/search` 와 같습니다 (`sort` 제외 — 아래 참고).

```bash
curl -G localhost:8080/v1/hsearch --data-urlencode "q=회 먹을 데"
```

```jsonc
{
  "query": "회 먹을 데",
  "total": 50,           // 결합 후보 중 유니크 문서 수. **코퍼스 전체 매칭 수가 아니다**
  "page": 0, "size": 10,
  "tookMs": 16,
  "degraded": false,     // 채널 하나가 죽어서 반쪽으로 답했는지
  "channels": [          // 어느 채널이 몇 건 냈고 얼마나 걸렸는지
    { "name": "keyword", "candidates": 0,  "tookMs": 13, "failed": false },
    { "name": "vector",  "candidates": 50, "tookMs": 5,  "failed": false }
  ],
  "hits": [
    { "placeId": "MA010120220810147236", "name": "먹어도", "category": "횟집",
      "address": "서울특별시 강남구 학동로56길 32",   // 벡터만 찾은 문서도 ES 에서 채워 넣는다
      "sigungu": "강남구", "dong": "삼성2동",
      "lat": 37.51518, "lon": 127.04282,
      "score": 0.01639,                            // **RRF 점수** (이 응답의 정렬 근거)
      "distanceM": null, "highlight": [],
      "ranks":  { "vector": 1 },                   // 채널별 등수 — 못 찾은 채널은 키가 없다
      "scores": { "vector": 0.872 }                // 채널별 **원점수** (keyword=BM25, vector=코사인)
    }
  ]
}
```

### 결합 방식 — RRF

채널별 점수는 스케일이 달라 더하지 않고 **등수만** 결합합니다
(근거: [ADR 0003](adr/0003-hybrid-search-rrf-in-app-layer.md)).

```
score(문서) = Σ  가중치 / (k + 그 채널에서의 등수)          k = 60
```

응답의 `ranks`·`scores` 를 나란히 보면 그 판단 과정이 그대로 보입니다. `역삼동 카페` 예시:

| 이름 | keyword 등수 | vector 등수 | BM25 | 코사인 | 결과 |
|---|---|---|---|---|---|
| 카페808 | 2 | 43 | 20.7 | 0.877 | **1위** (양쪽이 다 찾음) |
| 카페블루 | 1 | – | 21.2 | – | 밀림 (한쪽만 찾음) |

### 후보를 깊게 뜬다

각 채널에서 `50`건씩 가져와 합칩니다(`psp.hybrid.candidates`). 상위 10개씩만 합치면,
한 채널이 11등에 둔 정답은 다른 채널이 1등을 줘도 **결합에 들어오지도 못합니다.**
결합의 이득이 통째로 사라지는 지점입니다.

그래서 `total`은 최대 100(50+50)입니다. 페이지를 깊게 넘기면 결과가 끊깁니다.

### 한쪽이 죽어도 답한다

한 채널이 실패해도 **나머지로 답하고**, 그 사실을 `degraded` 와 `channels[].failed` 로
알립니다.

| 상황 | 응답 |
|---|---|
| 정상 | `200` · `degraded:false` · total 95 |
| Qdrant 중단 | `200` · `degraded:true` · keyword `failed:false` / vector `failed:true` · total 50 |
| ES 중단 | `200` · `degraded:true` · keyword `failed:true` / vector `failed:false` · total 50 |

> ES가 죽으면 주소 채우기(mget)를 아예 부르지 않습니다 — 방금 죽은 엔진에 다시 물어 봐야
> 타임아웃만 한 번 더 기다립니다. 그때는 벡터 payload 로만 답해서 `address` 가 `null` 입니다.

`degraded` 는 **백엔드 장애**일 때만 켜집니다. 채널 코드 자체의 버그는 반쪽 응답으로 감추지 않고
`500` 으로 드러냅니다 — 감추면 recall 이 조용히 반토막 난 채로 지표는 정상으로 보입니다.

### `sort` 는 없다

결합 결과를 거리로 다시 세우면 RRF 순위가 통째로 버려집니다. 다만 좌표를 주면 정렬과 무관하게
`distanceM` 은 채워줍니다 — 서버가 이미 아는 값이기 때문입니다. 거리 재랭킹은 7단계 일입니다.

### 답변 생성이 읽는 필드

`ask-api` 의 답변 생성([ADR 0015](adr/0015-ask-api-grounded-answer-generation.md))은 `hits[]` 에서
다섯 필드만 렌더합니다. 나머지(`ranks`·`scores`·`lat`·`lon`·`highlight` 등)는 읽지 않습니다.

| 구분 | 필드 |
|---|---|
| 필수 | `placeId` · `name` |
| 선택 | `category` · `dong` · `address` |

필수 필드가 없는 히트는 인용할 수 없어 컨텍스트에서 빠지고, 그 건수가 응답의
`answer.unrenderableRecords` 와 `ask-api` 경고 로그에 남습니다. 선택 필드는 없으면 그 줄만
짧아집니다 — ES 중단으로 `address` 가 `null` 인 경우가 여기 해당합니다.

필드 이름을 바꾸면 `ask-api` 의 `HsearchContract` 도 같이 바꿔야 합니다. 두 앱은 HTTP 로만
붙어 있어([ADR 0011](adr/0011-module-split-and-index-contract.md)) 컴파일이 막아주지 않습니다.

---

## 백엔드가 죽었을 때

ES·Qdrant 가 답하지 못하는 상태와 우리 코드의 버그를 **다른 상태 코드로** 구분합니다.
기다리거나 재시도하면 낫는 상태는 `503`, 코드를 고쳐야 낫는 상태는 `500` 입니다.

| 엔드포인트 | 백엔드 장애 | 채널 코드 버그 |
|---|---|---|
| `/v1/search` · `/v1/suggest` · `/v1/instant` · `/v1/vsearch` | `503` (아래 본문) | `500` |
| `/v1/hsearch` | `200` · `degraded:true` ([한쪽이 죽어도 답한다](#한쪽이-죽어도-답한다)) | `500` |

```jsonc
// 503 Service Unavailable
{
  "upstream": "elasticsearch",   // 또는 "qdrant"
  "message": "cannot reach the elasticsearch upstream — not indexed yet, or temporarily down"
}
```

무엇을 백엔드 장애로 볼지는 `UpstreamFailure` 한 곳이 정합니다.

| 상황 | 판정 |
|---|---|
| 연결 거부·타임아웃 | 장애 |
| ES·Qdrant `5xx` | 장애 |
| `429` — 과부하·circuit breaker | 장애 |
| `404` — 별칭·컬렉션이 아직 없음 | 장애 |
| 응답 본문 파싱·디코딩 실패 | 장애 |
| ES·Qdrant `400` — 우리가 보낸 질의가 잘못됨 | 버그 |
| 문서 스키마 불일치 (`JsonpMappingException`) | 버그 |
| 우리 응답 직렬화 실패 (`EncodingException`) | 버그 |

`404` 가 장애 쪽인 이유는 **첫 색인 전** 상태가 여기 걸리기 때문입니다. 배포 직후 색인이 한 번도
안 돌았으면 별칭이 없는데, 그건 고칠 코드가 있는 게 아니라 색인을 기다리면 되는 상태입니다.

반대로 스키마 불일치를 `503` 으로 답하면 영구적인 우리 잘못을 일시 장애로 광고하는 셈이라,
호출 측은 낫지 않을 상태를 계속 재시도하고 원인은 5xx 로 드러나지 않습니다.

원인이 다른 예외에 감싸여 있어도 사슬을 따라가 판정합니다 — 임베딩 추론 실패가 `IOException` 을
감싸고 있으면 `503` 입니다.

---

## 색인은 접수증을 받는다 (ADR 0013)

재색인 엔드포인트는 접수 즉시 `202` 와 `jobId` 를 돌려주고, 색인은 뒤에서 계속 돕니다.
`curl` 을 끊어도 색인은 영향받지 않습니다. 동기 방식에서 전환한 근거는
[ADR 0013](adr/0013-indexer-runtime-spring-batch.md)에 있습니다.

## `POST /admin/reindex`

무중단 전체 재색인 — 새 버전 인덱스를 뒤에서 만들고 alias만 원자적으로 옮깁니다.
(3단계 실측: 64,239건 약 14초, 그 사이 검색 무중단)

```jsonc
// 202 Accepted
{ "jobId": 12, "jobName": "keywordRebuild", "status": "STARTING", "poll": "/admin/jobs/12" }
```

## `POST /admin/reindex/incremental`

체크포인트 이후 바뀐 것만 반영합니다(멱등). 소프트 삭제된 행은 인덱스에서 지웁니다.
응답은 위와 같은 접수증 형태이고 `jobName` 이 `keywordIncremental` 입니다.

## `POST /admin/vector/reindex`

벡터 컬렉션을 무중단으로 새로 만듭니다. **키워드 색인과 따로 도는 것이 핵심입니다** —
임베딩 추론이 훨씬 느려서(64,239건 **8분 33초** vs ES bulk 14초) 한 파이프라인에 묶으면
느린 쪽이 주기를 결정해 버립니다. 체크포인트도 따로 전진합니다.

`POST /admin/vector/reindex/incremental` 은 벡터 체크포인트 이후 바뀐 것만 다시 임베딩합니다.

## `GET /admin/jobs/{jobId}`

진행 상황과 결과입니다. **끝난 job 도 답합니다** — 이력이 Postgres 의 `BATCH_*` 테이블에 남아서,
색인기를 재시작한 뒤에도 이전 실행이 몇 건이었는지 조회됩니다.

```jsonc
{
  "jobId": 12, "jobName": "keywordRebuild", "status": "COMPLETED",
  "running": false, "elapsedMs": 17204,
  // 아래 건수는 앱이 센 것이 아니라 Spring Batch 가 chunk 커밋마다 DB 에 적은 값이다.
  "steps": [
    { "name": "keywordRebuild.prepare", "status": "COMPLETED", "read": 0,     "written": 0,     "commits": 1,  "rollbacks": 0 },
    { "name": "keywordLoad",            "status": "COMPLETED", "read": 64239, "written": 64239, "commits": 33, "rollbacks": 0 },
    { "name": "keywordRebuild.promote", "status": "COMPLETED", "read": 0,     "written": 0,     "commits": 1,  "rollbacks": 0 }
  ],
  // 프레임워크가 알 수 없는 도메인 요약이다.
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

`GET /admin/jobs?name=keywordRebuild&limit=10` 은 최근 실행 이력을 줍니다.

## `POST /admin/cleanup` · `POST /admin/vector/cleanup`

이 둘만 **동기**입니다(`200`). alias 를 보고 옛 버전을 지우는 것뿐이라 밀리초로 끝나서, job 이력을
남길 가치가 없습니다.

```jsonc
{ "kept": 2, "removed": ["place_search_20260723043000"] }
```

> ⚠️ 관리 경로는 **인증이 없습니다.** 로컬 전용이라 그렇고, 운영이라면 관리자 인증과
> 레이트리밋이 필요합니다 (아키텍처 리뷰 #9). 다만 이 경로는 `indexer-batch`(8081)에만
> 있고 **공개 트래픽을 받는 `search-api` 의 jar 에는 클래스 자체가 없습니다** (ADR 0011).

---

## 질의 로그

모든 검색·자동완성 질의가 `logs/query.log` 에 **한 줄 JSON**으로 남습니다 (앱 로그와 분리 —
디버깅용이 아니라 **데이터 자산**이라 수명과 관리 주체가 다릅니다).

```jsonc
{"ts":"2026-07-23T17:30:20.265+09:00","type":"search","q":"존맛탱","total":0,"zero":true,"relaxed":true,"took_ms":1}
```

용도는 둘입니다. ① **사전 확보** — 0건 질의는 미등록 어휘의 직접 증거입니다
(`scripts/mine_query_log.py` 가 후보를 뽑는다). ② **랭킹 근거** — 질의–클릭 쌍이 쌓여야
필드 가중치를 nDCG 로 평가할 수 있습니다.

개인정보: 질의문 외에 식별자를 남기지 않습니다.

## 지표 (`/actuator/prometheus`)

| 지표 | 태그 | 뜻 |
|---|---|---|
| `psp_query_latency_seconds` | `channel=keyword\|suggest\|vector\|hybrid`, `outcome` | 채널별 질의 지연·실패 |
| `psp_query_stage_latency_seconds` | `channel`, `stage=embed\|ann\|narrow\|keyword\|vector\|fuse\|hydrate` | 채널 **안에서** 단계별 분해. 벡터가 느릴 때 모델 문제인지 탐색 문제인지, 하이브리드가 느릴 때 어느 채널 탓인지 가려준다 |
| `psp_index_lag_seconds` | `pipeline=keyword\|vector` | 원천 최신 변경과 색인 체크포인트의 차이(초). **0이면 따라잡음**, -1이면 체크포인트 없음 |

## `ask-api` 지표 (8082)

자연어 질의 이해 모듈의 지표입니다. 자세한 것은 ADR 0014 를 참고합니다.

| 지표 | 태그 | 뜻 |
|---|---|---|
| `psp_ask_latency_seconds` | `stage=llm\|search`, `outcome` | 구간별 소요. LLM 왕복과 `/v1/hsearch` 호출을 나눠 잰다 |
| `psp_ask_degraded_total` | `stage=llm`, `reason=config\|rate_limit\|upstream\|request\|payload\|unreachable` | LLM 실패로 원문 질의를 그대로 검색한 횟수. `reason=config` 는 키·설정 문제라 재시도로 낫지 않는다 |
| `psp_ask_degraded_total` | `stage=search`, `reason=channel` | 하류 하이브리드 채널 하나가 degrade 한 횟수 |

## 앱은 둘이다 (ADR 0011)

`/v1/*` 과 `/admin/*` 은 **서로 다른 아티팩트**입니다. 한 앱을 스위치로 나눠 띄우는 것이 아니라
빌드가 따로 나옵니다.

| 앱 | 포트 | 가진 것 | 없는 것 |
|---|---|---|---|
| `search-api` | **8080** | `/v1/search` · `/v1/suggest` · `/v1/instant` · `/v1/vsearch` · `/v1/hsearch` | `/admin/*`, PostGIS 연결 |
| `indexer-batch` | **8081** | `/admin/*` | `/v1/*` |

```bash
cd services
./gradlew :search-api:bootRun      # 질의 (8080)
./gradlew :indexer-batch:bootRun   # 색인 (8081)
```

"없는 것"은 꺼둔 것이 아니라 **jar 에 클래스가 없습니다.** (검증: `search-api.jar` 안에
postgresql 드라이버 0개, `Admin` 클래스 0개.)

여전히 런타임 스위치인 것은 하나입니다.

```bash
./gradlew :search-api:bootRun --args='--psp.vector.enabled=false'
# 키워드 전용: /v1/vsearch·/v1/hsearch 없음, 임베딩 모델을 아예 읽지 않는다
#   (메모리 0.5GB·기동 5.6초 절약)
```

### 기동 시 색인 계약을 대조한다

`search-api` 는 뜰 때 **색인된 데이터가 자기와 같은 계약으로 만들어졌는지** 확인합니다
(문서 스키마 버전, 임베딩 모델·차원). 다르면 **뜨지 않습니다.**

```
[search] the indexed data and this process disagree on the contract.
  - document schema version: indexed=1, querying=2
  in this state nothing throws — the results just go silently wrong.
  → run a full reindex with POST /admin/reindex on the indexer (indexer-batch), then start this app again.
```

경고가 아니라 기동 실패인 이유는, 이 어긋남이 **증상이 없기** 때문입니다 — 200 OK 에 로그도
깨끗하고 결과만 조용히 틀립니다. 도장이 아예 없으면(분리 이전에 만든 인덱스) 경고만 하고 뜹니다.

## 예정

| 단계 | 추가될 것 |
|---|---|
| 7 | 거리 기반 재랭킹 · 추천 엔드포인트 · 쿠키리스 세션 ([ADR 0004](adr/0004-cookieless-session-model.md), [0005](adr/0005-cold-start-and-recommend-strategy.md)) |
