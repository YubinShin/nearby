#!/usr/bin/env bash
# EKS 실험 클러스터 기동 — 이미지 amd64 재빌드 → ECR → 클러스터 → apply.
#
# 목적은 ADR 0011 의 남은 질문 하나다: **색인기를 다른 노드로 떼면 질의 지연이 회복되는가.**
# 답을 얻으면 `down.sh` 로 지운다.
#
# 사용:  ./deploy/eks/up.sh
# 전제:  aws cli(자격증명), eksctl, kubectl, docker, 그리고
#        models/multilingual-e5-small/ 와 deploy/postgis/seed.sql.gz
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT="$(pwd)"

REGION="${REGION:-ap-northeast-2}"
CLUSTER="${CLUSTER:-nearby}"
TAG="${TAG:-latest}"
ES_TAG="9.4.2"                 # base 매니페스트가 이 태그를 참조한다
NS=nearby

# ---- 사전 점검 ---------------------------------------------------------------
for c in aws eksctl kubectl docker; do
	command -v "$c" >/dev/null || { echo "필요: $c" >&2; exit 1; }
done
[ -d models/multilingual-e5-small ] || { echo "임베딩 모델 없음 → ./scripts/fetch_embedding_model.sh" >&2; exit 1; }
[ -f deploy/postgis/seed.sql.gz ]   || { echo "시드 덤프 없음 → ./deploy/postgis/make-seed.sh" >&2; exit 1; }

ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
ECR="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com"
echo "▶ 계정 ${ACCOUNT} · 리전 ${REGION} · 레지스트리 ${ECR}"

# ---- 1. ECR 리포지토리 ------------------------------------------------------
# model-base 는 **올리지 않는다.** 앱 Dockerfile 이 `FROM nearby-model-base` 로 쓰는 빌드 단계라
# 모델 레이어가 최종 이미지 안에 이미 구워져 있다. 레지스트리에 따로 둘 이유가 없다.
echo "▶ 1/6  ECR 리포지토리 (없으면 생성)"
for repo in nearby-search-api nearby-indexer-batch nearby-postgis psp-elasticsearch-komoran; do
	aws ecr describe-repositories --region "$REGION" --repository-names "$repo" >/dev/null 2>&1 \
		|| aws ecr create-repository --region "$REGION" --repository-name "$repo" >/dev/null
	echo "   $repo"
done

aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$ECR" >/dev/null

# ---- 2. 앱·PostGIS 이미지 (amd64) -------------------------------------------
# 로컬은 arm64 지만 노드가 amd64 다. build-images.sh 가 PLATFORM 을 지원하므로 그대로 넘긴다.
# 앱 이미지는 `FROM temurin + COPY jar` 라 에뮬레이션 빌드여도 컴파일이 없어 느리지 않다.
# PostGIS 는 베이스가 원래 amd64 라 오히려 에뮬레이션이 사라진다.
echo "▶ 2/6  이미지 빌드 (linux/amd64)"
PLATFORM=linux/amd64 REGISTRY="$ECR" TAG="$TAG" ./deploy/build-images.sh

# ---- 3. ES + KOMORAN 이미지 (amd64) ----------------------------------------
# build-images.sh 는 이걸 안 만든다 — 로컬에서는 docker compose 가 빌드해왔기 때문이다.
# k8s 에는 compose 가 없으니 여기서 직접 굽는다.
echo "▶ 3/6  ES + KOMORAN 이미지"
./es-analysis-komoran/gradlew -p es-analysis-komoran pluginZip -q
cp es-analysis-komoran/build/distributions/komoran-analysis.zip deploy/elasticsearch/
docker build --platform=linux/amd64 \
	-t "${ECR}/psp-elasticsearch-komoran:${ES_TAG}" deploy/elasticsearch

# ---- 4. push ----------------------------------------------------------------
echo "▶ 4/6  ECR push"
docker push "${ECR}/nearby-search-api:${TAG}"
docker push "${ECR}/nearby-indexer-batch:${TAG}"
docker push "${ECR}/nearby-postgis:${TAG}"
docker push "${ECR}/psp-elasticsearch-komoran:${ES_TAG}"

# 오버레이는 postgis 를 `seeded` 태그로 참조한다. 빌드가 만든 태그를 그 이름으로도 올린다.
docker tag  "${ECR}/nearby-postgis:${TAG}" "${ECR}/nearby-postgis:seeded"
docker push "${ECR}/nearby-postgis:seeded"

