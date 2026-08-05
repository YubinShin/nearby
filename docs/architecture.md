# Architecture

## Overview

원천 데이터는 PostGIS에 저장하고, 검색을 위해 Elasticsearch와 Qdrant에 각각 색인합니다.
검색 요청은 두 엔진을 병렬로 실행한 뒤 애플리케이션에서 Reciprocal Rank Fusion(RRF)으로 결합합니다.

증분 색인은 watermark 이후 변경분만 반영하며, 전체 재색인은 alias 스왑으로 무중단 교체합니다.

![Architecture](diagrams/architecture.png)

## Current Status

| Area | Status |
|---|---|
| 키워드 · 벡터 · 하이브리드 검색, 자동완성 | 구현 |
| LLM 질의 이해 · 근거 기반 답변 (`ask-api`) | 구현 |
| watermark 증분 색인 · alias 스왑 무중단 재색인 | 구현 |
| 색인 계약 버전 도장 | 구현 |
| Kubernetes 배포 (kind · EKS) | 구현 |
| 이벤트 트리거 색인 | TBD ([ADR 0001](adr/0001-event-triggered-incremental-indexing.md)) |
| 클러스터 분리 | TBD ([ADR 0002](adr/0002-index-and-cluster-separation.md)) |
| Redis · 추천 · 쿠키리스 세션 | TBD ([ADR 0004](adr/0004-cookieless-session-model.md) · [ADR 0005](adr/0005-cold-start-and-recommend-strategy.md)) |
| Kafka 스트리밍 색인 (`indexer-stream`) | TBD ([ADR 0001](adr/0001-event-triggered-incremental-indexing.md)) |

## Components

| Component | Role |
| --- | --- |
| PostGIS | 원천 저장소 · 공간 데이터 |
| Elasticsearch | BM25 검색 · 자동완성 · 색인 계약 메타 (`psp_index_meta`) |
| Qdrant | 벡터 검색 (384-dimensional embeddings) |
| Redis | 세션 · 인기 순위 (TBD — `docker-compose` 에만 존재하며 애플리케이션에서는 아직 사용하지 않음) |
| `search-api` (8080) | 검색 API (읽기 전용) |
| `indexer-batch` (8081) | 배치 색인 (쓰기 전용) |
| `ask-api` (8082) | 자연어 질의 이해 · 근거 기반 답변 |
| `indexer-stream` | 이벤트 기반 색인 (TBD) |
| `search-core` | 공유 계약 라이브러리 (문서 스키마 · 브랜드 규칙 · 임베딩 모델) |

`search-api`와 `indexer-batch`는 별도 아티팩트로 빌드 및 배포합니다 ([ADR 0011](adr/0011-module-split-and-index-contract.md)). `ask-api`는 `search-core`에 직접 의존하지 않고 `/v1/hsearch`를 HTTP로 호출합니다 ([ADR 0014](adr/0014-ask-api-llm-query-understanding.md)).

## Data Ingestion

공공데이터포털의 상가정보(소상공인시장진흥공단)와 지방행정 인허가 데이터(행정안전부)를 PostGIS에 원천으로 적재합니다.

검색 엔진은 원천을 매번 전체 재색인하지 않습니다. 마지막 색인 시각(watermark) 이후 변경된 데이터만 Elasticsearch와 Qdrant에 반영합니다.

증분 색인은 `POST /admin/reindex/incremental`로 실행하거나 스케줄러를 통해 수행합니다. 스케줄러는 `psp.index.schedule.enabled` 설정으로 제어하며 기본값은 `false`입니다.

이벤트 트리거 색인은 구현 예정입니다 ([ADR 0001](adr/0001-event-triggered-incremental-indexing.md)).

## Query Path

예시 질의: `분위기 좋은 강남 파스타집`

1. `search-api`가 Elasticsearch와 Qdrant를 병렬로 조회합니다. Elasticsearch는 키워드(BM25), Qdrant는 임베딩 유사도 기반으로 각각 순위를 생성합니다.
2. 두 결과를 애플리케이션에서 Reciprocal Rank Fusion(RRF)으로 결합합니다. RRF는 점수가 아닌 순위를 기준으로 결합하므로, 검색 엔진별 점수 분포 차이에 영향을 받지 않습니다 ([ADR 0003](adr/0003-hybrid-search-rrf-in-app-layer.md)).
3. 요청에 좌표가 포함되면 각 결과에 직선거리(m)를 계산해 추가합니다. 반경 필터링은 Elasticsearch와 Qdrant가 각각 수행하며, 하이브리드 순위는 거리 기준으로 재정렬하지 않습니다.
4. 최종 결과를 반환합니다.

Elasticsearch와 Qdrant 조회는 WebFlux와 Kotlin Coroutines로 병렬 실행합니다 ([ADR 0006](adr/0006-api-runtime-reactive-vs-blocking.md)). 한 채널이 실패하면 나머지 채널의 결과를 `degraded: true`와 함께 반환합니다.

### Natural Language Query

`ask-api`는 자연어 질의를 LLM으로 구조화한 뒤 `/v1/hsearch`를 호출합니다. 
응답에는 LLM이 해석한 결과(`parsed`)와 실제 검색에 적용된 파라미터(`applied`)를 함께 포함해 검색 요청의 근거를 제공합니다.
(LLM 호출에 실패하면 원문 질의 그대로 검색합니다.)

