# Nearby on EKS

노드 분리가 질의 지연에 주는 영향을 측정하기 위한 실험 클러스터입니다. 노드그룹 2개짜리 EKS 를 기동하고, 색인기와 질의기를 분리한 배치와 합친 배치에서 각각 지연을 측정합니다.

> **실험용 클러스터.** 측정이 끝나면 `./deploy/eks/down.sh`를 실행합니다. 컨트롤 플레인은 노드를 0으로 줄여도 시간당 요금이 계속 발생합니다.

각 설정의 근거는 [docs/eks-cluster-notes.md](../../docs/eks-cluster-notes.md), 측정 결과는 [ADR 0011](../../docs/adr/0011-module-split-and-index-contract.md) 에 있습니다.

## Layout

```
deploy/eks/
  cluster.yaml        # eksctl ClusterConfig — 노드그룹 2개 · 같은 AZ · NAT 비활성 · EBS CSI
  storageclass.yaml   # gp3 를 기본 StorageClass 로
  up.sh               # amd64 재빌드 → ECR push → 클러스터 생성 → apply
  down.sh             # 네임스페이스 → 클러스터 순으로 철거 + 고아 EBS 볼륨 확인
```

## Node Groups

| 노드그룹 | 인스턴스 | 파드 | 역할 |
|---|---|---|---|
| `ng-query` | m7i.xlarge (4 vCPU / 16 GiB) | search-api · elasticsearch · qdrant | 질의 경로 전체. 지연을 측정하는 노드 |
| `ng-index` | c7i.2xlarge (8 vCPU / 16 GiB) | indexer-batch · postgis | 색인 전용. 8 vCPU 중 7 을 색인기에 할당 |

두 노드그룹은 같은 AZ 에 둡니다. EBS 볼륨이 AZ 에 묶여 있어 AZ 가 갈리면 배치를 바꾸는 순간 PVC 가 Pending 이 됩니다.

## Running

전제: `aws` · `eksctl` · `kubectl` · `docker`, 임베딩 모델(`models/multilingual-e5-small/`), PostGIS 시드(`deploy/postgis/seed.sql.gz`).

```bash
./deploy/eks/up.sh        # 클러스터 생성 15~20분
./deploy/eks/down.sh      # 철거 10~15분. --all 이면 ECR 리포지토리까지
```

`up.sh`는 패스 A(분리) 상태까지 만들고, 이어서 실행할 명령을 출력합니다.

## Two Passes

같은 클러스터에서 두 번 측정합니다. 파드 사양은 전부 같고 배치만 다르므로, 두 패스의 차이가 노드 분리의 순효과입니다.

| 패스 | 오버레이 | 배치 |
|---|---|---|
| A (분리) | `overlays/eks` | 질의 경로는 `ng-query`, 색인기는 `ng-index` |
| B (합침) | `overlays/eks-colocated` | 전부 `ng-index` |

```bash
kubectl kustomize deploy/k8s/overlays/eks-colocated | sed "s|<ACCOUNT>|${ACCOUNT}|g" | kubectl apply -f -
python3 scripts/verify_zero_downtime.py
```

두 패스의 지연 절대값은 비교하지 않습니다. 패스 B 는 c7i.2xlarge, 패스 A 의 질의 노드는 m7i.xlarge 라 하드웨어가 다릅니다. 비교 가능한 것은 각 패스 내부의 유휴 대비 배수입니다.

로컬 docker-compose 에서 측정한 값(벡터 2.03배 / 키워드 1.32배)도 대조군이 아닙니다. 런타임과 환경이 함께 바뀐 값입니다 — [이유](../../docs/eks-cluster-notes.md#rejected-baseline).

## Results

| Scenario | Split | Combined |
|---|---:|---:|
| Vector reindex | **0.90×** | 1.09× |
| Keyword reindex | 1.07× | 1.11× |

앱 CPU 경쟁은 노드 분리로 해소되고, 같은 ES 파드를 사용하는 키워드 재색인의 경쟁은 남습니다.

## References

- [docs/eks-cluster-notes.md](../../docs/eks-cluster-notes.md) — 설정별 근거
- [deploy/k8s/README.md](../k8s/README.md) — 매니페스트 구조
- [docs/troubleshooting.md](../../docs/troubleshooting.md) — 증상별 원인과 조치
