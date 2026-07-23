#!/usr/bin/env bash
# 로컬 검색 스택 기동 헬퍼.
# KOMORAN 플러그인을 패키징 → ES 커스텀 이미지 빌드 컨텍스트로 복사 → compose up.
#
# 사용:  ./deploy/up.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "▶ 1/3  KOMORAN 플러그인 패키징"
./es-analysis-komoran/gradlew -p es-analysis-komoran pluginZip

echo "▶ 2/3  플러그인 zip을 ES 이미지 빌드 컨텍스트로 복사"
cp es-analysis-komoran/build/distributions/komoran-analysis.zip deploy/elasticsearch/

echo "▶ 3/3  docker compose up (ES 이미지 빌드 포함)"
docker compose -f deploy/docker-compose.yml up -d --build

echo "✔ 기동 완료. ES 상태:  curl -s localhost:9200/_cat/plugins?v"
echo "  KOMORAN 사용자 사전은 deploy/elasticsearch/analysis/komoran/place.dict 를 마운트해 쓴다."
echo "  원천 데이터가 바뀌었다면 재생성 후 재색인:"
echo "    python3 scripts/build_komoran_dict.py && curl -XPOST localhost:8080/admin/reindex"
