# Nearby on Kubernetes

Nearby 검색 스택(앱 2개, 백엔드 3개)을 Kubernetes에 배포하기 위한 매니페스트입니다.

- **로컬 검증:** kind
- **실제 배포:** Amazon EKS
- **환경 차이:** Kustomize Overlay 한 겹으로 관리

---

## Prerequisites

- kubectl
- 이미지 4개 빌드 완료

```bash
./deploy/build-images.sh
```

### kind only

- Docker Desktop
- kind

### EKS only

- EKS Cluster
- x86 Worker Node 2개 이상
- gp3 StorageClass
- ECR Push 완료

---

## Directory Layout

```text
deploy/k8s/
├── base/
│   ├── namespace.yaml
│   ├── elasticsearch.yaml        # StatefulSet + Headless Service
│   ├── qdrant.yaml               # StatefulSet + Service
│   ├── postgis.yaml              # StatefulSet + Service
│   ├── search-api.yaml           # Deployment + Service (8080)
│   ├── indexer-batch.yaml        # Deployment + Service (8081)
│   ├── backend-wiring.yaml       # Backend endpoint ConfigMap
│   └── komoran-dict.configmap.yaml
└── overlays/
    ├── kind/
    └── eks/
```

`base`는 환경과 무관한 공통 리소스를 정의하며, `kind`와 `eks`는 환경별 차이만 오버레이합니다.

---

## Architecture

| Component     | Workload    | 이유                    |
| ------------- | ----------- | ----------------------- |
| Elasticsearch | StatefulSet | 디스크 기반 데이터 저장 |
| Qdrant        | StatefulSet | 벡터 데이터 영속성      |
| PostGIS       | StatefulSet | 공간 데이터 영속성      |
| Search API    | Deployment  | Stateless               |
| Indexer Batch | Deployment  | Stateless               |

---

## Design Decisions

### Backend endpoint injection

백엔드 주소는 ConfigMap을 통해 환경 변수로 주입합니다.

Spring Boot의 Relaxed Binding을 이용해 기본 설정(`search-core/core.yml`)을 수정하지 않고 다음 값을 덮어씁니다.

- `SPRING_ELASTICSEARCH_URIS`
- `PSP_QDRANT_URL`
- `SPRING_DATASOURCE_URL`

Kubernetes 배포를 위해 별도의 설정 파일을 유지하지 않습니다.

---

### StatefulSet vs Deployment

검색 백엔드는 모두 상태를 가지므로 StatefulSet을 사용합니다.

- Stable Network Identity
- Persistent Volume 재사용
- Pod 재생성 시 데이터 유지

반면 Search API와 Indexer는 상태가 없으므로 Deployment를 사용합니다.

---

### Anti-affinity

색인 작업은 CPU 사용량이 매우 높습니다.

Search API와 같은 노드에 배치되면 검색 지연(latency)이 증가할 수 있으므로 EKS에서는 강제 Pod Anti-affinity를 적용합니다.

| Environment | Anti-affinity |
| ----------- | ------------- |
| kind        | preferred     |
| EKS         | required      |

---

### Seeded PostGIS

PostGIS 이미지는 강남 지역(64,239행) 데이터를 포함한 상태로 빌드됩니다.

데모 환경에서는 별도의

- CSV Import
- Migration Job
- Seed Job

없이 Pod 시작 즉시 검색이 가능합니다.

---

## Deploy to kind

### Create Cluster

```bash
# 클러스터 생성
kind create cluster --name nearby

# 도커 이미지 로드
kind load docker-image \
  nearby-search-api:latest \
  nearby-indexer-batch:latest \
  nearby-postgis:seeded \
  psp-elasticsearch-komoran:9.4.2 \
  --name nearby

# 배포
kubectl apply -k deploy/k8s/overlays/kind

# Pod 상태 — Elasticsearch가 가장 오래 걸림
kubectl -n nearby get pods -w
```

### Build Index

```bash
# Indexer Port Forward
kubectl -n nearby port-forward svc/indexer-batch 8081:80 &

# 키워드 색인
curl -X POST localhost:8081/admin/reindex

# 벡터 색인
curl -X POST localhost:8081/admin/vector/reindex

# 진행 상황 조회
curl -s localhost:8081/admin/jobs/2 \
| jq '{status, steps: [.steps[] | {name, read, written}]}'
```

색인은 백그라운드 Job으로 수행되므로 Port Forward가 종료되어도 계속 진행됩니다.

Spring Batch는 진행 상황을 `BATCH_STEP_EXECUTION` 테이블에 저장하므로 Pod가 재시작되어도 이력을 유지합니다.

### Search

```bash
kubectl -n nearby port-forward svc/search-api 8080:80 &

curl "http://localhost:8080/v1/search?q=강남역 카페"
```

### Cleanup

```bash
kind delete cluster --name nearby
```

---

## Deploy to EKS

```bash
# 이미지 빌드 (amd64)
PLATFORM=linux/amd64 \
TAG=latest \
REGISTRY=<ACCOUNT>.dkr.ecr.ap-northeast-2.amazonaws.com \
./deploy/build-images.sh

# ECR Push 후 배포
kubectl apply -k deploy/k8s/overlays/eks

# Pod 배치 — Search API와 Indexer가 다른 노드여야 함
kubectl -n nearby get pods -o wide
```

### kind vs EKS

|               | kind             | EKS             |
| ------------- | ---------------- | --------------- |
| Images        | Local Docker     | Amazon ECR      |
| Anti-affinity | preferred        | required        |
| Storage       | Local            | gp3 PVC         |
| Purpose       | Local Validation | Production-like |

두 환경의 차이는 대부분 Overlay에서 관리됩니다.

---

## Troubleshooting

증상별 원인과 조치는 [docs/troubleshooting.md](../../docs/troubleshooting.md)에 정리했습니다.

### Apple Silicon

`nearby-postgis`는 기본적으로 `postgis/postgis:16-3.4`(amd64)를 기반으로 합니다.

Apple Silicon에서는 QEMU 에뮬레이션으로 실행됩니다.

`exec format error`가 발생하면 다음과 같이 멀티 아키텍처 이미지를 사용합니다.

```dockerfile
FROM imresamu/postgis:16-3.4
```

이후 이미지를 다시 빌드합니다.

---

## References

- [ADR 0012](../../docs/adr/0012-manifests-in-monorepo.md) — 매니페스트를 소스와 같은 저장소에 둔 이유
- [deploy/eks/README.md](../eks/README.md) — EKS 실험 클러스터
- [docs/eks-cluster-notes.md](../../docs/eks-cluster-notes.md) — 오버레이·클러스터 설정별 근거
- [docs/troubleshooting.md](../../docs/troubleshooting.md) — 증상별 원인과 조치
