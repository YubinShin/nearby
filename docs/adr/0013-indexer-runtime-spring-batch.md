# ADR 0013 — 색인기 런타임을 리액티브에서 Spring Batch + 블로킹으로 전환

- **상태:** Accepted
- **날짜:** 2026-07-25
- **관련:** ADR 0006 (질의 런타임 — 리액티브를 *고른* 결정), ADR 0011 (모듈 분리), ADR 0002 (무중단 인덱스 교체), ADR 0001 (멱등 색인)

## 배경

ADR 0006 에서 **질의 경로**를 WebFlux + 코루틴으로 정했습니다. 그 결정은 지금도 유효합니다 — 검색 API 는
동시 연결이 많고 대기 시간의 대부분이 ES·Qdrant 를 기다리는 시간입니다.

문제는 그 결정이 **색인기까지 따라왔다**는 것입니다. `indexer-batch` 는 WebFlux + 코루틴 + R2DBC 로
만들어져 있었는데, 색인기의 성질은 질의기와 정반대입니다.

| | search-api (질의) | indexer-batch (색인) |
|---|---|---|
| 동시성 | 수백~수천 요청 | **1** (한 번에 job 하나) |
| 한 번의 일 | 수십 ms | 15.6초 ~ 32분 |
| 병목 | I/O 대기 (ES 응답) | **CPU** (임베딩 추론이 96.1%) |
| 실패 시 | 그 요청만 500 | 재시작·재개가 필요 |

리액티브의 이득은 **동시 연결이 많을 때 스레드를 아끼는 것**입니다. 색인기의 동시성은 1 이라 그 축이
아예 없습니다. 대신 비용은 그대로 냈습니다. 그리고 실제로 냈습니다:

- **취소 경로 커넥션 누수.** `curl` 로 재색인을 걸고 중간에 끊으면 r2dbc 가 `DataRow.release()`
  누락 LEAK 을 냈습니다. 정상 경로에는 없고 **취소 경로에만** 있는 누수라 찾기가 어려웠습니다.
- **8분짜리 작업이 HTTP 요청 수명에 매달려 있었습니다.** `curl` 을 끊으면 색인도 죽었습니다.
  진행 상황을 확인할 수단은 로그뿐이었습니다.
- **`CheckpointStore` = 가난한 자의 Spring Batch.** 재시작 지점·건수 집계·실패 처리를 손으로
  재구현하고 있었습니다. 프레임워크가 이미 갖고 있는 것들입니다.

즉 리액티브는 **정작 느린 부분(CPU 바운드 임베딩)을 못 건드리면서**, 세금만 받아가고 있었습니다.

## 결정

색인기의 런타임을 바꿉니다. 네 가지를 함께 정했습니다.

### 1. 색인 실행은 Spring Batch 로

손으로 만든 적재 루프를 chunk 지향 step 으로 옮깁니다. 없앤 것들:

| 기존 수제 구현 | 지금 |
|---|---|
| `ArrayList` 에 batchSize 만큼 모아 `flush()` | chunk 크기 설정 |
| `stats.read++` 건수 세기 | `BATCH_STEP_EXECUTION.READ_COUNT` |
| "어디까지 했나" 지역 변수 | `ExecutionContext` (DB 에 저장됨) |
| `try/catch` 로 고아 인덱스 정리 | `JobExecutionListener` |
| 스케줄러 단일 스레드에 의존한 겹침 방지 | job 실행기 단일 스레드 풀 (명시적) |

**`CheckpointStore` 는 남깁니다.** 겹쳐 보이지만 기억하는 대상이 다릅니다 — `ExecutionContext` 는
"이번 job 이 몇 번째 행까지 읽었나"(job **안**의 재시작)이고, watermark 는 "원천의 어느 시점까지
반영됐나"(job **사이**의 도메인 상태)입니다. 후자는 다음 증분의 출발점이고 `IndexLagMetrics` 의
기준점이라, 프레임워크가 대신 가질 수 있는 종류의 값이 아닙니다.

