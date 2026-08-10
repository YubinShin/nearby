# EKS Cluster Notes

`deploy/eks/cluster.yaml`과 `deploy/k8s/overlays/eks*`의 각 줄이 어떤 결정 위에 서 있는지 기록합니다. 매니페스트에는 주석을 두지 않습니다.

실행 절차는 [deploy/eks/README.md](../deploy/eks/README.md), 실험 결과는 [ADR 0011](adr/0011-module-split-and-index-contract.md) 에 있습니다. 실측 전문은 `git show 11f1eb9`로 확인합니다.

---

## Question

ADR 0011 의 표에 미차단으로 남은 칸이 있었습니다.

> 색인이 질의 지연을 밀어올리는 것 — 같은 머신이면 그대로 경쟁합니다.

kind 는 단일 노드라 이 질문에 답할 수 없습니다. 노드를 둘로 나눈 클러스터를 기동하고, 색인기와 질의기를 다른 노드에 놓고 지연을 다시 측정했습니다.

같은 클러스터에서 두 번 측정합니다 — 분리(`overlays/eks`)와 합침(`overlays/eks-colocated`). 파드 사양은 전부 같고 배치만 다르므로, 두 패스의 차이가 노드 분리의 순효과입니다.

---

## Node Groups

### `ng-query` — Query Path (m7i.xlarge · 4 vCPU / 16 GiB)

search-api + Elasticsearch + Qdrant. 지연을 측정하는 노드입니다.

ES·Qdrant 를 질의기와 같이 두는 것이 의도입니다. 질의 경로는 앱만이 아니라 엔진까지입니다. 이렇게 두면 재색인 종류에 따라 결과가 갈립니다.

| 재색인 종류 | 비용 위치 | 예상 |
|---|---|---|
| 벡터 | 96.1% 가 임베딩 = 순수 앱 CPU → 다른 노드로 이동 | 지연 회복 |
| 키워드 | ES `_bulk` → 같은 ES 파드를 사용 | 회복 안 됨 |

이 비대칭이 확인되면 "앱 분리는 앱 CPU 경쟁만 해소한다"는 결론이 데이터로 기록됩니다. 메모리 요청 합 ~2.8Gi · 제한 합 ~5Gi · ES 힙 1g.

### `ng-index` — Index Only (c7i.2xlarge · 8 vCPU / 16 GiB)

임베딩 추론이 색인 시간의 96.1% 라 인스턴스 타입이 곧 재색인 소요 시간입니다. 손잡이는 메모리가 아니라 CPU 입니다. 8 vCPU 중 7 을 파드에 주고 1 을 kubelet·CNI·EBS CSI 데몬셋에 남깁니다. 합침 패스에서는 이 노드가 전부를 받습니다 — CPU 요청 합 6.7 + 데몬셋 ~0.35 < 할당 가능 ~7.9.

---

## Same AZ Requirement

두 노드그룹을 같은 AZ 에 두는 이유는 대조군(패스 B) 입니다. 패스를 바꾸면 ES·Qdrant·PostGIS 가 노드를 옮기는데, EBS 볼륨은 AZ 에 묶여 있어 다른 AZ 의 노드에는 붙지 않습니다. storageclass 의 `WaitForFirstConsumer`가 첫 소비자의 AZ 에 볼륨을 만들어 둡니다. 그래서 AZ 가 갈리면 패스 B 에서 PVC 가 영구 Pending 이 됩니다. AZ 간 네트워크 지연이 변수에서 빠집니다 — 색인기에서 PostGIS 로 가는 경로가 노드를 건너갑니다.

어느 AZ 인지는 중요하지 않습니다.

---

## Overlay Design

### Pass A — `overlays/eks` (Split)

anti-affinity 를 `required`로 고정해 색인기와 질의기를 다른 노드에 놓습니다. 앱을 분리해도 같은 노드에 배치되면 색인기의 CPU 버스트가 질의 지연을 끌어올립니다.

