# Nearby

> **지역 검색·추천 플랫폼**
>
> 키워드 검색, 벡터 검색, 하이브리드 검색을 기반으로 장소를 검색하는 서비스이자,
> 여러 서비스에서 공통으로 사용할 수 있도록 설계한 검색 플랫폼입니다.

![Architecture](docs/diagrams/architecture.svg)

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
- [ ] 이벤트 트리거 색인 — 설계 완료([ADR 0001](docs/adr/0001-event-triggered-incremental-indexing.md)), 구현 예정

### 운영

- [x] 장애 발생 시 `degraded:true` 응답
- [x] Micrometer + Prometheus 기반 메트릭
- [x] 역할별 노드 분리 (Query / Indexer)

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

| 방식 | 평균 |
| --- | --- |
| Keyword | 9.2ms |
| Vector | 4.9ms |
| Hybrid | **16.3ms** |

- RRF 계산: 0.21ms

### 무중단 재색인

| 항목 | 대상 | 결과 |
| --- | --- | --- |
| 전체 재색인 중 검색 | 키워드 (ES) | 90회 요청 · 실패 0 |
| Alias Swap 20회 왕복 중 검색 | 벡터 (Qdrant) | 230회 요청 · 실패 0 · 빈 결과 0 |
| 컨테이너 중단 | ES / Qdrant | `200` + `degraded:true` |

---

## 아키텍처

검색 요청은 키워드 검색과 벡터 검색을 병렬 수행한 뒤 애플리케이션 레이어에서 RRF로 결합합니다.

원천 데이터(PostGIS)는 인덱스의 최신 `updated_at`을 watermark로 삼아 바뀐 행만 증분 색인하며,
Alias Swap을 이용해 무중단 재색인을 수행합니다. 색인 트리거는 현재 관리 API 호출이고,
원천 변경 이벤트로 자동 트리거하는 방식은 ADR 0001로 설계만 마친 상태입니다.

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

cd services/search-api
./gradlew bootRun

curl -XPOST localhost:8080/admin/reindex
curl -XPOST localhost:8080/admin/vector/reindex
```

상가정보 CSV 등 원천 데이터의 출처는 [docs/data-model.md](docs/data-model.md)에 정리했습니다.

브라우저에서 `http://localhost:8080`을 열면 동일한 질의를 키워드 검색, 벡터 검색,
하이브리드 검색으로 비교할 수 있습니다.

역할별 노드 분리는 동일 아티팩트에 옵션으로 지정합니다.

```bash
./gradlew bootRun --args='--psp.role.indexer=false'    # 질의 전용
./gradlew bootRun --args='--psp.vector.enabled=false'  # 키워드 전용
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
