# API 명세 — 검색 플랫폼 공통 입구

이 플랫폼을 쓰는 서비스는 ES·Qdrant·PostGIS를 몰라도 돼요. **이 API 하나만** 부르면 돼요.

- 기준 URL(로컬): `http://localhost:8080`
- 응답: `application/json` (UTF-8)
- 구현 상태: **4단계까지 실제 동작.** 벡터·하이브리드·추천은 5~7단계 예정 (아래 [예정](#예정) 참고)

## 목차

| 메서드 | 경로 | 설명 | 상태 |
|---|---|---|---|
| `GET` | [`/v1/search`](#get-v1search) | 키워드 본문 검색 (BM25 + KOMORAN) | ✅ |
| `GET` | [`/v1/suggest`](#get-v1suggest) | 자동완성 (edge_ngram) | ✅ |
| `GET` | [`/v1/instant`](#get-v1instant) | 추천어 + 결과 미리보기 (팬아웃) | ✅ |
| `POST` | [`/admin/reindex`](#post-adminreindex) | 무중단 전체 재색인 | ✅ |
| `POST` | [`/admin/reindex/incremental`](#post-adminreindexincremental) | 증분 색인 | ✅ |
| `GET` | `/actuator/health` · `/actuator/prometheus` | 상태·지표 | ✅ |

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
    { "placeId": "MA010120220800206533", "name": "스타",
      "category": "컴퓨터/소프트웨어 소매업", "dong": "논현2동", "score": 5.5697155 }
  ]
}
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

## `POST /admin/reindex`

무중단 전체 재색인 — 새 버전 인덱스를 뒤에서 만들고 alias만 원자적으로 옮겨요.
(3단계 실측: 64,239건 약 14초, 그 사이 검색 무중단)

```jsonc
{ "read": 64239, "searchIndexed": 64239, "suggestIndexed": 64239,
  "searchIndex": "place_search_v5", "suggestIndex": "place_suggest_v5",
  "removed": ["place_search_v4", "place_suggest_v4"] }
```

## `POST /admin/reindex/incremental`

체크포인트 이후 바뀐 것만 반영해요(멱등). 소프트 삭제된 행은 인덱스에서 지워요.

```jsonc
{ "since": "2026-07-22T19:35:06.111581+09:00", "matched": 1, "upserted": 1, "deleted": 0,
  "checkpoint": "2026-07-23T15:40:47.502760+09:00",
  "searchIndex": "place_search_v5", "suggestIndex": "place_suggest_v5" }
```

> ⚠️ `/admin/*` 는 **인증이 없어요.** 로컬 전용이라 그렇고, 운영이라면 관리자 인증과
> 레이트리밋이 필요해요 (아키텍처 크리틱 #9). 질의 전용 노드로 띄우면(`psp.role.indexer=false`)
> 이 경로 자체가 없어서 404가 나요.

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
| `psp_query_latency_seconds` | `channel=keyword\|suggest`, `outcome` | 채널별 질의 지연·실패 |
| `psp_index_lag_seconds` | – | 원천 최신 변경과 색인 체크포인트의 차이(초). **0이면 따라잡음**, -1이면 체크포인트 없음 |

채널을 나눠 재는 게 요점이에요. 합쳐 재면 "검색이 느리다"까지만 알고 *어디가* 느린지를 몰라요.

## 역할 분리

같은 아티팩트를 두 역할로 나눠 띄울 수 있어요 (아키텍처 크리틱 #5).

```bash
./gradlew bootRun --args='--psp.role.indexer=false'   # 질의 전용: /admin/* 없음, 색인 빈 없음
./gradlew bootRun --args='--psp.role.query=false'     # 색인 전용: /v1/* 없음
```

## 예정

| 단계 | 추가될 것 |
|---|---|
| 5 | 벡터 검색 채널 (Qdrant) |
| 6 | `/v1/search` 에 하이브리드 결합(RRF) — 응답 모양은 유지, 순위 산출만 바뀜 ([ADR 0003](adr/0003-hybrid-search-rrf-in-app-layer.md)) |
| 7 | 거리 기반 재랭킹 · 추천 엔드포인트 · 쿠키리스 세션 ([ADR 0004](adr/0004-cookieless-session-model.md), [0005](adr/0005-cold-start-and-recommend-strategy.md)) |