anti-affinity 만으로는 실험이 오염됩니다. 두 앱이 서로 다른 노드에 가는 것만 보장되고, ES·Qdrant·PostGIS 의 배치는 스케줄러가 정합니다. ES 가 색인 노드에 배치되면 "질의 노드의 지연"을 재는 의미가 사라집니다. 그래서 `nodeSelector`로 배치를 고정합니다.

| 노드 | 파드 | 의미 |
|---|---|---|
| `role=query` | search-api · elasticsearch · qdrant | 질의 경로 전체 = 지연을 측정하는 노드 |
| `role=index` | indexer-batch · postgis | 원천 읽기를 노드 내부로 제한 |

그 밖에 이 오버레이가 하는 일:

- 색인기 CPU 를 `requests 6 / limits 7`로 상향. base 는 `limits.cpu: "2"` — kind 단일 노드에서 다른 파드에 자리를 내주려 묶은 값이고, kind 재색인 32분 43초의 원인입니다. 여기서는 색인기가 노드를 독점하므로 8 vCPU 중 7 을 할당합니다.
- 메모리는 변경하지 않습니다. 메모리 제한을 바꾸면 `MaxRAMPercentage=40`을 통해 힙 크기가 따라 변해 변수가 둘이 됩니다. 한 번에 하나만 바꿉니다.
- `maxSurge: 0` — 아래 별도 항목.
- 상태 백엔드에 `storageClassName: gp3` 명시. kind 는 `standard`라 base 에 두지 않았습니다.

전제: 노드가 2개 이상이고 이미지가 ECR 같은 레지스트리에 push 돼 있어야 합니다.

### Pass B — `overlays/eks-colocated` (Colocated)

`overlays/eks`를 그대로 상속합니다. 이미지 · CPU 한도 · 메모리 · gp3 가 전부 같고, 바뀌는 것은 파드가 어느 노드에 놓이는지 하나뿐입니다.

- 합치는 방향은 색인 노드(c7i.2xlarge, 8 vCPU) 쪽입니다. 질의 노드(m7i.xlarge, 4 vCPU)에 몰면 색인기 `requests.cpu: 6`이 들어가지 않아 영구 Pending 이 됩니다. 이 방향이면 요청 합 6.7(색인기 6 + 질의기 .25 + ES .25 + Qdrant .1 + PostGIS .1)에 데몬셋 ~0.35 를 더해도 할당 가능 ~7.9 아래입니다.
- ES·Qdrant 도 같이 옮깁니다. 옮기지 않으면 "질의 경로가 색인기와 같은 노드"라는 조건이 반쪽만 성립해 패스 A 와 대칭이 되지 않습니다.
- anti-affinity 를 제거합니다. 패스 A 가 `required`로 고정해 두었으므로 그대로 두면 둘 중 하나가 영구 Pending 이 됩니다.
- 파드 스펙은 변경하지 않습니다. 색인기 `requests.cpu: 6`이라 CFS 가중치가 커서 합친 노드에서 색인기가 CPU 를 크게 가져갑니다(가중치 6000 대 질의기 250). 그것이 "같은 스펙을 한 노드에 얹으면 벌어지는 일"이고 측정 대상입니다. 여기서 6 을 낮추면 "합쳤다"와 "요청을 줄였다"가 섞여 변수가 다시 둘이 됩니다.

---

## `maxSurge: 0`

`requests.cpu: 6`은 8 vCPU 노드(할당 가능 ~7.9)의 3/4 입니다. Deployment 기본 전략은 새 파드를 먼저 기동하고 옛 파드를 내리는데, 그러면 잠깐 12 vCPU 가 필요해 새 파드가 영구 Pending 이 됩니다. 새 파드가 Ready 가 아니니 옛 파드도 내려가지 않습니다 — 쿠버네티스가 감지하지 않는 순환 대기라 기다려도 풀리지 않습니다.

2026-07-26 패스 A→B 전환에서 발생했고, 옛 파드를 직접 삭제해서 해소했습니다.

