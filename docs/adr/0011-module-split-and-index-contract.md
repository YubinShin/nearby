# ADR 0011 — 색인기와 질의기를 별도 아티팩트로 분리, 색인 계약은 런타임에 대조

- **상태:** Accepted
- **날짜:** 2026-07-24
- **관련:** ADR 0002 (용도별 인덱스·클러스터 분리), ADR 0010 (임베딩 모델 선택과 추론 위치 — 이 결정이 깨뜨리는 보호), ADR 0001 (이벤트 트리거 증분), ADR 0008 (형태소 사전 — 같은 문제의 첫 형태)
- **닫는 아키텍처 리뷰:** #5(부분 → 완전), #14, #15

## Context

`search-api` 하나가 쓰기(색인)와 읽기(질의)를 겸했습니다. 4단계에서 `psp.role.indexer` / `psp.role.query` **런타임 플래그**를 붙여 "같은 아티팩트를 두 역할로 나눠 배포하는 것"까지는 했습니다(#5). 하지만 나눠 배포해도 **한 아티팩트라는 사실**은 그대로입니다.

문제는 두 가지입니다.

1. **폭발 반경.** 색인기는 대량 적재·임베딩 추론을 하는 쪽이라 OOM이 나는 컴포넌트입니다. 검색 API와 같은 프로세스면 색인기 OOM이 곧 **검색 장애**입니다. 플래그로 빈을 꺼도 힙은 공유합니다.
2. **자원 성격 반대.** 색인은 CPU 버스트입니다 — 벡터 전체 재색인 492초 중 **471초(95.7%)**가 임베딩 추론입니다. 질의는 저지연 상시 대기입니다(하이브리드 중앙값 7.5ms). 한 프로세스에 묶으면 색인이 실행되는 동안 질의가 코어를 빼앗깁니다.

플래그에는 세 번째 문제도 있습니다. 잘못 켜면 열립니다. `psp.role.indexer=true`가 실수로 켜지면 질의 노드에 `POST /admin/reindex`가 노출됩니다. 사람이 실수할 여지가 남아 있습니다.

## Decision 1 — Four-module split

```
search-api  ──┐
              ├──▶  search-core  (라이브러리)
indexer-batch ┘
indexer-stream  (뼈대만)
```

| 모듈 | 내용 | 패키지 |
|---|---|---|
| **search-core** (lib) | 두 앱이 어긋나면 **오류 없이 깨지는 것** | `dev.yubin.search.core.*` |
| **search-api** (app) | `/v1/*` 질의. `/admin` 없음, PostGIS 없음 | `dev.yubin.search.*` (그대로) |
| **indexer-batch** (app) | 관리 API, Spring Batch job, ES bulk, 체크포인트, PostGIS JDBC | `dev.yubin.search.indexer.*` |
| **indexer-stream** | 비어 있음 (아래 참조) | `dev.yubin.search.indexer.stream` |

### Scope of core

기준을 좁게 잡았습니다. "둘 다 쓰니까 core"로 하면 core가 금방 쓰레기통이 됩니다.

> 한쪽만 바꿨을 때 프로그램이 죽지 않고 결과만 이상해지는 것 → core

| 불일치 | 증상 | core |
|---|---|---|
| 색인은 `brand`, 질의는 `brand_name` | 예외 없음. 브랜드가 보이지 않습니다 | 포함 |
| 색인은 A모델, 질의는 B모델 | 예외 없음. 점수가 의미를 잃습니다 | 포함 |
| RRF 상수 `k` | 질의기만의 문제 | 제외 |

그래서 `Brands`·`PlaceRow`·`PlaceDocuments`·`PlaceEsDocs`·`EmbeddingModel`·`PlaceVectorText`·`QdrantStore`·`PlaceVectorPayload`·`IndexMeta`가 core입니다.

`PlaceDocuments`(필드명을 쓰는 쪽)와 `PlaceEsDocs`(같은 필드명을 읽는 쪽)를 같은 폴더에 뒀습니다. `PlaceEsDocs` 주석에 원래부터 "색인된 스키마와 1:1"이라고 적혀 있었습니다. 말로 적는 것보다 나란히 두는 편이 강한 약속입니다.

`PlaceVectors`는 분리했습니다 — `payload()`는 Qdrant 문서 스키마라 core, `filter()`·`distanceM()`은 질의 규칙이라 search-api. core가 `SearchRequest`(질의 개념)를 몰라도 되게 됐습니다.

> 경계가 맞다는 신호: 아키텍처 리뷰 #21 회귀 테스트("색인 문서·임베딩 문장·payload가 같은 브랜드를 쓴다")가 검사하는 세 경로가 옮기고 보니 전부 core 안에 있었습니다.

### Module naming — `search-core` and `indexer-core`

공유 모듈 이름이 `indexer-core`면 질의 앱이 "indexer-core"에 의존하는 그림이 되어 리뷰어에게 잘못 읽힙니다. `indexer-core`는 **색인기가 둘이 됐을 때** 추출합니다 — 그때 비로소 둘이 공유하는 것(bulk 적용·체크포인트·멱등 규칙)이 눈에 보입니다. 지금 만들면 무엇을 공유할지 **코드 없이 추측**하는 꼴이라 두 번 틀립니다.

### `indexer-stream` — naming and empty skeleton

`indexer-batch`와 갈리는 축은 **일이 도착하는 방식**입니다(스케줄 vs 이벤트).

- **`-incremental`.** 틀린 이름입니다. `indexer-batch`가 이미 `CheckpointStore` 기반 증분입니다.
- **`-realtime`.** ADR 0001과 어긋납니다. 거기서 실시간을 기각하고 이벤트 트리거를 골랐습니다.
- **`batch ↔ stream`.** 설명 없이 읽히는 축입니다.

YAGNI 대로면 디렉토리 자체가 없어야 맞습니다. 그걸 알면서 남기는 이유는 `indexer-batch`라는 이름이 짝이 있을 때만 의미가 있기 때문입니다. 대신 동작하는 척하는 코드는 넣지 않습니다 — Spring Boot 플러그인도 붙이지 않았습니다.

## Decision 2 — `psp.role.*` flag removal

모듈이 곧 역할입니다. 12개 클래스의 `@ConditionalOnProperty(prefix = "psp.role")`를 제거했습니다. (무엇을 어디로 옮길지는 그 어노테이션이 이미 답을 갖고 있었습니다 — 분류가 아니라 **승격**입니다.)

플래그와 다른 점은 **클래스가 없다**는 것입니다. jar로 확인했습니다.

```
                        리액티브계열  postgresql  spring-batch  Admin
search-api.jar                 37          0            0        0
indexer-batch.jar               0          1            2        5
```

(리액티브계열 = webflux · reactor · kotlinx-coroutines · netty 아티팩트 수)

질의 앱이 원천 창고를 열 방법 자체가 없습니다. 그리고 색인 앱에는 리액티브가 한 조각도 없습니다. 표는 ADR 0013(색인기 JDBC 전환) 이후 기준입니다.

`psp.vector.enabled`는 남깁니다. 임베딩 모델을 읽을지 말지는 여전히 런타임 선택입니다(ADR 0010).

## Decision 3 — Shared settings in `core.yml`

`application.yml`을 그냥 반으로 잘라 복사하면, 이 값들이 한쪽만 바뀌는 사고가 언젠가 발생합니다.

```yaml
psp.index.search-alias   # 색인기가 채우고 질의기가 읽는 같은 이름
psp.vector.alias
psp.embedding.*          # 같은 모델·같은 전처리 (ADR 0010)
spring.elasticsearch.uris
```

그리고 그 사고는 예외를 던지지 않습니다. 색인기는 `place_search_v9`를 채우고 질의기는 `v8`을 읽는데 로그는 깨끗하고 결과만 낡습니다. 코드를 core에 모은 것과 **같은 이유로** 설정도 한 벌만 둡니다. 두 앱은 `spring.config.import: classpath:core.yml`로 읽습니다.

## Decision 4 — Runtime version stamp (`IndexMeta`)

이 분리의 필수 조건입니다.

### Lost protection

ADR 0010이 노린 보호("색인과 질의가 같은 모델·같은 전처리")는 **한 프로세스였기에 코드로 강제**됐습니다. 분리하면 그 보호가 사라집니다. `search-core`를 공유하는 건 **한 빌드 안에서만** 드리프트를 막고, 따로 배포된 두 아티팩트는 여전히 어긋날 수 있습니다(롤링 배포·배포 실패·롤백).

그 어긋남은 증상이 없습니다. 384차원끼리면 유사도 계산은 정상 동작하고 숫자도 정상으로 보입니다. 로그도 깨끗하고 헬스체크도 초록입니다. 결과만 오류 없이 틀립니다.

### Design

- **찍는 주체는 indexer-batch.** alias 스왑이 **성공한 뒤에** 찍습니다. 순서가 중요합니다. 스왑 전에 찍으면 적재가 실패했을 때 "새 스키마로 색인됐다"는 거짓말이 기록됩니다.
- 도장 값은 설정이 아니라 **실제로 로드된 모델**에서 읽습니다(`EmbeddingModel.modelId`/`dimension`). 설정은 "그럴 것이다"이고 로드된 모델은 "그렇다"라, 설정만 맞고 파일이 다른 경우까지 잡힙니다.
- **대조 시점은 search-api 기동 시.** 다르면 컨텍스트가 기동하지 않습니다.
- **도장이 없으면 경고만.** 분리 이전에 만든 인덱스로도 기동할 수 있어야 합니다. "모르는 것"과 "다른 것"을 구분합니다.

### Fail-fast rationale

경고면 로그 한 줄 지나가고 서비스는 계속 실행됩니다. 품질이 나빠진 걸 누군가 알아채기까지 며칠이 걸립니다. 기동하지 않으면 배포가 그 자리에서 실패합니다. 오류 없이 틀리는 것보다 시끄럽게 실패하는 쪽이 낫습니다.

### Stamp location — ES, not Qdrant

원래는 Qdrant 컬렉션 메타에 붙이려 했습니다(#15가 적어둔 방향입니다). 확인해보니 Qdrant 1.12는 모르는 필드를 오류 없이 버립니다.

```
PUT /collections/probe  {"vectors":{...}, "metadata":{"x":"1"}}  →  {"result":true,"status":"ok"}
GET /collections/probe                                           →  metadata 없음
```

추측했다면 "도장을 찍었다"고 믿으면서 아무것도 안 찍혔을 것입니다. 그래서 ES·Qdrant 양쪽 도장을 ES 문서 한 곳(`psp_index_meta`)에 모았습니다.

### Trade-offs — verification after model load

기동 실패 경로에서 임베딩 모델 로딩 5.6초·0.5GB를 버립니다. 벡터 계약을 대조하려면 "설정에 뭐라고 적혀 있나"가 아니라 **실제로 로드된 모델**이 무엇인가가 필요해서입니다. 오탐을 줄이는 값으로 크래시루프 시 기동 시간을 지불합니다.

## Decision 5 — Rejecting incremental runs on contract mismatch

증분은 새 인덱스를 만들지 않고 살아있는 인덱스에 덮어씁니다. 그래서 모델이 바뀐 채 증분을 실행하면 한 컬렉션에 두 모델의 벡터가 섞입니다.

```
1. 모델 A 로 전체 재색인   → 64,239건 전부 A
2. 모델 B 로 색인기 재배포
3. 증분 실행              → 바뀐 500건만 B
   → A 63,739 + B 500 섞임. 도장은 여전히 A
```

대응은 둘입니다. **감지**(문서마다 `meta`를 기록해 사후 확인)와 **차단**(증분 전에 도장을 대조하고 거부). 차단을 골랐습니다 — 섞인 걸 나중에 아는 것보다 애초에 못 섞이게 하는 편이 비용이 낮습니다. 비용은 GET 한 번이고, 문서당 저장 오버헤드가 0입니다.

## Decision 6 — Three indexing cadences

도장은 **담기로 한 것**만 지킵니다. 담지 않은 어긋남은 그대로 누적됩니다.

- **형태소 사전 변경** — 질의 로그 채굴로 계속 자랍니다(ADR 0008). 증분은 바뀐 행만 건드리므로 나머지는 옛 분석 결과로 남습니다.
- **원천이 `updated_at`을 안 올리고 값만 고친 경우** — watermark 기반 증분은 감지하지 못합니다.
- **tombstone** — 전체 재색인이 곧 청소입니다.

이건 도장으로 못 막습니다. **주기적 전체 재색인**이 쓸어내야 합니다.

| 대상 | 주기 | 결정 요인 |
|---|---|---|
| 증분 | 5분 | 신선도 요구 ↔ 변경량. 장소는 느리게 바뀝니다(ADR 0001) |
| 전체 재색인 | 매일 04:30 | 전체 재색인 비용 ↔ 드리프트 상한선 |
| 모델·스키마 변경 | **주기가 아니라 이벤트** | 도장 대조가 증분을 거부해 강제 |

전체 재색인 비용은 **키워드 17초 + 벡터 8분 12초 = 하루의 0.6%**입니다. 드리프트를 고민하는 것보다 매일 다시 만드는 쪽이 비용이 낮습니다. (서울 전체 53.7만이면 8.4배 — 약 71분. 여전히 새벽 창에 들어갑니다.)

스케줄러는 만들되 기본은 꺼둡니다. 앱이 기동하자마자 원천을 훑으면 로컬 시연·실측이 방해받습니다. `application.yml`의 cron 값이 곧 "프로덕션이면 이렇게 실행된다"의 기록입니다.

## Benchmarks

2026-07-24, 강남구 64,239건 기준입니다.

### Indexing

분리 후에도 성능이 같습니다.

| 항목 | 이번 | 분리 이전 |
|---|---|---|
| 키워드 전체 재색인 + alias 스왑 | **17초** (`v15`→`v16`) | 17초 |
| 벡터 전체 재색인 + alias 스왑 | **492초** (8분 12초), 임베딩 471초 = **95.7%** | 513초, 95.6% |

### Query

재색인 전후 결과가 완전히 같습니다. 회귀 질의 20개(`scripts/eval/queries_regression.txt`, `scripts/eval/measure_search.py`).

| 채널 | 0건 (재색인 전) | 0건 (재색인 후) | 중앙값 | p95 |
|---|---|---|---|---|
| 키워드 | 7 | **7** | 3.6ms | 5.3ms |
| 벡터 | 2 | **2** | 4.0ms | 5.2ms |
| 하이브리드 | 0 | **0** | 7.5ms | 12.5ms |

> 지연 수치는 **2026-07-29 재측정** 값입니다(로컬 docker-compose, 강남구 64,239건 동일). 원래 이 표의 8.2ms는 2026-07-24 측정이었는데, README에는 이 값과 ADR 0003의 0.21ms(2026-07-23, 별도 실행, 예열 50회 평균)가 마치 한 번에 측정한 것처럼 한 줄에 같이 적혀 있었습니다 — 날짜도 지표(중앙값 vs 평균)도 다른 두 측정이었습니다. `measure_search.py`에 하이브리드 단계별(keyword·vector·hydrate·fuse) breakdown을 추가해, **같은 실행 안에서** 같이 측정했습니다.

| 하이브리드 내부 단계 (Actuator 카운터 차분, 평균) | 호출 | 평균 |
|---|---|---|
| keyword (후보 50건) | 100 | 6.01ms |
| vector (후보 50건) | 100 | 3.48ms |
| hydrate (ES mget, 필요할 때만) | 55 | 1.21ms |
| fuse (RRF 계산) | 100 | 0.02ms |

keyword·vector는 병렬로 실행되므로 느린 쪽(keyword 6.01ms)에 수렴하고, 거기에 hydrate·fuse가 더해져 하이브리드 총 중앙값(7.5ms)과 맞아떨어집니다. hydrate는 RRF 상위 결과 중 키워드 채널이 못 찾은 문서에만 걸리므로 100회 중 55회만 호출됐습니다. 결합(fuse) 자체는 여전히 비용이 없습니다 — 0.02ms, 총 지연의 0.3%.

### Query during indexing

분리의 주장을 직접 측정합니다. 색인이 실행되는 동안 세 채널을 계속 호출했습니다(`scripts/verify_zero_downtime.py`).

**EKS·Spring Batch 색인기 기준 (2026-07-29, `overlays/eks` 분리 배치, 워커 48)** — 아래 "20만+ 무중단" 주장의 실제 근거.

| 구간 | 요청 | 실패 | 빈 결과 | 하이브리드 중앙값 | p95 |
|---|---|---|---|---|---|
| 유휴 (기준선) | 43,947 | **0** | **0** | 172.2ms | 263.1ms |
| 키워드 전체 재색인 중 | 48,113 | **0** | **0** | 202.3ms | 315.4ms |
| 벡터 전체 재색인 중 | 118,287 | **0** | **0** | 174.9ms | 271.7ms |

합계 **210,347건, 실패 0 · 빈 결과 0.** 절대 지연값은 워커 48(20만+ 도달을 위해 올린 동시성)로 측정한 값이라, 앞서 실측한 단일 요청 중앙값(7.5ms)과는 직접 비교할 수 없습니다 — 대기열이 섞여 있습니다. 유휴(172.2ms) 대비 벡터 재색인 중(174.9ms)이 거의 그대로인 것은 2026-07-26 EKS 실험(아래)의 "앱 CPU 경쟁은 노드 분리로 풀린다"는 결론과 같은 방향입니다. 키워드 재색인 중(202.3ms, +17.5%)만 오르는 것도 "엔진 공유 경쟁은 남는다"는 그 실험 결론과 일치합니다.

**로컬/구 리액티브 색인기 기준 (2026-07-24) — 참고용, 대조군 아님:**

| 구간 | 요청 | 실패 | 빈 결과 | 하이브리드 중앙값 | p95 |
|---|---|---|---|---|---|
| 유휴 (기준선) | 12,887 | **0** | **0** | 11.5ms | 20.5ms |
| 키워드 전체 재색인 중 (17초) | 10,127 | **0** | **0** | 15.2ms | 30.8ms |
| 벡터 전체 재색인 중 (879초) | 183,475 | **0** | **0** | 23.4ms | 52.3ms |

**얻은 것:** 20만 건 넘게 요청하는 동안 실패 0·빈 결과 0 — EKS·로컬 양쪽에서 재현됐습니다. alias 스왑이 원자적이라 "가리키는 곳이 없는" 순간이 생기지 않습니다. 빈 결과를 따로 센 이유가 이것입니다 — 실패 카운터만 보면 200 OK에 0건이 반환되는 사고를 놓칩니다.

> **주의.** 로컬 표의 환경을 반드시 같이 읽어야 합니다. 위 로컬 세 줄은 **로컬 docker-compose**에서 **리액티브 색인기**(ADR 0013 리팩터 이전)로 측정한 값입니다. 벡터 재색인 879초가 그 증거입니다 — kind 단일 노드는 1,963초, 로컬 유휴는 492초입니다. 즉 질의 4워커에 밀린 로컬 수치입니다. 다른 환경의 대조군으로 쓰면 안 됩니다. 2026-07-26 EKS 실험에서 이 표를 대조군으로 쓰려다 걸러냈고, 대신 같은 클러스터 안에 대조군을 따로 만들었습니다(아래).

**남는 것.** 지연은 2배가 됐습니다(11.5ms → 23.4ms, p95는 2.6배). 프로세스는 나뉘었지만 로컬에서는 같은 CPU를 나눠 씁니다. 즉 이 분리가 지금 보장하는 것은 **장애 격리**이지 **자원 격리**가 아닙니다.

| 항목 | 지금 상태 |
|---|---|
| 색인기 OOM이 검색 프로세스를 죽이는 것 | 차단. 다른 프로세스라 전파되지 않습니다 |
| 질의 노드에 `/admin`이 열리는 것 | 차단. 클래스가 jar에 없습니다 |
| 색인이 질의 지연을 밀어올리는 것 | 미차단. 같은 머신이면 그대로 경쟁합니다 |

세 번째는 아티팩트 분리로 풀리는 문제가 아닙니다. 두 앱을 다른 노드(또는 K8s 파드)에 배치해야 완성됩니다. 다만 그 배치가 **가능해진 것**이 이 ADR의 성과입니다 — 한 아티팩트였을 때는 애초에 불가능했습니다. ADR 0002의 멀티클러스터도 같은 선상에 있습니다.

## EKS re-measurement of the unblocked item (2026-07-26)

위 표에서 미차단으로 남은 항목이 대상입니다. 두 노드짜리 EKS 클러스터를 기동해 **같은 클러스터에서 두 번** 측정했습니다. 파드 스펙(이미지·CPU 요청 6/제한 7·메모리)은 전부 같고 배치만 다릅니다. 그래서 두 패스의 차이가 곧 노드 분리의 순효과입니다.

```
패스 A (분리)  search-api·ES·Qdrant → m7i.xlarge  /  indexer·PostGIS → c7i.2xlarge
패스 B (합침)  전부 → c7i.2xlarge                    (overlays/eks-colocated)
```

**대조군을 옛 표(11.5→23.4ms)로 쓰지 않은 이유**는 위 경고 상자에 있습니다. 그걸 그대로 쓰면 런타임(리액티브→Batch) × 환경(compose→EKS) × 토폴로지가 한꺼번에 바뀌어, 지연이 회복돼도 셋 중 무엇 때문인지 말할 수 없습니다.

### Result

배수로만 비교합니다. 하드웨어가 달라(4코어 vs 8코어) 절대값은 비교할 수 없습니다. 각 패스 **내부의** 유휴 대비 배수만 비교 대상입니다.

| 구간 | 패스 A (분리) | 패스 B (합침) | 차이 |
|---|---|---|---|
| ① 유휴 | 53.3ms (1.00) | 43.2ms (1.00) | — |
| ② 키워드 재색인 중 | 57.0ms (**1.07**) | 48.1ms (**1.11**) | +0.04 |
| ③ 벡터 재색인 중 | 48.1ms (**0.90**) | 47.1ms (**1.09**) | **+0.19** |

실험 전 예측이 그대로 맞았습니다(`deploy/eks/cluster.yaml`의 `ng-query` 주석):

> · 벡터 재색인 중 → 색인 비용의 96.1%가 임베딩(순수 앱 CPU)이라 다른 노드로 빠진다 → 회복 예상
> · 키워드 재색인 중 → 비용이 ES `_bulk`라 같은 ES 파드를 때린다 → 회복 안 될 것으로 예상

벡터 쪽만 0.19 움직였고 키워드 쪽은 0.04로 제자리입니다.

### Bidirectional contention

경쟁은 양방향이고, 색인 쪽 손해가 더 컸습니다.

| 구간 | 벡터 재색인 소요 (64,239건) |
|---|---|
| 예열 (질의 부하 없음) | 275초 |
| 패스 A ③ (분리) | 249초 |
| 패스 B ③ (합침) | **346초 (+39%)** |

질의 쪽 손해(배수 +0.19 ≈ 21%)보다 색인 쪽 손해가 큽니다. 색인기가 `requests.cpu: 6`으로 CFS 가중치를 크게 쥐고 있는데도 그렇습니다. 분리의 값을 "질의 지연 보호"로만 설명하면 절반을 빠뜨립니다.

### Zero downtime in both passes

무중단은 양쪽 다 성립합니다.

| 패스 | 요청 | 실패 | 빈 결과 |
|---|---|---|---|
| 패스 A | 28,277 | **0** | **0** |
| 패스 B | 37,704 | **0** | **0** |

성능 문제와 정확성 문제가 분리돼 있고, alias 원자 스왑이 실제 멀티노드 클러스터에서도 버팁니다.

### Revised table

앞의 표를 이렇게 고칩니다.

| 항목 | 로컬(한 머신) | 노드 분리 후 |
|---|---|---|
| 색인기 OOM이 검색 프로세스를 죽이는 것 | 차단 | 차단 |
| 질의 노드에 `/admin`이 열리는 것 | 차단 | 차단 |
| **앱 CPU 경쟁**이 질의 지연을 밀어올리는 것 (벡터 재색인) | 미차단 | 차단 **1.09 → 0.90** |
| **엔진 공유** 경쟁이 질의 지연을 밀어올리는 것 (키워드 재색인) | 미차단 | 미차단 **1.11 → 1.07** |

원래 한 줄이던 미차단 항목을 둘로 분리한 것이 이 실험의 결론입니다.

> 아티팩트를 분리하고 노드를 나누면 앱 CPU 경쟁은 풀립니다. 그러나 같은 엔진(ES)을 공유하는 경쟁은 남습니다 — 그건 앱 배치가 아니라 ADR 0002의 클러스터 분리로만 풀립니다.

### Remaining confounders

두 패스 모두 ①이 첫 구간이라 JVM이 예열되지 않은 상태에서 측정합니다(①의 최대값이 패스 A 769ms, 패스 B 629ms인데 ③ 구간은 100~270ms입니다). 그래서 두 패스의 ③ 배수는 둘 다 과소평가입니다.

다만 교란이 양쪽에 같은 방향·같은 순서로 들어가므로 **두 배수의 차이(0.19)**는 유효합니다. 이 실험이 답하려던 건 차이지 절대 배수가 아닙니다.

실험 과정에서 겪은 문제 12개와 진단 방법은 `.claude/doc/eks-troubleshooting.md`.

### Version stamp

```
search   {schema_version: 1}
suggest  {schema_version: 1}
vector   {schema_version: 1, embedding_model: multilingual-e5-small, embedding_dim: 384}
```

### Negative test

계약이 불일치하면 기동을 차단합니다. `SCHEMA_VERSION`을 2로 올리고 질의기를 기동했습니다. 기동에 실패했습니다.

```
Caused by: java.lang.IllegalStateException: [search] the indexed data and this process disagree on the contract.
  - document schema version: indexed=1, querying=2
  in this state nothing throws — the results just go silently wrong.
  → run a full reindex with POST /admin/reindex on the indexer (indexer-batch), then start this app again.
```

이 테스트가 통과해야 "분리해도 안전하다"고 말할 수 있습니다. 되돌린 뒤 정상 기동과 `index contract verified — schema v1`을 확인했습니다.

## Side finding — Jackson 이중 등재 버그

회귀 측정 스크립트가 `/v1/search?q=CU` 187건의 이름·주소가 전부 빈 문자열로 반환되는 것을 확인했습니다(분리 이전 `main`에서도 재현). 원인은 Jackson 2(ES 클라이언트)와 Jackson 3(Spring Boot 4)이 함께 올라온 상태에서, Kotlin 모듈 없는 Jackson 2가 `val` data class를 **예외 없이 전부 기본값**으로 채우는 것. 증분 체크포인트·하이브리드 키워드 후보·`IndexMeta.Stamp`가 같은 문제를 겪었습니다. 고침은 `search-core`가 `JsonpMapper` 빈을 제공하는 것 — 한 벌만 존재해야 하는 것을 core에 두는 이 ADR의 논리와 같습니다. 상세는 아키텍처 리뷰 #26.

## Trade-offs

- **빌드 복잡도 증가.** 모듈 4개, `settings.gradle.kts` 두 겹(컴포지트 + 멀티프로젝트). 단일 아티팩트보다 처음 여는 사람에게 불친절합니다.
- **로컬 실행 두 명령.** 데모할 때 앱을 둘 기동해야 합니다.
- **core 비대화.** ES 클라이언트·WebClient·DJL을 다 압니다. "순수한 도메인 모듈"은 아닙니다. 대신 **한 벌만 존재해야 하는 것**을 담는다는 기준은 지켰습니다.
- **기동 시간 지불.** 계약 불일치일 때 모델 로딩 5.6초를 버리고 실패합니다(위 트레이드오프).

## Consequences

- **멀티클러스터 전제 확보** (ADR 0002의 연장). 질의기가 독립 아티팩트라 "어느 클러스터를 보나"가 그 앱의 설정 한 줄이 됩니다.
- **문서 계약 검증.** 문서 필드명이나 ES 매핑을 바꾸면 재색인 전까지 질의기가 기동하지 않습니다. 처음에는 `SCHEMA_VERSION`을 사람이 올리는 방식이었고, 2026-08-08에 문서 생성기의 출력 해시(`document_fingerprint`)로 대체해 상수를 제거했습니다.
- **`indexer-core` 추출 시점 확정** — `indexer-stream`에 실코드가 붙을 때.
- **#14·#15 종료.** 사전 쪽은 2026-08-03에 분석기 지문으로 마저 닫았습니다(아래 "Dictionary follow-up").

## Open issues

- **도장에 형태소 사전 버전 없음.** 사전은 ES 플러그인 쪽에 있어 앱이 읽기 어렵습니다. 지금은 매일 전체 재색인이 쓸어내는 것으로 대신합니다(결정 6). 사전 해시를 도장에 넣는 게 #14의 완전한 해결입니다. (2026-08-03에 닫았습니다 — 아래 "Dictionary follow-up".)
- **문서 단위 출처 없음.** 차단으로 섞임을 막았지만, 이미 섞인 인덱스를 사후에 감지할 방법은 없습니다. 필요해지면 문서에 `meta` 필드를 더합니다.
- **자원 격리 부재.** 색인 중 질의 지연이 2배가 됩니다(위 실측). 프로세스는 나뉘었지만 같은 CPU를 씁니다. 두 앱을 다른 노드에 배치해야 닫히는 항목이고, 이 ADR은 그 배치를 **가능하게** 만든 데까지입니다.
- **`indexer-batch` 자신의 기동 시 대조 없음.** 증분 시작 전에만 대조합니다. 색인기는 어긋나도 "전체 재색인"이라는 정당한 복구 경로가 있어서, 기동을 막으면 복구 수단까지 막힙니다.

## Dictionary follow-up — analyzer fingerprint (2026-08-03)

위 "Open issues" 첫 항목을 닫습니다.

### Rejected alternative — file hash

사전 파일은 Elasticsearch 파드에만 마운트됩니다(`deploy/k8s/base/elasticsearch.yaml`, compose는 ES 서비스에만). 색인기와 질의기는 별도 JVM이라 그 파일을 읽을 방법이 없습니다.

### Decision

토큰 스트림을 지문으로 씁니다. 고정된 문장 세 개를 ES `_analyze`에 던져, 돌아온 `term:startOffset:endOffset` 목록을 SHA-256으로 해시합니다(앞 6바이트, 16진수 12자).

측정 대상은 "파일이 같은가"가 아니라 "같은 글자가 같게 분해되는가"입니다. 그래서 사전뿐 아니라 애널라이저 설정·플러그인 버전이 바뀌어도 잡힙니다. 반대로 파일만 바뀌고 ES가 아직 안 읽었으면 지문도 안 변하는데, 그때는 질의 동작도 안 변했으므로 막지 않는 것이 맞습니다.

검색 인덱스와 자동완성 인덱스는 애널라이저가 다르므로 지문도 따로 찍습니다.

| 파이프라인 | 애널라이저 | 사전 |
|---|---|---|
| `search` | `komoran` | 사용 |
| `suggest` | `autocomplete_search` | 무관 (edge_ngram) |
| `vector` | 없음 | — |

찍는 곳은 `KeywordIndexJobConfig` promote, 대조하는 곳은 증분 prepare와 `IndexContractGuard` 기동 검사입니다. 나머지 규약은 결정 4·5 그대로입니다.

### Backward compatibility

이미 찍혀 있는 도장에는 이 필드가 없습니다. `EsJsonpMapperConfig`의 `KotlinModule`이 없는 필드를 기본값(null)으로 채우고, `verify`는 한쪽이 null이면 차이로 세지 않습니다. 기존 색인으로도 그대로 기동하고, 보호는 다음 전체 재색인부터 걸립니다 — `embedding_model`을 도입할 때와 같습니다.

### Benchmarks

2026-08-03, 로컬, `place_search` 531,528건 기준입니다.

| 인덱스 / 애널라이저 | 지문 |
|---|---|
| `place_search` / `komoran` | `6985af8f19f6` |
| `place_suggest` / `autocomplete_search` | `98bd512b40b3` |
| `komoran_probe` / `komoran_raw` (사전 없는 인덱스) | `263b8fe32946` |

사전 유무로 토큰이 달라집니다.

```
사전 있음(14): 논현 2 동 투썸 플레이스 브런치 빈 강남 아메리카노 …
사전 없음(20): 놓 ㄴ 현 2 동 투 썸 플레이스 브 런 치 빈 강남 아메리카 노 …
```

`build_komoran_dict.py`가 사전에 넣으려는 오분석(`논현` → `놓/ㄴ/현`)이 지문을 바꿉니다.

전체 재색인(job #5, 531,528건, 115.6초)을 실행한 뒤 도장에 찍힌 값이 위 표의 앞 두 줄과 같았습니다. 색인기 안에서 실행한 계산과 밖에서 ES에 직접 조회해 얻은 값이 일치합니다.

```
search   {"schema_version": 2, ..., "analyzer_fingerprint": "6985af8f19f6"}
suggest  {"schema_version": 2, ..., "analyzer_fingerprint": "98bd512b40b3"}
```

### Negative test

도장의 지문을 틀린 값으로 바꾸고 증분을 실행했습니다. prepare에서 거부됐습니다(job #6 FAILED, 53ms).

```
java.lang.IllegalStateException: [search] the indexed data and this process disagree on the contract.
  - analyzer fingerprint: indexed=deadbeef0000, querying=6985af8f19f6
  in this state nothing throws — the results just go silently wrong.
  → run a full reindex with POST /admin/reindex. an incremental run would leave the index mixed.
```

53ms는 prepare 단계라 원천을 한 행도 읽기 전이고, 살아 있는 인덱스에 아무것도 쓰지 않습니다. 도장을 원복한 뒤 증분이 다시 통과하는 것(job #7 COMPLETED, read=0)까지 확인했습니다.

### Limitations

- 프로브 문장은 고정입니다. 바꾸면 모든 지문이 바뀌어 전체 재색인이 강제됩니다. 문서 지문의 프로브 행도 같습니다.
- 고정 문장에 안 걸리는 사전 변경은 못 잡습니다. 사전을 통째로 재생성하는 경우는 잡지만, 단어 하나만 더한 경우는 프로브가 그 단어를 안 쓰면 놓칩니다.
- 결정 6의 주기적 전체 재색인은 그대로 필요합니다. 지문은 어긋남을 막을 뿐 원천 드리프트를 쓸어내지는 않습니다.

## Implementation

| 모듈 | 파일 | 확정 커밋 | 날짜 |
|---|---|---|---|
| `search-core` | `core/analysis/AnalyzerFingerprint.kt` | `7dafc54` | 2026-08-03 |
| `indexer-batch` | `indexer/IndexerBatchApplication.kt` | `e0478c0` | 2026-07-25 |
| `indexer-batch` | `indexer/ReindexScheduler.kt` | `e0478c0` | 2026-07-25 |
| `indexer-batch` | `indexer/admin/AdminController.kt` | `e0478c0` | 2026-07-25 |
| `indexer-batch` | `indexer/batch/KeywordIndexJobConfig.kt` | `e0478c0` | 2026-07-25 |
| `indexer-batch` | `indexer/batch/VectorIndexJobConfig.kt` | `e0478c0` | 2026-07-25 |
| `search-api` | `SearchApiApplication.kt` | `770218d` | 2026-07-24 |
| `search-api` | `startup/IndexContractGuard.kt` | `ca99720` | 2026-07-25 |
| `search-api` | `vector/PlaceVectors.kt` | `824f361` | 2026-07-24 |
| `search-core` | `core/brand/Brands.kt` | `824f361` | 2026-07-24 |
| `search-core` | `core/embed/EmbeddingModel.kt` | `ca99720` | 2026-07-25 |
| `search-core` | `core/es/EsJsonpMapperConfig.kt` | `cec4e48` | 2026-07-24 |
| `search-core` | `core/index/IndexVersion.kt` | `0a6b8b9` | 2026-07-25 |
| `search-core` | `core/meta/IndexMeta.kt` | `5f39cde` | 2026-07-24 |
| `search-core` | `core/meta/IndexMetaStore.kt` | `ca99720` | 2026-07-25 |
| `search-core` | `core/vector/QdrantContract.kt` | `ca99720` | 2026-07-25 |
| `search-core` | `core/vector/PlaceVectorDocTest.kt` *(테스트)* | `ca99720` | 2026-07-25 |