### 2. 상시 앱 + job 비동기 실행 (run-and-exit 아님)

배치라고 해서 "돌고 끝나는 프로세스 + k8s CronJob" 으로 가지 않습니다. 그러면 잃는 게 많습니다 —
프로브, prometheus lag 지표, 데모용 즉시 트리거. 대신 앱은 계속 떠 있고 **job 만 비동기로** 실행됩니다.

```
POST /admin/reindex   → 202 {"jobId": 12, "poll": "/admin/jobs/12"}
GET  /admin/jobs/12   → 진행 상황 (읽은/쓴/커밋 건수 + 도메인 요약)
```

이게 위의 세 문제를 한꺼번에 없앱니다: ①끊어도 색인이 안 죽고 ②요청 취소와 색인 수명이 무관해져
누수 경로가 사라지고 ③진행률이 **조회 가능한 데이터**가 됩니다.

여기엔 부트 4 의 함정이 하나 있었습니다. Spring Batch 의 기본 실행기는 `SyncTaskExecutor` 라서
`JobOperator.start()` 가 **호출한 스레드에서 job 을 끝까지 돌립니다.** 이걸 안 갈면 202 자체가
불가능합니다 — 예전과 똑같이 요청이 8분간 매달립니다.

### 3. JobRepository 는 Postgres `BATCH_*` 테이블

인메모리(resourceless) 대안을 기각했습니다. 이력과 재시작 지점이 **남아야** "손으로 만든 체크포인트를
프레임워크로 대체했다"는 말이 성립합니다. 남지 않으면 파드가 재시작될 때 다 사라지고, 그건 전과
같은 상태입니다.

원천 읽기와 같은 DB 를 씁니다. job 메타데이터를 위해 저장소를 하나 더 띄우는 건 과하고, 색인기는
이미 이 DB 에 붙어 있습니다.

### 4. 색인기의 웹 스택은 MVC(Tomcat), 원천 접속은 JDBC

색인기 안에 성질이 다른 두 평면이 있습니다.

- **관리 평면** — HTTP, 상시 대기, ms 단위. 하는 일은 job 트리거·진행 조회·프로브·지표 스크레이프.
- **데이터 평면** — Batch, 블로킹, CPU 바운드, 분 단위.

관리 평면은 얇게 유지하면 되고 동시성이 낮으니 MVC 로 충분합니다. 데이터 평면은 리액티브를 완전히
제거합니다. 이건 ADR 0006 의 논리를 모듈 *사이*가 아니라 모듈 *안*에 한 번 더 적용한 것입니다.

R2DBC → JDBC 는 선택이 아니라 따라온 결과입니다. Batch 의 chunk 는 **트랜잭션 경계가 스레드에 묶인**
블로킹 모델이고, JobRepository 도 같은 `DataSource` 를 씁니다.

## 이걸 가능하게 한 전제 — `search-core` 를 쪼갰다

처음엔 MVC 전환을 접었습니다. 이유는 "`search-core` 가 `api("spring-boot-starter-webflux")` 를 걸고
있어서 WebFlux 가 어차피 딸려온다"였습니다. 그 전제 자체가 문제였습니다.

core 의 `QdrantStore` 가 `WebClient` 를 쓰는 바람에, **core 를 쓰는 모든 앱에 리액티브 스택이
강제**되고 있었습니다. 그래서 core 에서 **계약만 남기고 I/O 방법은 앱이 고르게** 했습니다.

| | 남는 곳 | 무엇 |
|---|---|---|
| 계약 | `search-core` `QdrantContract` | point id 계산법, 거리 함수, HNSW 파라미터, payload 인덱스 |
| 호출 방법 | `search-api` `QdrantSearchStore` | `WebClient` + `suspend`, `query` 만 |
| 호출 방법 | `indexer-batch` `QdrantIndexStore` | `RestClient` + 블로킹, 컬렉션 생명주기 + upsert/delete |