`answer=true`일 때만 검색 결과를 근거로 답변을 생성합니다. 
`GroundingValidator`가 생성된 답변을 검사해, 검색 결과에 없는 `place_id`를 근거에서 제거하고 `droppedEvidence`에 기록합니다. 답변 문장 자체는 지우지 않으므로 이 목록이 비어 있지 않다는 것은 생성이 계약을 벗어났다는 신호입니다 ([ADR 0014](adr/0014-ask-api-llm-query-understanding.md), [ADR 0015](adr/0015-ask-api-grounded-answer-generation.md)).

## Recommendation (TBD)

추천은 세 가지 시나리오로 구분합니다.

- **Similar Places** — 현재 장소와 임베딩 유사도가 높은 장소를 Qdrant에서 조회합니다.
- **Session-based Recommendation** — 세션 내 열람 순서를 기반으로 다음 후보를 추천합니다.
- **Cold Start** — 행동 데이터가 없는 신규 사용자는 Redis의 인기 순위와 거리 정보를 기반으로 추천합니다 ([ADR 0005](adr/0005-cold-start-and-recommend-strategy.md)).

콘텐츠 데이터와 사용자 행동 데이터는 별도로 관리합니다. 장소 정보는 증분 색인으로 관리하고, 사용자 행동은 Redis에서 처리하여 변경 주기가 다른 데이터를 분리합니다.

## Cookie-less Session (TBD)

추천을 위해 세션 내 행동은 유지하지만 장기 사용자 식별은 하지 않습니다. 브라우저 `sessionStorage`에 세션 UUID만 두고 해당 세션의 행동만 추천에 사용합니다.

식별 방식 · 프라이버시 근거 · 만료 정책은 [ADR 0004](adr/0004-cookieless-session-model.md)에 정리했습니다.

## Component Separation

- **인덱스 분리** — 본문 검색과 자동완성은 요구사항이 다르므로 별도 인덱스로 관리합니다. 현재는 하나의 Elasticsearch 노드를 공유하며, 클러스터 분리는 TBD입니다 ([ADR 0002](adr/0002-index-and-cluster-separation.md)).
- **검색 엔진 역할 분리** — 키워드 검색은 Elasticsearch, 의미 기반 검색은 Qdrant가 담당합니다. 하나의 검색 엔진에 두 역할을 모두 맡기지 않습니다.
- **단일 진입점** — 검색을 사용하는 서비스는 검색 엔진 구성을 알 필요 없이 `search-api`만 호출합니다.
- **색인기·질의기 분리** — 읽기 경로와 쓰기 경로를 분리합니다. ([Indexer / Searcher Split](#indexer--searcher-split))

## Indexer / Searcher Split

색인과 검색은 부하 특성이 다르므로 별도 애플리케이션으로 분리합니다.

| Attribute | `indexer-batch` | `search-api` |
| --- | --- | --- |
| 부하 형태 | 배치 집중 — 벡터 재색인 8분 32초, 그중 96.1%가 임베딩 계산 (2026-07-25 실측) | 상시 저지연 — 하이브리드 중앙값 9.7ms (2026-08-03 실측) |
| 목표 | 완주 | 응답 시간 |
| 중단 영향 | 색인 지연 | 검색 장애 |
| 동시성 | 한 번에 하나 | 동시 1,000 요청에서 실패 0 · `degraded` 0 (서울 531,528건, 2026-08-03 실측) |
| 런타임 | Spring Batch + 블로킹 ([ADR 0013](adr/0013-indexer-runtime-spring-batch.md)) | WebFlux + 코루틴 ([ADR 0006](adr/0006-api-runtime-reactive-vs-blocking.md)) |

하나의 프로세스에서 두 워크로드를 함께 실행하면 색인 작업의 메모리 부족(OOM)이 검색 장애로 이어질 수 있습니다. 이를 방지하기 위해 `search-api`에는 색인 코드를 포함하지 않고 별도 아티팩트로 배포합니다.

### Index Contract

단일 프로세스에서는 색인과 질의가 항상 같은 임베딩 모델을 사용했습니다. 하지만 색인기와 질의기를 별도 배포하면 색인기만 새 모델로 교체되고 질의기는 이전 모델을 사용하는 상태가 발생할 수 있습니다. 이 경우 예외는 발생하지 않지만 검색 점수가 조용히 잘못됩니다.

이를 방지하기 위해 색인기는 색인 완료 시 사용한 임베딩 모델 정보를 기록하고, 질의기는 기동 시 해당 정보가 자신의 모델과 일치하는지 검사합니다. 계약이 일치하지 않으면 질의기는 기동하지 않습니다 ([ADR 0011](adr/0011-module-split-and-index-contract.md)).

| Decision | Reason |
| --- | --- |
| 계약 불일치 시 질의기 기동 차단 | 잘못된 검색 결과를 제공하는 것보다 기동 실패가 원인을 파악하고 복구하기 쉽습니다. |

## Infrastructure

로컬 개발 환경은 `docker compose`를 사용하고, 배포 환경은 Kubernetes(kind, EKS)를 사용합니다. 인덱스 교체는 alias를 새 인덱스로 전환하는 방식으로 무중단 수행합니다.

## References

- [ADR](adr/) — 아키텍처 및 설계 결정
- [api-spec.md](api-spec.md) — API 명세
- [architecture-review.md](architecture-review.md) — 설계 과정에서 확인한 한계와 개선점
- [data-model.md](data-model.md) — 데이터 모델 및 원천 데이터
- [glossary.md](glossary.md) — 용어 사전
