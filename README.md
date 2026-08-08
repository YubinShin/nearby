# Nearby

> **키워드·벡터·하이브리드 검색을 지원하는 지역 검색 플랫폼**
> 강남구 상가 64,239건으로 색인부터 질의까지 수행하는 지역 검색 시스템입니다.

[![build](https://github.com/YubinShin/nearby/actions/workflows/build.yml/badge.svg)](https://github.com/YubinShin/nearby/actions/workflows/build.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-JVM%2021-7F52FF?logo=kotlin&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4-6DB33F?logo=springboot&logoColor=white)
![Elasticsearch](https://img.shields.io/badge/Elasticsearch-9.4.2-005571?logo=elasticsearch&logoColor=white)
![Qdrant](https://img.shields.io/badge/Qdrant-vector-DC244C)
![PostGIS](https://img.shields.io/badge/PostGIS-source-336791?logo=postgresql&logoColor=white)
![Kubernetes](https://img.shields.io/badge/Kubernetes-kind%20%C2%B7%20EKS-326CE5?logo=kubernetes&logoColor=white)

![세 채널 비교](docs/screenshots/three-channel-comparison.png)

> `회 먹을 데` — 키워드 0건, 벡터와 하이브리드는 50건. 
> *화면의 ms 는 데모를 위해 세 채널을 동시에 호출한 값입니다. 채널별 실측 값은 아래 [Benchmarks](#benchmarks) 에서 확인할 수 있습니다.*

---

## Overview


강남구 상가정보 **64,239건**을 대상으로 구축한 지역 검색 시스템입니다.

설계 결정은 [ADR](docs/adr/), 성능 측정은 [Benchmarks](#benchmarks)에 정리했습니다.

| Metric | Result |
|------------------------|-------------------------------------------------------------------------------------|
| **검색 정확도**        | 하이브리드 nDCG@10 0.85 (키워드 0.53 · 벡터 0.72) · LLM 질의 이해를 얹으면 **0.87** |
| **채널 결과 0건 질의** | 키워드 7/20 → 하이브리드 **0/20**                                                   |
| **응답 시간**          | 하이브리드 중앙값 **9.7ms** (RRF 평균 **0.04 ms**)       |
| **무중단 재색인**      | 20만+ 요청 동안 **실패 0 · 빈 결과 0** (EKS·Spring Batch 색인기 실측)               |
| **노드 분리 효과**     | 벡터 재색인 중 질의 지연 **1.09 → 0.90** (EKS 실측)                                 |
| **이미지 공유**        | 두 앱 합산 1,403MB → **917MB**                                                      |

---

## How It Works

`회 먹을 데`처럼 **단어가 일치하지 않는 질의**도 검색됩니다.

```bash
curl -G localhost:8080/v1/hsearch --data-urlencode "q=회 먹을 데"

→ 먹어도
→ 마시아
→ 어방참치
```

BM25 + KOMORAN 기반 키워드 검색과 임베딩 기반 벡터 검색을 [**RRF**](services/search-api/src/main/kotlin/dev/yubin/search/hybrid/Rrf.kt)로 결합합니다. 두 채널은 코루틴으로 병렬 호출하며, 한 채널이 실패해도 다른 채널의 결과를 반환합니다 — [`HybridSearchService`](services/search-api/src/main/kotlin/dev/yubin/search/hybrid/HybridSearchService.kt) (`degraded: true`).

---

## Quick Start

```bash
# 1. 인프라 (Elasticsearch + Qdrant + PostGIS + Redis)
./deploy/up.sh

# 2. 원천 데이터와 임베딩 모델
./scripts/load_place.sh
./scripts/fetch_embedding_model.sh

# 3. 색인기 (8081) — 원천을 읽어 검색 엔진에 색인
cd services && ./gradlew :indexer-batch:bootRun

curl -XPOST localhost:8081/admin/reindex          # 키워드 · 15.6초 (2026-07-25 실측)
curl -XPOST localhost:8081/admin/vector/reindex   # 벡터 · 8분 32초 (2026-07-25 실측)

# 4. 검색기 (8080)
./gradlew :search-api:bootRun
```

Kubernetes 환경 실행 시 → [deploy/k8s/README.md](deploy/k8s/README.md)

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
| [`GET /admin/jobs/{jobId}`](docs/api-spec.md#get-adminjobsjobid) | 색인 진행·결과 |
| [`GET /v1/ask`](services/ask-api/README.md) | 자연어 질의 이해 — **`ask-api`(8082)** |

전체 명세 → [docs/api-spec.md](docs/api-spec.md)

---

## Features

**Search**

- [x] BM25 + KOMORAN 키워드 검색 · 0건 시 조건 완화 폴백
- [x] 임베딩 + Qdrant 벡터 검색 (엔진 내 필터·반경)
- [x] Application Layer RRF 하이브리드
- [x] Coroutine Fan-out 병렬 질의
- [x] edge_ngram 자동완성
- [x] LLM 질의 이해 — 자연어를 검색 요청으로 (`ask-api`) · [ADR 0014](docs/adr/0014-ask-api-llm-query-understanding.md)
- [x] 근거 기반 답변 생성 — 검색 결과만 근거로 사용, 검증기가 검색 결과에 없는 근거를 제거하고 계약 위반을 기록 · [ADR 0015](docs/adr/0015-ask-api-grounded-answer-generation.md)

**Indexing**

- [x] Watermark 기반 증분 색인
- [x] Alias Swap 무중단 재색인 · 버전 인덱스 reconcile
- [x] 색인 계약 **런타임 버전 도장** — 모델·스키마가 어긋나면 질의기 기동 차단
- [ ] 이벤트 트리거 색인 — [ADR 0001](docs/adr/0001-event-triggered-incremental-indexing.md)

**Operations**

- [x] 색인기 / 질의기 **별도 아티팩트** 분리
- [x] Kubernetes 배포 (kustomize · kind · EKS) · 노드 분리 실측
- [x] 채널 장애 시 `degraded: true` 부분 응답
- [x] Micrometer + Prometheus 메트릭
- [ ] Kafka 스트리밍 색인 — 자리를 `indexer-stream` 빈 모듈로 확보 · [ADR 0001](docs/adr/0001-event-triggered-incremental-indexing.md)
- [ ] 멀티클러스터 — [ADR 0002](docs/adr/0002-index-and-cluster-separation.md)

**Recommendation**

- [ ] 인기 + 거리 콜드 스타트 — [ADR 0005](docs/adr/0005-cold-start-and-recommend-strategy.md)
- [ ] Cookie-less Session — [ADR 0004](docs/adr/0004-cookieless-session-model.md)

---

## Architecture

검색은 키워드와 벡터를 병렬 수행한 뒤 애플리케이션 레이어에서 RRF로 결합합니다.

색인은 PostGIS를 원천으로 사용하며, 체크포인트를 watermark로 삼아 변경된 행만 증분 색인합니다. 배포는 Alias Swap으로 무중단 교체합니다.

![Architecture](docs/diagrams/architecture.png)

### Module Separation

|                | Query               | Index                     |
| -------------- | ------------------- | ------------------------- |
| Artifact       | `search-api`        | `indexer-batch`           |
| Runtime        | WebFlux + Coroutine | Spring Batch + MVC + JDBC |
| Workload       | Low latency         | CPU burst                 |
| Main cost      | Request processing  | Embedding inference (96%) |
| Failure impact | Search availability | Reindex job               |

공유 계약은 `search-core`에서 관리합니다.

| Module           | Responsibility                                            |
| ---------------- | --------------------------------------------------------- |
| `search-core`    | 문서 스키마 · 브랜드 규칙 · 임베딩 모델                                  |
| `search-api`     | 질의 처리                                                     |
| `indexer-batch`  | 배치 색인                                                     |
| `indexer-stream` | 이벤트 색인 (TBD)                                              |
| `ask-api`        | 자연어 질의 이해 (`search-core`에 의존하지 않고 `/v1/hsearch`를 HTTP 호출) |

### Node Separation

동일한 EKS 2노드 환경에서 Query와 Index를 분리 배포했습니다.

| Metric                 | Before |    After |
| ---------------------- | -----: | -------: |
| Vector search latency  |   1.09 | **0.90** |
| Keyword search latency |   1.11 |     1.07 |

![Deployment view](docs/diagrams/deploy.png)

---

## Benchmarks

### Search quality

골든셋 25질의 · 정답 1,088건 · k=10 · 2026-08-08

| Method                               | Precision@10 |      MRR |  nDCG@10 |
| ------------------------------------ | -----------: | -------: | -------: |
| Keyword                              |         0.51 |     0.64 |     0.53 |
| Vector                               |         0.71 |     0.80 |     0.72 |
| Hybrid                               |         0.85 |     0.88 |     0.85 |
| **Hybrid + LLM query understanding** |     **0.86** | **0.98** | **0.87** |

`Recall@10`은 뺐습니다. 정답 수가 질의마다 25건에서 65건까지 달라 `k=10`에서 상한이 평균 0.248 입니다. 채널 간 비교에 쓰기 어렵습니다. 값 자체는 `scripts/eval/scores/`에 남아 있습니다.

* 라벨링 절차 · 재현 명령 · 측정 한계: [골든셋](scripts/eval/README.md)

### Search Latency

20 representative queries · 2026-08-03

| Method     | Zero results |     Median |         P95 |
| ---------- | -----------: | ---------: | ----------: |
| Keyword    |       7 / 20 |     5.3 ms |      9.1 ms |
| Vector     |       2 / 20 |     4.4 ms |      5.0 ms |
| **Hybrid** |   **0 / 20** | **9.7 ms** | **16.2 ms** |

* 검색 방식별 응답 차이: [search-modes-comparison.md](docs/search-modes-comparison.md)

### Node Separation

EKS 2노드 · 동일한 파드 스펙 기준 

| Scenario        |     Split | Combined |
| --------------- | --------: | -------: |
| Vector reindex  | **0.90×** |    1.09× |
| Keyword reindex |     1.07× |    1.11× |

### Image Size

470MB 임베딩 모델을 베이스 이미지로 공유합니다.

|                  |   Before |      After |
| ---------------- | -------: | ---------: |
| Total image size | 1,403 MB | **917 MB** |
| Saved            |          | **486 MB** |

---

## Design Decisions

주요 설계 결정과 트레이드오프는 [ADR](docs/adr/)에 기록했습니다.

| ADR | Decision | Status |
| --- | --- | --- |
| [0001](docs/adr/0001-event-triggered-incremental-indexing.md) | 이벤트 기반 증분 색인 | 증분 구현 · 트리거 예정 |
| [0002](docs/adr/0002-index-and-cluster-separation.md) | 인덱스·클러스터 분리 | 인덱스 구현 · 클러스터 예정 |
| [0003](docs/adr/0003-hybrid-search-rrf-in-app-layer.md) | Application Layer RRF | 구현 |
| [0004](docs/adr/0004-cookieless-session-model.md) | Cookie-less Session | 예정 |
| [0005](docs/adr/0005-cold-start-and-recommend-strategy.md) | Cold Start | 예정 |
| [0006](docs/adr/0006-api-runtime-reactive-vs-blocking.md) | 질의기 WebFlux + Coroutine | 구현 |
| [0007](docs/adr/0007-vector-engine-qdrant-vs-milvus.md) | Qdrant 선택 | 구현 |
| [0008](docs/adr/0008-korean-analyzer-komoran-vs-nori.md) | KOMORAN 재포팅 | 구현 |
| [0009](docs/adr/0009-keyword-ranking-and-fallback.md) | 키워드 랭킹·폴백 | 구현 |
| [0010](docs/adr/0010-embedding-model-and-serving.md) | 임베딩 모델·추론 위치 | 구현 |
| [0011](docs/adr/0011-module-split-and-index-contract.md) | 아티팩트 분리 · 색인 계약 대조 | 구현 |
| [0012](docs/adr/0012-manifests-in-monorepo.md) | 배포 매니페스트를 모노레포에 | 구현 |
| [0013](docs/adr/0013-indexer-runtime-spring-batch.md) | 색인기를 Spring Batch로 | 구현 |
| [0014](docs/adr/0014-ask-api-llm-query-understanding.md) | 자연어 질의 이해를 `ask-api`로 분리 | 구현 |
| [0015](docs/adr/0015-ask-api-grounded-answer-generation.md) | 근거 기반 답변 생성 (opt-in) | 구현 |

각 ADR에는 배경, 결정, 트레이드오프와 구현 위치를 함께 기록했습니다.

아키텍처 검토와 측정 결과는 [Architecture Review](docs/architecture-review.md)에서 다룹니다.

---

## Tech Stack

| Category | Technology |
| --- | --- |
| Language | Kotlin (JVM 21) |
| Query Service | Spring Boot · WebFlux · Kotlin Coroutines |
| Index Service | Spring Boot · Spring Batch · Spring MVC · JDBC |
| Search | Elasticsearch 9.4.2 · KOMORAN |
| Vector | Qdrant · DJL · ONNX Runtime · multilingual-e5-small (384d) |
| Data | PostgreSQL · PostGIS |
| Deployment | Docker Compose · Kubernetes · Kustomize · kind · Amazon EKS |
| Observability | Micrometer · Prometheus |
| Cache | Redis *(TBD)* |

---

## Documentation

| 문서 | 내용 |
| --- | --- |
| [architecture.md](docs/architecture.md) | 아키텍처 |
| [api-spec.md](docs/api-spec.md) | API 명세 |
| [adr/](docs/adr/) | 설계 결정 15편 |
| [architecture-review.md](docs/architecture-review.md) | 예상과 다른 결과·한계 |
| [troubleshooting.md](docs/troubleshooting.md) | 증상별 원인과 조치 |
| [eks-cluster-notes.md](docs/eks-cluster-notes.md) | EKS 클러스터·오버레이 설정 근거 |
| [search-modes-comparison.md](docs/search-modes-comparison.md) | 세 검색 방식 비교 |
| [data-model.md](docs/data-model.md) | 데이터 모델·원천 출처 |
| [roadmap.md](docs/roadmap.md) | 로드맵 |
| [glossary.md](docs/glossary.md) | 용어 사전 |