색인기는 상시 응답하는 서비스가 아니라 job 을 받아 도는 쪽이라 교체 중 잠깐 끊겨도 됩니다. `spec.strategy`는 파드 템플릿이 아니므로 이 값을 고쳐도 롤아웃이 새로 돌지는 않습니다.

---

## Rejected Baseline

ADR 0011 에 남아 있는 11.5 → 23.4ms 표는 대조군으로 재사용하지 않습니다. 유효한 관찰이지만 환경이 다릅니다.

- 환경이 EKS 도 kind 도 아닌 로컬 docker-compose 입니다. 벡터 재색인 879초가 근거입니다 — kind 2코어는 1,963초, 로컬 유휴는 ~492초이므로 질의 4워커에 밀린 로컬 수치입니다.
- 런타임이 다릅니다. 측정 커밋(`f260f2a`, 07-24 23:15)이 Spring Batch 리팩터(`ca99720`, 07-25 16:40)보다 앞섭니다. 리액티브 색인기에서 측정한 값입니다.

그대로 비교하면 런타임 × 환경 × 토폴로지가 한꺼번에 바뀝니다. 그래서 대조군을 같은 클러스터 안으로 가져왔습니다(패스 B).

---

## Cluster Configuration

### Pinned Availability Zones

지정하지 않으면 eksctl 이 AZ 3개를 선택합니다. 그 선택과 노드그룹의 `availabilityZones`가 어긋나면 컨트롤 플레인을 다 만든 뒤에 노드그룹 단계에서 실패합니다.

```
could not find public subnets for zones ["ap-northeast-2a"]
(allSubnets=… "ap-northeast-2b" … "ap-northeast-2c" … "ap-northeast-2d" …)
```

2026-07-26 사례: eksctl 이 2b·2c·2d 를 선택했는데 노드그룹은 2a 로 고정돼 있어 컨트롤 플레인 생성(11분) 뒤에 실패했습니다. `--dry-run`은 이것을 잡지 못합니다 — 요청한 값만 표시하고 AZ 선택은 실제 생성 시점에 일어납니다.

요구사항은 "두 노드그룹이 같은 AZ" 였지 2a 가 아니었으므로, VPC 가 가진 2b 로 옮기고 AZ 목록 자체를 파일에 고정했습니다. 3개를 적는 이유는 EKS 컨트롤 플레인이 최소 2개 AZ 의 서브넷을 요구하기 때문이고, 노드는 그중 하나에만 배치합니다.

### NAT Gateway Disabled

`vpc.nat.gateway: Disable`에서 차단해야 NAT 가 생성되지 않습니다. 노드그룹의 `privateNetworking: false`는 노드를 퍼블릭 서브넷에 놓을 뿐이고, eksctl 은 프라이빗 서브넷을 만들면서 거기에 NAT 게이트웨이를 붙입니다(기본값 `Single`). `--dry-run`으로 확인했습니다. 이 둘은 한 쌍입니다 — 노드그룹만 false 로 두면 노드는 퍼블릭인데 NAT 는 생성돼 요금만 발생합니다.

끄는 것이 안전한 이유는 두 노드그룹이 모두 퍼블릭 서브넷이라 IGW 로 나가기 때문입니다. ECR·Docker Hub 이미지 풀, EKS 컨트롤 플레인 통신 모두 NAT 가 필요 없습니다. 만들어진 프라이빗 서브넷에는 아무것도 배치하지 않으므로 인터넷 경로가 없어도 무해합니다.

요금(시간당 + GB당)과 함께, 생성·삭제가 느린 NAT 가 클러스터 생성·철거 왕복에 더하는 시간도 절약합니다.

### EBS CSI Driver and OIDC

최신 EKS 에는 in-tree EBS 프로비저너가 없습니다. 이 드라이버 없이는 StatefulSet 세 개(ES·Qdrant·PostGIS)의 PVC 가 바인딩되지 않습니다. 증상이 "파드가 Pending" 뿐이라 원인을 찾기 어렵습니다.

