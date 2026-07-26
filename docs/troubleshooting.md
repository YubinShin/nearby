# 트러블슈팅

매니페스트와 설정에 **왜 그 줄이 있는지**를 증상 기준으로 모았다.
지우면 다시 밟게 되는 것들이라, 지우기 전에 여기를 본다.

관련: [deploy/k8s/README.md](../deploy/k8s/README.md) ·
[deploy/eks/README.md](../deploy/eks/README.md) · [ADR 0011](adr/0011-module-split-and-index-contract.md)

---

## `search-api` 가 CrashLoopBackOff 로 뜨지 않는다

**증상** — 파드가 몇 번 죽었다 살아나고, ES 가 준비된 뒤에도 한동안 계속 죽어 있다.

**원인** — Elasticsearch 가 뜨기 전에 시작하면 **스프링 컨텍스트 refresh 자체가 실패한다.**
부팅 중에 `Rest5Client` 가 ES 로 요청을 쏘기 때문이다.

```
Rest5Client.performRequest → AsyncConnectExec
```

`indexer-batch` 가 PostGIS 로 겪는 것과 같은 모양이고, 아픈 곳도 같다 — CrashLoopBackOff 의
**지수 백오프(10s → 20s → 40s → 80s)** 때문에 ES 가 준비된 뒤에도 몇 분을 더 죽어 있는다.
EKS 에서는 그게 곧 과금 시간이고, 특히 합침 패스에서 ES 가 노드를 옮기며 EBS 를 재부착하는
동안 이 백오프에 걸리면 재측정 시작이 그만큼 밀린다.

**조치** — `wait-for-elasticsearch` initContainer 로 ES 를 기다린다
(`deploy/k8s/base/search-api.yaml`).

### 왜 TCP 체크로는 부족한가

**ES 는 클러스터가 서빙 준비되기 전에 9200 포트를 먼저 연다.** 포트가 열린 것만 보고
넘어가면 같은 실패를 그대로 만난다. 그래서 `_cluster/health` 를 본다.

```
until wget -q -O /dev/null \
  "$SPRING_ELASTICSEARCH_URIS/_cluster/health?wait_for_status=yellow&timeout=5s"
```

- **`yellow` 가 정상이다.** 단일 노드는 복제본이 배정되지 않아 `green` 이 될 수 없다.
  `green` 을 기다리면 영원히 안 끝난다.
- `wait_for_status` 가 **ES 쪽에서 블록**해 주므로 폴링이 촘촘할 필요가 없다.

### 주소를 initContainer 에 하드코딩하지 않는 이유

앱과 **같은 ConfigMap**(`search-backends`)에서 받는다. 하드코딩하면
`backend-wiring.yaml` 을 고쳤을 때 앱과 initContainer 가 **조용히 어긋난다** —
앱은 새 주소를 보는데 initContainer 는 옛 주소가 뜨기를 기다린다.

### Qdrant 는 왜 안 기다리나

클라이언트가 lazy 라 **부팅을 막지 않는다.** 컬렉션이 없으면 질의 시점에 500 을 낼 뿐이다
(kind 리허설에서 확인). **부팅을 죽이는 것만 기다린다** — 기다릴 대상을 늘리면 기동만 느려진다.

---

## `search-api` 가 힙에 여유가 있는데 OOMKilled 된다

**원인** — 이 앱은 **JVM 힙 밖에서도 메모리를 쓴다.** 임베딩 추론이 ONNX 네이티브라,
컨테이너 메모리를 힙과 네이티브가 나눠 쓴다.

이미지가 `-XX:MaxRAMPercentage` 로 힙을 컨테이너 메모리의 **50%** 로 잡고, 나머지 절반을
ONNX 네이티브가 쓴다. 컨테이너 `limits.memory` 를 이 비율을 무시하고 잡으면
**힙 여유가 있어도 네이티브 할당 실패로 OOMKilled** 가 난다.

**조치** — `limits.memory` 를 바꿀 때는 힙 비율을 함께 본다.
현재값은 `requests 1Gi / limits 2Gi` (`deploy/k8s/base/search-api.yaml`).

---

## 색인 중에 검색 지연이 튄다

**원인** — 앱을 별도 아티팩트로 쪼갰어도 **같은 노드에 얹히면 자원은 안 나뉜다.**
색인기의 임베딩 배치가 CPU 를 태울 때 질의 지연이 함께 오른다.

**조치** — anti-affinity 로 두 앱을 다른 노드에 놓는다. base 와 오버레이의 강도가 다르다.

| | 강도 | 이유 |
|---|---|---|
| `base` | `preferred` | 단일 노드 kind 에서도 떠야 한다. required 면 둘 중 하나가 영구 Pending |
| `overlays/eks` | `required` | 진짜로 다른 노드에 강제 — 이게 분리의 마지막 조각 |

노드를 나눴을 때 실제로 얼마나 회복되는지는 [deploy/eks/README.md](../deploy/eks/README.md) 참고.
벡터 재색인 중 지연은 회복되고(앱 CPU) 키워드는 회복되지 않는다(공유 ES 의 `_bulk`).

> ⚠️ 로컬 docker-compose 에서 잰 **11.5 → 23.4ms** 를 EKS 결과의 대조군으로 쓰지 말 것.
> 환경도 런타임(리액티브 색인기)도 다르다. 대조군은 같은 클러스터에서
> `overlays/eks-colocated` 로 따로 잰다.

---

## `search-api` 에 `/admin` 이나 PostGIS 설정이 없다

의도된 것이다. **플래그로 끈 게 아니라 클래스가 jar 에 없다** (ADR 0011).
질의 앱과 색인 앱은 자원 성격이 정반대라(저지연 상시 ↔ CPU 버스트) 별도 아티팩트로 갈랐고,
질의 경로가 원천 창고를 여는 일이 없도록 빌드 시점에 보장한다.

재색인은 색인 앱(`indexer-batch`, 8081)의 `/admin/*` 으로 부른다.
