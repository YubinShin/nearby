# Nearby

> **지역 검색·추천 플랫폼**
>
> 키워드 검색, 벡터 검색, 하이브리드 검색을 기반으로 장소를 검색하는 서비스이자,
> 여러 서비스에서 공통으로 사용할 수 있도록 설계한 검색 플랫폼입니다.

![Architecture](docs/diagrams/architecture.png)

---

## 프로젝트 소개

실무에서 다루는 검색 플랫폼을 공개 가능한 형태로 재구성한 프로젝트입니다.

검색 엔진 운영, 색인 파이프라인, 키워드 검색, 벡터 검색, 하이브리드 검색까지
검색 플랫폼의 핵심 구성을 직접 구현했습니다.

강남구 상가정보 **64,239건**을 기반으로 실제 동작과 성능을 검증했습니다.

---

## 핵심 기능

체크된 항목은 구현·실측을 마친 기능이고, 나머지는 설계만 마친 예정 범위입니다.

### 검색

- [x] BM25 + KOMORAN 기반 키워드 검색
- [x] 임베딩 + Qdrant 기반 벡터 검색
- [x] Application Layer RRF 기반 하이브리드 검색
- [x] Coroutine Fan-out 기반 병렬 검색
- [x] edge_ngram 자동완성

### 색인

- [x] Watermark 기반 증분 색인 (`/admin/reindex/incremental`)
- [x] Alias Swap 기반 무중단 재색인
- [x] PostGIS 원천 데이터 관리
- [x] 색인 계약 버전 도장 — 모델·스키마가 어긋나면 질의기 기동 차단, 증분 색인 거부
- [ ] 이벤트 트리거 색인 — 설계 완료([ADR 0001](docs/adr/0001-event-triggered-incremental-indexing.md)), 구현 예정

### 운영

- [x] 장애 발생 시 `degraded:true` 응답
- [x] Micrometer + Prometheus 기반 메트릭
- [x] 색인기 / 질의기 **별도 아티팩트** 분리 ([ADR 0011](docs/adr/0011-module-split-and-index-contract.md))

### 추천 (예정)

- [ ] 인기 + 거리 기반 콜드 스타트 — [ADR 0005](docs/adr/0005-cold-start-and-recommend-strategy.md)
- [ ] Cookie-less Session — [ADR 0004](docs/adr/0004-cookieless-session-model.md)

---

## API