중복을 감수한 결정인데, 실제 중복은 거의 없었습니다 — **질의기는 `query` 하나만 쓰고, 색인기는
`query` 를 한 번도 안 씁니다.** 진짜로 어긋나면 안 되는 것(같은 place_id → 같은 point id, 같은 거리
함수)은 전부 계약 쪽에 있고, 그게 어긋나면 조용히 결과가 나빠지는 유일한 지점입니다.

같은 이유로 `IndexMetaStore` 와 `EmbeddingModel` 도 블로킹으로 내렸습니다. 둘 다 안쪽이 원래 동기
클라이언트였고(`ElasticsearchClient`, ONNX), 코루틴 껍데기 한 겹이 있었을 뿐입니다. 부수 효과로
**질의기에서 `runBlocking` 두 겹이 사라졌습니다** — 리액티브를 걷어내는 작업이 리액티브 쪽 코드를
더 깨끗하게 만들었습니다.

## 전환 시점

**R2DBC → JDBC 는 k8s 환경변수를 바꿉니다** (`SPRING_R2DBC_URL` → `SPRING_DATASOURCE_URL`).
이미지를 굽고 ECR 에 올린 **뒤에** 이걸 발견하면 이미지 재빌드 + 매니페스트 수정 + 재배포를 다시
돌아야 합니다. 이미지 빌드 전에 한 번에 처리하는 것이 쌉니다.

## 결과와 트레이드오프

**얻은 것**

- 색인기 클래스패스에서 reactor·webflux·r2dbc·coroutines·netty 가 **전부 사라졌습니다** (실측: 0개).
  `suspend` 함수도 0개입니다.
- 진행 상황이 로그 말고 **DB 에도** 기록됩니다. 파드를 재시작해도, 실패한 실행이 몇 건까지 갔는지 조회됩니다.
- chunk = 트랜잭션 = 재시작 단위. retry/skip 정책을 붙일 자리가 프레임워크에 이미 있습니다.
- 겹침 방지가 "스케줄러가 스레드 하나라서" 라는 우연이 아니라, **한 곳에 적힌 규칙**이 됐습니다.
  스케줄러든 HTTP 든 같은 단일 스레드 job 큐로 들어갑니다.

**대가**

- 코드가 한 함수에서 **step 세 개(prepare → load → promote)** 로 흩어졌습니다. 한 화면에 안 담깁니다.
  대신 순서가 구조로 드러납니다 — 적재가 실패하면 promote 는 아예 실행되지 않습니다.
- step 사이에 값을 넘기려면 `ExecutionContext` 를 거쳐야 합니다. 지역 변수보다 번거롭습니다.
- **chunk 트랜잭션이 ES·Qdrant 를 보호하지 않습니다.** 그 트랜잭션은 Postgres 의 Batch 메타데이터만
  감쌉니다. chunk 가 실패하면 "메타데이터는 롤백됐는데 ES 엔 일부 쓰인" 상태가 될 수 있습니다.
  안전한 이유는 되돌릴 수 있어서가 아니라 **색인이 멱등이라서**입니다 (ADR 0001).
- 부트 4 에서 `spring.batch.jdbc.initialize-schema` 가 없어져 `BATCH_*` DDL 을 앱이 직접 만듭니다.
  로컬·데모 편의고, 운영이면 마이그레이션 도구가 할 일입니다.

**의도적으로 안 한 것 — 전체 재색인의 재시작**

전체 재색인은 실행마다 새 `JobInstance` 가 되게 했습니다(`requestedAt` 파라미터). 즉 실패한 재색인을
**재시작하지 않고 다시 실행합니다.** 32분짜리 작업이라 재시작이 아까워 보이지만, 재시작은 몇 시간 전에
열어둔 커서를 이어받는 셈이라 **그때의 원천과 지금의 원천이 섞인 인덱스**가 만들어집니다. 전체
재색인이 원하는 건 한 시점의 일관된 스냅샷입니다. 증분은 애초에 watermark 로 이어받으므로 다시
실행하는 것이 곧 이어받는 것입니다.

