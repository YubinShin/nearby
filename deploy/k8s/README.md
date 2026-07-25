# Nearby on Kubernetes

검색 스택(앱 2개 + 백엔드 3개)을 쿠버네티스로 올리는 매니페스트.
로컬 검증은 **kind**, 실제 배포는 **EKS** — 차이는 오버레이 한 겹뿐이다.

> 이 매니페스트를 왜 소스와 같은 저장소에 뒀는지(모노레포 vs config repo 분리)는
> [ADR 0012](../../docs/adr/0012-manifests-in-monorepo.md).

```
deploy/k8s/
  base/                     # 공통 정의 (환경 무관)
    namespace.yaml
    elasticsearch.yaml      # StatefulSet + headless svc + 사전 ConfigMap 마운트
    qdrant.yaml             # StatefulSet + svc
    postgis.yaml            # StatefulSet + svc (강남 64,239행이 이미지에 구워져 있음)
    search-api.yaml         # Deployment + svc  (질의, 8080)
    indexer-batch.yaml      # Deployment + svc  (색인, 8081)
    backend-wiring.yaml     # ConfigMap: 백엔드 주소를 env 로 주입 (코드 안 건드림)
    komoran-dict.configmap.yaml   # ES 사용자 사전 (아래 '사전' 참고)
  overlays/
    kind/                   # 로컬 단일 노드: 자원 축소, anti-affinity=preferred
    eks/                    # 멀티 노드: anti-affinity=required(다른 노드 강제), ECR 접두어
```

## 설계 메모 (왜 이렇게 했나)

- **주소 주입은 env 로.** `search-core/core.yml` 은 ES·Qdrant 주소를 `localhost` 로 적어 뒀지만,
  Spring 완화 바인딩이 `SPRING_ELASTICSEARCH_URIS` / `PSP_QDRANT_URL` / `SPRING_R2DBC_URL`
  환경변수로 그 값을 덮는다. 그래서 **k8s 를 위해 설정 파일을 고치지 않았다** — 서비스 DNS 만 넣는다.
- **백엔드는 StatefulSet, 앱은 Deployment.** 백엔드(ES/Qdrant/PostGIS)는 디스크에 상태를 들고 있어
  안정된 이름과 PVC 재부착이 필요하다. 앱은 상태가 없어 언제든 갈아끼워도 된다.
- **anti-affinity 가 분리의 마지막 조각.** 앱을 쪼갰어도 같은 노드에 얹히면 색인기의 CPU 버스트가
  질의 지연을 끌어올린다(kind 단일 노드 실측: 11.5 → 23.4ms). eks 오버레이는 이를 `required` 로
  굳혀 두 앱을 강제로 다른 노드에 놓는다 → 지연이 ~11ms 로 돌아오는지 재측정하는 게 목표.
- **PostGIS 데이터는 이미지에 구웠다.** 덤프가 3.6MB 라 파드가 뜨는 순간 데이터가 있다.
  k8s 로 1.4GB CSV 를 옮기거나 적재 Job 을 돌릴 필요가 없다(데모용 결정).

## 로컬(kind)에서 띄우기

전제: Docker Desktop 실행 중, 이미지 4개 빌드됨(`./deploy/build-images.sh`).

```bash
# 0) kind 설치 (한 번만)
brew install kind

# 1) 클러스터 생성
kind create cluster --name nearby

# 2) 로컬 이미지를 클러스터 안으로 넣는다 (kind 는 레지스트리에서 안 당김)
kind load docker-image \
  nearby-search-api:latest nearby-indexer-batch:latest \
  nearby-postgis:seeded psp-elasticsearch-komoran:9.4.2 \
  --name nearby

# 3) 배포
kubectl apply -k deploy/k8s/overlays/kind

# 4) 뜨는지 지켜보기 (ES 가 제일 오래 걸린다)
kubectl -n nearby get pods -w
```

전부 `Running` + `READY` 가 되면:

```bash
# 5) 색인 트리거 (indexer-batch 로 port-forward 후 /admin/reindex)
kubectl -n nearby port-forward svc/indexer-batch 8081:80 &
curl -X POST localhost:8081/admin/reindex

# 6) 검색 (search-api)
kubectl -n nearby port-forward svc/search-api 8080:80 &
curl "localhost:8080/v1/search?q=강남역 카페"
```

정리: `kind delete cluster --name nearby`

### ⚠️ Apple Silicon 주의 — PostGIS 아키텍처

`nearby-postgis` 는 `postgis/postgis:16-3.4`(amd64 전용) 기반이라 **amd64** 다.
M4(arm64) kind 노드에서는 에뮬레이션으로 뜬다 — 느리지만 데모엔 충분. 만약 파드가
`exec format error` 로 죽으면 멀티아치 PostGIS 로 교체:

```bash
# deploy/postgis/Dockerfile 의 FROM 을 arm64 지원 이미지로 바꿔 재빌드
#   FROM imresamu/postgis:16-3.4     # 멀티아치(arm64/amd64)
```

## EKS

전제: 노드 2개 이상(**x86** — PostGIS amd64), 이미지가 ECR 에 push 됨, gp3 StorageClass.

```bash
# 이미지 빌드·push (x86)
PLATFORM=linux/amd64 TAG=latest \
  REGISTRY=<ACCOUNT>.dkr.ecr.ap-northeast-2.amazonaws.com \
  ./deploy/build-images.sh
# ... docker push (4개) ...

# overlays/eks/kustomization.yaml 의 <ACCOUNT> 를 실제 값으로 바꾼 뒤
kubectl apply -k deploy/k8s/overlays/eks

# 색인기와 질의기가 정말 다른 노드에 떨어졌는지 확인
kubectl -n nearby get pods -o wide
```

kind 와 EKS 의 유일한 본질적 차이 = **anti-affinity(preferred→required)**. 나머지(자원·이미지 경로·
스토리지클래스)는 환경에 맞춘 곁가지다.
