# 트러블슈팅

매니페스트와 설정에 **왜 그 줄이 있는지**를 증상 기준으로 모았습니다.
지우면 다시 밟게 되는 것들이라, 지우기 전에 여기를 봅니다.

관련: [deploy/k8s/README.md](../deploy/k8s/README.md) ·
[deploy/eks/README.md](../deploy/eks/README.md) · [ADR 0011](adr/0011-module-split-and-index-contract.md)

---

## `search-api` 가 CrashLoopBackOff 로 뜨지 않는다

**증상** — 파드가 몇 번 죽었다 살아나고, ES 가 준비된 뒤에도 한동안 계속 죽어 있습니다.

**원인** — Elasticsearch 가 뜨기 전에 시작하면 **스프링 컨텍스트 refresh 자체가 실패합니다.**
부팅 중에 `Rest5Client` 가 ES 로 요청을 쏘기 때문입니다.

```
Rest5Client.performRequest → AsyncConnectExec
```

`indexer-batch` 가 PostGIS 로 겪는 것과 같은 모양입니다. CrashLoopBackOff 의
**지수 백오프(10s → 20s → 40s → 80s)** 때문에 ES 가 준비된 뒤에도 몇 분을 더 죽어 있습니다.

**조치** — `wait-for-elasticsearch` initContainer 로 ES 를 기다립니다
(`deploy/k8s/base/search-api.yaml`).

### 왜 TCP 체크로는 부족한가

**ES 는 클러스터가 서빙 준비되기 전에 9200 포트를 먼저 엽니다.** 포트가 열린 것만 보고
넘어가면 같은 실패를 그대로 만납니다. 그래서 `_cluster/health` 를 봅니다.

```
until wget -q -O /dev/null \
  "$SPRING_ELASTICSEARCH_URIS/_cluster/health?wait_for_status=yellow&timeout=5s"
```

- **`yellow` 가 정상입니다.** `green` 을 기다리면 영원히 안 끝납니다.
  단일 노드라서가 아니라 **레플리카를 요구하는 인덱스가 섞여 있어서**입니다. ES 는 레플리카를
  프라이머리와 같은 노드에 두지 않으므로, 노드가 하나면 그 샤드가 계속 `UNASSIGNED` 로 남습니다.
  `place_search_*` · `place_suggest_*` 는 `es/place_search.json` 이 `replicas: 0` 을 지정해
  단일 노드에서도 green 입니다. 클러스터를 yellow 로 만드는 것은 매핑 파일 없이
  **ES 가 암묵 생성**해 기본값(replicas=1)을 받은 `psp_index_meta` · `psp_index_checkpoint` 둘입니다.
- `wait_for_status` 가 **ES 쪽에서 블록**해 주므로 폴링이 촘촘할 필요가 없습니다.

### 주소를 initContainer 에 하드코딩하지 않는 이유

앱과 **같은 ConfigMap**(`search-backends`)에서 받습니다. 하드코딩하면
`backend-wiring.yaml` 을 고쳤을 때 앱과 initContainer 가 **조용히 어긋납니다** —
앱은 새 주소를 보는데 initContainer 는 옛 주소가 뜨기를 기다립니다.

### Qdrant 는 왜 안 기다리나

클라이언트가 lazy 라 **부팅을 막지 않습니다.** 컬렉션이 없으면 질의 시점에 500 을 낼 뿐입니다
(kind 리허설에서 확인). **부팅을 죽이는 것만 기다립니다** — 기다릴 대상을 늘리면 기동만 느려집니다.

---

## Elasticsearch 가 EKS 에서만 부팅에 실패한다 — kind 로는 재현되지 않는다

같은 매니페스트가 kind 에서는 멀쩡히 뜨는데 EKS 에서 죽는다면 아래 둘 중 하나입니다.
둘 다 **실제 블록 스토리지와 실제 노드 커널**을 처음 만나야 드러나는 계열이라,
로컬 검증만으로는 절대 안 걸립니다.

### ① 데이터 디렉터리에 잠금 파일을 못 만든다

```
failed to obtain node locks, tried [/usr/share/elasticsearch/data]
  Suppressed: java.nio.file.AccessDeniedException: …/data/node.lock
```

ES 컨테이너는 **uid 1000** 으로 도는데, 새로 만든 EBS 볼륨은 **root:root** 로 마운트됩니다.
`fsGroup: 1000` 을 주면 kubelet 이 볼륨을 그 그룹으로 chown 하고 프로세스에 보조 그룹으로
달아줍니다 → uid 1000 이 쓸 수 있게 됩니다.

kind 에서 안 나는 이유는 로컬 경로 프로비저너가 디렉터리를 헐겁게 만들기 때문입니다.
**"Qdrant·PostGIS 는 같은 gp3 를 쓰는데 멀쩡하다"는 반증이 아닙니다** — 그 이미지들은 root 로
돌거나 진입점에서 스스로 소유권을 고칩니다.

### ② bootstrap check 에서 죽는다 (`vm.max_map_count`)

ES 는 Lucene 세그먼트를 **mmap 으로 엽니다.** 기본값 `vm.max_map_count=65530` 으로는 매핑 개수가
모자라 부팅 중 bootstrap check 에서 실패합니다.

이 값은 **노드 커널** 설정이라 컨테이너 안에서 못 바꿉니다. 그래서 privileged initContainer 로
노드에 sysctl 을 겁니다.