| API | 설명 |
| --- | --- |
| [`GET /v1/search`](docs/api-spec.md#get-v1search) | 키워드 검색 |
| [`GET /v1/vsearch`](docs/api-spec.md#get-v1vsearch) | 벡터 검색 |
| [`GET /v1/hsearch`](docs/api-spec.md#get-v1hsearch) | 하이브리드 검색 |
| [`GET /v1/suggest`](docs/api-spec.md#get-v1suggest) | 자동완성 |
| [`GET /v1/instant`](docs/api-spec.md#get-v1instant) | 추천어 + 결과 미리보기 |
| [`POST /admin/reindex`](docs/api-spec.md#post-adminreindex) | 전체 재색인 |
| [`POST /admin/reindex/incremental`](docs/api-spec.md#post-adminreindexincremental) | 증분 색인 |
| [`POST /admin/vector/reindex`](docs/api-spec.md#post-adminvectorreindex) | 벡터 재색인 |
| `POST /admin/vector/reindex/incremental` | 벡터 증분 색인 |

### 하이브리드 검색 예시

```bash
curl -G localhost:8080/v1/hsearch \
  --data-urlencode "q=회 먹을 데"

→ 먹어도
→ 마시아
→ 어방참치
```

---

## 실측 결과

### 검색 품질

실제 사용자가 입력할 법한 질의 20개를 기준으로 측정했습니다.

| 방식 | 0건 질의 |
| --- | --- |
| 키워드 | 10 / 20 |
| Hybrid | **1 / 20** |

'회 먹을 데', '머리 자르는 곳', '매운 거 먹고 싶다'처럼 단어가 일치하지 않는 질의도
검색할 수 있음을 확인했습니다.

### 응답 시간

예열 후 50회, Prometheus 카운터 차분 기준입니다.

| 방식 | 중앙값 | p95 |
| --- | --- | --- |
| Keyword | 4.6ms | 7.0ms |
| Vector | 4.7ms | 5.7ms |
| Hybrid | **8.2ms** | 11.4ms |

- RRF 계산 자체: 0.21ms — 하이브리드 비용은 결합이 아니라 **두 번 부르고 깊게 뜨는 것**에서 옵니다.
- 회귀 측정은 스크립트로 고정했습니다 (`scripts/measure_search.py`, 질의 20개 × 5회).

### 무중단 재색인

| 항목 | 대상 | 결과 |
| --- | --- | --- |
| 전체 재색인 중 검색 | 키워드 (ES) | 90회 요청 · 실패 0 |
| Alias Swap 20회 왕복 중 검색 | 벡터 (Qdrant) | 230회 요청 · 실패 0 · 빈 결과 0 |
| 컨테이너 중단 | ES / Qdrant | `200` + `degraded:true` |

---

## 아키텍처

검색 요청은 키워드 검색과 벡터 검색을 병렬 수행한 뒤 애플리케이션 레이어에서 RRF로 결합합니다.

원천 데이터(PostGIS)는 체크포인트를 watermark로 삼아 바뀐 행만 증분 색인하며,
Alias Swap을 이용해 무중단 재색인을 수행합니다. 색인 트리거는 현재 관리 API 호출이고,
원천 변경 이벤트로 자동 트리거하는 방식은 ADR 0001로 설계만 마친 상태입니다.

**색인기와 검색기는 별도 아티팩트입니다.** 두 앱의 자원 성격이 반대이고(색인은 CPU 버스트 —
벡터 재색인 492초 중 471초가 임베딩 추론, 질의는 저지연 상시 대기), 한 프로세스에 두면 색인
쪽 OOM 한 번이 곧 검색 장애가 되기 때문입니다. 공유해야 하는 것(문서 스키마·브랜드 규칙·임베딩
모델)은 `search-core` 한 벌만 두고, 따로 배포되면서 어긋나는 것은 **런타임 버전 도장**으로
막습니다 ([ADR 0011](docs/adr/0011-module-split-and-index-contract.md)).

자세한 내용은 [docs/architecture.md](docs/architecture.md)를 참고하세요.

---

## 설계 의사결정 (ADR)

주요 설계 결정과 트레이드오프를 [ADR](docs/adr/)로 기록했습니다.

| | 결정 | 구현 |
| --- | --- | --- |
| [0001](docs/adr/0001-event-triggered-incremental-indexing.md) | 이벤트 기반 증분 색인 | 증분 색인만 구현 · 트리거 예정 |
| [0002](docs/adr/0002-index-and-cluster-separation.md) | 검색 인덱스 분리 | 인덱스 분리 구현 · 클러스터 분리 예정 |
| [0003](docs/adr/0003-hybrid-search-rrf-in-app-layer.md) | Application Layer RRF | 구현 |
| [0004](docs/adr/0004-cookieless-session-model.md) | Cookie-less Session | 예정 |
| [0005](docs/adr/0005-cold-start-and-recommend-strategy.md) | Cold Start | 예정 |
| [0006](docs/adr/0006-api-runtime-reactive-vs-blocking.md) | WebFlux + Coroutine | 구현 |
| [0007](docs/adr/0007-vector-engine-qdrant-vs-milvus.md) | Qdrant 선택 | 구현 |
| [0008](docs/adr/0008-korean-analyzer-komoran-vs-nori.md) | KOMORAN 재포팅 | 구현 |
| [0009](docs/adr/0009-keyword-ranking-and-fallback.md) | 키워드 랭킹 및 폴백 | 구현 |
| [0010](docs/adr/0010-embedding-model-and-serving.md) | 임베딩 모델 및 추론 | 구현 |
| [0011](docs/adr/0011-module-split-and-index-contract.md) | 색인기/질의기 아티팩트 분리 · 색인 계약 대조 | 구현 |

---

## Architecture Review

예상과 다른 결과와 설계상의 한계를 [함께 기록](docs/architecture-review.md)했습니다.

대표 사례

- Threshold 0.78 → 0.84
- Threshold 기반 접근의 한계
- Hybrid Search의 한계
- 마지막 단계 복구 API 부재

---

## 로컬 실행

```bash
./deploy/up.sh

./scripts/load_place.sh

./scripts/load_boundaries.sh
./scripts/load_localdata.sh
./scripts/recover_brands.sh

./scripts/fetch_embedding_model.sh

cd services

# 색인기 (8081) — 원천을 읽어 검색 엔진에 밀어넣습니다
./gradlew :indexer-batch:bootRun

curl -XPOST localhost:8081/admin/reindex          # 키워드 전체 재색인 (17초)
curl -XPOST localhost:8081/admin/vector/reindex   # 벡터 전체 재색인 (8분 12초)

# 검색기 (8080) — 재색인을 마친 뒤 띄웁니다
./gradlew :search-api:bootRun
```

색인기와 검색기는 **별도 아티팩트**라 각각 띄웁니다. 검색기는 기동할 때 색인된 데이터가
자기와 같은 계약(문서 스키마·임베딩 모델)으로 만들어졌는지 대조하고, 다르면 기동하지
않습니다 ([ADR 0011](docs/adr/0011-module-split-and-index-contract.md)).

상가정보 CSV 등 원천 데이터의 출처는 [docs/data-model.md](docs/data-model.md)에 정리했습니다.

브라우저에서 `http://localhost:8080`을 열면 동일한 질의를 키워드 검색, 벡터 검색,
하이브리드 검색으로 비교할 수 있습니다.

검색기에서 벡터 채널을 끄면 임베딩 모델을 아예 읽지 않습니다(메모리 0.5GB · 기동 5.6초 절약).

```bash
./gradlew :search-api:bootRun --args='--psp.vector.enabled=false'   # 키워드 전용
```

---

## 문서

| 문서 | 내용 |
| --- | --- |
| [glossary.md](docs/glossary.md) | 용어 사전 |
| [architecture.md](docs/architecture.md) | 아키텍처 |
| [api-spec.md](docs/api-spec.md) | API 명세 |
| [architecture-review.md](docs/architecture-review.md) | Architecture Review |
| [roadmap.md](docs/roadmap.md) | 로드맵 |
| [data-model.md](docs/data-model.md) | 데이터 모델 |
| [adr/](docs/adr/) | ADR |

---

## 기술 스택

- Kotlin
- Spring Boot (WebFlux)
- Kotlin Coroutine
- Elasticsearch
- KOMORAN
- Qdrant
- DJL / ONNX Runtime
- PostgreSQL / PostGIS
- Redis
- Docker Compose
- Micrometer
- Prometheus

> Kubernetes, Kafka, 멀티클러스터는 현재 구현 범위를 넘어서는 운영 영역으로 판단하여
> 설계 문서로만 정리했습니다.