# ---- 5. 클러스터 ------------------------------------------------------------
if eksctl get cluster --region "$REGION" --name "$CLUSTER" >/dev/null 2>&1; then
	echo "▶ 5/6  클러스터 $CLUSTER 이미 있음 — 건너뜀"
else
	echo "▶ 5/6  클러스터 생성 (15~20분 걸린다)"
	eksctl create cluster -f deploy/eks/cluster.yaml
fi
aws eks update-kubeconfig --region "$REGION" --name "$CLUSTER" >/dev/null

echo "   노드 라벨 확인:"
kubectl get nodes -L role

# gp3 를 기본 클래스로. **기본이 둘이면 K8s 는 어느 쪽도 고르지 않는다** —
# gp2 의 default 표시를 반드시 떼어야 한다.
kubectl apply -f deploy/eks/storageclass.yaml
kubectl patch storageclass gp2 \
	-p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"false"}}}' \
	2>/dev/null || echo "   (gp2 없음 — 무시)"

# ---- 6. apply ---------------------------------------------------------------
# 오버레이의 <ACCOUNT> 를 실제 계정으로 바꿔 넣는다. 추적 중인 파일을 고치지 않으려고
# `kubectl kustomize` 출력에 sed 를 걸어 파이프로 apply 한다.
echo "▶ 6/6  매니페스트 apply"
kubectl kustomize deploy/k8s/overlays/eks \
	| sed "s|<ACCOUNT>|${ACCOUNT}|g" \
	| kubectl apply -f -

echo
echo "▶ 파드가 뜨는 걸 지켜본다 (ES 가 제일 오래 걸린다)"
kubectl -n "$NS" get pods -o wide -w &
WATCH=$!
trap 'kill $WATCH 2>/dev/null || true' EXIT

kubectl -n "$NS" wait --for=condition=ready pod --all --timeout=900s || {
	echo "!! 일부 파드가 안 떴다. PVC 부터 확인할 것:" >&2
	echo "   kubectl -n $NS get pvc     # Pending 이면 EBS CSI 애드온 / gp3 SC 문제" >&2
	echo "   kubectl -n $NS describe pod <name>" >&2
	exit 1
}
kill $WATCH 2>/dev/null || true

cat <<EOF

━━━ 준비 완료 (지금은 패스 A = 분리 상태) ━━━

이 실험은 **한 클러스터에서 두 번** 잰다. 파드 스펙은 전부 같고 배치만 다르다 —
그래서 두 패스의 '유휴 대비 배수' 차이가 곧 **노드 분리의 순효과**다.

배치 확인 (패스 A: 질의기와 색인기가 다른 노드여야 한다):
  kubectl -n $NS get pods -o wide
  kubectl get nodes -L topology.kubernetes.io/zone,role   # 두 노드가 같은 AZ 여야 한다

port-forward 두 개 (LoadBalancer 는 일부러 안 만든다):
  kubectl -n $NS port-forward svc/indexer-batch 8081:80 &
  kubectl -n $NS port-forward svc/search-api    8080:80 &

── 패스 A (분리) ──
  python3 scripts/verify_zero_downtime.py
  # 유휴 → 키워드 재색인 중 → 벡터 재색인 중을 한 번에 잰다(약 10분).
  # 벡터 재색인은 7 vCPU 에서 ~700초 예상 — 이게 CPU 3번째 측정점이다.

── 패스 B (합침 = 대조군) ──
  kubectl kustomize $ROOT/deploy/k8s/overlays/eks-colocated | sed "s|<ACCOUNT>|${ACCOUNT}|g" | kubectl apply -f -
  kubectl -n $NS rollout status deploy/search-api deploy/indexer-batch
  kubectl -n $NS get pods -o wide      # 이제 **전부 role=index 노드**여야 한다
  python3 scripts/verify_zero_downtime.py

  ⚠️ 두 패스의 지연 **절대값을 비교하지 말 것** — 패스 B 는 c7i.2xlarge,
     패스 A 의 질의 노드는 m7i.xlarge 라 하드웨어가 다르다.
     비교 가능한 건 각 패스 **내부**의 유휴 대비 배수뿐이다.

  ⚠️ 예전 값(벡터 2.03배 / 키워드 1.32배)을 대조군으로 쓰지 말 것 — 그건 로컬
     docker-compose + 리액티브 색인기에서 잰 값이다. 대조군은 위 패스 B 다.

끝나면 반드시:
  ./deploy/eks/down.sh
EOF
