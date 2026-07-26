#!/usr/bin/env bash
# 컨테이너 이미지 4개를 만든다.
#
#   실행:  ./deploy/build-images.sh              # 호스트 아키텍처 (로컬 kind 용)
#          PLATFORM=linux/amd64 ./deploy/build-images.sh   # x86 EKS 용
#          TAG=v1 REGISTRY=1234.dkr.ecr.ap-northeast-2.amazonaws.com ./deploy/build-images.sh
#
# 만드는 것:
#   nearby-model-base  임베딩 모델 470MB 만 담은 공유 베이스
#   nearby-search-api  질의 앱  (model-base 위에 jar)
#   nearby-indexer     색인 앱  (model-base 위에 jar) — 같은 레이어를 공유한다
#   nearby-postgis     원천 데이터가 들어 있는 PostGIS
#
# ── 왜 gradle 을 먼저 돌리나 ────────────────────────────────────────────────
# jar 은 아키텍처와 무관하므로 **호스트에서 네이티브로** 만들고, 이미지는 복사만 한다.
# 이미지 안에서 컴파일하면 amd64 크로스 빌드가 QEMU 에뮬레이션으로 돌아 몇 배 느려진다.
#
# ── 아키텍처 주의 ─────────────────────────────────────────────────────────
# postgis/postgis:16-3.4 는 **amd64 전용**이다(다른 베이스는 arm64 도 있다).
# 그래서 Graviton(arm64) 노드로는 이 스택이 그대로 안 올라간다 — x86 노드를 쓰거나
# arm64 를 지원하는 PostGIS 이미지로 바꿔야 한다.
set -euo pipefail

cd "$(dirname "$0")/.."

TAG="${TAG:-latest}"
REGISTRY="${REGISTRY:-}"                 # 있으면 접두어로 붙는다 (ECR 등)
PLATFORM="${PLATFORM:-}"                 # 비우면 호스트 아키텍처
PREFIX="${REGISTRY:+$REGISTRY/}"

# PLATFORM 이 비면 --platform 을 **아예 넘기지 않는다**(호스트 아키텍처).
# 배열로 하면 안 된다: `PLAT_ARG=("${PLATFORM:+...}")` 는 PLATFORM 이 빌 때 원소 0개가 아니라
# **빈 문자열 원소 1개**라서 `docker build '' … .` 이 되고, docker 가 컨텍스트를 두 개 받은 걸로
# 보고 "requires 1 argument" 로 죽는다. 빈 배열로 고쳐도 macOS 기본 bash 3.2 에서는
# `set -u` 아래 `"${arr[@]}"` 가 unbound 로 죽어서, 분기를 함수 안에 둔다.
dbuild() {
  if [ -n "$PLATFORM" ]; then
    docker build --platform="$PLATFORM" "$@"
  else
    docker build "$@"
  fi
}

[ -d models/multilingual-e5-small ] || {
  echo "임베딩 모델이 없습니다. ./scripts/fetch_embedding_model.sh 를 먼저 실행하세요." >&2
  exit 1
}
[ -f deploy/postgis/seed.sql.gz ] || {
  echo "시드 덤프가 없습니다. ./deploy/postgis/make-seed.sh 를 먼저 실행하세요." >&2
  exit 1
}

echo "==> jar 빌드 (호스트 네이티브)"
(cd services && ./gradlew :search-api:bootJar :indexer-batch:bootJar -x test -q)

echo "==> ${PREFIX}nearby-model-base:$TAG   (470MB 모델 — 두 앱이 공유할 레이어)"
dbuild -f deploy/model-base/Dockerfile \
  -t "${PREFIX}nearby-model-base:$TAG" .

# 앱 이미지는 위 베이스를 FROM 하므로 태그가 맞아야 한다. 태그를 바꿔 쓰려면
# Dockerfile 의 FROM 도 같이 바꿔야 해서, 여기서는 로컬 별칭을 고정해 둔다.
docker tag "${PREFIX}nearby-model-base:$TAG" nearby-model-base:e5-small

for app in search-api indexer-batch; do
  echo "==> ${PREFIX}nearby-$app:$TAG"
  dbuild -f "deploy/$app/Dockerfile" -t "${PREFIX}nearby-$app:$TAG" .
done

echo "==> ${PREFIX}nearby-postgis:$TAG   (원천 64,239행 포함)"
dbuild -f deploy/postgis/Dockerfile \
  -t "${PREFIX}nearby-postgis:$TAG" deploy/postgis

echo
echo "완료:"
docker images --format '{{.Repository}}:{{.Tag}}\t{{.Size}}' | grep -E "nearby-" | sort