드라이버는 IRSA(서비스 어카운트 → IAM 역할)를 사용하므로 `iam.withOIDC: true`가 짝입니다. 이것이 빠지면 애드온이 볼륨을 만들지 못하고 PVC 3개가 Pending 에서 영구 대기합니다.

### amd64 Images

로컬(M4)에서 빌드한 이미지 3개는 arm64 인데 `nearby-postgis`만 amd64 입니다 — `postgis/postgis:16-3.4`가 amd64 전용입니다.

셋을 amd64 로 다시 빌드하는 쪽을 선택했습니다. `build-images.sh`가 `PLATFORM`을 이미 지원해 명령 하나로 끝나고, 반대로 가면 데이터가 구워진 유일한 이미지(PostGIS 시드 64,239행)의 베이스를 교체해야 합니다. 수명 몇 시간짜리 실험에서 Graviton 절약분은 무의미합니다.

### Unpinned Cluster Version

eksctl 이 그 시점에 지원되는 기본 버전을 선택하게 둡니다. 버전을 고정하면 몇 달 뒤 지원 종료로 생성이 실패해서, 실험을 재현하려는 사람이 클러스터 버전부터 고쳐야 합니다.

### No Taints

`ng-index`에 테인트를 걸면 coredns 같은 시스템 파드까지 막아 측정이 더 깨끗해집니다. 적용하지 않았습니다 — 얻는 정밀도(coredns 는 CPU 를 거의 쓰지 않습니다)보다 잃는 것이 큽니다. 톨러레이션을 하나 빠뜨리면 파드가 오류 없이 Pending 이 되고, 그 디버깅이 실험보다 오래 걸립니다. 배치 보장은 `nodeSelector`(`overlays/eks`) 로 충분합니다.

---

## Reindex by Environment

같은 64,239건 기준입니다. 아키텍처가 셋 다 다릅니다.

| 환경 | 코어 | 소요 | 처리량 | 코어당 |
|---|---:|---:|---:|---:|
| kind (arm64 · Docker Desktop VM) | 2 | 1,963초 | 32.7건/초 | 16.4 |
| 로컬 (arm64 네이티브 · M4) | 10 | 512초 | 125.5건/초 | 12.6 |
| **EKS c7i** (x86-64 · Sapphire Rapids) | 7 | **275초** | 233.2건/초 | **33.3** |

7코어가 10코어보다 1.86배 빠릅니다. 코어를 늘리면 보통 코어당 처리량이 내려가는데(16.4 → 12.6) 여기만 33.3 으로 올라갑니다. 병렬 효율로는 설명되지 않고, 남는 설명은 명령어 집합입니다 — 임베딩 비용은 대부분 행렬곱인데, x86-64 의 AVX-512 는 한 명령에 fp32 16개를 다루고 arm64 의 NEON 은 4개입니다.

AMX·AVX-512 VNNI 는 아닙니다. 둘 다 bf16·int8 전용 경로인데 이 모델은 fp32 입니다 — `models/multilingual-e5-small/config.json`의 `torch_dtype: float32`, `model.onnx` 470MB 이 파라미터 117.7M × 4바이트와 일치합니다. 양자화를 하면 그때 열리는 경로입니다.

프로파일러로 커널을 확인한 값이 아니라 dtype 과 하드웨어 스펙으로 좁힌 추정입니다. 위 세 줄은 "코어 수 대 시간" 곡선이 아니라 환경별 실측 세 개로만 읽어야 합니다.

---

## References

- [ADR 0011](adr/0011-module-split-and-index-contract.md) — 아티팩트 분리 · 노드 분리 실험 결과
- [ADR 0013](adr/0013-indexer-runtime-spring-batch.md) — 색인기 런타임
- [deploy/eks/README.md](../deploy/eks/README.md) — 실행 절차
- [troubleshooting.md](troubleshooting.md) — 증상별 원인과 조치