**의도적으로 안 한 것 — 임베딩을 `ItemProcessor` 로**

모양으로는 "행 → 벡터" 가 processor 자리입니다. 그런데 Batch 의 processor 는 **한 건씩** 부릅니다.
임베딩은 배치로 넣어야 빠르고(ONNX 가 행렬 연산을 뭉칩니다), 색인 시간의 96.1%가 임베딩인
파이프라인에서 그걸 포기하면 리팩터가 성능 퇴행이 됩니다. chunk 전체를 볼 수 있는 writer 에서
배치 추론을 합니다 — **모양보다 실측을 따랐습니다.**

## 이 결정이 뒤집히는 조건

- **색인 동시성이 1 을 넘을 때.** 파티셔닝으로 여러 워커가 붙으면 리액티브가 아니라 Batch 의
  `partitioner` 로 가는 게 맞습니다 — 이미 그 자리가 있습니다.
- **관리 평면이 두꺼워질 때.** `/admin` 이 스트리밍 응답이나 SSE 진행률을 제공해야 한다면 MVC 가
  불편해집니다. 그때는 진행률 조회만 별도로 떼는 쪽이 먼저입니다.
- **원천이 CDC 스트림으로 바뀔 때.** ADR 0001 의 이벤트 트리거가 실제 스트림(Debezium 등)이 되면
  그건 `indexer-stream` 이라는 **다른 앱**이고, 거기서는 리액티브가 맞습니다. 이 ADR 은 배치 색인기에
  대한 것입니다.

## 구현 위치

| 모듈 | 파일 | 확정 커밋 | 날짜 |
|---|---|---|---|
| `indexer-batch` | `indexer/ReindexScheduler.kt` | `e0478c0` | 2026-07-25 |
| `indexer-batch` | `indexer/admin/AdminController.kt` | `e0478c0` | 2026-07-25 |
| `indexer-batch` | `indexer/batch/BatchConfig.kt` | `e0478c0` | 2026-07-25 |
| `indexer-batch` | `indexer/batch/BatchSchema.kt` | `ca99720` | 2026-07-25 |
| `indexer-batch` | `indexer/batch/IndexJobService.kt` | `e0478c0` | 2026-07-25 |
| `indexer-batch` | `indexer/batch/IndexJobs.kt` | `e0478c0` | 2026-07-25 |
| `indexer-batch` | `indexer/batch/KeywordIndexJobConfig.kt` | `e0478c0` | 2026-07-25 |
| `indexer-batch` | `indexer/batch/LoadProgress.kt` | `ca99720` | 2026-07-25 |
| `indexer-batch` | `indexer/batch/VectorIndexJobConfig.kt` | `e0478c0` | 2026-07-25 |
| `indexer-batch` | `indexer/index/CheckpointStore.kt` | `ca99720` | 2026-07-25 |
| `indexer-batch` | `indexer/index/EsBulkIndexer.kt` | `ca99720` | 2026-07-25 |
| `indexer-batch` | `indexer/index/IndexAdminService.kt` | `e0478c0` | 2026-07-25 |
| `indexer-batch` | `indexer/index/PlaceSource.kt` | `e0478c0` | 2026-07-25 |
| `indexer-batch` | `indexer/observability/IndexLagMetrics.kt` | `ca99720` | 2026-07-25 |
| `search-api` | `vector/PlaceVectorSearchService.kt` | `ca99720` | 2026-07-25 |
| `search-api` | `vector/QdrantSearchStore.kt` | `ca99720` | 2026-07-25 |
| `search-core` | `core/embed/EmbeddingModel.kt` | `ca99720` | 2026-07-25 |
| `search-core` | `core/meta/IndexMetaStore.kt` | `ca99720` | 2026-07-25 |
| `search-core` | `core/vector/QdrantContract.kt` | `ca99720` | 2026-07-25 |
