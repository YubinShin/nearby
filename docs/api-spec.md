# API 명세 — 검색 플랫폼 공통 입구

이 플랫폼을 쓰는 서비스는 ES·Qdrant·PostGIS를 몰라도 돼요. **이 API 하나만** 부르면 돼요.

- 기준 URL(로컬): `http://localhost:8080` — 질의(`/v1/*`).
  색인(`/admin/*`)은 **다른 앱**이라 `http://localhost:8081` 이에요 ([앱이 둘이에요](#앱이-둘이에요-adr-0011))
- 응답: `application/json` (UTF-8)
- 구현 상태: **6단계까지 실제 동작.** 추천·거리 재랭킹은 7단계 예정 (아래 [예정](#예정) 참고)

## 목차

| 메서드 | 경로 | 설명 | 상태 |
|---|---|---|---|
| `GET` | [`/v1/search`](#get-v1search) | 키워드 본문 검색 (BM25 + KOMORAN) | ✅ |
| `GET` | [`/v1/suggest`](#get-v1suggest) | 자동완성 (edge_ngram) | ✅ |
| `GET` | [`/v1/instant`](#get-v1instant) | 추천어 + 결과 미리보기 (팬아웃) | ✅ |
| `GET` | [`/v1/vsearch`](#get-v1vsearch) | 벡터(뜻) 검색 (Qdrant) | ✅ |
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

키워드 본문 검색. 랭킹 규칙은 [ADR 0009](adr/0009-keyword-ranking-and-fallback.md)에 있어요.

### 요청 파라미터

| 이름 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `q` | string | (필수) | 검색어. 비면 빈 결과를 돌려줘요 |
| `size` | int | `10` | 페이지 크기. **1~50으로 잘려요** |
| `page` | int | `0` | 0부터. **0~99로 잘려요** |
| `sigungu` | string | – | 시군구 정확 일치 필터 (예: `강남구`) |
| `dong` | string | – | 행정동 정확 일치 필터 (예: `역삼1동`) |
| `category` | string | – | 대분류 정확 일치 필터 |
| `lat`, `lon` | double | – | 기준 좌표. **둘 다 있어야** 위치 기능이 켜져요 |
| `radius` | int | `2000` | 반경(m). 좌표가 있을 때만. **1~50,000으로 잘려요** |
| `sort` | string | `relevance` | `relevance` \| `distance` (좌표 없이 `distance`면 `relevance`로 되돌아가요) |

범위를 벗어난 값은 **에러 대신 가장 가까운 합법값으로 접어요.** `size=100000` 한 방이 그대로
클러스터 부하가 되는 걸 막기 위한 첫 방어선이에요.

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
      "brand": null,     // 복원한 브랜드명. 상호명에서 브랜드가 빠져 있던 가게에만 채워져요 (아래 참고)
      "category": "카페",
      "address": "서울특별시 강남구 테헤란로25길 20",
      "sigungu": "강남구",
      "dong": "역삼1동",
      "lat": 37.5016243265646,
      "lon": 127.035657929451,
      "score": 53.23402,
      "distanceM": null,                        // sort=distance 일 때만 채워져요 (미터)
      "highlight": ["<em>역삼커피</em>로스터스"]  // 어느 글자가 걸렸는지
    }
  ]
}
```

### `relaxed` 를 꼭 보세요

기본은 **질의의 모든 단어**를 요구해요(정밀도 우선). 그래서 0건이 나오면 조건을 풀어 한 번 더
질의하는데, 그때 `relaxed: true` 가 됩니다. **조건이 다른 결과**이므로 UI에서 "정확히 일치하는
결과가 없어 유사 결과를 보여줍니다" 같은 안내를 붙이는 걸 권해요.

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

### 브랜드가 없는 가게가 있어요

원천(상가정보)이 스타벅스 매장을 **브랜드명 없이** 등록해 뒀어요 — `스타벅스 신사역점` 이 아니라
`신사역` 으로요. 그래서 예전엔 `스타벅스` 검색이 **0건**이었어요.

두 번째 원천(인허가)과 좌표로 맞춰 **브랜드를 복원**해 두었고([data-model.md](data-model.md)),
검색은 `brand` 필드로도 걸려요.

```bash
curl -G localhost:8080/v1/search --data-urlencode "q=스타벅스"
#  → total 86.  {"brand":"스타벅스", "name":"개포동", "highlight":["<em>스타벅스</em>"]}
```

> ⚠️ **`brand` 를 화면에 꼭 같이 보여주세요.** 안 그러면 `스타벅스` 를 친 사용자에게
> `개포동` 이라는 가게가 나와서 **맞는 결과인데 틀린 것처럼** 보여요.
> `highlight` 에 브랜드가 실리는 것도 같은 이유예요 — 왜 걸렸는지가 응답에 보여야 해요.

`brand` 는 **원천이 준 값이 아니라 우리가 추론한 값**이에요. 없는 게 기본이고, 지금은 86건에만
채워져 있어요.

---

## `GET /v1/suggest`

검색창 자동완성. **본문 검색과 다른 인덱스**를 봐요 ([ADR 0002](adr/0002-index-and-cluster-separation.md)).
한 글자마다 불려 호출량이 많은 대신 문서가 가볍기 때문이에요.

| 이름 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `q` | string | (필수) | 입력 중인 글자. 한 글자부터 걸려요 |
| `size` | int | `8` | **1~20으로 잘려요** |

```jsonc
{
  "query": "스타",
  "tookMs": 3,
  "items": [
    { "placeId": "MA010120220800206533", "name": "스타", "brand": null,
      "category": "컴퓨터/소프트웨어 소매업", "dong": "논현2동", "score": 5.5697155 },
    { "placeId": "MA0106202501A0500235", "name": "포이", "brand": "스타벅스",
      "category": "카페", "dong": "개포4동", "score": 4.9 }   // 화면엔 "스타벅스 포이"로 붙여 보여주세요
  ]
}
```

자동완성은 **`브랜드 + 상호명`을 한 덩어리로** 색인해 두고 그걸로 매칭해요. 본문 검색처럼
브랜드를 별도 필드로만 두면, `스타`를 쳤을 때 드롭다운에 뜨는 글자가 `개포동`이라 아무 의미가
없거든요. **걸리는 것과 보여주는 것이 같아야** 하는 게 자동완성이에요.

```
'스'      → 스타 · 스팟 · 스윙 · 스텝 · 스펜
'스타'    → 스타 · 스타 · 스타벅스 포이 · 스타벅스 대치 · 스타벅스 청담
'스타벅'  → 스타벅스 포이 · 스타벅스 대치 · 스타벅스 청담 · 스타벅스 삼성 · 스타벅스 도곡역
```

**순서는 결정적이에요.** 점수 동점이 대량으로 생기는데(실측: `스타` 상위 20건의 점수가 3종),
동점을 `place_id` 로 못 박아 두었어요. 같은 글자를 다시 쳐도 목록이 튀지 않아요.

`total` 이 없는 건 일부러예요 — 자동완성은 "몇 건인지"가 필요 없어서 전체 집계를 꺼요.

---

## `GET /v1/instant`

검색창 한 번의 입력에 필요한 걸 **한 번에** 줘요. 서버가 자동완성과 본문 검색을 **동시에**
호출해요 ([ADR 0006](adr/0006-api-runtime-reactive-vs-blocking.md)).

| 이름 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `q` | string | (필수) | 입력 중인 글자 |
| `suggestSize` | int | `8` | 추천어 개수 |
| `previewSize` | int | `5` | 미리보기 결과 개수 |

```jsonc
{
  "query": "스타",
  "tookMs": 9,          // 두 호출의 '합'이 아니라 '느린 쪽'에 가까워요
  "suggestions": [ /* /v1/suggest 의 items 와 같은 모양 */ ],
  "preview":     [ /* /v1/search 의 hits 와 같은 모양 */ ]
}
```

**실측(100회):** 두 채널 평균이 각각 4.5ms · 3.1ms(합 7.6ms)인데, 팬아웃 응답 중앙값은 **3ms**였어요.
클라이언트에서 따로 두 번 부르면 중앙값 7.9ms, `instant` 한 번이면 4.6ms.

---

## `GET /v1/vsearch`

**뜻으로** 찾아요. 글자가 하나도 안 겹쳐도 의미가 비슷하면 나와요
([ADR 0007](adr/0007-vector-engine-qdrant-vs-milvus.md), [ADR 0010](adr/0010-embedding-model-and-serving.md)).

파라미터는 `/v1/search` 와 **똑같아요** (`sort` 제외). 같은 질의를 두 채널에 던져 나란히
비교할 수 있어야 6단계 결합을 설계할 수 있기 때문이에요.

| 이름 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `q` | string | (필수) | 검색어 |
| `size` · `page` | int | `10` · `0` | `/v1/search` 와 같은 규칙 |
| `sigungu` · `dong` · `category` | string | – | 정확 일치 필터 (**벡터 엔진 안에서** 걸려요) |
| `lat`, `lon`, `radius` | – | `2000` | 반경 필터. 좌표가 있으면 `distanceM` 도 채워져요 |

`sort` 는 없어요. 뜻으로 뽑은 순서를 거리로 다시 세우면 벡터 점수가 통째로 버려져요.
거리 다듬기는 결합 **뒤에** 할 일이에요(7단계).

```bash
curl -G localhost:8080/v1/vsearch --data-urlencode "q=회 먹을 데"
```

```jsonc
{
  "query": "회 먹을 데",
  "total": 50,          // '몇 건 있나'가 아니라 '건져 올린 후보 중 문턱을 넘은 수' — 키워드의 total 과 뜻이 달라요
                        //   후보는 최소 50건 떠요. size 만큼만 뜨면 total 이 size 를 그대로 되읊어요.
  "page": 0, "size": 10,
  "tookMs": 5,
  "relaxed": false,     // 벡터 채널엔 완화 재질의가 없어요 (항상 false)
  "hits": [
    { "placeId": "MA010120220810147236", "name": "먹어도", "category": "횟집",
      "address": null,          // 벡터 payload 엔 주소를 안 담아요 (토큰만 먹고 의미가 없어서)
      "sigungu": "강남구", "dong": "삼성2동",
      "lat": 37.51518, "lon": 127.04282,
      "score": 0.872,           // 코사인 유사도 (0~1)
      "distanceM": null, "highlight": [] }   // 하이라이트는 없어요 — 걸린 '글자'가 없으니까요
  ]
}
```

### 키워드 검색과 뭐가 다른가요

| | 키워드 (`/v1/search`) | 벡터 (`/v1/vsearch`) |
|---|---|---|
| 판단 기준 | 글자가 겹치나 | 뜻이 가까운가 |
| `회 먹을 데` | **0건** | 횟집·일식 회/초밥 |
| `스타벅스`([원천에 없음](data-model.md#이-데이터가-담지-못하는-것)) | 0건 (**정확**) | 0건 (**정확** — 최고점 0.834 < 문턱 0.84) |
| `차 고치는 곳`(정비소 없음) | 0건 (**정확**) | 차병원사거리포차 (**틀림** — 0.842) |
| 점수 | BM25 (수십 점대) | 코사인 (0~1) |
| 지연(중앙값) | 8.0ms | 4.8ms (질의 캐시 히트) / 9.3ms (미스) |

**벡터는 "없다"고 말할 줄 몰라요.** 항상 가장 가까운 것들을 주기 때문에, 코사인 `0.84`
미만은 잘라내요. 그래도 완벽하진 않아요 (`차 고치는 곳` 0.842가 통과) — 진짜 해결책은
"맞는 게 있느냐"를 글자로 판단하는 [`/v1/hsearch`](#get-v1hsearch) 예요.

> ⚠️ **필터(`sigungu`·`dong`·`category`·`radius`)가 붙으면 이 문턱을 적용하지 않아요.**
> 문턱 `0.84`는 *필터 없는 전체 코퍼스*의 점수 분포에서 정한 값이에요. 필터가 후보를 좁히면
> "그 안에서 제일 가까운 것"의 절대 점수도 같이 내려가는데, 거기에 옛 기준선을 그대로 대면
> **결과를 통째로 지워요** — 반경 300m 안에 2,015건이 있는데 0건이 나왔어요(실측).
> 범위를 좁힌 판단은 이미 사용자가 내린 것이고, 남는 질문은 "그 안에서 뭐가 제일 가깝나"뿐이에요.

---

## `GET /v1/hsearch`

키워드와 벡터를 **합쳐서** 찾아요 ([ADR 0003](adr/0003-hybrid-search-rrf-in-app-layer.md)).

두 채널은 서로의 실패를 메워요. 키워드는 `회 먹을 데`를 못 찾고(글자가 하나도 안 겹쳐요),
벡터는 `스타벅스`에 "없다"고 말할 줄 몰라요. 그래서 **둘 다 부르고 순위로 합쳐요.**

파라미터는 `/v1/search` 와 같아요 (`sort` 제외 — 아래 참고).

```bash
curl -G localhost:8080/v1/hsearch --data-urlencode "q=회 먹을 데"
```

```jsonc
{
  "query": "회 먹을 데",
  "total": 50,           // 결합 후보 중 유니크 문서 수. **코퍼스 전체 매칭 수가 아니에요**
  "page": 0, "size": 10,
  "tookMs": 16,
  "degraded": false,     // 채널 하나가 죽어서 반쪽으로 답했는지
  "channels": [          // 어느 채널이 몇 건 냈고 얼마나 걸렸는지
    { "name": "keyword", "candidates": 0,  "tookMs": 13, "failed": false },
    { "name": "vector",  "candidates": 50, "tookMs": 5,  "failed": false }
  ],
  "hits": [
    { "placeId": "MA010120220810147236", "name": "먹어도", "category": "횟집",
      "address": "서울특별시 강남구 학동로56길 32",   // 벡터만 찾은 문서도 ES 에서 채워 넣어요
      "sigungu": "강남구", "dong": "삼성2동",
      "lat": 37.51518, "lon": 127.04282,
      "score": 0.01639,                            // **RRF 점수** (이 응답의 정렬 근거)
      "distanceM": null, "highlight": [],
      "ranks":  { "vector": 1 },                   // 채널별 등수 — 못 찾은 채널은 키가 없어요
      "scores": { "vector": 0.872 }                // 채널별 **원점수** (keyword=BM25, vector=코사인)
    }
  ]
}
```

### 어떻게 합치나요 — RRF

점수를 더하지 **않아요.** BM25는 수십 점대인데 코사인은 0~1이라, 그냥 더하면 스케일이 큰 쪽이
독식해요. 그래서 **등수만** 써요.

```
score(문서) = Σ  가중치 / (k + 그 채널에서의 등수)          k = 60
```

`k=60`이면 1등(1/61)과 2등(1/62)이 거의 붙어 있어요. 그래서 **"두 채널이 다 찾았다"가
"한 채널에서 1등"보다 세져요.** 하이브리드에서 원하는 성질이 정확히 이거예요.

응답의 `ranks`·`scores` 를 나란히 보면 그 판단 과정이 그대로 보여요. `역삼동 카페` 예시:

| 이름 | keyword 등수 | vector 등수 | BM25 | 코사인 | 결과 |
|---|---|---|---|---|---|
| 카페808 | 2 | 43 | 20.7 | 0.877 | **1위** (양쪽이 다 찾음) |
| 카페블루 | 1 | – | 21.2 | – | 밀림 (한쪽만 찾음) |

### 후보를 깊게 떠요

각 채널에서 `50`건씩 가져와 합쳐요(`psp.hybrid.candidates`). 상위 10개씩만 합치면,
한 채널이 11등에 둔 정답은 다른 채널이 1등을 줘도 **결합에 들어오지도 못해요.**
결합의 이득이 통째로 사라지는 지점이에요.

그래서 `total`은 최대 100(50+50)이에요. 페이지를 깊게 넘기면 결과가 끊겨요.

### 한쪽이 죽어도 답해요

채널이 둘이면 고장날 곳도 둘이에요. 하이브리드가 각 채널보다 *덜* 안정적이면 합칠 이유가 없죠.
그래서 한 채널이 실패해도 **나머지로 답하고**, 그 사실을 `degraded` 와 `channels[].failed` 로
알려요. 조용히 반쪽 결과를 주는 게 제일 나빠요.

| 상황 | 응답 |
|---|---|
| 정상 | `200` · `degraded:false` · total 95 |
| Qdrant 중단 | `200` · `degraded:true` · keyword `failed:false` / vector `failed:true` · total 50 |
| ES 중단 | `200` · `degraded:true` · keyword `failed:true` / vector `failed:false` · total 50 |

> ES가 죽으면 주소 채우기(mget)도 같이 실패해요. 그때는 벡터 payload 로만 답해서
> `address` 가 `null` 이에요. **주소 없는 결과가 결과 없음보다 나아요.**

### `sort` 는 없어요

결합 결과를 거리로 다시 세우면 RRF 순위가 통째로 버려져요. 다만 좌표를 주면 정렬과 무관하게
`distanceM` 은 채워줘요 — 서버가 이미 아는 값이라서요. 거리 재랭킹은 7단계 일이에요.

---

## 색인은 접수증을 받아요 (ADR 0013)

재색인 엔드포인트는 **색인이 끝날 때까지 기다리지 않아요.** 접수하면 즉시 `202` 와 `jobId` 를
주고, 색인은 뒤에서 계속 돌아요. 택배 송장번호랑 같아요.

전에는 응답이 올 때까지 기다렸는데, 벡터 재색인이 **8분 33초**(kind 환경 32분)라 문제가 셋이었어요.
`curl` 을 끊으면 색인도 죽고, 그 취소 경로에서 DB 커넥션이 새고, 진행 상황은 로그밖에 없었어요.

## `POST /admin/reindex`

무중단 전체 재색인 — 새 버전 인덱스를 뒤에서 만들고 alias만 원자적으로 옮겨요.
(3단계 실측: 64,239건 약 14초, 그 사이 검색 무중단)

```jsonc
// 202 Accepted
{ "jobId": 12, "jobName": "keywordRebuild", "status": "STARTING", "poll": "/admin/jobs/12" }
```

## `POST /admin/reindex/incremental`

체크포인트 이후 바뀐 것만 반영해요(멱등). 소프트 삭제된 행은 인덱스에서 지워요.
응답은 위와 같은 접수증 형태고 `jobName` 이 `keywordIncremental` 이에요.

## `POST /admin/vector/reindex`

벡터 컬렉션을 무중단으로 새로 만들어요. **키워드 색인과 따로 도는 게 핵심**이에요 —
임베딩 추론이 훨씬 느려서(64,239건 **8분 33초** vs ES bulk 14초) 한 파이프라인에 묶으면
느린 쪽이 주기를 결정해버려요. 체크포인트도 따로 전진해요.

`POST /admin/vector/reindex/incremental` 은 벡터 체크포인트 이후 바뀐 것만 다시 임베딩해요.

## `GET /admin/jobs/{jobId}`

진행 상황과 결과예요. **끝난 job 도 답해요** — 이력이 Postgres 의 `BATCH_*` 테이블에 남아서,
색인기를 재시작한 뒤에도 어제 실행이 몇 건이었는지 조회돼요.

```jsonc
{
  "jobId": 12, "jobName": "keywordRebuild", "status": "COMPLETED",
  "running": false, "elapsedMs": 17204,
  // 아래 건수는 제가 센 게 아니라 Spring Batch 가 chunk 커밋마다 DB 에 적은 값이에요.
  "steps": [
    { "name": "keywordRebuild.prepare", "status": "COMPLETED", "read": 0,     "written": 0,     "commits": 1,  "rollbacks": 0 },
    { "name": "keywordLoad",            "status": "COMPLETED", "read": 64239, "written": 64239, "commits": 33, "rollbacks": 0 },
    { "name": "keywordRebuild.promote", "status": "COMPLETED", "read": 0,     "written": 0,     "commits": 1,  "rollbacks": 0 }
  ],
  // 프레임워크가 알 수 없는 도메인 요약이에요.
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

`GET /admin/jobs?name=keywordRebuild&limit=10` 은 최근 실행 이력을 줘요.

## `POST /admin/cleanup` · `POST /admin/vector/cleanup`

이 둘만 **동기**예요(`200`). alias 를 보고 옛 버전을 지우는 것뿐이라 밀리초로 끝나서, job 이력을
남길 가치가 없어요. **오래 걸리는 것만 job 으로 만들어요.**

```jsonc
{ "kept": 2, "removed": ["place_search_20260723043000"] }
```

> ⚠️ 관리 경로는 **인증이 없어요.** 로컬 전용이라 그렇고, 운영이라면 관리자 인증과
> 레이트리밋이 필요해요 (아키텍처 크리틱 #9). 다만 이 경로는 `indexer-batch`(8081)에만
> 있고 **공개 트래픽을 받는 `search-api` 의 jar 에는 클래스 자체가 없어요** (ADR 0011).

---

## 질의 로그

모든 검색·자동완성 질의가 `logs/query.log` 에 **한 줄 JSON**으로 남아요 (앱 로그와 분리 —
디버깅용이 아니라 **데이터 자산**이라 수명과 관리 주체가 달라요).

```jsonc
{"ts":"2026-07-23T17:30:20.265+09:00","type":"search","q":"존맛탱","total":0,"zero":true,"relaxed":true,"took_ms":1}
```

용도가 둘이에요. ① **사전 확보** — 0건 질의는 미등록 어휘의 직접 증거예요
(`scripts/mine_query_log.py` 가 후보를 뽑아요). ② **랭킹 근거** — 질의–클릭 쌍이 쌓여야
필드 가중치를 nDCG 로 평가할 수 있어요.

개인정보: 질의문 외에 식별자를 남기지 않아요.

## 지표 (`/actuator/prometheus`)

| 지표 | 태그 | 뜻 |
|---|---|---|
| `psp_query_latency_seconds` | `channel=keyword\|suggest\|vector\|hybrid`, `outcome` | 채널별 질의 지연·실패 |
| `psp_query_stage_latency_seconds` | `channel`, `stage=embed\|ann\|keyword\|vector\|fuse\|hydrate` | 채널 **안에서** 단계별 분해. 벡터가 느릴 때 모델 문제인지 탐색 문제인지, 하이브리드가 느릴 때 어느 채널 탓인지 가려줘요 |
| `psp_index_lag_seconds` | – | 원천 최신 변경과 색인 체크포인트의 차이(초). **0이면 따라잡음**, -1이면 체크포인트 없음 |

채널을 나눠 재는 게 요점이에요. 합쳐 재면 "검색이 느리다"까지만 알고 *어디가* 느린지를 몰라요.

## 앱이 둘이에요 (ADR 0011)

`/v1/*` 과 `/admin/*` 은 **서로 다른 아티팩트**예요. 한 앱을 스위치로 나눠 띄우는 게 아니라
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

"없는 것"은 꺼둔 게 아니라 **jar 에 클래스가 없어요.** (검증: `search-api.jar` 안에
postgresql 드라이버 0개, `Admin` 클래스 0개.) 그래서 질의 앱이 원천 창고를 열 방법 자체가 없어요.

여전히 런타임 스위치인 것은 하나예요.

```bash
./gradlew :search-api:bootRun --args='--psp.vector.enabled=false'
# 키워드 전용: /v1/vsearch·/v1/hsearch 없음, 임베딩 모델을 아예 안 읽어요
#   (메모리 0.5GB·기동 5.6초 절약)
```

### 기동할 때 색인 계약을 대조해요

`search-api` 는 뜰 때 **색인된 데이터가 자기와 같은 계약으로 만들어졌는지** 확인해요
(문서 스키마 버전, 임베딩 모델·차원). 다르면 **뜨지 않아요.**

```
[search] 색인된 데이터와 이 프로세스의 계약이 다릅니다.
  - 문서 스키마 버전: 색인=1, 질의=2
  이 상태로는 오류 없이 결과만 조용히 틀려집니다.
  → 색인기(indexer-batch)에서 POST /admin/reindex 로 전체 재색인한 뒤 다시 띄우세요.
```

경고가 아니라 기동 실패인 이유는, 이 어긋남이 **증상이 없기** 때문이에요 — 200 OK 에 로그도
깨끗하고 결과만 조용히 틀려요. 도장이 아예 없으면(분리 이전에 만든 인덱스) 경고만 하고 떠요.

## 예정

| 단계 | 추가될 것 |
|---|---|
| 7 | 거리 기반 재랭킹 · 추천 엔드포인트 · 쿠키리스 세션 ([ADR 0004](adr/0004-cookieless-session-model.md), [0005](adr/0005-cold-start-and-recommend-strategy.md)) |