```yaml
initContainers:
  - name: sysctl-max-map-count
    image: busybox:1.36
    command: ["sh", "-c", "sysctl -w vm.max_map_count=262144"]
    securityContext: { privileged: true }
```

kind 는 Docker Desktop VM 커널을 공유해 대개 이미 값이 큽니다. **이 initContainer 를 지우면
EKS 에서 ES 가 안 뜹니다.**

> 참고: `xpack.security.enabled: false` 는 **로컬/데모 전용**입니다. 이 매니페스트를 그대로
> 운영에 옮기면 인증 없는 ES 를 노출하게 됩니다.

---

## `search-api` 가 힙에 여유가 있는데 OOMKilled 된다

**원인** — 이 앱은 **JVM 힙 밖에서도 메모리를 씁니다.** 임베딩 추론이 ONNX 네이티브라,
컨테이너 메모리를 힙과 네이티브가 나눠 씁니다.

이미지가 `-XX:MaxRAMPercentage` 로 힙을 컨테이너 메모리의 **50%** 로 잡고, 나머지 절반을
ONNX 네이티브가 씁니다. 컨테이너 `limits.memory` 를 이 비율을 무시하고 잡으면
**힙 여유가 있어도 네이티브 할당 실패로 OOMKilled** 가 납니다.

**조치** — `limits.memory` 를 바꿀 때는 힙 비율을 함께 봅니다.
현재값은 `requests 1Gi / limits 2Gi` (`deploy/k8s/base/search-api.yaml`).

---

## 색인기가 뜨자마자 시키지도 않은 색인을 시작한다

**원인** — Spring Batch 의 `spring.batch.job.enabled` **기본값이 `true`** 입니다.
끄지 않으면 앱이 부팅하자마자 등록된 job 중 하나를 골라 돌리려 하고(여럿이면 그 시점에 실패)
원천을 훑기 시작합니다.

**조치** — `services/indexer-batch/src/main/resources/application.yml` 에서 끕니다.

```yaml
spring:
  batch:
    job:
      enabled: false
```

job 은 `/admin/*` 이나 스케줄러가 부를 때만 실행됩니다. 그게 이 앱의 계약입니다 (ADR 0013 —
`POST /admin/reindex` → `202 {jobId}` 접수 후 폴링).

**이 한 줄을 지우면 재현이 어려운 방식으로 깨집니다.** 로컬에서는 "왜 안 시킨 색인이 돌지"가
되고, 운영에서는 **롤링 배포마다 새 파드가 색인을 시작합니다.**

---

## 재색인이 중간에 끊기고 고아 인덱스가 남는다

**원인** — 파드 종료 유예가 앱의 종료 대기보다 짧으면 kubelet 이 **SIGKILL** 합니다.
그러면 색인이 **alias 스왑 전에** 끊기고, 고아 인덱스와 고아 컬렉션(벡터는 64k 점을 통째로)이
PVC 에 남습니다.

**두 값은 한 쌍입니다.**

```
deploy/k8s/base/indexer-batch.yaml          terminationGracePeriodSeconds: 630
services/indexer-batch/…/BatchConfig.kt     AWAIT_TERMINATION_SECONDS = 600
```

매니페스트 값이 **앱의 종료 대기보다 길어야 합니다.** 630 > 600 인 게 그 이유입니다.
기본값 30초를 그대로 두면 kubelet 이 600초를 기다려주지 않습니다.

**둘 중 하나를 바꾸면 다른 하나를 같이 봅니다.** 한쪽만 바꾸면 이 관계가 조용히 깨집니다.

---

## 색인 중에 검색 지연이 튄다

**원인** — 앱을 별도 아티팩트로 쪼갰어도 **같은 노드에 얹히면 자원은 안 나뉩니다.**
색인기의 임베딩 배치가 CPU 를 태울 때 질의 지연이 함께 오릅니다.

**조치** — anti-affinity 로 두 앱을 다른 노드에 놓습니다. base 와 오버레이의 강도가 다릅니다.

| | 강도 | 이유 |
|---|---|---|
| `base` | `preferred` | 단일 노드 kind 에서도 떠야 한다. required 면 둘 중 하나가 영구 Pending |
| `overlays/eks` | `required` | 진짜로 다른 노드에 강제 — 이게 분리의 마지막 조각 |

노드를 나눴을 때 실제로 얼마나 회복되는지는 [deploy/eks/README.md](../deploy/eks/README.md) 참고.
벡터 재색인 중 지연은 회복되고(앱 CPU) 키워드는 회복되지 않습니다(공유 ES 의 `_bulk`).

> 로컬 docker-compose 실측(11.5 → 23.4ms)은 EKS 결과의 대조군이 아닙니다 —
> [eks-cluster-notes.md](eks-cluster-notes.md#rejected-baseline) 참고.

---

## `search-api` 에 `/admin` 이나 PostGIS 설정이 없다

의도된 것입니다. **플래그로 끈 게 아니라 클래스가 jar 에 없습니다** (ADR 0011).
질의 앱과 색인 앱은 자원 성격이 정반대라(저지연 상시 ↔ CPU 버스트) 별도 아티팩트로 갈랐고,
질의 경로가 원천 창고를 여는 일이 없도록 빌드 시점에 보장합니다.

재색인은 색인 앱(`indexer-batch`, 8081)의 `/admin/*` 으로 부릅니다.
