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
echo
echo "  ── 색인은 색인기(8081)에 건다. 질의기(8080)에는 관리 API 가 아예 없다 (ADR 0011)."
echo "     재색인은 접수만 하고 즉시 202 + jobId 를 준다 — 진행은 따로 조회한다 (ADR 0013)."
echo
echo "  원천 데이터가 바뀌었다면 사전 재생성 후 재색인:"
echo "    python3 scripts/build_komoran_dict.py"
echo "    curl -XPOST localhost:8081/admin/reindex            # → 202 {\"jobId\":1,...}"
echo "    curl localhost:8081/admin/jobs/1                    # 진행·결과 조회"
echo
echo "  벡터(뜻) 검색을 쓰려면 임베딩 모델(470MB)이 먼저 있어야 한다:"
echo "    ./scripts/fetch_embedding_model.sh"
echo "    curl -XPOST localhost:8081/admin/vector/reindex"
echo "  (벡터 색인은 6만 건에 약 9분 — 임베딩 추론이 병목이다. ADR 0010)"
echo "  이제 curl 을 끊어도 색인은 계속 돈다 — job 스레드에서 돌기 때문이다."
